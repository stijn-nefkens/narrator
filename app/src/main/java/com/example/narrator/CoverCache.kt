package com.example.narrator

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache

/**
 * Process-wide cache of downscaled cover bitmaps, keyed by file path.
 *
 * Every NarratorState emission (at least once per sentence) used to decode the full-resolution
 * cover on the main thread in four places — Player, mini-player, and twice in NarrationService —
 * plus the library row rebind. A 1600×2400 EPUB cover is ~15 MB per decode, and pushing it into
 * the MediaSession metadata risks TransactionTooLargeException. Covers are shown at ≤ ~200dp, so
 * one decode subsampled to [MAX_DIM_PX] and reused is plenty. Cover files are UUID-named and
 * never rewritten in place, so the path is a safe key.
 */
object CoverCache {
    private const val MAX_DIM_PX = 512
    private const val CACHE_BYTES = 8 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** The cover at [path], decoded at most once, or null if absent / undecodable. */
    fun get(path: String?): Bitmap? = path?.let { p ->
        cache.get(p) ?: runCatching { decodeScaled(p) }.getOrNull()?.also { cache.put(p, it) }
    }

    private fun decodeScaled(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = coverSampleSize(bounds.outWidth, bounds.outHeight, MAX_DIM_PX)
        }
        return BitmapFactory.decodeFile(path, opts)
    }
}

/**
 * Largest power-of-two subsample that keeps the longer side of a [width]×[height] image at or
 * above [maxDim] (BitmapFactory only honours powers of two). Top-level, not in [CoverCache], so
 * unit tests don't initialise the Android LruCache.
 */
@androidx.annotation.VisibleForTesting
internal fun coverSampleSize(width: Int, height: Int, maxDim: Int): Int {
    val longest = maxOf(width, height)
    var sample = 1
    while (longest / (sample * 2) >= maxDim) sample *= 2
    return sample
}
