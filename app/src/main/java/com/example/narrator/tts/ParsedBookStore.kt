package com.example.narrator.tts

import com.example.narrator.data.BookEntity
import com.example.narrator.data.ParsedBookCache
import com.example.narrator.epub.Book
import com.example.narrator.epub.Chapter
import com.example.narrator.epub.EpubParser
import com.example.narrator.pdf.PdfParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Where Narrator gets a book's parsed content: an in-memory LRU of recently-read books, backed by
 * the on-disk [ParsedBookCache] (survives process death), backed by a full parse of the source
 * file. Extracted from Narrator, which mixed this with playback.
 *
 * Entries are keyed by book id and invalidated by [signature], so a skip-pattern edit, page-range
 * change or file replacement re-parses. Finished books are never cached (the user has moved on).
 *
 * Main-thread confined like Narrator: the LRU is only touched on the caller's (Main) thread; file
 * I/O and parsing hop to [Dispatchers.IO] and return.
 */
internal class ParsedBookStore(private val diskDir: File) {

    private data class Entry(val signature: String, val book: Book)

    // accessOrder = true makes this an LRU; removeEldestEntry caps it at MAX_IN_MEMORY.
    private val memory = object : LinkedHashMap<Long, Entry>(MAX_IN_MEMORY + 1, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Entry>): Boolean =
            size > MAX_IN_MEMORY
    }

    /** Memory hit for [book] at its current signature, or null. Instant — no spinner needed. */
    fun inMemory(book: BookEntity): Book? =
        memory[book.id]?.takeIf { it.signature == signature(book) }?.book

    /** Disk-cache hit for [book] (also warms memory), or null. Finished books aren't cached. */
    suspend fun fromDisk(book: BookEntity): Book? {
        if (book.isFinished) return null
        val sig = signature(book)
        val chapters = withContext(Dispatchers.IO) { ParsedBookCache.read(diskDir, book.id, sig) }
        return chapters?.let { Book(book.title, book.author, it, null, null) }
            ?.also { remember(book, sig, it) }
    }

    /** Full parse of [book]'s source file, cached in memory + on disk. Throws on parse failure. */
    suspend fun parse(book: BookEntity): Book {
        val sig = signature(book)
        val parsed = withContext(Dispatchers.IO) { parseBookFile(book) }
        remember(book, sig, parsed)
        if (!book.isFinished) {
            withContext(Dispatchers.IO) { ParsedBookCache.write(diskDir, book.id, sig, parsed.chapters) }
        }
        return parsed
    }

    /**
     * Pre-load [books] (most recent first) so switching to them is instant. Sequential and
     * failure-tolerant — a book that won't parse is just skipped.
     */
    suspend fun warm(books: List<BookEntity>) {
        for (book in books.take(MAX_IN_MEMORY)) {
            // Cheapest first; only a miss on memory AND disk pays for a parse.
            if (inMemory(book) == null && fromDisk(book) == null) runCatching { parse(book) }
        }
    }

    /** Drop [bookId] from memory and disk (before the book is deleted). */
    fun forget(bookId: Long) {
        memory.remove(bookId)
        ParsedBookCache.delete(diskDir, bookId)
    }

    /** Drop everything — ids are meaningless after a backup restore. */
    suspend fun clear() {
        memory.clear()
        withContext(Dispatchers.IO) { diskDir.deleteRecursively() }
    }

    private fun remember(book: BookEntity, sig: String, parsed: Book) {
        if (book.isFinished) {
            forget(book.id)
            return
        }
        // Drop cover bytes: the Player loads the cover from coverPath, so they're dead weight.
        memory[book.id] = Entry(sig, parsed.copy(coverImage = null, coverMimeType = null))
    }

    private fun signature(book: BookEntity): String =
        "${book.epubPath}|${book.pageRangeStart}|${book.pageRangeEnd}|${book.skipPatterns}"

    /** Picks the parser by source extension (stored with the file), applies the per-book page
     *  range (PDF only) and skip patterns. No cover render — playback reads it from coverPath. */
    private fun parseBookFile(book: BookEntity): Book {
        val file = File(book.epubPath)
        val raw = when (file.extension.lowercase()) {
            "pdf" -> {
                val range = if (book.pageRangeStart > 0 && book.pageRangeEnd >= book.pageRangeStart) {
                    book.pageRangeStart..book.pageRangeEnd
                } else null
                PdfParser.parse(file, range, includeCover = false)
            }
            else -> EpubParser.parse(file)
        }
        return raw.copy(chapters = applySkipPatterns(raw.chapters, book.skipPatterns))
    }

    companion object {
        /** Recently-read, non-finished books whose parse is kept in memory. */
        const val MAX_IN_MEMORY = 5
        private const val LOAD_FACTOR = 0.75f

        /**
         * Drops every chunk matching any of the newline-separated regexes in [patterns], then any
         * chapter left empty. Blank lines and invalid regexes are ignored (a typo in the Library
         * dialog must not break loading). Pure so the filter is unit-testable.
         */
        @androidx.annotation.VisibleForTesting
        internal fun applySkipPatterns(chapters: List<Chapter>, patterns: String): List<Chapter> {
            val regexes = patterns.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { runCatching { Regex(it) }.getOrNull() }
            if (regexes.isEmpty()) return chapters
            return chapters
                .map { ch -> ch.copy(chunks = ch.chunks.filterNot { c -> regexes.any { it.containsMatchIn(c) } }) }
                .filter { it.chunks.isNotEmpty() }
        }
    }
}
