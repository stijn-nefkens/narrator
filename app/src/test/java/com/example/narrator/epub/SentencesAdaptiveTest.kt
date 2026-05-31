package com.example.narrator.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the buffer-adaptive splitting path: splitSentences (merge-only position units),
 * subChunk (on-demand sub-chunking at a runtime budget), and budgetForDepth (the curve).
 */
class SentencesAdaptiveTest {

    // A ~150-char sentence with internal commas: stays whole at deep budgets, splits at shallow.
    private val longSentence =
        "Alice opened the door and stepped into the corridor, paused for a long moment " +
            "to look around, and then walked slowly toward the distant flickering light."

    // --- budgetForDepth curve --------------------------------------------

    @Test fun `budget grows with buffer depth`() {
        val d0 = Sentences.budgetForDepth(0)
        val d1 = Sentences.budgetForDepth(1)
        val d2 = Sentences.budgetForDepth(2)
        val d3 = Sentences.budgetForDepth(3)
        assertTrue(d0.target < d1.target)
        assertTrue(d1.target < d2.target)
        assertTrue(d2.target < d3.target)
        // Deep buffer = effectively no cutting until the hard cap.
        assertEquals(Sentences.MAX_CHUNK_CHARS, d3.threshold)
    }

    @Test fun `negative or zero depth is the cold-start budget`() {
        assertEquals(Sentences.budgetForDepth(0), Sentences.budgetForDepth(-5))
    }

    // --- subChunk respects the budget ------------------------------------

    @Test fun `cold-start budget sub-chunks a long sentence`() {
        val parts = Sentences.subChunk(longSentence, Sentences.budgetForDepth(0))
        assertTrue("expected cold start to split, got: $parts", parts.size > 1)
    }

    @Test fun `deep buffer keeps the whole sentence as one chunk`() {
        val parts = Sentences.subChunk(longSentence, Sentences.budgetForDepth(3))
        assertEquals("expected deep buffer to keep it whole, got: $parts", 1, parts.size)
        assertEquals(longSentence, parts[0])
    }

    @Test fun `mid buffer cuts less than cold start`() {
        val cold = Sentences.subChunk(longSentence, Sentences.budgetForDepth(0)).size
        val mid = Sentences.subChunk(longSentence, Sentences.budgetForDepth(2)).size
        assertTrue("expected fewer pieces at depth 2 ($mid) than depth 0 ($cold)", mid <= cold)
    }

    @Test fun `subChunk never breaks a word mid-character`() {
        for (depth in 0..3) {
            val parts = Sentences.subChunk(longSentence, Sentences.budgetForDepth(depth))
            val rejoinedWords = parts.joinToString(" ").split(Regex("\\s+"))
            val originalWords = longSentence.split(Regex("\\s+"))
            assertEquals("word integrity broken at depth $depth", originalWords, rejoinedWords)
        }
    }

    @Test fun `blank sentence yields nothing`() {
        assertEquals(emptyList<String>(), Sentences.subChunk("   ", Sentences.budgetForDepth(0)))
    }

    // --- splitSentences keeps long sentences as single units --------------

    @Test fun `splitSentences keeps a long sentence as one unit`() {
        val units = Sentences.splitSentences(longSentence)
        assertEquals("a long sentence is one position unit, not pre-split: $units", 1, units.size)
    }

    @Test fun `splitSentences still merges short dialogue`() {
        val units = Sentences.splitSentences("\"Hi!\" she said. \"Bye,\" he replied.")
        assertEquals(1, units.size)
    }

    // --- parse-time split unchanged (regression) -------------------------

    @Test fun `eager split still sub-chunks long sentences as before`() {
        val out = Sentences.split(longSentence)
        assertTrue("parse-time split should still chunk a long sentence", out.size > 1)
        assertTrue(out.all { it.length <= Sentences.MAX_CHUNK_CHARS })
    }
}
