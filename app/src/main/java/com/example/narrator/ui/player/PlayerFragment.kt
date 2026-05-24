package com.example.narrator.ui.player

import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.narrator.NarratorApp
import com.example.narrator.R
import com.example.narrator.databinding.FragmentPlayerBinding
import com.example.narrator.tts.NarratorState
import kotlinx.coroutines.launch

class PlayerFragment : Fragment() {
    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!

    private val container get() = (requireActivity().application as NarratorApp).container

    private var scrubbing = false
    private val highlightHandler = Handler(Looper.getMainLooper())
    private val highlightTick = object : Runnable {
        override fun run() {
            refreshHighlight()
            if (container.narrator.state.value.isPlaying) {
                highlightHandler.postDelayed(this, HIGHLIGHT_INTERVAL_MS)
            }
        }
    }

    private val synthesisingHandler = Handler(Looper.getMainLooper())
    private val showSynthesisingRunnable = Runnable {
        val state = container.narrator.state.value
        if (state.isPlaying && state.currentChunkStartedAt == 0L) {
            _binding?.playerSynthesising?.visibility = View.VISIBLE
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.playerPlayPause.setOnClickListener { container.narrator.togglePlayPause() }
        binding.playerPrevChapter.setOnClickListener { container.narrator.skipChapterPrev() }
        binding.playerNextChapter.setOnClickListener { container.narrator.skipChapterNext() }
        binding.playerPrevStep.setOnClickListener { container.narrator.skipStepPrev() }
        binding.playerNextStep.setOnClickListener { container.narrator.skipStepNext() }
        binding.playerSpeed.setOnClickListener { cycleSpeed() }

        binding.playerScrub.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) updateProgressText(progress, seekBar.max)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) { scrubbing = true }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                scrubbing = false
                container.narrator.seekToGlobalChunk(seekBar.progress)
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.narrator.state.collect(::render)
            }
        }
    }

    override fun onDestroyView() {
        highlightHandler.removeCallbacks(highlightTick)
        synthesisingHandler.removeCallbacks(showSynthesisingRunnable)
        super.onDestroyView()
        _binding = null
    }

    private fun render(state: NarratorState) {
        val loaded = state.loaded
        if (loaded == null) {
            binding.playerEmpty.visibility = View.VISIBLE
            binding.playerLoaded.visibility = View.GONE
            highlightHandler.removeCallbacks(highlightTick)
            return
        }
        binding.playerEmpty.visibility = View.GONE
        binding.playerLoaded.visibility = View.VISIBLE

        binding.playerTitle.text = loaded.title
        binding.playerAuthor.text = loaded.author

        val chapterTitle = loaded.chapterTitles.getOrNull(state.position.chapterIndex).orEmpty()
        binding.playerChapter.text = getString(
            R.string.player_chapter_format,
            state.position.chapterIndex + 1,
            loaded.chapterTitles.size,
            chapterTitle,
        )

        val bitmap = loaded.coverPath?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
        if (bitmap != null) {
            binding.playerCover.setImageBitmap(bitmap)
        } else {
            binding.playerCover.setImageResource(R.drawable.ic_book_placeholder)
        }

        binding.playerPlayPause.setIconResource(
            if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
        )
        binding.playerPlayPause.contentDescription = getString(
            if (state.isPlaying) R.string.player_pause else R.string.player_play,
        )

        val total = (loaded.totalChunks - 1).coerceAtLeast(0)
        binding.playerScrub.max = total
        if (!scrubbing) {
            binding.playerScrub.progress = state.position.globalChunk.coerceIn(0, total)
            updateProgressText(state.position.globalChunk, total)
        }

        binding.playerSpeed.text = getString(R.string.player_speed_format, state.speed)

        binding.playerNextText.text = state.nextText
        // Reset highlight for the new chunk, then start ticking if we're playing.
        refreshHighlight()
        highlightHandler.removeCallbacks(highlightTick)
        if (state.isPlaying) highlightHandler.postDelayed(highlightTick, HIGHLIGHT_INTERVAL_MS)

        // Show "Synthesising…" only if we're playing but the current chunk hasn't started any
        // audio for SYNTHESISING_DELAY_MS. This suppresses the flicker at every chunk transition
        // while still explaining the wait when sherpa-onnx is genuinely slow on a long chunk.
        synthesisingHandler.removeCallbacks(showSynthesisingRunnable)
        if (state.isPlaying && state.currentChunkStartedAt == 0L) {
            synthesisingHandler.postDelayed(showSynthesisingRunnable, SYNTHESISING_DELAY_MS)
        } else {
            binding.playerSynthesising.visibility = View.INVISIBLE
        }
    }

    private fun refreshHighlight() {
        val state = container.narrator.state.value
        val text = state.currentText
        if (text.isEmpty()) {
            binding.playerCurrentText.text = ""
            return
        }
        // Use the MediaPlayer's actual playback position so the highlight tracks the audio
        // exactly. (The previous time-based estimate at 80ms/char drifted behind real audio
        // because Kokoro's per-character rate varies with content and speed.)
        val position = container.narrator.playbackPositionMs()
        val duration = container.narrator.playbackDurationMs()
        val charsSpoken = if (duration > 0 && position > 0) {
            (position.toFloat() / duration * text.length).toInt().coerceIn(0, text.length)
        } else 0
        // Snap to word boundary so highlight grows word-by-word.
        val highlightEnd = snapToWordEnd(text, charsSpoken)
        if (highlightEnd <= 0) {
            binding.playerCurrentText.text = text
            return
        }
        val spannable = SpannableString(text)
        val highlightColor = highlightColor()
        spannable.setSpan(
            BackgroundColorSpan(highlightColor),
            0, highlightEnd,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        binding.playerCurrentText.text = spannable
    }

    private fun snapToWordEnd(text: String, charsSpoken: Int): Int {
        if (charsSpoken >= text.length) return text.length
        if (charsSpoken <= 0) return 0
        // Move forward to include any partial word.
        var i = charsSpoken
        while (i < text.length && !text[i].isWhitespace()) i++
        return i
    }

    private fun highlightColor(): Int {
        // Subtle highlight from the theme's primary color at ~30% alpha.
        val base = ContextCompat.getColor(requireContext(), com.google.android.material.R.color.design_default_color_primary)
        return Color.argb(
            70,
            Color.red(base),
            Color.green(base),
            Color.blue(base),
        )
    }

    private fun updateProgressText(current: Int, total: Int) {
        val pct = if (total <= 0) 0 else (current.toDouble() / total * 100).toInt().coerceIn(0, 100)
        binding.playerProgressText.text = getString(R.string.library_progress_format, pct)
    }

    private fun cycleSpeed() {
        val current = container.narrator.state.value.speed
        val next = SPEED_CYCLE.firstOrNull { it > current + 0.001f } ?: SPEED_CYCLE.first()
        container.narrator.setSpeed(next)
    }

    private companion object {
        val SPEED_CYCLE = listOf(0.8f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        const val HIGHLIGHT_INTERVAL_MS = 50L
        // Long enough that the natural 1.5s chapter pause doesn't show the spinner; short enough
        // that a genuinely slow synth on a long sentence still surfaces feedback.
        const val SYNTHESISING_DELAY_MS = 1800L
    }
}
