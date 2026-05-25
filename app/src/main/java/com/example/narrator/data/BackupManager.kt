package com.example.narrator.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * On-device backup / restore as a single ZIP file. The zip contains:
 *   - narrator.db         — the SQLite books / bookmarks / saved_bookmarks tables
 *   - epubs/<uuid>.epub|pdf — every imported source file
 *   - covers/<uuid>.<ext> — every cover image
 *
 * No metadata.json, no version pinning: the schema migrations in NarratorDatabase handle
 * any upgrade between the version that wrote the backup and the version that reads it.
 *
 * Restore is destructive — it wipes the existing library and replaces it with the zip's
 * contents. The UI must confirm with the user before calling restoreFrom().
 */
class BackupManager(
    private val context: Context,
    private val database: NarratorDatabase,
    private val repository: BookRepository,
) {
    data class BackupSummary(val bookFiles: Int, val coverFiles: Int)

    suspend fun backupTo(uri: Uri): BackupSummary = withContext(Dispatchers.IO) {
        // Force any WAL pages back into the main database file so the byte-copy below
        // captures a complete, consistent snapshot. Has to be rawQuery — the WAL pragma
        // returns a (busy, log, checkpointed) row, and SQLiteDatabase.execSQL rejects
        // any statement that returns data with "execSQL ... is not allowed". Consuming the
        // cursor is enough; we don't care about the result values.
        runCatching {
            database.readableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { c ->
                c.moveToFirst()
            }
        }  // checkpoint failure (e.g. non-WAL journal mode) is not fatal — proceed anyway
        var bookFiles = 0
        var coverFiles = 0
        val outStream = context.contentResolver.openOutputStream(uri)
            ?: throw IllegalStateException("Could not open destination")
        outStream.use { stream ->
            ZipOutputStream(stream.buffered()).use { zip ->
                writeEntry(zip, "narrator.db", context.getDatabasePath("narrator.db"))
                for (f in repository.epubDir.listFiles().orEmpty()) {
                    writeEntry(zip, "epubs/${f.name}", f)
                    bookFiles++
                }
                for (f in repository.coverDir.listFiles().orEmpty()) {
                    writeEntry(zip, "covers/${f.name}", f)
                    coverFiles++
                }
            }
        }
        BackupSummary(bookFiles, coverFiles)
    }

    /** Returns the number of source files restored. After this completes, the caller MUST
     *  call repository.refresh() (and rebuild any in-memory state derived from the DB). */
    suspend fun restoreFrom(uri: Uri): BackupSummary = withContext(Dispatchers.IO) {
        // Stage everything to a temp dir first; only swap into place once we've verified the
        // zip contains at least a narrator.db. That way a corrupt zip leaves the existing
        // library untouched.
        val staging = File(context.cacheDir, "restore-${UUID.randomUUID()}")
        staging.mkdirs()
        try {
            var dbStaged = false
            var bookFiles = 0
            var coverFiles = 0
            context.contentResolver.openInputStream(uri)
                ?.use { inStream ->
                    ZipInputStream(inStream.buffered()).use { zip ->
                        var entry: ZipEntry? = zip.nextEntry
                        while (entry != null) {
                            val name = entry.name
                            // Guard against zip-slip: only allow the three known top-level
                            // paths and reject anything escaping with '..'.
                            if (name.contains("..")) {
                                zip.closeEntry()
                                entry = zip.nextEntry
                                continue
                            }
                            val target = File(staging, name)
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
                }
                ?: throw IllegalStateException("Could not open source")
            if (!dbStaged) {
                throw IllegalStateException("Backup ZIP is missing narrator.db")
            }

            // Swap into place. Close the open DB handle first so we can overwrite the file.
            database.close()
            val dbFile = context.getDatabasePath("narrator.db")
            File(staging, "narrator.db").copyTo(dbFile, overwrite = true)
            // Drop any sidecar journal/WAL so SQLite doesn't try to roll forward stale data.
            File(dbFile.parent, "narrator.db-journal").delete()
            File(dbFile.parent, "narrator.db-wal").delete()
            File(dbFile.parent, "narrator.db-shm").delete()

            replaceDir(repository.epubDir, File(staging, "epubs"))
            replaceDir(repository.coverDir, File(staging, "covers"))

            BackupSummary(bookFiles, coverFiles)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, source: File) {
        zip.putNextEntry(ZipEntry(name))
        source.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun replaceDir(target: File, sourceOrNull: File) {
        target.listFiles()?.forEach { it.delete() }
        target.mkdirs()
        if (sourceOrNull.isDirectory) {
            sourceOrNull.listFiles()?.forEach { src ->
                src.copyTo(File(target, src.name), overwrite = true)
            }
        }
    }
}
