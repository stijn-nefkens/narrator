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
     * Consecutive short sentences within a paragraph get merged up to this length so dialogue
     * and tag (`"Why?" said Alice.`) read as one utterance rather than half a dozen choppy
     * synth calls with audible per-chunk transition gaps.
     */
    private const val MERGE_TARGET = 80

    fun split(text: String, locale: Locale = Locale.US): List<String> {
        if (text.isBlank()) return emptyList()
        // Repair run-together sentences and strip numeric grouping commas before the
        // BreakIterator sees the text. Shared with the PDF pipeline via TextNormalize.
        val repaired = TextNormalize.normalize(text)
        val it = BreakIterator.getSentenceInstance(locale)
        it.setText(repaired)
        val raw = mutableListOf<String>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            val piece = repaired.substring(start, end).trim()
            if (piece.isNotEmpty()) raw.add(piece)
            start = end
            end = it.next()
        }
        return mergeAndSubChunk(raw)
    }

    private fun mergeAndSubChunk(sentences: List<String>): List<String> {
        val out = mutableListOf<String>()
        val acc = StringBuilder()
        for (sentence in sentences) {
            if (sentence.length > SUB_CHUNK_THRESHOLD) {
                // Long sentence: emit any merge buffer and sub-chunk this one separately.
                if (acc.isNotEmpty()) {
                    out.add(acc.toString())
                    acc.setLength(0)
                }
                out.addAll(subChunkByClauses(sentence))
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

    /** Repeatedly carve [text] at the best available break until each piece fits. Prefers a
     *  clause delimiter; failing that, a word boundary near the target; failing even that (a
     *  single space-less mega-token), a hard character cut. */
    private fun subChunkByClauses(text: String): List<String> {
        val parts = mutableListOf<String>()
        var remaining = text
        while (remaining.length > SUB_CHUNK_THRESHOLD) {
            val cut = findClauseCut(remaining).takeIf { it > 0 } ?: findWordCut(remaining)
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
    private fun findClauseCut(text: String): Int {
        val minCut = SUB_CHUNK_TARGET / 2
        if (minCut >= text.length) return -1

        var bestCut = -1
        var bestCost = Double.MAX_VALUE
        for (delim in DELIMITERS) {
            var idx = text.indexOf(delim.token)
            while (idx >= 0) {
                val cutPos = idx + delim.cutOffset
                if (cutPos in minCut until text.length) {
                    val cost = abs(cutPos - SUB_CHUNK_TARGET) * delim.weight
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
    private fun findWordCut(text: String): Int {
        val minCut = SUB_CHUNK_TARGET / 2
        if (minCut >= text.length) return -1
        var bestCut = -1
        var bestDist = Int.MAX_VALUE
        var idx = text.indexOf(' ')
        while (idx >= 0) {
            val cutPos = idx + 1  // cut after the space; the next chunk starts on a whole word
            if (cutPos in minCut until text.length) {
                val dist = abs(cutPos - SUB_CHUNK_TARGET)
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
