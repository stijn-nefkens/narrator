package com.example.narrator

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverSampleSizeTest {

    @Test fun `small covers are decoded at full size`() {
        assertEquals(1, coverSampleSize(300, 450, 512))
        assertEquals(1, coverSampleSize(512, 800, 512))  // longest 800 / 2 = 400 < 512
    }

    @Test fun `large covers subsample by powers of two, never below the target`() {
        assertEquals(2, coverSampleSize(1024, 1536, 512))  // 1536 → 768
        assertEquals(4, coverSampleSize(1600, 2400, 512))  // 2400 → 600
        assertEquals(8, coverSampleSize(3000, 4500, 512))  // 4500 → 562
    }

    @Test fun `landscape uses the longer side`() {
        assertEquals(4, coverSampleSize(2400, 1600, 512))
    }
}
