package com.example.narrator.epub

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the parser against a real .epub on disk so we can eyeball the output
 * before wiring it to TTS. Skipped if no fixture is present.
 *
 * Fixture lookup order:
 *   1. -Depub.fixture=<absolute path>
 *   2. <project-root>/alices_adventures_in_wonderland.epub
 */
class RealEpubSmokeTest {

    @Test
    fun `parses a real EPUB and prints a summary`() {
        val file = locateFixture()
        assumeTrue("No real EPUB fixture available — skipping", file != null && file.exists())
        file!!

        val book = EpubParser.parse(file)

        println("=".repeat(60))
        println("File:    ${file.name} (${file.length()} bytes)")
        println("Title:   ${book.title}")
        println("Author:  ${book.author}")
        println("Cover:   ${book.coverImage?.size ?: 0} bytes (${book.coverMimeType ?: "n/a"})")
        println("Chapters: ${book.chapters.size}")
        println("Total chunks: ${book.chapters.sumOf { it.chunks.size }}")
        println("-".repeat(60))
        book.chapters.take(5).forEachIndexed { i, ch ->
            println("[Chapter ${i + 1}] \"${ch.title}\" (${ch.chunks.size} chunks)")
            ch.chunks.take(3).forEachIndexed { j, chunk ->
                val preview = if (chunk.length > 120) chunk.substring(0, 117) + "..." else chunk
                println("    ${j + 1}. $preview")
            }
            if (ch.chunks.size > 3) println("    ... +${ch.chunks.size - 3} more")
        }
        if (book.chapters.size > 5) println("... +${book.chapters.size - 5} more chapters")
        println("=".repeat(60))

        assertTrue("expected at least one chapter", book.chapters.isNotEmpty())
        assertTrue("expected at least one chunk", book.chapters.any { it.chunks.isNotEmpty() })
        assertTrue("expected non-empty title", book.title.isNotBlank())
    }

    private fun locateFixture(): File? {
        System.getProperty("epub.fixture")?.let {
            val f = File(it)
            if (f.exists()) return f
        }
        // Test working dir is the module dir (app/); walk up one for project root.
        val candidate = File("..", "alices_adventures_in_wonderland.epub")
        return if (candidate.exists()) candidate else null
    }
}
