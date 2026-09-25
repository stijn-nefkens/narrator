package com.example.narrator.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookIndexTest {

    // Chapters of 3, 1 and 4 chunks → globals 0-2 | 3 | 4-7.
    private val index = BookIndex(listOf(3, 1, 4))

    @Test fun `total and chapter starts`() {
        assertEquals(8, index.total)
        assertEquals(0, index.chapterStart(0))
        assertEquals(3, index.chapterStart(1))
        assertEquals(4, index.chapterStart(2))
        assertEquals(listOf(3, 4), index.chapterBoundaries())
    }

    @Test fun `chapterStart clamps out-of-range chapters`() {
        assertEquals(0, index.chapterStart(-1))
        assertEquals(4, index.chapterStart(99))
    }

    @Test fun `position computes the global index and clamps`() {
        assertEquals(Position(2, 1, 5), index.position(2, 1))
        assertEquals(Position(0, 0, 0), index.position(-5, -5))
        assertEquals(Position(2, 3, 7), index.position(9, 9))     // past the end → last chunk
        assertEquals(Position(1, 0, 3), index.position(1, 50))    // chunk clamped within chapter
    }

    @Test fun `fromGlobal round-trips every position`() {
        for (g in 0 until index.total) {
            val p = index.fromGlobal(g)
            assertEquals(g, p.globalChunk)
            assertEquals(p, index.position(p.chapterIndex, p.chunkIndex))
        }
    }

    @Test fun `fromGlobal clamps`() {
        assertEquals(Position(0, 0, 0), index.fromGlobal(-3))
        assertEquals(Position(2, 3, 7), index.fromGlobal(100))
    }

    @Test fun `advance and retreat cross chapter boundaries and stop at the ends`() {
        val start = index.position(0, 2)
        assertEquals(Position(1, 0, 3), index.advance(start, 1))
        assertEquals(Position(2, 0, 4), index.advance(start, 2))
        assertEquals(Position(2, 3, 7), index.advance(start, 50))
        assertEquals(Position(0, 1, 1), index.retreat(index.position(0, 2), 1))
        assertEquals(Position(0, 2, 2), index.retreat(index.position(1, 0), 1))
        assertEquals(Position(0, 0, 0), index.retreat(index.position(2, 0), 50))
    }

    @Test fun `ahead returns null past the end`() {
        assertEquals(Position(2, 3, 7), index.ahead(index.position(2, 0), 3))
        assertNull(index.ahead(index.position(2, 0), 4))
    }

    @Test fun `isLast only at the final chunk`() {
        assertTrue(index.isLast(index.position(2, 3)))
        assertFalse(index.isLast(index.position(2, 2)))
    }

    @Test fun `empty chapters are skipped`() {
        val gappy = BookIndex(listOf(2, 0, 2))
        assertEquals(Position(2, 0, 2), gappy.fromGlobal(2))
        assertEquals(Position(2, 0, 2), gappy.advance(gappy.position(0, 1), 1))
    }

    @Test fun `empty book is all zeros`() {
        val empty = BookIndex(emptyList())
        assertEquals(0, empty.total)
        assertEquals(Position(0, 0, 0), empty.fromGlobal(5))
        assertEquals(Position(0, 0, 0), empty.position(3, 3))
        assertNull(empty.ahead(Position(0, 0, 0), 1))
        assertTrue(empty.chapterBoundaries().isEmpty())
    }
}
