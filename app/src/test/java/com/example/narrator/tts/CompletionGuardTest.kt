package com.example.narrator.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard that stops a pipeline completion from advancing the playhead unless it is for the
 * sentence currently at the playhead. A prefetched sentence's synth error used to be reported as
 * a completion immediately, pushing the caption / bookmark one sentence ahead of the audio.
 */
class CompletionGuardTest {

    private val here = Position(chapterIndex = 2, chunkIndex = 5, globalChunk = 40)

    @Test fun `completion for the playhead sentence advances`() {
        assertTrue(Narrator.isCompletionForPosition("n_2_5", here))
    }

    @Test fun `completion for a prefetched sentence is ignored`() {
        assertFalse(Narrator.isCompletionForPosition("n_2_7", here))
        assertFalse(Narrator.isCompletionForPosition("n_3_0", here))
    }

    @Test fun `completion for an earlier sentence is ignored`() {
        assertFalse(Narrator.isCompletionForPosition("n_2_4", here))
    }

    @Test fun `ids differing only by digit grouping do not collide`() {
        // "n_21_5" vs chapter 2 chunk 15 etc. — the separator keeps them distinct.
        assertFalse(Narrator.isCompletionForPosition("n_21_5", Position(2, 15, 0)))
        assertFalse(Narrator.isCompletionForPosition("n_2_15", Position(21, 5, 0)))
    }

    @Test fun `malformed or missing ids are ignored`() {
        assertFalse(Narrator.isCompletionForPosition(null, here))
        assertFalse(Narrator.isCompletionForPosition("", here))
        assertFalse(Narrator.isCompletionForPosition("n_2_5#0", here))
    }

    @Test fun `utterance id format is stable`() {
        assertEquals("n_2_5", Narrator.utteranceIdFor(here))
    }
}
