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

    private fun putEntry(zip: ZipOutputStream, name: String, source: File) {
        zip.putNextEntry(ZipEntry(name))
        source.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }
}
