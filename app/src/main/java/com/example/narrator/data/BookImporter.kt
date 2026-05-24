package com.example.narrator.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.narrator.epub.EpubParseException
import com.example.narrator.epub.EpubParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

sealed class ImportResult {
    data class Inserted(val book: BookEntity) : ImportResult()
    data class Duplicate(val existing: BookEntity, val pending: PendingImport) : ImportResult()
    data class Failed(val reason: String) : ImportResult()
}

data class PendingImport(
    val title: String,
    val author: String,
    val epubPath: String,
    val coverPath: String?,
    val totalChunks: Int,
) {
    fun cleanup() {
        runCatching { File(epubPath).delete() }
        coverPath?.let { runCatching { File(it).delete() } }
    }
}

class BookImporter(
    private val context: Context,
    private val repository: BookRepository,
) {
    suspend fun importFromUri(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        // Capture the picker's display name BEFORE we copy the file — needed as a fallback
        // for the title when the EPUB has no metadata title (per spec §5.1).
        val originalDisplayName = displayNameFor(uri)

        val epubFile = repository.newEpubFile()
        try {
            copyUriToFile(uri, epubFile)
        } catch (e: Exception) {
            epubFile.delete()
            return@withContext ImportResult.Failed(e.message ?: "Could not read source file")
        }

        val parsed = try {
            EpubParser.parse(epubFile)
        } catch (e: EpubParseException) {
            epubFile.delete()
            return@withContext ImportResult.Failed(e.message ?: "Could not parse EPUB")
        } catch (e: Exception) {
            epubFile.delete()
            return@withContext ImportResult.Failed(e.message ?: "Unexpected parse error")
        }

        val coverFile = parsed.coverImage?.let { bytes ->
            val ext = (parsed.coverMimeType ?: "").toExtension()
            val f = repository.newCoverFile(ext)
            f.writeBytes(bytes)
            f
        }

        // Spec §5.1 fallback order: metadata → filename → "Unknown".
        // The parser already substitutes "Unknown title" / "Unknown author" when metadata is
        // missing. Replace those sentinels with the picker's display name when we have one.
        val finalTitle = if (parsed.title == UNKNOWN_TITLE && !originalDisplayName.isNullOrBlank()) {
            prettifyFilename(originalDisplayName)
        } else parsed.title

        val totalChunks = parsed.chapters.sumOf { it.chunks.size }
        val pending = PendingImport(
            title = finalTitle,
            author = parsed.author,
            epubPath = epubFile.absolutePath,
            coverPath = coverFile?.absolutePath,
            totalChunks = totalChunks,
        )

        val existing = repository.findByTitleAndAuthor(finalTitle, parsed.author)
        if (existing != null) return@withContext ImportResult.Duplicate(existing, pending)

        val id = repository.insertBook(
            title = pending.title,
            author = pending.author,
            epubPath = pending.epubPath,
            coverPath = pending.coverPath,
            totalChunks = pending.totalChunks,
        )
        val inserted = repository.getBook(id)
            ?: return@withContext ImportResult.Failed("Insert succeeded but row could not be read back")
        ImportResult.Inserted(inserted)
    }

    suspend fun confirmDuplicate(
        existing: BookEntity,
        pending: PendingImport,
        replace: Boolean,
    ) {
        if (replace) {
            repository.replaceBookFiles(
                existingId = existing.id,
                title = pending.title,
                author = pending.author,
                epubPath = pending.epubPath,
                coverPath = pending.coverPath,
                totalChunks = pending.totalChunks,
            )
        } else {
            pending.cleanup()
        }
    }

    private fun copyUriToFile(uri: Uri, target: File) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Could not open content URI")
        input.use { src -> target.outputStream().use { src.copyTo(it) } }
    }

    private fun displayNameFor(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
    }.getOrNull()

    /** Turn "alices_adventures-in.wonderland.epub" → "Alices Adventures In Wonderland". */
    private fun prettifyFilename(name: String): String {
        val stripped = name.substringBeforeLast('.', name)
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (stripped.isEmpty()) return name
        return stripped.split(' ').joinToString(" ") { word ->
            word.replaceFirstChar { c -> c.uppercaseChar() }
        }
    }

    private fun String.toExtension(): String = when (this) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/svg+xml" -> "svg"
        else -> "jpg"
    }

    private companion object {
        // Must match EpubParser's hard-coded fallback string.
        const val UNKNOWN_TITLE = "Unknown title"
    }
}
