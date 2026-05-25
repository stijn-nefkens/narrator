package com.example.narrator.pdf

import android.graphics.Bitmap
import com.example.narrator.epub.Book
import com.example.narrator.epub.Chapter
import com.example.narrator.epub.EpubParseException
import com.example.narrator.epub.Sentences
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Locale

/**
 * PDF parser that produces the same [Book] structure as [com.example.narrator.epub.EpubParser]
 * so the existing chunker + TTS pipeline can consume PDFs transparently.
 *
 * Two layout problems with PDFs that EPUBs don't have:
 *   1. PDFs are visual layouts; we have to *infer* paragraph and chapter structure from
 *      whitespace and font-size patterns rather than read it from semantic markup.
 *   2. Most non-fiction PDFs have running headers, footers, and page numbers that repeat
 *      on every page and would otherwise read out loud as noise between every page.
 *
 * Our approach:
 *   - Use the document outline (TOC) for chapter splits if one is present — many publisher
 *     PDFs have it, including the Dutch Political System fixture we test against.
 *   - Fall back to font-size heuristics: any line whose font is noticeably larger than the
 *     body text becomes a chapter break.
 *   - Strip lines that recur near the top or bottom of most pages (running header / footer
 *     / page numbers).
 *   - Rejoin words split across a line break by an end-of-line hyphen.
 *   - Refuse encrypted PDFs and image-only ("scanned") PDFs with clear errors rather than
 *     importing an empty book.
 */
object PdfParser {

    fun parse(file: File, pageRange: IntRange? = null): Book =
        FileInputStream(file).use { parse(it, pageRange) }

    fun parse(input: InputStream, pageRange: IntRange? = null): Book {
        // PDFBox marks any PDF with security handlers as "encrypted" — including the very
        // common case of publisher PDFs that have an owner password (preventing copy/print
        // permission changes) but an EMPTY user password (so anyone can read them). Those
        // load fine with the empty-password attempt below. Only a truly password-locked PDF
        // throws InvalidPasswordException.
        val doc = try {
            PDDocument.load(input, "")
        } catch (e: InvalidPasswordException) {
            throw EpubParseException("This PDF is password-protected and can't be imported.")
        } catch (e: Exception) {
            throw EpubParseException("Failed to read PDF", e)
        }
        doc.use {
            val effectiveRange = pageRange?.let { r ->
                val start = r.first.coerceAtLeast(1)
                val end = r.last.coerceAtMost(it.numberOfPages).coerceAtLeast(start)
                start..end
            } ?: (1..it.numberOfPages)
            val rawLines = extractLines(it, effectiveRange)
            if (rawLines.none { ln -> ln.text.isNotBlank() }) {
                throw EpubParseException(
                    "This PDF has no selectable text. Run it through OCR first."
                )
            }

            val cleanedLines = stripHeadersAndFooters(rawLines)

            val tocChapters = readOutline(it)
            val chapterStarts = if (tocChapters.isNotEmpty()) {
                tocChapters
            } else {
                detectChaptersByFontSize(cleanedLines)
            }

            val rawChapters = assembleChapters(cleanedLines, chapterStarts)
            val chapters = dropFrontMatter(rawChapters)
            if (chapters.isEmpty()) {
                throw EpubParseException("Could not extract any readable chapters from this PDF.")
            }

            val cover = renderCover(it, effectiveRange.first - 1)

            return Book(
                title = it.documentInformation?.title?.takeIf { t -> t.isNotBlank() }
                    ?: "Unknown title",
                author = it.documentInformation?.author?.takeIf { a -> a.isNotBlank() }
                    ?: "Unknown author",
                chapters = chapters,
                coverImage = cover,
                coverMimeType = cover?.let { "image/png" },
            )
        }
    }

    // --- text extraction --------------------------------------------------

    private data class StyledLine(val text: String, val fontSize: Float, val page: Int)

    private fun extractLines(doc: PDDocument, pageRange: IntRange): List<StyledLine> {
        val collected = mutableListOf<StyledLine>()
        val stripper = object : PDFTextStripper() {
            override fun writeString(text: String, textPositions: List<TextPosition>) {
                // Average font size for this run; PDFBox's "line" granularity is per writeString
                // call which already corresponds to a visual line under sort-by-position mode.
                val avg = if (textPositions.isEmpty()) {
                    0f
                } else {
                    var sum = 0f
                    for (p in textPositions) sum += p.fontSizeInPt
                    sum / textPositions.size
                }
                collected.add(StyledLine(text, avg, currentPageNo))
                super.writeString(text, textPositions)
            }
        }
        stripper.sortByPosition = true
        stripper.startPage = pageRange.first
        stripper.endPage = pageRange.last
        // Discard PDFBox's accumulated text; we use writeString as a hook only.
        stripper.getText(doc)
        return collected
    }

