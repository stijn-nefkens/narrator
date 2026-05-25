package com.example.narrator.pdf

/**
 * Per-line text normalisation that runs on every line extracted from a PDF before paragraph
 * assembly. PDFs carry a lot of unicode debris that doesn't matter when the document is
 * rendered visually but makes the audio output noticeably worse:
 *
 *   - Soft hyphens (U+00AD) hide inside justified words and get pronounced as syllable breaks.
 *   - Ligatures (ﬁ ﬂ ﬃ ...) aren't in most TTS engines' dictionaries.
 *   - Bullet glyphs at the start of lines turn into a clearly-audible "bullet" word.
 *   - URLs and email addresses get spelled character-by-character — long, ugly, useless.
 *   - Common abbreviations (Dr., e.g., etc.) read awkwardly without expansion.
 *   - Roman numerals in headings (Chapter XIV) get spelled letter-by-letter by sherpa.
 *
 * All transforms here are idempotent and safe to apply to every line.
 */
internal object TextCleaner {

    /** Master entry: applies every normalisation in order. Order matters in a couple of
     *  places — ligature expansion must happen before whitespace collapse, and URL
     *  replacement must happen before abbreviation expansion. */
    fun clean(text: String): String {
        if (text.isEmpty()) return text
        var s = text
        s = stripSoftHyphens(s)
        s = expandLigatures(s)
        s = restoreSentenceSpaces(s)
        s = replaceLinks(s)
        s = stripBulletPrefixes(s)
        s = expandAbbreviations(s)
        s = romanNumeralsInHeadings(s)
        s = collapseWhitespace(s)
        return s
    }

    /** Cleans the chapter title separately — same rules but Roman-numeral conversion is
     *  applied unconditionally (titles like "Chapter XIV" are the whole point). */
    fun cleanTitle(text: String): String {
        if (text.isEmpty()) return text
        var s = text
        s = stripSoftHyphens(s)
        s = expandLigatures(s)
        s = romanNumeralsAnywhere(s)
        s = collapseWhitespace(s)
        return s.trim()
    }

    private fun stripSoftHyphens(s: String): String = s.replace("­", "")

    /**
     * PDFBox sometimes runs sentences together without a separating space ("scale.Quite",
     * "abroad.As"). With no space, BreakIterator can't see a sentence boundary, so a whole
     * paragraph fuses into one mega-sentence, hits MAX_CHUNK_CHARS, and gets cut mid-word.
     *
     * Heuristic: when a sentence-terminating punctuation mark sits between a lowercase
     * letter and an uppercase-then-lowercase pair, insert a space. The lookbehind guards
     * against acronyms like "U.S." and "i.e."; the lookahead guards against all-caps runs
     * like "U.S.A." being broken in the middle.
     */
    private val missingSentenceSpace = Regex("(?<=[a-z])([.!?])(?=[A-Z][a-z])")

    private fun restoreSentenceSpaces(s: String): String =
        if (s.length < 4) s else missingSentenceSpace.replace(s, "$1 ")

    private val ligatures = mapOf(
        "ﬀ" to "ff", "ﬁ" to "fi", "ﬂ" to "fl",
        "ﬃ" to "ffi", "ﬄ" to "ffl",
        "ﬅ" to "ft", "ﬆ" to "st",
    )

    private fun expandLigatures(s: String): String {
        if (s.none { it.code in 0xFB00..0xFB06 }) return s
        var result = s
        for ((from, to) in ligatures) result = result.replace(from, to)
        return result
    }

    private val bulletChars = setOf(
        '•',  // •
        '◦',  // ◦
        '▪',  // ▪
        '▸',  // ▸
        '◆',  // ◆
        '✓',  // ✓
        '❖',  // ❖
        '⁃',  // ⁃
        '∙',  // ∙
        '·',  // ·
    )

    /** Removes a leading bullet glyph if it's the first non-whitespace character on the
     *  line. Doesn't touch bullets mid-line (rare but harmless to keep there). */
    private fun stripBulletPrefixes(s: String): String {
        val trimmed = s.trimStart()
        if (trimmed.isEmpty() || trimmed[0] !in bulletChars) return s
        return trimmed.drop(1).trimStart()
    }

    private val urlRegex = Regex("(?i)\\b(?:https?://|www\\.)[\\w./?#=&%+\\-]+")
    private val emailRegex = Regex("\\b[\\w.+-]+@[\\w-]+\\.[\\w.-]+\\b")

    /** Replaces URLs and email addresses with a brief spoken placeholder so they don't get
     *  read character-by-character. */
    private fun replaceLinks(s: String): String {
        if (!s.contains('@') && !s.contains("http", ignoreCase = true) &&
            !s.contains("www.", ignoreCase = true)) return s
        return s.replace(urlRegex, "link").replace(emailRegex, "email address")
    }

    // Small, focused abbreviation list. Resist the temptation to grow this without test
    // cases — every new entry is a chance to mis-expand something in international text.
    private val abbreviations = listOf(
        Regex("\\bDr\\.") to "Doctor",
        Regex("\\bMr\\.") to "Mister",
        Regex("\\bMrs\\.") to "Misses",
        Regex("\\bMs\\.") to "Miss",
        Regex("\\bSt\\.") to "Saint",
        Regex("\\bvs\\.") to "versus",
        Regex("\\bvs\\b") to "versus",
        Regex("\\betc\\.") to "et cetera",
        Regex("\\be\\.g\\.") to "for example",
        Regex("\\bi\\.e\\.") to "that is",
        Regex("\\bet al\\.") to "and others",
    )

    private fun expandAbbreviations(s: String): String {
        var result = s
        for ((re, replacement) in abbreviations) result = result.replace(re, replacement)
        return result
    }

    /** Convert standalone Roman numerals that follow a chapter/part marker word.
     *  Only fires for "Chapter|Part|Section|Volume|Book <ROMAN>" patterns inside body text;
     *  bare Roman numerals elsewhere stay as-is (could be a section number, an actual name, etc.). */
    private val headingRoman = Regex("(?i)\\b(Chapter|Part|Section|Volume|Book)\\s+([IVXLCDM]+)\\b")

    private fun romanNumeralsInHeadings(s: String): String {
        if (!s.contains(' ')) return s
        return headingRoman.replace(s) { m ->
            val word = m.groupValues[1]
            val roman = m.groupValues[2]
            val arabic = romanToInt(roman)
            if (arabic > 0) "$word $arabic" else m.value
        }
    }

    /** Used by cleanTitle — converts any standalone Roman numeral token in the string. */
    private val anyRoman = Regex("\\b[IVXLCDM]+\\b")

    private fun romanNumeralsAnywhere(s: String): String =
        anyRoman.replace(s) { m ->
            val arabic = romanToInt(m.value)
            if (arabic > 0) arabic.toString() else m.value
        }

    private fun romanToInt(s: String): Int {
        if (s.isEmpty()) return 0
        val map = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
        var total = 0
        var prev = 0
        for (i in s.length - 1 downTo 0) {
            val v = map[s[i]] ?: return 0
            if (v < prev) total -= v else total += v
            prev = v
        }
        // Sanity check: round-trip back to verify we have a well-formed numeral.
        return if (total in 1..3999) total else 0
    }

    private val multiWs = Regex("[\\s\\u00A0\\u2000-\\u200B]+")

    private fun collapseWhitespace(s: String): String = multiWs.replace(s, " ").trim()
}
