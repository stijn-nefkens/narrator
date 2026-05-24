package com.example.narrator.epub

import java.text.BreakIterator
import java.util.Locale

internal object Sentences {
    const val MAX_CHUNK_CHARS = 500

    fun split(text: String, locale: Locale = Locale.US): List<String> {
        if (text.isBlank()) return emptyList()
        val it = BreakIterator.getSentenceInstance(locale)
        it.setText(text)
        val out = mutableListOf<String>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            val raw = text.substring(start, end).trim()
            if (raw.isNotEmpty()) {
                if (raw.length > MAX_CHUNK_CHARS) out.addAll(softSplit(raw))
                else out.add(raw)
            }
            start = end
            end = it.next()
        }
        return out
    }

    private fun softSplit(text: String): List<String> {
        val parts = mutableListOf<String>()
        var remaining = text
        while (remaining.length > MAX_CHUNK_CHARS) {
            val lo = MAX_CHUNK_CHARS / 2
            val hi = MAX_CHUNK_CHARS
            val window = remaining.substring(lo, hi)
            val rel = window.lastIndexOfAny(charArrayOf(';', ','))
            val cut = if (rel >= 0) lo + rel + 1 else hi
            parts.add(remaining.substring(0, cut).trim())
            remaining = remaining.substring(cut).trim()
        }
        if (remaining.isNotEmpty()) parts.add(remaining)
        return parts
    }
}
