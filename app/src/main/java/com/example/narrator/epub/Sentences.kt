package com.example.narrator.epub

import java.text.BreakIterator
import java.util.Locale
import kotlin.math.abs

internal object Sentences {
    /** Hard upper bound on any chunk length — last-resort cut when no clause break exists. */
    const val MAX_CHUNK_CHARS = 500

    /**
     * Sentences longer than this are sub-chunked at clause boundaries (em-dash, brackets,
     * semicolon, colon, comma). Neural TTS engines like Kokoro spend O(n²)-ish synth time on
     * long inputs, so a single 370-char "sentence" can take ~10s of synth before any audio
     * plays. Sub-chunking lets the first clause start synthesising on its own and audio begins
     * almost immediately. The threshold/target were lowered from 100/70 to bias toward shorter
     * chunks the engine can keep up with in real time.
     */
    private const val SUB_CHUNK_THRESHOLD = 70
    private const val SUB_CHUNK_TARGET = 45

    /**
     * How long a sub-chunk may grow, as a function of how many chunks are already synthesised
     * and waiting ahead of the playhead. Mid-sentence cutting sounds unnatural, so we only cut
     * aggressively when there's no audio banked to cover the synth time:
     *
     *   depth 0 (cold start / starved): 70/45  — fast first audio, the 0.13 behaviour
     *   depth 1:                        110/75 — cut a bit more to bank audio faster
     *   depth 2:                        170/120
     *   depth 3:                        240/180
     *   depth 4+:                       320/240 — the bounded ceiling
     *
     * The curve is BOUNDED — earlier it jumped to "whole sentence" (MAX_CHUNK_CHARS) at depth 3+,
     * but a single very long sentence then synthesised as one 400–500 char chunk that took many
     * seconds, starving playback (audible gaps) and tripping the synth watchdog. Capping the
     * ceiling well under MAX_CHUNK_CHARS keeps even a deeply-buffered reader cutting a long
     * sentence into a couple of pieces the engine can synth quickly. The hard MAX_CHUNK_CHARS cut
     * still exists in [subChunkByClauses] as the last resort for space-less mega-tokens.
     *
     * Depths 1–2 were tightened (from 130/90 and 220/160) after testing: an occasional
     * stop-to-synthesise mid-playback is more jarring than a slightly-glued sentence, so we keep
     * cutting a touch longer to build buffer headroom. Cold start (depth 0) is unchanged — first
     * audio must stay snappy. [threshold] is the length above which a sentence is sub-chunked at
     * all; [target] is the preferred cut length when it is.
     */
    data class CutBudget(val threshold: Int, val target: Int)

    fun budgetForDepth(depth: Int): CutBudget = when {
        depth <= 0 -> CutBudget(SUB_CHUNK_THRESHOLD, SUB_CHUNK_TARGET)
        depth == 1 -> CutBudget(110, 75)
        depth == 2 -> CutBudget(170, 120)
        depth == 3 -> CutBudget(240, 180)
        else -> CutBudget(320, 240)
    }

    /**
     * Consecutive short sentences within a paragraph get merged up to this length so dialogue
     * and tag (`"Why?" said Alice.`) read as one utterance rather than half a dozen choppy
     * synth calls with audible per-chunk transition gaps.
     */
    private const val MERGE_TARGET = 80

    /** Parse-time split: BreakIterator sentences, merged for dialogue flow AND sub-chunked at
     *  the fixed default budget. Output is byte-for-byte what it was before the adaptive work. */
    fun split(text: String, locale: Locale = Locale.US): List<String> =
        mergeAndSubChunk(rawSentences(text, locale))

    /**
     * Merge-only split: the paragraph's natural sentence/utterance units (short sentences merged
     * for dialogue flow) WITHOUT sub-chunking long ones. This is the position unit for
     * buffer-adaptive playback — a long sentence stays a single position and is sub-chunked on
     * demand at synth time via [subChunk] using the live [CutBudget].
     */
    fun splitSentences(text: String, locale: Locale = Locale.US): List<String> =
        mergeOnly(rawSentences(text, locale))

    private val SENTENCE_END = charArrayOf('.', '!', '?')

