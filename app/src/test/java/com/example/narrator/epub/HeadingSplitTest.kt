package com.example.narrator.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for Sentences.splitHeadingFromBody — splitting a chapter heading glued to the first body
 * sentence ("Chapter One It was a dark night." → ["Chapter One.", "It was a dark night."]) so the
 * heading becomes its own paragraph (and thus its own chunk, escaping the dialogue-merge) and the
 * chapter-title pause can fire. Headings carry no period, which is what causes the glue.
 */
class HeadingSplitTest {

    @Test fun `splits a glued heading into two paragraphs`() {
        assertEquals(
            listOf("Chapter One.", "It was a dark and stormy night."),
            Sentences.splitHeadingFromBody("Chapter One It was a dark and stormy night.", "Chapter One"),
        )
    }

    @Test fun `match is case-insensitive`() {
        assertEquals(
            listOf("CHAPTER ONE.", "The story begins here."),
            Sentences.splitHeadingFromBody("CHAPTER ONE The story begins here.", "Chapter One"),
        )
    }

    @Test fun `leaves a paragraph that is only the title as one element`() {
        // h-tag case: heading already its own paragraph → its own chunk; nothing to split.
        assertEquals(listOf("Chapter One"), Sentences.splitHeadingFromBody("Chapter One", "Chapter One"))
    }

    @Test fun `splits even when the title is already terminated`() {
        // "Chapter One. body" → heading still pulled into its own paragraph so it escapes merge.
        assertEquals(
            listOf("Chapter One.", "It begins."),
            Sentences.splitHeadingFromBody("Chapter One. It begins.", "Chapter One"),
        )
    }

    @Test fun `no-op when the paragraph does not start with the title`() {
        val p = "It was the best of times, in the chapter of our lives."
        assertEquals(listOf(p), Sentences.splitHeadingFromBody(p, "Chapter One"))
    }

    @Test fun `no-op for a blank or null title`() {
        val p = "Some paragraph text here."
        assertEquals(listOf(p), Sentences.splitHeadingFromBody(p, ""))
        assertEquals(listOf(p), Sentences.splitHeadingFromBody(p, null))
        assertEquals(listOf(p), Sentences.splitHeadingFromBody(p, "   "))
    }

    @Test fun `does not split when the title is a prefix of a longer first word`() {
        // "Art" must not split "Artisanal bread is...".
        val p = "Artisanal bread is wonderful."
        assertEquals(listOf(p), Sentences.splitHeadingFromBody(p, "Art"))
    }

    @Test fun `the heading paragraph stays its own chunk and is not merged into the body`() {
        // The fix's real guarantee: because the heading is its own PARAGRAPH, splitting each
        // paragraph independently keeps the short heading from being dialogue-merged onto the
        // first body sentence. The chapter-title detector then sees a short chunk 0 == the title.
        val parts = Sentences.splitHeadingFromBody(
            "Down the Rabbit-Hole Alice was beginning to get very tired of sitting.",
            "Down the Rabbit-Hole",
        )
        val headingChunks = Sentences.splitSentences(parts[0])
        assertEquals(listOf("Down the Rabbit-Hole."), headingChunks)
        // And the body is a separate paragraph, so it never fuses with the heading.
        val bodyChunks = Sentences.splitSentences(parts[1])
        assertTrue(bodyChunks.none { it.contains("Rabbit-Hole") })
    }
}
