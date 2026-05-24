package com.example.narrator.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentencesTest {

    @Test
    fun `splits paragraph into sentences`() {
        val out = Sentences.split("Hello world. This is a test! Is it though?")
        assertEquals(listOf("Hello world.", "This is a test!", "Is it though?"), out)
    }

    @Test
    fun `returns empty list for blank input`() {
        assertEquals(emptyList<String>(), Sentences.split("   "))
    }

    @Test
    fun `soft-splits a sentence longer than the cap`() {
        val long = ("a, ".repeat(400)) + "end."
        val chunks = Sentences.split(long)
        assertTrue("expected multiple chunks for an overlong sentence", chunks.size > 1)
        assertTrue("each chunk respects the cap", chunks.all { it.length <= Sentences.MAX_CHUNK_CHARS })
    }
}