    /** Private-use placeholder swapped in for the period inside name initials so BreakIterator
     *  can't treat "J.H. Blom" / "J. Blom" as a sentence boundary; restored after segmentation. */
    private const val INITIAL_DOT = '\uE000'

    /** A single-capital initial followed by a period and then (optionally a space and) another
     *  capital — i.e. "J." in "J.H.", "J. Blom", "U.S." The lookbehind keeps it to genuine
     *  one-letter initials, not the last letter of a word ("USA."). */
    private val INITIAL_PERIOD = Regex("(?<![A-Za-z])([A-Z])\\.(?=\\s?[A-Z])")

    /**
     * If [paragraph] begins with the chapter [title] glued to the body — e.g. an inline heading
     * "Chapter One It was a dark night." or a PDF heading line that ran into the first line —
     * split it into the heading and the body as TWO separate paragraphs (the heading terminated
     * with a period). Returning two paragraphs is what keeps them apart: paragraphs are
     * sentence-split and dialogue-merged *independently*, so the short heading can't be merged
     * back onto the first body sentence — which is the whole point (it must read on its own and,
     * being a short chunk 0 matching the title, trigger the chapter-title pause).
     *
     * Returns a single-element list (the paragraph unchanged) when the title is blank, the
     * paragraph IS just the title, the prefix doesn't match at a word boundary, or it's already
     * "Title. body" (BreakIterator will separate those anyway, but we still split so the heading
     * lands in its own paragraph and escapes the merge).
     */
    fun splitHeadingFromBody(paragraph: String, title: String?): List<String> {
        val unchanged = listOf(paragraph)
        val t = title?.trim().orEmpty()
        val p = paragraph.trimStart()
        if (t.isEmpty() || p.length <= t.length) return unchanged
        if (!p.regionMatches(0, t, 0, t.length, ignoreCase = true)) return unchanged
        // Skip an existing terminator after the title ("Title." / "Title!"), then require a word
        // boundary — guards against the title being a prefix of a longer word ("Art"/"Artisanal").
        val idx = (t.length + if (p[t.length] in SENTENCE_END) 1 else 0)
        val boundaryOk = idx < p.length && p[idx].isWhitespace()
        val rest = if (boundaryOk) p.substring(idx).trimStart() else ""
        return if (rest.isEmpty()) unchanged else listOf("${p.substring(0, t.length).trimEnd()}.", rest)
    }

