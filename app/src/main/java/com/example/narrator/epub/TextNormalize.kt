package com.example.narrator.epub

/**
 * Per-string text normalisations shared by both the EPUB and PDF pipelines. Everything here
 * runs through [Sentences.split], so it applies to every chunk regardless of source format —
 * the PDF [com.example.narrator.pdf.TextCleaner] also calls into these so its per-line cleanup
 * benefits before paragraph assembly. All transforms are idempotent and safe to apply twice.
 */
internal object TextNormalize {

    /**
     * Source text occasionally runs two sentences together with no separating space
     * ("scale.Quite", "abroad.As", "1990.The", "done.\"Then"). PDFBox does this regularly;
     * EPUB content does it when paragraphs are joined across element boundaries. With no space
     * BreakIterator can't see the sentence end, the whole paragraph fuses into one
     * mega-sentence, and the fallback hard cut chops a word in half.
     *
     * Heuristic: when a sentence terminator sits between a lowercase letter or digit and an
     * uppercase-then-lowercase pair, insert a space. An optional closing quote between the
     * terminator and the next sentence is preserved on the left of the inserted space
     * (`done."Then` → `done." Then`). The lowercase/digit lookbehind guards against acronyms
     * ("U.S.", "i.e.") and the uppercase-then-lowercase lookahead guards against all-caps runs
     * ("U.S.A.") being broken in the middle.
     */
    private val missingSentenceSpace =
        Regex("(?<=[a-z0-9])([.!?])([\"'“”‘’]?)(?=[A-Z][a-z])")

    /**
     * Grouping commas inside numbers ("200,000", "1,234,567") make some TTS engines spell the
     * number out digit-group by digit-group or mis-read it. Stripping them ("200000") lets the
     * engine read it as a single quantity. The lookbehind/lookahead require the comma to sit
     * between a digit and a run of exactly-three-digit groups with no trailing digit, so list
     * commas ("eggs, milk"), enumerations ("1, 2, 3") and European decimals ("3,14") are left
     * untouched.
     */
    private val groupingComma = Regex("(?<=\\d),(?=\\d{3}(?:,\\d{3})*(?!\\d))")

    fun restoreSentenceSpaces(s: String): String =
        if (s.length < 4) s else missingSentenceSpace.replace(s, "$1$2 ")

    fun stripGroupingCommas(s: String): String =
        if (!s.contains(',')) s else groupingComma.replace(s, "")

    /** Master entry applied by [Sentences.split] before sentence breaking. */
    fun normalize(s: String): String = stripGroupingCommas(restoreSentenceSpaces(s))
}
