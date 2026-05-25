package com.example.narrator.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.narrator.epub.Book
import com.example.narrator.epub.EpubParseException
import com.example.narrator.epub.EpubParser
import com.example.narrator.pdf.PdfParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

sealed class ImportResult {
    data class Inserted(val book: BookEntity) : ImportResult()
    data class Duplicate(val existing: BookEntity, val pending: PendingImport) : ImportResult()
    data class Failed(val reason: String) : ImportResult()
}

/** Result of the prepare step. The UI inspects this and decides whether to commit
 *  (Ready / Duplicate's replace branch) or cancel (cleanup). */
sealed class PrepareResult {
    /** Parse succeeded and the book is new; UI shows the preview and waits for confirmation. */
    data class Ready(
        val title: String,
        val author: String,
        val chapterPreviews: List<ChapterPreview>,
        val pending: PendingImport,
    ) : PrepareResult()

    /** Book is already in the library; UI asks whether to replace. */
    data class Duplicate(val existing: BookEntity, val pending: PendingImport) : PrepareResult()

    data class Failed(val reason: String) : PrepareResult()
}

/** Per-chapter information shown in the import preview dialog. */
data class ChapterPreview(val title: String, val chunkCount: Int, val firstChars: String)

data class PendingImport(
    val title: String,
    val author: String,
    val epubPath: String,
    val coverPath: String?,
    val totalChunks: Int,
    val pageRangeStart: Int = 0,
    val pageRangeEnd: Int = 0,
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
    /** Single-shot import for external intents (file manager VIEW, share SEND) — no
     *  preview UI involved. Calls prepare + auto-commits when the parse succeeds. */
    suspend fun importFromUri(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        when (val r = prepareImport(uri, pageRange = null)) {
            is PrepareResult.Ready -> ImportResult.Inserted(commit(r.pending) ?: return@withContext
                ImportResult.Failed("Insert succeeded but row could not be read back"))
            is PrepareResult.Duplicate -> ImportResult.Duplicate(r.existing, r.pending)
            is PrepareResult.Failed -> ImportResult.Failed(r.reason)
        }
    }

    /** Counts pages in a PDF without parsing the full text. Returns null for non-PDFs or
     *  files we can't open. Used to populate the page-range dialog max value. */
    suspend fun peekPdfPageCount(uri: Uri): Int? = withContext(Dispatchers.IO) {
        if (!isPdfUri(uri)) return@withContext null
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                PDDocument.load(stream, "").use { doc -> doc.numberOfPages }
            }
        }.getOrNull()
    }

    /** Copies the source file to app-private storage, parses it (PDF parse honours the
     *  page range), and returns a PrepareResult the UI can act on. The on-disk file lives
     *  until the UI either calls commit() or cancel() on the PendingImport. */
    suspend fun prepareImport(uri: Uri, pageRange: IntRange?): PrepareResult = withContext(Dispatchers.IO) {
        val originalDisplayName = displayNameFor(uri)
        val format = detectFormat(uri, originalDisplayName)
        val sourceFile = repository.newSourceFile(format.extension)
        try {
            copyUriToFile(uri, sourceFile)
        } catch (e: Exception) {
            sourceFile.delete()
            return@withContext PrepareResult.Failed(e.message ?: "Could not read source file")
        }

        val parsed: Book = try {
            when (format) {
                SourceFormat.EPUB -> EpubParser.parse(sourceFile)
                SourceFormat.PDF -> PdfParser.parse(sourceFile, pageRange)
            }
        } catch (e: EpubParseException) {
            sourceFile.delete()
            return@withContext PrepareResult.Failed(e.message ?: "Could not parse file")
        } catch (e: Exception) {
            sourceFile.delete()
            return@withContext PrepareResult.Failed(e.message ?: "Unexpected parse error")
        }

        val coverFile = parsed.coverImage?.let { bytes ->
            val ext = (parsed.coverMimeType ?: "").toExtension()
            val f = repository.newCoverFile(ext)
            f.writeBytes(bytes)
            f
        }

        val finalTitle = if (parsed.title == UNKNOWN_TITLE && !originalDisplayName.isNullOrBlank()) {
            prettifyFilename(originalDisplayName)
        } else parsed.title

        val totalChunks = parsed.chapters.sumOf { it.chunks.size }
        val pending = PendingImport(
            title = finalTitle,
            author = parsed.author,
            epubPath = sourceFile.absolutePath,
            coverPath = coverFile?.absolutePath,
            totalChunks = totalChunks,
            pageRangeStart = pageRange?.first ?: 0,
            pageRangeEnd = pageRange?.last ?: 0,
        )

        val existing = repository.findByTitleAndAuthor(finalTitle, parsed.author)
        if (existing != null) return@withContext PrepareResult.Duplicate(existing, pending)

        val previews = parsed.chapters.map { ch ->
            ChapterPreview(
                title = ch.title,
                chunkCount = ch.chunks.size,
                firstChars = ch.chunks.joinToString(" ").take(160),
            )
        }
        PrepareResult.Ready(
            title = finalTitle,
            author = parsed.author,
            chapterPreviews = previews,
            pending = pending,
        )
    }

    /** Inserts the prepared book into the library. Returns the inserted entity or null
     *  on DB failure. */
    suspend fun commit(pending: PendingImport): BookEntity? = withContext(Dispatchers.IO) {
        val id = repository.insertBook(
            title = pending.title,
            author = pending.author,
            epubPath = pending.epubPath,
            coverPath = pending.coverPath,
            totalChunks = pending.totalChunks,
            pageRangeStart = pending.pageRangeStart,
            pageRangeEnd = pending.pageRangeEnd,
        )
        repository.getBook(id)
    }

    /** Drops a prepared import the user declined (preview cancel, dup keep-existing). */
    fun cancel(pending: PendingImport) { pending.cleanup() }

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
                pageRangeStart = pending.pageRangeStart,
                pageRangeEnd = pending.pageRangeEnd,
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

    private fun isPdfUri(uri: Uri): Boolean = detectFormat(uri, displayNameFor(uri)) == SourceFormat.PDF

    /** Picks parser by MIME first, then by filename extension. Defaults to EPUB so legacy
     *  imports (where the picker returns application/octet-stream) keep working. */
    private fun detectFormat(uri: Uri, displayName: String?): SourceFormat {
        val mime = context.contentResolver.getType(uri)?.lowercase()
        if (mime == "application/pdf") return SourceFormat.PDF
        if (mime == "application/epub+zip") return SourceFormat.EPUB
        val ext = displayName?.substringAfterLast('.', "")?.lowercase()
        return when (ext) {
            "pdf" -> SourceFormat.PDF
            else -> SourceFormat.EPUB
        }
    }

    private enum class SourceFormat(val extension: String) {
        EPUB("epub"),
        PDF("pdf"),
    }

    private companion object {
        // Must match EpubParser's hard-coded fallback string.
        const val UNKNOWN_TITLE = "Unknown title"
    }
}