    /** BreakIterator sentence segmentation after [TextNormalize], no merging or sub-chunking. */
    private fun rawSentences(text: String, locale: Locale): List<String> {
        if (text.isBlank()) return emptyList()
        // Repair run-together sentences and strip numeric grouping commas before the
        // BreakIterator sees the text. Shared with the PDF pipeline via TextNormalize.
        val repaired = TextNormalize.normalize(text)
        // Shield name initials ("J.H. Blom", "J. Blom") so BreakIterator can't split the name
        // across sentences on the period between initials; restore the periods in each piece.
        val shielded = INITIAL_PERIOD.replace(repaired) { m -> "${m.groupValues[1]}$INITIAL_DOT" }
        val it = BreakIterator.getSentenceInstance(locale)
        it.setText(shielded)
        val raw = mutableListOf<String>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            val piece = shielded.substring(start, end).replace(INITIAL_DOT, '.').trim()
            if (piece.isNotEmpty()) raw.add(piece)
            start = end
            end = it.next()
        }
        return raw
    }

    /**
     * Sub-chunk a single sentence for synthesis using the [CutBudget] for the current buffer
     * depth. Returns the sentence whole when it fits the budget's threshold; otherwise carves it
     * at clause / word boundaries near the budget's target — the same machinery as parse-time
     * [split], just with a runtime-chosen budget instead of the fixed constants.
     */
    fun subChunk(sentence: String, budget: CutBudget): List<String> {
        val s = sentence.trim()
        if (s.isEmpty()) return emptyList()
        return subChunkByClauses(s, budget.threshold, budget.target)
    }

    /** A synthesis segment of a sentence with the char [offset] at which its [text] begins within
     *  that sentence. Lets the player highlight the right span of the whole-sentence caption. */
    data class OffsetSegment(val text: String, val offset: Int)

    /**
     * Like [subChunk] but also reports each segment's offset within [sentence], so the caption can
     * stay the whole sentence while the highlight maps onto the segment being spoken.
     *
     * [subChunkByClauses] cuts the trimmed sentence into consecutive substrings and trims each, so
     * the pieces are contiguous in the sentence separated only by the whitespace the cutter
     * stripped at each boundary. We reconstruct exact offsets by walking the sentence, skipping
     * inter-piece whitespace before each piece — exact and unambiguous (a plain `indexOf` could
     * match a duplicate word earlier in the sentence). Defensive `coerceAtMost` keeps it in bounds
     * if a piece ever fails to line up rather than throwing.
     */
    fun subChunkWithOffsets(sentence: String, budget: CutBudget): List<OffsetSegment> {
        val s = sentence.trim()
        if (s.isEmpty()) return emptyList()
        val pieces = subChunkByClauses(s, budget.threshold, budget.target)
        val out = ArrayList<OffsetSegment>(pieces.size)
        var cursor = 0
        for (piece in pieces) {
            while (cursor < s.length && s[cursor].isWhitespace()) cursor++
            val offset = cursor.coerceAtMost(s.length)
            out.add(OffsetSegment(piece, offset))
            cursor = offset + piece.length
        }
        return out
    }

    /** Parse-time path operating on RAW sentences: a sentence longer than SUB_CHUNK_THRESHOLD is
     *  emitted as its own sub-chunked run; shorter ones are merged for dialogue flow. Identical
     *  to the pre-adaptive behaviour — note it sub-chunks only individually-long sentences, never
     *  a merged run of short ones (a merge can reach MERGE_TARGET without being cut). */
    private fun mergeAndSubChunk(sentences: List<String>): List<String> {
        val out = mutableListOf<String>()
        val acc = StringBuilder()
        for (sentence in sentences) {
            if (sentence.length > SUB_CHUNK_THRESHOLD) {
                if (acc.isNotEmpty()) {
                    out.add(acc.toString())
                    acc.setLength(0)
                }
                out.addAll(subChunkByClauses(sentence, SUB_CHUNK_THRESHOLD, SUB_CHUNK_TARGET))
                continue
            }
            val combined = acc.length + (if (acc.isEmpty()) 0 else 1) + sentence.length
            if (combined <= MERGE_TARGET) {
                if (acc.isNotEmpty()) acc.append(' ')
                acc.append(sentence)
            } else {
                if (acc.isNotEmpty()) {
                    out.add(acc.toString())
                    acc.setLength(0)
                }
                acc.append(sentence)
            }
        }
        if (acc.isNotEmpty()) out.add(acc.toString())
        return out
    }

    /** Merges consecutive short sentences (dialogue + tag) up to MERGE_TARGET; a sentence longer
     *  than MERGE_TARGET is emitted as its own element. No sub-chunking — the playback caller does
     *  that on demand via [subChunk]. */
    private fun mergeOnly(sentences: List<String>): List<String> {
        val out = mutableListOf<String>()
        val acc = StringBuilder()
        for (sentence in sentences) {
            if (sentence.length > MERGE_TARGET) {
                if (acc.isNotEmpty()) {
                    out.add(acc.toString())
                    acc.setLength(0)
                }
                out.add(sentence)
                continue
            }
            val combined = acc.length + (if (acc.isEmpty()) 0 else 1) + sentence.length
            if (combined <= MERGE_TARGET) {
                if (acc.isNotEmpty()) acc.append(' ')
                acc.append(sentence)
            } else {
                if (acc.isNotEmpty()) {
                    out.add(acc.toString())
                    acc.setLength(0)
                }
                acc.append(sentence)
            }
        }
        if (acc.isNotEmpty()) out.add(acc.toString())
        return out
    }

    /** Repeatedly carve [text] at the best available break until each piece fits [threshold].
     *  Prefers a clause delimiter near [target]; failing that, a word boundary; failing even
     *  that (a single space-less mega-token), a hard character cut. */
    private fun subChunkByClauses(text: String, threshold: Int, target: Int): List<String> {
        val parts = mutableListOf<String>()
        var remaining = text
        while (remaining.length > threshold) {
            val cut = findClauseCut(remaining, target).takeIf { it > 0 } ?: findWordCut(remaining, target)
            if (cut <= 0) {
                // No clause break AND no usable word boundary — a single space-less mega-token.
                // Hard-cut at MAX_CHUNK_CHARS as the absolute last resort so we never ship a
                // chunk so big it stalls the engine for many seconds.
                val hard = MAX_CHUNK_CHARS.coerceAtMost(remaining.length)
                parts.add(remaining.substring(0, hard).trim())
                remaining = remaining.substring(hard).trim()
            } else {
                parts.add(remaining.substring(0, cut).trim())
                remaining = remaining.substring(cut).trim()
            }
        }
        if (remaining.isNotEmpty()) parts.add(remaining)
        return parts
    }

    /**
     * Best clause break in [text], or -1 if none exists past TARGET/2. Scans every
     * candidate position in the text; the cost function `distance_to_target * weight`
     * naturally biases toward cuts near SUB_CHUNK_TARGET while still admitting later cuts
     * when no nearby option exists. Each delimiter type carries a "naturalness" weight,
     * so em-dashes are preferred per unit of distance but a much closer comma still wins
     * over a far-away dash.
     *
     * The search spans the whole sentence (no upper cap), so a long sentence whose first
     * clause break sits far in still breaks at the break rather than the MAX_CHUNK_CHARS
     * hard cut. Most delimiters cut *after* the token; parentheses cut so a mid-sentence
     * aside ("happening (an aside) and more") becomes its own chunk — break before " (" and
     * after ") ".
     */
    private fun findClauseCut(text: String, target: Int): Int {
        val minCut = target / 2
        if (minCut >= text.length) return -1

        var bestCut = -1
        var bestCost = Double.MAX_VALUE
        for (delim in DELIMITERS) {
            var idx = text.indexOf(delim.token)
            while (idx >= 0) {
                val cutPos = idx + delim.cutOffset
                if (cutPos in minCut until text.length) {
                    val cost = abs(cutPos - target) * delim.weight
                    if (cost < bestCost) {
                        bestCost = cost
                        bestCut = cutPos
                    }
                }
                idx = text.indexOf(delim.token, idx + 1)
            }
        }
        return bestCut
    }

    /**
     * Last-resort cut for a sentence with no clause-break delimiter in reach. Returns the word
     * boundary (position just after a space) closest to SUB_CHUNK_TARGET, so an unpunctuated
     * long sentence still yields a short first chunk — the engine begins audio in ~3s instead
     * of synthesising the whole sentence first (~7s on the FP6 for a 90-char run). Never breaks
     * a word mid-character. Returns -1 only when there is no usable space at all (a single
     * mega-token), leaving the caller to hard-cut at MAX_CHUNK_CHARS.
     */
    private fun findWordCut(text: String, target: Int): Int {
        val minCut = target / 2
        if (minCut >= text.length) return -1
        var bestCut = -1
        var bestDist = Int.MAX_VALUE
        var idx = text.indexOf(' ')
        while (idx >= 0) {
            val cutPos = idx + 1  // cut after the space; the next chunk starts on a whole word
            if (cutPos in minCut until text.length) {
                val dist = abs(cutPos - target)
                if (dist < bestDist) {
                    bestDist = dist
                    bestCut = cutPos
                }
            }
            idx = text.indexOf(' ', idx + 1)
        }
        return bestCut
    }

    /**
     * A clause delimiter. [cutOffset] is added to the token's start index to get the cut
     * position, so a token can break either after itself (e.g. ";" → offset 1) or before its
     * trailing content (" (" → offset 1 cuts at the space, leaving "(" to start the next
     * chunk). Lower [weight] = more natural break point per unit of distance from target.
     */
    private data class Delim(val token: String, val weight: Double, val cutOffset: Int)

    private val DELIMITERS: List<Delim> = listOf(
        Delim("—", 0.5, 1),
        Delim("–", 0.5, 1),
        Delim("--", 0.5, 2),
        Delim(") ", 0.8, 2),   // close of a parenthetical: end the clause after it
        Delim(" (", 0.8, 1),   // open of a parenthetical: end the clause before it
        Delim(";", 1.0, 1),
        Delim(":", 1.0, 1),
        Delim(",", 1.5, 1),
    )
}
