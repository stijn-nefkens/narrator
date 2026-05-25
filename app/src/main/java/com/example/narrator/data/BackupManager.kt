package com.example.narrator.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

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
        val outStream = context.contentResolver.openOutputStream(uri)
            ?: throw IllegalStateException("Could not open destination")
        val w = outStream.use { stream ->
            BackupArchive.write(
                out = stream,
                dbFile = context.getDatabasePath("narrator.db"),
                epubDir = repository.epubDir,
                coverDir = repository.coverDir,
            )
        }
        BackupSummary(w.bookFiles, w.coverFiles)
    }

    /** Returns the number of source files restored. After this completes, the caller MUST
     *  call repository.refresh() (and rebuild any in-memory state derived from the DB). */
    suspend fun restoreFrom(uri: Uri): BackupSummary = withContext(Dispatchers.IO) {
        // Stage everything to a temp dir first; only swap into place once BackupArchive
        // has verified the zip contains a narrator.db. That way a corrupt zip leaves the
        // existing library untouched.
        val staging = File(context.cacheDir, "restore-${UUID.randomUUID()}")
        try {
            val inStream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Could not open source")
            val r = inStream.use { BackupArchive.read(it, staging) }

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

            BackupSummary(r.bookFiles, r.coverFiles)
        } finally {
            staging.deleteRecursively()
        }
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
