package com.example.narrator.tts

import com.example.narrator.data.BookEntity
import com.example.narrator.epub.Book
import com.example.narrator.epub.Chapter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LoadedBook.from is the single seam that builds the Player's view-model from the DB row + the
 * parsed file. Regression net for the title-sync bug: title/author/cover MUST come from the DB
 * (user-editable), content from the parsed file. If this ever flips back to the parsed title,
 * the Library and Player diverge again.
 */
class LoadedBookTest {

    private fun book(title: String, author: String) = BookEntity(
        id = 7,
        title = title,
        author = author,
        epubPath = "/books/x.epub",
        coverPath = "/covers/x.png",
        totalChunks = 0,
        importedAt = 0L,
    )

    private val parsed = Book(
        title = "FILE EMBEDDED TITLE",
        author = "File Embedded Author",
        chapters = listOf(
            Chapter("Chapter One", listOf("a", "b", "c")),
            Chapter("Chapter Two", listOf("d", "e")),
        ),
        coverImage = null,
        coverMimeType = null,
    )

    @Test fun `title and author come from the DB row, not the parsed file`() {
        val loaded = LoadedBook.from(book("My Edited Title", "My Edited Author"), parsed, totalChunks = 5)
        assertEquals("My Edited Title", loaded.title)
        assertEquals("My Edited Author", loaded.author)
    }

    @Test fun `cover path comes from the DB row`() {
        val loaded = LoadedBook.from(book("t", "a"), parsed, totalChunks = 5)
        assertEquals("/covers/x.png", loaded.coverPath)
    }

    @Test fun `chapter structure comes from the parsed file`() {
        val loaded = LoadedBook.from(book("t", "a"), parsed, totalChunks = 5)
        assertEquals(listOf("Chapter One", "Chapter Two"), loaded.chapterTitles)
        assertEquals(listOf(3, 2), loaded.chapterChunkCounts)
        assertEquals(7L, loaded.bookId)
        assertEquals(5, loaded.totalChunks)
    }
}
