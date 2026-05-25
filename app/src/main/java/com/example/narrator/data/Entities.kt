package com.example.narrator.data

data class BookEntity(
    val id: Long,
    val title: String,
    val author: String,
    val epubPath: String,
    val coverPath: String?,
    val totalChunks: Int,
    val importedAt: Long,
    val playbackSpeed: Float = 1.0f,
    /** Inclusive 1-based page range to parse from the source file. 0 in either field means
     *  "no restriction"; only applied for PDFs. */
    val pageRangeStart: Int = 0,
    val pageRangeEnd: Int = 0,
    /** Newline-separated regex patterns; chunks matching any pattern are dropped at load. */
    val skipPatterns: String = "",
    /** User-marked "finished" flag — purely cosmetic; the library shows a badge but doesn't
     *  hide the book unless the user explicitly filters. */
    val isFinished: Boolean = false,
)

data class SavedBookmark(
    val id: Long,
    val bookId: Long,
    val chapterIndex: Int,
    val chunkIndex: Int,
    val globalChunk: Int,
    val label: String?,
    val createdAt: Long,
)

data class Bookmark(
    val bookId: Long,
    val chapterIndex: Int,
    val chunkIndex: Int,
    val globalChunk: Int,
    val updatedAt: Long,
)

data class BookWithProgress(
    val book: BookEntity,
    val bookmark: Bookmark?,
) {
    val progressPercent: Int
        get() {
            val total = book.totalChunks
            val done = bookmark?.globalChunk ?: 0
            return if (total <= 0) 0 else ((done.toDouble() / total) * 100).toInt().coerceIn(0, 100)
        }
}
