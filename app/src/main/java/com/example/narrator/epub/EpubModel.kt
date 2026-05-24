package com.example.narrator.epub

data class Book(
    val title: String,
    val author: String,
    val chapters: List<Chapter>,
    val coverImage: ByteArray?,
    val coverMimeType: String?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Book) return false
        return title == other.title &&
            author == other.author &&
            chapters == other.chapters &&
            coverMimeType == other.coverMimeType &&
            coverImage.contentEqualsOrNull(other.coverImage)
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + author.hashCode()
        result = 31 * result + chapters.hashCode()
        result = 31 * result + (coverImage?.contentHashCode() ?: 0)
        result = 31 * result + (coverMimeType?.hashCode() ?: 0)
        return result
    }
}

data class Chapter(
    val title: String,
    val chunks: List<String>,
)

class EpubParseException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

private fun ByteArray?.contentEqualsOrNull(other: ByteArray?): Boolean =
    if (this == null) other == null else other != null && this.contentEquals(other)
