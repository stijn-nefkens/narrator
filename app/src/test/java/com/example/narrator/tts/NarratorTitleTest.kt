package com.example.narrator.tts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure title-matching heuristic that decides whether a chapter's first chunk is
 * its spoken heading (and so should be followed by a brief pause). The full
 * isChapterTitlePosition needs a loaded book + Android context; this covers the text logic.
 */
class NarratorTitleTest {

    @Test fun `exact heading matches the title`() {
        assertTrue(Narrator.titleLike("Down the Rabbit-Hole", "Down the Rabbit-Hole"))
    }

    @Test fun `match ignores case and punctuation`() {
        assertTrue(Narrator.titleLike("CHAPTER III.", "Chapter III"))
        assertTrue(Narrator.titleLike("The Pool of Tears", "the pool of tears!"))
    }

    @Test fun `heading containing the title still matches`() {
        // In-body heading carries a number the TOC title omits (or vice versa).
        assertTrue(Narrator.titleLike("Chapter 3 Down the Rabbit Hole", "Down the Rabbit Hole"))
        assertTrue(Narrator.titleLike("Introduction", "Introduction to the Work"))
    }

    @Test fun `ordinary opening sentence does not match the title`() {
        assertFalse(
            Narrator.titleLike(
                "It was a bright cold day in April.",
                "Chapter One",
            )
        )
    }

    @Test fun `blank inputs never match`() {
        assertFalse(Narrator.titleLike("", "Chapter One"))
        assertFalse(Narrator.titleLike("Chapter One", "   "))
    }
}
