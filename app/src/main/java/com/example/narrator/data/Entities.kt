package com.example.narrator.data

data class BookEntity(
    val id: Long,
    val title: String,
    val author: String,
    val epubPath: String,
    val coverPath: String?,
    val totalChunks: Int,
    val importedAt: Long,
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
