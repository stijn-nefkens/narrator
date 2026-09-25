package com.example.narrator.data

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Pure java.io ZIP read/write helpers used by [BackupManager]. Extracted so the archive
 * format can be unit-tested without an Android Context / Uri / SQLiteDatabase. Everything
 * here operates on regular Files and Streams.
 */
internal object BackupArchive {

    data class WriteSummary(val bookFiles: Int, val coverFiles: Int)
    data class ReadSummary(val bookFiles: Int, val coverFiles: Int)

    /** Writes a backup ZIP containing [dbFile], every file in [epubDir], and every file
     *  in [coverDir]. Closes [out]. */
    fun write(out: OutputStream, dbFile: File, epubDir: File, coverDir: File): WriteSummary {
        var bookFiles = 0
        var coverFiles = 0
        ZipOutputStream(out.buffered()).use { zip ->
            putEntry(zip, "narrator.db", dbFile)
            for (f in epubDir.listFiles().orEmpty()) {
                putEntry(zip, "epubs/${f.name}", f)
                bookFiles++
            }
            for (f in coverDir.listFiles().orEmpty()) {
                putEntry(zip, "covers/${f.name}", f)
                coverFiles++
            }
        }
        return WriteSummary(bookFiles, coverFiles)
    }

    /** Reads a backup ZIP into [stagingDir]. The caller swaps the staged files into place
     *  once validation succeeds. Throws [IllegalStateException] if narrator.db is missing
     *  from the archive — the caller should treat this as a hard failure and abort,
     *  *before* touching any existing on-disk state. */
    fun read(input: InputStream, stagingDir: File): ReadSummary {
        stagingDir.mkdirs()
        var dbStaged = false
        var bookFiles = 0
        var coverFiles = 0
        ZipInputStream(input.buffered()).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name.contains("..")) {
                    // Zip-slip guard: skip any entry trying to escape the staging dir.
                    zip.closeEntry()
                    entry = zip.nextEntry
                    continue
                }
                val target = File(stagingDir, name)
                target.parentFile?.mkdirs()
                if (!entry.isDirectory) {
                    target.outputStream().use { zip.copyTo(it) }
                    when {
                        name == "narrator.db" -> dbStaged = true
                        name.startsWith("epubs/") -> bookFiles++
                        name.startsWith("covers/") -> coverFiles++
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        if (!dbStaged) throw IllegalStateException("Backup ZIP is missing narrator.db")
        return ReadSummary(bookFiles, coverFiles)
    }

    /**
     * Where a book/cover file recorded as [storedPath] in a restored DB lives now: the same file
     * name inside [currentDir]. The DB stores absolute paths, which embed the ORIGINAL device's
     * data directory (/data/user/<n>/<applicationId>/files/...). Restoring under another user /
     * work profile, or after an applicationId change, left every path dangling. The archive only
     * ever holds flat `epubs/<name>` and `covers/<name>` entries, so the file name is the key.
     */
    fun relocatedPath(storedPath: String, currentDir: File): String =
        File(currentDir, File(storedPath).name).absolutePath

    private const val SQLITE_HEADER_BYTES = 100
    private const val SQLITE_USER_VERSION_OFFSET = 60
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    /**
     * The schema version (`PRAGMA user_version`, which SQLiteOpenHelper sets to DATABASE_VERSION)
     * stored in a SQLite file's header, or null if [dbFile] isn't a SQLite database. Read from the
     * raw header so a restore can be vetted BEFORE it replaces the live DB: a backup written by a
     * newer app version would otherwise make SQLiteOpenHelper throw "Can't downgrade database" on
     * every launch — a crash loop only clearing app data escapes.
     */
    fun sqliteUserVersion(dbFile: File): Int? {
        val header = ByteArray(SQLITE_HEADER_BYTES)
        val read = if (dbFile.length() < SQLITE_HEADER_BYTES) {
            0
        } else {
            dbFile.inputStream().use { it.readNBytesCompat(header) }
        }
        val isSqlite = read == SQLITE_HEADER_BYTES &&
            header.copyOfRange(0, SQLITE_MAGIC.size).contentEquals(SQLITE_MAGIC)
        // user_version is a 4-byte big-endian int, which is ByteBuffer's default order.
        return if (isSqlite) java.nio.ByteBuffer.wrap(header, SQLITE_USER_VERSION_OFFSET, Int.SIZE_BYTES).int else null
    }

    private fun InputStream.readNBytesCompat(buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun putEntry(zip: ZipOutputStream, name: String, source: File) {
        zip.putNextEntry(ZipEntry(name))
        source.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }
}
