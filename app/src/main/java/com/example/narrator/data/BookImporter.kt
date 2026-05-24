package com.example.narrator.data

import android.content.Context
import android.net.Uri
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

        val totalChunks = parsed.chapters.sumOf { it.chunks.size }
        val pending = PendingImport(
            title = parsed.title,
            author = parsed.author,
            epubPath = epubFile.absolutePath,
            coverPath = coverFile?.absolutePath,
            totalChunks = totalChunks,
        )

        val existing = repository.findByTitleAndAuthor(parsed.title, parsed.author)
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

    private fun String.toExtension(): String = when (this) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/svg+xml" -> "svg"
        else -> "jpg"
    }
}
