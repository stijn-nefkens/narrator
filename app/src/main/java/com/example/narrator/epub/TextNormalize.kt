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

    /**
     * Words glued together at a camelCase boundary ("happeningThen", "dayThe") — a common PDF
     * extraction artifact where a word break (often a lost sentence boundary) collapses into no
     * space at all. Inserting a space lets the engine pronounce both words instead of one
     * mangled token.
     *
     * This is the false-positive-prone transform, so the guards are deliberate:
     *   - `(?<=[a-z]{2})` requires at least two lowercase letters before the boundary. This
     *     spares single-lowercase-after-capital brand/name forms — "iPhone", "eBook",
     *     "DeForest", "McDonald", "LaSalle", "DiCaprio" all have only one lowercase letter
     *     before the internal capital and so are left intact.
     *   - `(?<!Mac)` spares the one common name prefix with two trailing lowercase letters
     *     ("MacArthur", "MacBeth") that the rule above would otherwise split.
     *   - `(?=[A-Z][a-z])` requires the second word to start uppercase-then-lowercase, which
     *     avoids breaking acronym runs ("USData" stays whole).
     *
     * Brand compounds like "JavaScript" / "PowerPoint" are still split ("Java Script"), but
     * when spoken aloud the audio is essentially identical, so the tradeoff is inaudible — the
     * cases that would actually mispronounce (proper-name prefixes) are the ones guarded above.
     * Digit↔letter boundaries ("3D", "mp3", "1st") are intentionally NOT touched: too many
     * legitimate forms, real risk of audible damage.
     */
    private val gluedWords = Regex("(?<!Mac)(?<=[a-z]{2})(?=[A-Z][a-z])")

    /**
     * Inline reference/citation removal. These get read aloud as noise mid-sentence, so we strip
     * the common academic shapes. Conservative on purpose — each pattern requires citation-shaped
     * content so ordinary parentheticals (asides, definitions, "(in 2019 he left)") survive:
     *
     *   - [refPointer]: "(see fig 1.3)", "(graph 4.2)", "(table 2)", "(p. 99)" — a figure/table/
     *     page word followed by a number. "see"/"cf." lead-in optional.
     *   - [authorYear]: "(Smith, 2019)", "(Smith & Jones, 2019, p. 99)", "(Smith et al., 2019)" —
     *     a Capitalised author token, then a 4-digit year. The leading capital is what spares
     *     lowercase asides that merely mention a year.
     *   - [bracketRef]: "[12]", "[3, 4]", "[5-9]" — numbered reference markers.
     *
     * The leading `\s*` lets each pattern absorb the space before it so "text (Smith, 2019)."
     * collapses cleanly to "text."; [tidyAfterCitation] mops up any space left before punctuation.
     */
    private val refPointer = Regex(
        "\\s*\\((?:see\\s+|cf\\.?\\s+)?" +
            "(?:fig(?:ure)?|box|graph|table|chart|diagram|eq(?:uation)?|" +
            "section|chapter|appendix|p{1,2}|pg|page)\\.?\\s*\\d+(?:[.\\-]\\d+)*\\.?\\)",
        RegexOption.IGNORE_CASE,
    )

    // One "Author(s), year[, p.N]" citation, WITHOUT the surrounding parens — composed below into
    // the full pattern so a single parenthetical can carry several, semicolon-separated:
    // "(Smith, 2020; Jones & Lee, 2018, p. 9; Brown et al., 2021)".
    private const val AUTHOR_YEAR_UNIT =
        "[A-Z][\\w.'’-]+" +
            "(?:(?:,| and| &) [A-Z][\\w.'’-]+| et al\\.?)*" +
            ",?\\s+(?:1[5-9]\\d\\d|20\\d\\d)[a-z]?" +
            "(?:,\\s*pp?\\.?\\s*\\d+(?:[-–]\\d+)?)?"

    private val authorYear = Regex(
        "\\s*\\($AUTHOR_YEAR_UNIT(?:;\\s*$AUTHOR_YEAR_UNIT)*\\)",
    )

    private val bracketRef = Regex("\\s*\\[\\d+(?:\\s*[-–,]\\s*\\d+)*\\]")

    /** After removing a citation we can be left with " ." / "  " — tidy those. */
    private val spaceBeforePunct = Regex("\\s+([,.;:!?])")
    private val doubleSpace = Regex("  +")

    fun stripCitations(s: String): String {
        if (!s.contains('(') && !s.contains('[')) return s
        var r = s
        r = refPointer.replace(r, "")
        r = authorYear.replace(r, "")
        r = bracketRef.replace(r, "")
        if (r != s) {
            // Removing a citation can leave " ." or doubled spaces — tidy only when we changed text.
            r = spaceBeforePunct.replace(r, "$1")
            r = doubleSpace.replace(r, " ")
        }
        return r
    }

    fun restoreSentenceSpaces(s: String): String =
        if (s.length < 4) s else missingSentenceSpace.replace(s, "$1$2 ")

    fun stripGroupingCommas(s: String): String =
        if (!s.contains(',')) s else groupingComma.replace(s, "")

    fun splitGluedWords(s: String): String = gluedWords.replace(s, " ")

    /** Master entry applied by [Sentences.split] before sentence breaking. Citations are stripped
     *  first so the leftover text flows as one clean sentence into the rest of the pipeline. */
    fun normalize(s: String): String =
        splitGluedWords(stripGroupingCommas(restoreSentenceSpaces(stripCitations(s))))
}
