package com.example.narrator.tts

import com.example.narrator.epub.Chapter
import org.junit.Assert.assertEquals
import org.junit.Test

class SkipPatternsTest {

    private val chapters = listOf(
        Chapter("One", listOf("Keep this.", "Figure 3 caption.", "And this.")),
        Chapter("Two", listOf("Figure 4 only.")),
        Chapter("Three", listOf("Final words.")),
    )

    @Test fun `blank patterns leave the book untouched`() {
        assertEquals(chapters, ParsedBookStore.applySkipPatterns(chapters, ""))
        assertEquals(chapters, ParsedBookStore.applySkipPatterns(chapters, "  \n\n "))
    }

    @Test fun `matching chunks are dropped and emptied chapters removed`() {
        val out = ParsedBookStore.applySkipPatterns(chapters, "^Figure \\d+")
        assertEquals(
            listOf(
                Chapter("One", listOf("Keep this.", "And this.")),
                Chapter("Three", listOf("Final words.")),
            ),
            out,
        )
    }

    @Test fun `multiple newline-separated patterns all apply`() {
        val out = ParsedBookStore.applySkipPatterns(chapters, "Figure\n  Final  \n")
        assertEquals(listOf(Chapter("One", listOf("Keep this.", "And this."))), out)
    }

    @Test fun `an invalid regex is ignored rather than failing the load`() {
        val out = ParsedBookStore.applySkipPatterns(chapters, "([unclosed\nFinal")
        assertEquals(listOf(chapters[0], chapters[1]), out)
    }
}
