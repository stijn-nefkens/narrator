package com.example.narrator.ui.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.palette.graphics.Palette
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.SeekBar
import kotlin.math.abs
import kotlin.math.round
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.narrator.tts.SleepTimer
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
            // Refresh time-remaining + sleep info on every tick so the countdown updates
            // independently of state changes.
            _binding?.let { b ->
                val state = container.narrator.state.value
                b.playerRemaining.text = formatRemainingWithSleep(state.sleepTimer)
            }
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
        binding.playerCover.setOnClickListener { container.narrator.togglePlayPause() }
        binding.playerChapter.setOnClickListener { openChapterNavigator() }
        binding.playerPrevChapter.setOnClickListener { container.narrator.skipChapterPrev() }
        binding.playerNextChapter.setOnClickListener { container.narrator.skipChapterNext() }
        binding.playerPrevStep.setOnClickListener { container.narrator.skipStepPrev() }
        binding.playerNextStep.setOnClickListener { container.narrator.skipStepNext() }
        setupSpeedDragGesture()
        binding.playerSleep.setOnClickListener { openSleepDialog() }
        binding.playerBookmark.setOnClickListener { openBookmarksDialog() }

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
            applyCoverTint(bitmap)
        } else {
            binding.playerCover.setImageResource(R.drawable.ic_book_placeholder)
            binding.playerLoaded.background = null
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
        // Sleep button is icon-only; the countdown / state is shown in the time-remaining line
        // so the user has a single place to look for "what's coming up".
        binding.playerRemaining.text = formatRemainingWithSleep(state.sleepTimer)

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
        // Prefer the engine's exact char range when available (onRangeStart events). Otherwise
        // fall back to mapping MediaPlayer's playback position onto the text length.
        val exact = container.narrator.currentSpokenCharEnd()
        val charsSpoken = if (exact >= 0) {
            exact.coerceIn(0, text.length)
        } else {
            val position = container.narrator.playbackPositionMs()
            val duration = container.narrator.playbackDurationMs()
            if (duration > 0 && position > 0) {
                (position.toFloat() / duration * text.length).toInt().coerceIn(0, text.length)
            } else 0
        }
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

    private fun applyCoverTint(bitmap: Bitmap) {
        // Sample asynchronously so we don't block render.
        Palette.from(bitmap).generate { palette ->
            val root = _binding?.playerLoaded ?: return@generate
            val accent = palette?.darkVibrantSwatch?.rgb
                ?: palette?.vibrantSwatch?.rgb
                ?: palette?.darkMutedSwatch?.rgb
                ?: return@generate
            // 25%-alpha tint at the top fading to transparent — gives the player a per-book
            // mood without fighting the dark theme.
            val tint = Color.argb(64, Color.red(accent), Color.green(accent), Color.blue(accent))
            val gradient = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(tint, Color.TRANSPARENT),
            )
            root.background = gradient
        }
    }

    /**
     * Speed control gesture: tap = reset to 1.0x; horizontal drag = adjust in 0.1x steps.
     * Replaces the previous −/+ buttons so a single chip is the whole speed control.
     */
    @Suppress("ClickableViewAccessibility")
    private fun setupSpeedDragGesture() {
        val density = resources.displayMetrics.density
        val touchSlop = ViewConfiguration.get(requireContext()).scaledTouchSlop
        val pxPerStep = 30f * density   // 30dp horizontal drag = one 0.1x step

        var startX = 0f
        var startSpeed = 1.0f
        var dragging = false
        var downAt = 0L

        binding.playerSpeed.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startSpeed = container.narrator.state.value.speed
                    dragging = false
                    downAt = SystemClock.elapsedRealtime()
                    binding.playerSpeed.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    if (!dragging && abs(dx) > touchSlop) dragging = true
                    if (dragging) {
                        val steps = round(dx / pxPerStep).toInt()
                        val raw = startSpeed + steps * 0.1f
                        // Snap to a 0.1 grid to avoid float drift between strokes.
                        val target = (round(raw / 0.1f) * 0.1f).coerceIn(0.8f, 2.0f)
                        if (abs(target - container.narrator.state.value.speed) > 0.001f) {
                            container.narrator.setSpeed(target)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = SystemClock.elapsedRealtime() - downAt
                    if (!dragging && elapsed < 300L) {
                        container.narrator.setSpeed(1.0f)
                    }
                    binding.playerSpeed.parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    binding.playerSpeed.parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }
                else -> false
            }
        }
    }

    private fun openBookmarksDialog() {
        val bookId = container.narrator.state.value.loaded?.bookId ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val bookmarks = container.bookRepository.listBookmarks(bookId)
            val loaded = container.narrator.state.value.loaded
            val totalChunks = loaded?.totalChunks ?: 1
            val labels: Array<String> = if (bookmarks.isEmpty()) {
                arrayOf(getString(R.string.bookmarks_empty))
            } else {
                bookmarks.map { bm ->
                    val pct = (bm.globalChunk.toDouble() / totalChunks * 100).toInt().coerceIn(0, 100)
                    val raw = getString(R.string.bookmarks_item_format, bm.chapterIndex + 1, pct)
                    if (bm.label.isNullOrBlank()) raw else "$raw · ${bm.label}"
                }.toTypedArray()
            }
            val title = if (bookmarks.isEmpty()) getString(R.string.bookmarks_title)
            else "${getString(R.string.bookmarks_title)} · ${getString(R.string.bookmarks_long_press_hint)}"
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setItems(labels) { _, which ->
                    if (bookmarks.isNotEmpty()) {
                        container.narrator.seekToGlobalChunk(bookmarks[which].globalChunk)
                    }
                }
                .setPositiveButton(R.string.bookmarks_add_here) { _, _ ->
                    val s = container.narrator.state.value
                    viewLifecycleOwner.lifecycleScope.launch {
                        container.bookRepository.addBookmark(
                            bookId = bookId,
                            chapterIndex = s.position.chapterIndex,
                            chunkIndex = s.position.chunkIndex,
                            globalChunk = s.position.globalChunk,
                            label = null,
                        )
                    }
                }
                .setNeutralButton(R.string.bookmarks_close, null)
                .create()
            dialog.show()
            // Long-press a row to delete it. setItems doesn't have a native long-click hook,
            // so attach to the underlying ListView after show().
            if (bookmarks.isNotEmpty()) {
                dialog.listView?.setOnItemLongClickListener { _, _, position, _ ->
                    val target = bookmarks[position]
                    dialog.dismiss()
                    AlertDialog.Builder(requireContext())
                        .setMessage(R.string.bookmarks_delete_confirm)
                        .setPositiveButton(R.string.library_delete) { _, _ ->
                            viewLifecycleOwner.lifecycleScope.launch {
                                container.bookRepository.deleteBookmark(target.id)
                            }
                        }
                        .setNegativeButton(R.string.library_cancel, null)
                        .show()
                    true
                }
            }
        }
    }

    private fun openChapterNavigator() {
        val state = container.narrator.state.value
        val loaded = state.loaded ?: return
        if (loaded.chapterTitles.isEmpty()) return
        val labels = loaded.chapterTitles.mapIndexed { i, title ->
            "${i + 1}. $title"
        }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.player_chapter_dialog_title)
            .setSingleChoiceItems(labels, state.position.chapterIndex) { dialog, which ->
                val start = loaded.chapterChunkCounts.take(which).sum()
                container.narrator.seekToGlobalChunk(start)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.bookmarks_close, null)
            .show()
    }

    private fun openSleepDialog() {
        val labels = arrayOf(
            getString(R.string.sleep_dialog_off),
            getString(R.string.sleep_dialog_eoc),
            getString(R.string.sleep_dialog_15),
            getString(R.string.sleep_dialog_30),
            getString(R.string.sleep_dialog_60),
            getString(R.string.sleep_dialog_custom),
        )
        val current = container.narrator.state.value.sleepTimer
        val selected = when {
            current is SleepTimer.EndOfChapter -> 1
            current is SleepTimer.At -> -1
            else -> 0
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.sleep_dialog_title)
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                when (which) {
                    1 -> container.narrator.setSleepTimer(SleepTimer.EndOfChapter)
                    2 -> container.narrator.setSleepTimer(SleepTimer.At(SystemClock.elapsedRealtime() + 15 * 60_000L))
                    3 -> container.narrator.setSleepTimer(SleepTimer.At(SystemClock.elapsedRealtime() + 30 * 60_000L))
                    4 -> container.narrator.setSleepTimer(SleepTimer.At(SystemClock.elapsedRealtime() + 60 * 60_000L))
                    5 -> openCustomSleepDialog()
                    else -> container.narrator.setSleepTimer(SleepTimer.Off)
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun openCustomSleepDialog() {
        val input = android.widget.EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.sleep_custom_hint)
        }
        val container = android.widget.FrameLayout(requireContext()).apply {
            val pad = (resources.displayMetrics.density * 24).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.sleep_custom_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val mins = input.text.toString().toIntOrNull()?.coerceIn(1, 600) ?: return@setPositiveButton
                this.container.narrator.setSleepTimer(
                    SleepTimer.At(SystemClock.elapsedRealtime() + mins * 60_000L)
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Time-remaining line; appends a sleep-timer indicator when one is active. */
    private fun formatRemainingWithSleep(sleep: SleepTimer): String {
        val base = formatRemaining(container.narrator.remainingMs())
        val sleepPart = when (sleep) {
            SleepTimer.Off -> ""
            SleepTimer.EndOfChapter -> " · " + getString(R.string.player_sleep_end_of_chapter)
            is SleepTimer.At -> {
                val remainingMs = (sleep.endsAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                val mins = (remainingMs / 60_000L).toInt()
                val secs = ((remainingMs % 60_000L) / 1000L).toInt()
                " · " + getString(R.string.player_sleep_countdown_format, mins, secs)
            }
        }
        return base + sleepPart
    }

    private fun formatRemaining(ms: Long): String {
        if (ms <= 0L) return ""
        val totalMin = (ms / 60_000L).toInt()
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) getString(R.string.player_remaining_long_format, h, m)
        else getString(R.string.player_remaining_short_format, m.coerceAtLeast(1))
    }

    private companion object {
        const val HIGHLIGHT_INTERVAL_MS = 50L
        // Long enough that the natural 1.5s chapter pause doesn't show the spinner; short enough
        // that a genuinely slow synth on a long sentence still surfaces feedback.
        const val SYNTHESISING_DELAY_MS = 1800L
    }
}
