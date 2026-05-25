package com.example.narrator.epub

import java.text.BreakIterator
import java.util.Locale
import kotlin.math.abs

internal object Sentences {
    /** Hard upper bound on any chunk length — last-resort cut when no clause break exists. */
    const val MAX_CHUNK_CHARS = 500

    /**
     * Sentences longer than this are sub-chunked at clause boundaries (em-dash, semicolon,
     * colon, comma). Neural TTS engines like Kokoro spend O(n²)-ish synth time on long inputs,
     * so a single 370-char "sentence" can take ~10s of synth before any audio plays. Sub-chunking
     * lets the first ~100 chars start synthesising on its own and audio begins almost immediately.
     */
    private const val SUB_CHUNK_THRESHOLD = 100
    private const val SUB_CHUNK_TARGET = 70

    /**
     * Consecutive short sentences within a paragraph get merged up to this length so dialogue
     * and tag (`"Why?" said Alice.`) read as one utterance rather than half a dozen choppy
     * synth calls with audible per-chunk transition gaps.
     */
    private const val MERGE_TARGET = 80

    /**
     * When sentence-terminating punctuation sits between a lowercase letter and an upper+
     * lowercase pair, source text occasionally drops the separating space ("scale.Quite",
     * "abroad.As"). PDFBox does this regularly; EPUB content can do it when paragraphs are
     * joined across element boundaries. Without that space BreakIterator can't see the
     * sentence end, the whole paragraph fuses into one mega-sentence, and the fallback
     * MAX_CHUNK_CHARS cut chops a word in half. Lookbehind/lookahead guard against
     * acronyms ("U.S.", "i.e.") and all-caps runs ("U.S.A.").
     */
    private val missingSentenceSpace = Regex("(?<=[a-z])([.!?])(?=[A-Z][a-z])")

    fun split(text: String, locale: Locale = Locale.US): List<String> {
        if (text.isBlank()) return emptyList()
        val repaired = missingSentenceSpace.replace(text, "$1 ")
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

    /** Repeatedly carve [text] at the best available clause break until each piece fits. */
    private fun subChunkByClauses(text: String): List<String> {
        val parts = mutableListOf<String>()
        var remaining = text
        while (remaining.length > SUB_CHUNK_THRESHOLD) {
            val cut = findClauseCut(remaining)
            if (cut <= 0) {
                // No clause break in reach — fall back to a hard cut at MAX_CHUNK_CHARS so we
                // never ship a chunk so big it stalls the engine for many seconds.
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
     * Previously the search was capped at SUB_CHUNK_THRESHOLD * 1.5 (= 150 chars), which
     * meant any sentence whose first clause break landed past that was hard-cut at
     * MAX_CHUNK_CHARS — splitting words mid-character. Removing the cap lets the search
     * find the best break across the whole sentence.
     */
    private fun findClauseCut(text: String): Int {
        val minCut = SUB_CHUNK_TARGET / 2
        if (minCut >= text.length) return -1

        var bestCut = -1
        var bestCost = Double.MAX_VALUE
        for ((delim, weight) in DELIMITERS) {
            var idx = text.indexOf(delim)
            while (idx >= 0) {
                val cutPos = idx + delim.length
                if (cutPos in minCut until text.length) {
                    val cost = abs(cutPos - SUB_CHUNK_TARGET) * weight
                    if (cost < bestCost) {
                        bestCost = cost
                        bestCut = cutPos
                    }
                }
                idx = text.indexOf(delim, idx + 1)
            }
        }
        return bestCut
    }

    // Lower weight = more natural break point (preferred per unit of distance from target).
    private val DELIMITERS: List<Pair<String, Double>> = listOf(
        "—" to 0.5,
        "–" to 0.5,
        "--" to 0.5,
        ";" to 1.0,
        ":" to 1.0,
        "," to 1.5,
    )
}
