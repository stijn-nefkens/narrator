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

    fun split(text: String, locale: Locale = Locale.US): List<String> {
        if (text.isBlank()) return emptyList()
        val it = BreakIterator.getSentenceInstance(locale)
        it.setText(text)
        val raw = mutableListOf<String>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            val piece = text.substring(start, end).trim()
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
     * Best clause break in [text], or -1 if none in range. Searches the window
     * [TARGET/2, THRESHOLD*1.5]. Each delimiter type has a "naturalness" weight; we pick the
     * candidate minimising `distance_to_target * weight`, so em-dashes are preferred per unit
     * of distance but a much closer comma still wins over a far-away dash.
     */
    private fun findClauseCut(text: String): Int {
        val minCut = SUB_CHUNK_TARGET / 2
        val maxCut = (SUB_CHUNK_THRESHOLD * 3 / 2).coerceAtMost(text.length)
        if (minCut >= maxCut) return -1

        var bestCut = -1
        var bestCost = Double.MAX_VALUE
        for ((delim, weight) in DELIMITERS) {
            var idx = text.indexOf(delim)
            while (idx >= 0) {
                val cutPos = idx + delim.length
                if (cutPos > maxCut) break
                if (cutPos in minCut..maxCut) {
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