    // --- header / footer strip --------------------------------------------

    /** Drops lines that recur as a near-identical short string at the top or bottom of most
     *  pages. Page numbers (which usually vary by page) get caught by an additional all-digit
     *  / very-short-line check. */
    private fun stripHeadersAndFooters(lines: List<StyledLine>): List<StyledLine> {
        if (lines.isEmpty()) return lines
        val pageCount = (lines.maxOf { it.page }).coerceAtLeast(1)
        if (pageCount < 3) return lines  // Not enough pages to identify a "recurring" line.

        val byPage = lines.groupBy { it.page }
        val firstLines = byPage.mapNotNull { it.value.firstOrNull() }
        val lastLines = byPage.mapNotNull { it.value.lastOrNull() }

        val headerKeys = recurringKeys(firstLines, pageCount)
        val footerKeys = recurringKeys(lastLines, pageCount)
        val drop = headerKeys + footerKeys

        return lines.filter { ln ->
            val key = normalize(ln.text)
            // Always drop standalone page-number lines (1-4 chars, mostly digits) — they don't
            // need to recur identically; a varying "23" / "24" / "25" still doesn't read well.
            if (isProbablePageNumber(ln.text)) return@filter false
            key !in drop
        }
    }

    private fun recurringKeys(candidates: List<StyledLine>, pageCount: Int): Set<String> {
        if (candidates.isEmpty()) return emptySet()
        val counts = HashMap<String, Int>()
        for (ln in candidates) {
            val key = normalize(ln.text)
            if (key.isEmpty() || key.length > 100) continue
            counts[key] = (counts[key] ?: 0) + 1
        }
        val threshold = (pageCount * 0.5).toInt().coerceAtLeast(2)
        return counts.filterValues { it >= threshold }.keys
    }

    private fun normalize(s: String): String =
        s.trim().replace(Regex("\\s+"), " ").lowercase(Locale.US)

    private fun isProbablePageNumber(s: String): Boolean {
        val t = s.trim()
        if (t.length > 6) return false
        return t.all { it.isDigit() || it == '-' || it == '.' || it == ' ' } &&
            t.any { it.isDigit() }
    }

    // --- chapter detection ------------------------------------------------

    /** Reads the PDF outline (TOC). Returns a list of (chapter title, first matching line index)
     *  pairs in document order. Empty if no outline or none of its destinations resolve. */
    private fun readOutline(doc: PDDocument): List<Pair<String, Int>> {
        val outline = doc.documentCatalog?.documentOutline ?: return emptyList()
        val result = mutableListOf<Pair<String, Int>>()  // (title, page)
        flattenOutline(outline.firstChild, doc, result)
        // Sort by page to ensure document order even if the outline is out of sequence.
        return result.distinctBy { it.second }.sortedBy { it.second }
    }

    private fun flattenOutline(
        start: PDOutlineItem?,
        doc: PDDocument,
        out: MutableList<Pair<String, Int>>,
    ) {
        var item = start
        while (item != null) {
            val title = item.title?.trim().orEmpty()
            val page = resolveOutlinePage(item, doc)
            if (title.isNotEmpty() && page > 0) {
                out.add(title to page)
            }
            item.firstChild?.let { flattenOutline(it, doc, out) }
            item = item.nextSibling
        }
    }

    private fun resolveOutlinePage(item: PDOutlineItem, doc: PDDocument): Int {
        return runCatching {
            val dest = item.destination
            if (dest is PDPageDestination) {
                val page = dest.page ?: return@runCatching -1
                doc.pages.indexOf(page) + 1
            } else {
                // Action-based destinations and named destinations are rare in TOCs but exist;
                // skip them for now rather than dragging in the full resolver chain.
                -1
            }
        }.getOrDefault(-1)
    }

    /** Fallback when no outline: any line whose font size is meaningfully larger than the
     *  body text size and which is short enough to plausibly be a heading. */
    private fun detectChaptersByFontSize(lines: List<StyledLine>): List<Pair<String, Int>> {
        val bodySizes = lines.filter { it.text.isNotBlank() }.map { it.fontSize }
        if (bodySizes.isEmpty()) return emptyList()
        val sorted = bodySizes.sorted()
        val median = sorted[sorted.size / 2]
        val threshold = median * 1.2f

        val result = mutableListOf<Pair<String, Int>>()
        for (ln in lines) {
            if (ln.fontSize >= threshold && ln.text.trim().length in 3..120) {
                result.add(ln.text.trim() to ln.page)
            }
        }
        return result
    }

    // --- chapter assembly -------------------------------------------------

