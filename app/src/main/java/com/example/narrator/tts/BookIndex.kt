package com.example.narrator.tts

/**
 * Position arithmetic over a book's per-chapter chunk counts: (chapter, chunk) ↔ global chunk
 * index, stepping forward/back across chapter boundaries, chapter start offsets.
 *
 * This used to be re-derived in three places — Narrator (positionFor / positionFromGlobal /
 * advanceChunk / retreatChunk / positionAhead), PlayerFragment (chapter navigator + seek-bar
 * ticks) and NarrationService (chapter-scoped seek) — each with its own loop and edge handling.
 * One pure implementation, unit-tested, keeps them consistent.
 *
 * All results are clamped into the book: out-of-range chapters/chunks snap to the nearest valid
 * position. An empty book yields `Position(0, 0, 0)` everywhere.
 */
class BookIndex(private val chapterChunkCounts: List<Int>) {

    /** chapterStarts[i] = global index of chapter i's first chunk; the last entry is [total]. */
    private val chapterStarts: IntArray = IntArray(chapterChunkCounts.size + 1).also { starts ->
        chapterChunkCounts.forEachIndexed { i, n -> starts[i + 1] = starts[i] + n.coerceAtLeast(0) }
    }

    /** Total number of chunks in the book. */
    val total: Int get() = chapterStarts.last()

    private val lastGlobal: Int get() = (total - 1).coerceAtLeast(0)

    /** Global index of [chapter]'s first chunk (clamped to a valid chapter). */
    fun chapterStart(chapter: Int): Int =
        chapterStarts[chapter.coerceIn(0, chapterChunkCounts.lastIndex.coerceAtLeast(0))]

    /** Global indices where chapters 2..N begin — the seek-bar chapter ticks. */
    fun chapterBoundaries(): List<Int> = (1 until chapterChunkCounts.size).map { chapterStarts[it] }

    /** The position at [chapter]/[chunk], each clamped into range. */
    fun position(chapter: Int, chunk: Int): Position {
        if (chapterChunkCounts.isEmpty()) return Position(0, 0, 0)
        val ch = chapter.coerceIn(0, chapterChunkCounts.lastIndex)
        val size = chapterChunkCounts[ch]
        val ck = chunk.coerceIn(0, (size - 1).coerceAtLeast(0))
        return Position(ch, ck, chapterStarts[ch] + ck)
    }

    /** The position at global chunk [global], clamped to the first/last chunk. */
    fun fromGlobal(global: Int): Position {
        if (total == 0) return position(chapterChunkCounts.lastIndex, 0)
        val g = global.coerceIn(0, lastGlobal)
        // Last chapter whose start is <= g and that actually has chunks.
        var ch = chapterChunkCounts.lastIndex
        while (ch > 0 && (chapterStarts[ch] > g || chapterChunkCounts[ch] <= 0)) ch--
        return Position(ch, g - chapterStarts[ch], g)
    }

    /** [n] chunks after [from], stopping at the last chunk of the book. */
    fun advance(from: Position, n: Int): Position = fromGlobal(from.globalChunk + n)

    /** [n] chunks before [from], stopping at the first chunk of the book. */
    fun retreat(from: Position, n: Int): Position = fromGlobal(from.globalChunk - n)

    /** The position exactly [n] chunks after [from], or null if that runs past the end. */
    fun ahead(from: Position, n: Int): Position? {
        val target = from.globalChunk + n
        return if (target > lastGlobal || total == 0) null else fromGlobal(target)
    }

    /** True if [p] is the book's last chunk (advancing would not move). */
    fun isLast(p: Position): Boolean = p.globalChunk >= lastGlobal
}
