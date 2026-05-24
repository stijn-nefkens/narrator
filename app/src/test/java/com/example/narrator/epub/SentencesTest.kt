package com.example.narrator.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentencesTest {

    @Test
    fun `merges short sentences within a paragraph so they read as one utterance`() {
        val out = Sentences.split("Hello world. This is a test! Is it though?")
        // All three combined are 42 chars, under MERGE_TARGET; the engine renders one
        // continuous utterance with natural pauses from the punctuation.
        assertEquals(1, out.size)
        assertEquals("Hello world. This is a test! Is it though?", out[0])
    }

    @Test
    fun `merges dialogue and tag so quoted speech reads naturally`() {
        val out = Sentences.split(
            "\"Why with an M?\" said Alice. \"Why not?\" said the March Hare. Alice was silent."
        )
        assertEquals(
            "expected the dialogue exchange to merge into a single chunk, got: $out",
            1, out.size,
        )
    }

    @Test
    fun `does not merge when combined would exceed target`() {
        // Two ~50-char sentences shouldn't merge (combined ~100 > 80 target).
        val a = "Alice opened the door and stepped into the long corridor."  // 57 chars
        val b = "She paused for a moment to look around at the strange place."  // 60 chars
        val out = Sentences.split("$a $b")
        assertEquals(listOf(a, b), out)
    }

    @Test
    fun `returns empty list for blank input`() {
        assertEquals(emptyList<String>(), Sentences.split("   "))
    }

    @Test
    fun `short sentences are not sub-chunked`() {
        val out = Sentences.split("Just a normal length sentence with one comma, and an ending.")
        assertEquals(1, out.size)
    }

    @Test
    fun `long sentence is split at em-dash`() {
        val text = "The Dormouse had closed its eyes by this time, and was going off into a " +
            "doze; but, on being pinched by the Hatter, it woke up again with a little shriek, " +
            "and went on: --that begins with an M, such as mouse-traps, and the moon, and " +
            "memory, and muchness-- you know you say things are much of a muchness."
        val out = Sentences.split(text)
        assertTrue("expected multiple sub-chunks, got ${out.size}: $out", out.size >= 3)
        // No sub-chunk should be much longer than the threshold * 1.5
        assertTrue(
            "expected all sub-chunks <= ~225 chars, got ${out.map { it.length }}",
            out.all { it.length <= 250 },
        )
    }

    @Test
    fun `comma-only long sentence is split at commas`() {
        val text = ("word, ".repeat(60)).trim() + " end."
        val out = Sentences.split(text)
        assertTrue("expected multiple sub-chunks for long comma-only sentence", out.size > 1)
    }

    @Test
    fun `sentence with no clause breaks falls back to hard split`() {
        val long = "a".repeat(800) + "."
        val out = Sentences.split(long)
        assertTrue(out.size > 1)
        assertTrue(out.all { it.length <= Sentences.MAX_CHUNK_CHARS })
    }
}