    private fun assembleChapters(
        lines: List<StyledLine>,
        chapterStarts: List<Pair<String, Int>>,
    ): List<Chapter> {
        if (lines.isEmpty()) return emptyList()

        // Build chapter buckets: a list of (title, startPage); body text is everything from
        // startPage up to the next chapter's startPage (or end of document).
        val buckets = if (chapterStarts.isNotEmpty()) {
            chapterStarts
        } else {
            listOf("Document" to 1)
        }

        val chapters = mutableListOf<Chapter>()
        for ((index, entry) in buckets.withIndex()) {
            val (title, startPage) = entry
            val endPageExclusive = buckets.getOrNull(index + 1)?.second ?: (Int.MAX_VALUE)
            val chapterLines = lines.filter { it.page in startPage until endPageExclusive }
            val withoutFootnotes = stripFootnotes(chapterLines)
            val withoutTables = stripTableRuns(withoutFootnotes)
            val cleaned = withoutTables.map { TextCleaner.clean(it.text) }
            val paragraphs = linesToParagraphs(cleaned).filterNot { looksLikeImageCaption(it) }
            val chunks = paragraphs.flatMap { Sentences.split(it) }
            if (chunks.isNotEmpty()) {
                val cleanTitle = TextCleaner.cleanTitle(title)
                    .ifBlank { "Chapter ${index + 1}" }
                chapters.add(Chapter(cleanTitle, chunks))
            }
        }
        return chapters
    }

    /** Drops lines whose font size is meaningfully smaller than the body median — typical
     *  shape of footnotes printed at page bottom in small type. Conservative threshold so a
     *  slightly-smaller-font paragraph doesn't get dropped. */
    private fun stripFootnotes(lines: List<StyledLine>): List<StyledLine> {
        val sizes = lines.map { it.fontSize }.filter { it > 0 }.sorted()
        if (sizes.isEmpty()) return lines
        val median = sizes[sizes.size / 2]
        val threshold = median * 0.75f
        return lines.filterNot { it.fontSize in 0f..threshold && it.fontSize > 0 }
    }

    /** Drops runs of 4+ consecutive table-shaped lines. A "table-shaped" line has at least
     *  two runs of 3+ consecutive spaces (PDFBox's marker for column-aligned cells under
     *  sortByPosition mode). Reading a table row-by-row produces nonsense ("Year Country
     *  Population 1990 Netherlands 15 million 2000 Netherlands 16 million"), so for a v1
     *  we drop the entire region. Poetry and indented dialogue don't hit this threshold
     *  because they don't have multiple wide internal gaps per line. */
    private fun stripTableRuns(lines: List<StyledLine>): List<StyledLine> {
        if (lines.size < 4) return lines
        val tabular = Regex("\\s{3,}")
        val flags = BooleanArray(lines.size) { i ->
            tabular.findAll(lines[i].text).count() >= 2
        }
        val drop = BooleanArray(lines.size)
        var i = 0
        while (i < lines.size) {
            if (!flags[i]) { i++; continue }
            var j = i
            while (j < lines.size && flags[j]) j++
            if (j - i >= 4) {
                for (k in i until j) drop[k] = true
            }
            i = j
        }
        return lines.filterIndexed { idx, _ -> !drop[idx] }
    }

    /** Drops short paragraphs that look like image / figure / table captions. These
     *  interrupt the reading flow with single-sentence asides that often don't make sense
     *  without seeing the figure. */
    private fun looksLikeImageCaption(paragraph: String): Boolean {
        if (paragraph.length > 220) return false  // a real paragraph is usually longer
        val captionStart = Regex(
            "(?i)^(figure|fig\\.|table|chart|graph|diagram|image|photo|illustration|plate)\\s*\\d"
        )
        return captionStart.containsMatchIn(paragraph)
    }

    /** Joins PDFBox line outputs into paragraph strings: a blank line separates paragraphs,
     *  and intra-paragraph line breaks ending in `-` are dehyphenated. */
    private fun linesToParagraphs(rawLines: List<String>): List<String> {
        val paragraphs = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            val text = current.toString().trim()
            if (text.isNotEmpty()) paragraphs.add(text)
            current.setLength(0)
        }

