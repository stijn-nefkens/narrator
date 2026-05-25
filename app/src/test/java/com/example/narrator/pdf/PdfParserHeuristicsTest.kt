package com.example.narrator.pdf

import com.example.narrator.epub.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-Kotlin tests for the heuristics PdfParser uses to identify front-/back-matter
 * chapters and to assemble paragraph text. End-to-end parsing needs PDFBox + Android
 * Bitmap so it lives in instrumented tests; these cover the decision logic in isolation.
 */
class PdfParserHeuristicsTest {

    // --- chapter classifiers ----------------------------------------------

    @Test fun `title contents marks front matter`() {
        assertTrue(PdfParser.titleSuggestsFrontMatter("Contents"))
        assertTrue(PdfParser.titleSuggestsFrontMatter("Table of Contents"))
        assertTrue(PdfParser.titleSuggestsFrontMatter("Index"))
        assertTrue(PdfParser.titleSuggestsFrontMatter("Bibliography"))
        assertTrue(PdfParser.titleSuggestsFrontMatter("References"))
    }

    @Test fun `title preface or foreword is real content, not front matter`() {
        // These often contain narratable content; the heuristic must keep them.
        assertFalse(PdfParser.titleSuggestsFrontMatter("Preface"))
        assertFalse(PdfParser.titleSuggestsFrontMatter("Foreword"))
        assertFalse(PdfParser.titleSuggestsFrontMatter("Acknowledgements"))
        assertFalse(PdfParser.titleSuggestsFrontMatter("Chapter 1"))
    }

    @Test fun `toc-shaped body is detected even with neutral title`() {
        // Many short lines, mostly "Chapter N" markers, no real prose.
        val chunks = (1..10).map { "Chapter $it" } + listOf("Conclusion")
        val c = Chapter(title = "Untitled", chunks = chunks)
        assertTrue(PdfParser.looksLikeToc(c))
    }

    @Test fun `normal chapter is not toc-shaped`() {
        val c = Chapter(
            title = "Chapter 1",
            chunks = listOf(
                "It was the best of times, it was the worst of times, it was the age of wisdom.",
                "It was the age of foolishness, it was the epoch of belief.",
                "It was the spring of hope, it was the winter of despair.",
            ),
        )
        assertFalse(PdfParser.looksLikeToc(c))
    }

    @Test fun `copyright page is detected by body markers`() {
        val c = Chapter(
            title = "Some Page",
            chunks = listOf(
                "Copyright (c) 2024 The Publisher.",
                "All rights reserved. ISBN 978-1-2345-6789-0.",
                "First published 2024. Printed in the Netherlands.",
            ),
        )
        assertTrue(PdfParser.looksLikeCopyrightPage(c))
    }

    @Test fun `long real chapter that happens to mention copyright is kept`() {
        // Single mention of "copyright" in a long body shouldn't trigger the drop.
        val long = "x".repeat(2000)
        val c = Chapter(title = "Real Chapter", chunks = listOf("This chapter mentions copyright once.", long))
        assertFalse(PdfParser.looksLikeCopyrightPage(c))
    }

    @Test fun `reference list is detected`() {
        val chunks = (1..10).map { i ->
            "Smith, J. (2019) Some paper title $i. Journal of Stuff."
        }
        val c = Chapter(title = "Some title", chunks = chunks)
        assertTrue(PdfParser.looksLikeReferenceList(c))
    }

    @Test fun `index is detected`() {
        val chunks = listOf(
            "Alice 14", "Bob 27, 81", "Caterpillar 5", "Cheshire Cat 92, 103",
            "Dodo 18", "Dormouse 145", "Duchess 76", "Hatter 81-83",
            "March Hare 91", "Mock Turtle 109",
        )
        val c = Chapter(title = "untitled", chunks = chunks)
        assertTrue(PdfParser.looksLikeIndex(c))
    }

    @Test fun `image caption is detected`() {
        assertTrue(PdfParser.looksLikeImageCaption("Figure 3. Population growth 1900-2000."))
        assertTrue(PdfParser.looksLikeImageCaption("Table 7: Voting results by region."))
        assertTrue(PdfParser.looksLikeImageCaption("Fig. 12 Map of the territory."))
    }

    @Test fun `regular sentence starting with figure is not flagged as caption`() {
        // No digit follows; this is just narrative prose mentioning figures.
        assertFalse(PdfParser.looksLikeImageCaption(
            "Figure prominently in his memory was the day they met at the railway station."
        ))
    }

    // --- paragraph assembly ----------------------------------------------

    @Test fun `joins consecutive lines with spaces`() {
        val out = PdfParser.linesToParagraphs(
            listOf("It was the best of times,", "it was the worst of times.")
        )
        assertEquals(1, out.size)
        assertEquals("It was the best of times, it was the worst of times.", out[0])
    }

    @Test fun `blank line separates paragraphs`() {
        val out = PdfParser.linesToParagraphs(
            listOf("First paragraph.", "", "Second paragraph.")
        )
        assertEquals(listOf("First paragraph.", "Second paragraph."), out)
    }

    @Test fun `dehyphenates word split across a line break`() {
        val out = PdfParser.linesToParagraphs(listOf("hyphen-", "ated word"))
        assertEquals(1, out.size)
        assertEquals("hyphenated word", out[0])
    }

    @Test fun `keeps real hyphens between words`() {
        // "twenty-" + "year" is a line break inside a compound; the heuristic prefers
        // joining when the second piece starts lowercase, which is the common case.
        val out = PdfParser.linesToParagraphs(listOf("twenty-", "year-old"))
        assertEquals(1, out.size)
        assertEquals("twentyyear-old", out[0])
    }
}
