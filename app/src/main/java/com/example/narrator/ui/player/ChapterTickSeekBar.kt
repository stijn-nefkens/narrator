package com.example.narrator.ui.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatSeekBar

/**
 * SeekBar that paints a small dot at each chapter boundary on top of the track. Boundaries
 * are passed in as chunk indices (the same scale as the SeekBar's progress/max), so 0 is
 * always the first dot and we skip drawing one at the absolute end.
 *
 * The dots sit slightly above the track line so they don't fight the thumb visually while
 * the user drags.
 */
class ChapterTickSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.seekBarStyle,
) : AppCompatSeekBar(context, attrs, defStyleAttr) {

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Resolves via theme so it picks up the right tint in light / dark / AMOLED.
        color = resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        style = Paint.Style.FILL
    }
    private val tickRadiusPx = 2.5f * resources.displayMetrics.density
    private var chapterStarts: List<Int> = emptyList()

    fun setChapterStarts(positions: List<Int>) {
        chapterStarts = positions
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val effectiveMax = max
        if (effectiveMax <= 0 || chapterStarts.isEmpty()) return
        val trackLeft = paddingLeft.toFloat()
        val trackRight = (width - paddingRight).toFloat()
        val trackWidth = trackRight - trackLeft
        val y = height / 2f
        for (pos in chapterStarts) {
            if (pos <= 0 || pos >= effectiveMax) continue
            val x = trackLeft + (pos.toFloat() / effectiveMax) * trackWidth
            canvas.drawCircle(x, y, tickRadiusPx, tickPaint)
        }
    }

    private fun resolveThemeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }
}