        for (line in rawLines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                flush()
                continue
            }
            if (current.isEmpty()) {
                current.append(trimmed)
            } else {
                // Dehyphenate "word-" + next line if the next starts lowercase. Otherwise just
                // insert a single space.
                val pending = current.toString()
                if (pending.endsWith("-") && trimmed.firstOrNull()?.isLowerCase() == true) {
                    current.setLength(pending.length - 1)  // drop trailing hyphen
                    current.append(trimmed)
                } else {
                    current.append(' ').append(trimmed)
                }
            }
        }
        flush()
        return paragraphs
    }

    // --- front-matter filter ----------------------------------------------

    /** Drops obvious front matter — copyright/imprint page, table of contents — that's
     *  technically a chapter in the outline but isn't something a reader wants narrated.
     *  Mirrors EpubParser.looksLikeToc for the TOC-shape heuristic.
     *
     *  Two signals:
     *    - Title hint: chapter title literally says "Contents" / "Copyright" / "Imprint" etc.
     *    - Body shape: many short lines + chapter markers (TOC), or short body containing
     *      copyright / ISBN / "all rights reserved" tokens (imprint page).
     *
     *  The filter is "drop if matches" rather than "keep only", so an unusual chapter that
     *  happens to mention copyright won't get dropped — it has to actually look like the
     *  front-matter shape. */
    private fun dropFrontMatter(chapters: List<Chapter>): List<Chapter> =
        chapters.filterNot { looksLikeFrontMatter(it) }

    private fun looksLikeFrontMatter(c: Chapter): Boolean {
        if (c.chunks.isEmpty()) return true  // empty chapter is never useful
        if (titleSuggestsFrontMatter(c.title)) return true
        if (looksLikeToc(c)) return true
        if (looksLikeCopyrightPage(c)) return true
        if (looksLikeReferenceList(c)) return true
        if (looksLikeIndex(c)) return true
        return false
    }

    private fun titleSuggestsFrontMatter(title: String): Boolean {
        val t = title.trim().lowercase(Locale.US)
        // Match titles like "Contents", "Table of contents", "Copyright", "Imprint",
        // "Colophon". Keep "Acknowledgements" / "Foreword" / "Preface" — those often
        // contain substantive content the user may want narrated.
        // Back-matter that's rarely worth narrating: references, bibliography, index.
        return t == "contents" ||
            t == "table of contents" ||
            t == "toc" ||
            t == "copyright" ||
            t == "imprint" ||
            t == "colophon" ||
            t == "credits" ||
            t == "references" ||
            t == "bibliography" ||
            t == "works cited" ||
            t == "index" ||
            t == "glossary"
    }

    /** Body-shape heuristic for a references / bibliography chapter: many short numbered
     *  or bracketed entries, often containing author-year patterns like "(2019)" or "et al.". */
    private fun looksLikeReferenceList(c: Chapter): Boolean {
        if (c.chunks.size < 6) return false
        val refMarker = Regex("\\(\\d{4}[a-z]?\\)|\\[\\d+\\]|^\\s*\\d+\\.\\s|et al\\.|and others")
        val matches = c.chunks.count { refMarker.containsMatchIn(it) }
        return matches.toDouble() / c.chunks.size > 0.4
    }

    /** Body-shape heuristic for an index: very short chunks (mostly one or two words plus
     *  page numbers), in alphabetical order, and the bulk of lines end with digits. */
    private fun looksLikeIndex(c: Chapter): Boolean {
        if (c.chunks.size < 10) return false
        val endsInDigit = c.chunks.count { it.trimEnd().lastOrNull()?.isDigit() == true }
        val shortRatio = c.chunks.count { it.length < 60 }.toDouble() / c.chunks.size
        return endsInDigit.toDouble() / c.chunks.size > 0.5 && shortRatio > 0.7
    }

    private fun looksLikeToc(c: Chapter): Boolean {
        if (c.chunks.size < 3) return false
        val shortRatio = c.chunks.count { it.length < 40 }.toDouble() / c.chunks.size
        val chapterMarker = Regex("(?i)\\bchapter\\s+[\\dIVXLCivxlc]+\\b|^\\s*\\d+\\s")
        val hasMarkers = c.chunks.count { chapterMarker.containsMatchIn(it) } >= 2
        return shortRatio >= 0.6 && hasMarkers
    }

    /** Imprint / copyright page detection. These are typically short (<800 chars) and
     *  contain at least two of: copyright symbol or "copyright" word, "all rights reserved",
     *  ISBN, "first published", "printed in", "published by". */
    private fun looksLikeCopyrightPage(c: Chapter): Boolean {
        val totalChars = c.chunks.sumOf { it.length }
        if (totalChars > 1500) return false  // real chapter; copyright pages are short
        val body = c.chunks.joinToString(" ").lowercase(Locale.US)
        val markers = listOf(
            "all rights reserved",
            "copyright",
            "©",
            "isbn",
            "first published",
            "printed in",
            "published by",
            "no part of this",
        )
        val hits = markers.count { it in body }
        return hits >= 2
    }

    // --- cover ------------------------------------------------------------

    private fun renderCover(doc: PDDocument, pageIndex: Int): ByteArray? {
        if (doc.numberOfPages <= pageIndex || pageIndex < 0) return null
        return runCatching {
            val renderer = PDFRenderer(doc)
            // 1.0f = 72 DPI which is the PDF unit; bump to 1.5f for a sharper thumbnail without
            // blowing up the storage cost. The cover image is shown at ~140dp in the player.
            val bitmap: Bitmap = renderer.renderImage(pageIndex, 1.5f, ImageType.RGB)
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                bitmap.recycle()
                out.toByteArray()
            }
        }.getOrNull()
    }
}
