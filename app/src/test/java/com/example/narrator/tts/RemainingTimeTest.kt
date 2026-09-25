package com.example.narrator.tts

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Remaining-time estimate. Samples are recorded normalised to 1.0x (wall time × speed); the
 * estimate divides by the current speed exactly once. The old code stored raw wall time and then
 * divided by speed again, so at 2x it showed a quarter of the real remaining time.
 */
class RemainingTimeTest {

    @Test fun `no estimate until three samples`() {
        assertEquals(0L, Narrator.estimateRemainingMs(8_000, 2, 100, 1.0f))
    }

    @Test fun `no estimate at the end of the book`() {
        assertEquals(0L, Narrator.estimateRemainingMs(12_000, 3, 0, 1.0f))
    }

    @Test fun `at 1x remaining is average times chunks`() {
        // 3 sentences of 4s each → 4s average; 100 left → 400s.
        assertEquals(400_000L, Narrator.estimateRemainingMs(12_000, 3, 100, 1.0f))
    }

    @Test fun `at 2x a sentence heard in 2s estimates half the 1x time, not a quarter`() {
        // A 4s-at-1x sentence plays in 2s wall time at 2x; recorded normalised as 2s × 2 = 4s.
        val samples1x = 3 * (2_000L * 2)
        assertEquals(200_000L, Narrator.estimateRemainingMs(samples1x, 3, 100, 2.0f))
    }

    @Test fun `mixed-speed samples stay comparable`() {
        // One 4s sentence heard at 1x (4s) and two heard at 2x (2s each), all normalised to 4s.
        // Now listening at 1.25x (exact in binary) → 400s / 1.25 = 320s.
        val samples1x = 4_000L + 2 * (2_000L * 2)
        assertEquals(320_000L, Narrator.estimateRemainingMs(samples1x, 3, 100, 1.25f))
    }
}
