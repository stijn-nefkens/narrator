package com.example.narrator.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class TextCleanerTest {

    @Test fun `strips soft hyphens hidden inside words`() {
        val out = TextCleaner.clean("hyphe­nated")
        assertEquals("hyphenated", out)
    }

    @Test fun `expands common ligatures`() {
        assertEquals("efficient flight", TextCleaner.clean("eﬃcient ﬂight"))
    }

    @Test fun `restores missing post-period spaces`() {
        // Same behaviour Sentences.split applies — TextCleaner runs it per-line so the
        // PDF assembly stage also benefits before sentence splitting.
        val out = TextCleaner.clean("First.Second one.")
        assertEquals("First. Second one.", out)
    }

    @Test fun `does not break acronyms`() {
        val out = TextCleaner.clean("The U.S. and U.K.")
        assertEquals("The U.S. and U.K.", out)
    }

    @Test fun `replaces URLs with spoken placeholder`() {
        val out = TextCleaner.clean("Visit https://example.com/page for more.")
        assertEquals("Visit link for more.", out)
    }

    @Test fun `replaces email addresses`() {
        val out = TextCleaner.clean("Contact foo@bar.example for info.")
        assertEquals("Contact email address for info.", out)
    }

    @Test fun `strips leading bullet glyphs`() {
        assertEquals("item one", TextCleaner.clean("• item one"))
        assertEquals("item two", TextCleaner.clean("◦ item two"))
    }

    @Test fun `keeps bullets mid-line alone`() {
        // Not a leading bullet; leave it for the engine to decide.
        val out = TextCleaner.clean("two items: foo • bar")
        assertEquals("two items: foo • bar", out)
    }

    @Test fun `expands abbreviations`() {
        assertEquals(
            "Doctor Smith said for example you and others.",
            TextCleaner.clean("Dr. Smith said e.g. you et al."),
        )
    }

    @Test fun `converts roman numerals only in chapter headings`() {
        // Plain "Chapter XIV" should become "Chapter 14"...
        assertEquals("Chapter 14 begins", TextCleaner.clean("Chapter XIV begins"))
        // ...but a standalone "I" elsewhere should not turn into "1".
        assertEquals("I had a thought", TextCleaner.clean("I had a thought"))
    }

    @Test fun `cleanTitle converts standalone roman numerals`() {
        // Heading-only conversion: bare numerals in titles are usually section numbers.
        assertEquals("Part 4", TextCleaner.cleanTitle("Part IV"))
        assertEquals("14. The march", TextCleaner.cleanTitle("XIV. The march"))
    }

    @Test fun `collapses weird unicode whitespace`() {
        val out = TextCleaner.clean("a  b\t  c")
        assertEquals("a b c", out)
    }

    @Test fun `empty input is a no-op`() {
        assertEquals("", TextCleaner.clean(""))
        assertEquals("", TextCleaner.cleanTitle(""))
    }
}
