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

    @Test
    fun `inserts missing space after period between sentences`() {
        // Regression for the 0.9.2 fix: PDF / EPUB content that drops the space after a
        // sentence-ending period should still split into separate sentences.
        val out = Sentences.split("First sentence.Second sentence here.Third sentence here.")
        assertEquals(
            "expected three chunks once the missing spaces are restored, got: $out",
            1, // all three are short enough to merge into one utterance
            out.size,
        )
        assertTrue(out[0].contains("First sentence. Second"))
        assertTrue(out[0].contains("here. Third"))
    }

    @Test
    fun `does not insert space inside acronyms`() {
        // U.S. is a single token, not three sentences. Guard against false positives.
        val out = Sentences.split("The U.S. is a country. Canada is too.")
        assertEquals(1, out.size)
        assertTrue("acronym should survive intact: ${out[0]}", out[0].contains("U.S."))
    }

    @Test
    fun `mid-sentence parenthetical starts its own chunk when the sentence is long`() {
        val text = "Something important was happening (the cause was unclear) and it " +
            "affected a great many things in the surrounding area."
        val out = Sentences.split(text)
        assertTrue("expected the long sentence to be sub-chunked, got: $out", out.size > 1)
        assertTrue(
            "expected a chunk to begin at the opening parenthesis, got: $out",
            out.any { it.startsWith("(") },
        )
    }

    @Test
    fun `strips numeric grouping commas so the engine reads the whole number`() {
        val out = Sentences.split("We raised 200,000 dollars for the cause.")
        assertEquals(1, out.size)
        assertTrue("expected grouping comma stripped, got: ${out[0]}", out[0].contains("200000"))
    }

    @Test
    fun `ordinary comma-laden sentence over the threshold is sub-chunked`() {
        // ~118 chars with commas: under the old 100 threshold this stayed whole; now it splits
        // so the engine can keep up in real time.
        val text = "Alice opened the door, stepped into the corridor, paused for a moment, " +
            "and looked around at the strange and silent place."
        val out = Sentences.split(text)
        assertTrue("expected sub-chunking of a long comma-laden sentence, got: $out", out.size > 1)
    }

    @Test
    fun `sub-chunker finds break past 150 char window`() {
        // Regression for 0.9.2: previously the search was capped at SUB_CHUNK_THRESHOLD * 1.5
        // (= 150 chars). A long sentence whose first clause break sits past that point was
        // hard-cut at character 500, breaking words mid-character. The fix removes the cap.
        // First clause break here is an en-dash at ~155 chars.
        val text = "This is a very long opening clause that goes on without any punctuation " +
            "for quite a while before finally arriving at the first separator " +
            "— at which point the second clause begins, and continues for a while, " +
            "and includes a comma, and another clause."
        val out = Sentences.split(text)
        assertTrue("expected the sentence to be sub-chunked, got 1 chunk: $out", out.size > 1)
        // Critically: no chunk should be hit by the 500-char hard cut.
        assertTrue(
            "no chunk should hit MAX_CHUNK_CHARS as a fallback hard cut, got ${out.map { it.length }}",
            out.all { it.length < Sentences.MAX_CHUNK_CHARS },
        )
    }
}
