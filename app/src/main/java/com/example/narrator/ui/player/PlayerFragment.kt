package com.example.narrator.ui.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
import com.example.narrator.ui.voicesetup.VoiceSetupActivity
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class PlayerFragment : Fragment() {
    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!

    private val container get() = (requireActivity().application as NarratorApp).container

    /** Triggered after the user picks a destination file for bookmark export. */
    private val exportBookmarks = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri: Uri? -> uri?.let(::writeBookmarksTo) }

    private var scrubbing = false
    private val highlightHandler = Handler(Looper.getMainLooper())
    private val highlightTick = object : Runnable {
        override fun run() {
            refreshHighlight()
            // Refresh time-remaining + sleep info on every tick so the countdown updates
            // independently of state changes.
            _binding?.let { b ->
                val state = container.narrator.state.value
                // Don't trample over the "Synthesising…" message — let it stay visible
                // until the chunk actually starts (render() will clear the flag then).
                if (!showingSynthesising) {
                    b.playerRemaining.text = formatRemainingWithSleep(state.sleepTimer)
                }
            }
            if (container.narrator.state.value.isPlaying) {
                highlightHandler.postDelayed(this, HIGHLIGHT_INTERVAL_MS)
            }
        }
    }

    private val synthesisingHandler = Handler(Looper.getMainLooper())
    /** Tracks whether the status line is currently showing the "Synthesising…" message;
     *  the highlight tick that refreshes player_remaining each second checks this so it
     *  doesn't overwrite the message with the remaining-time string. */
    private var showingSynthesising = false
    private val showSynthesisingRunnable = Runnable {
        val state = container.narrator.state.value
        if (state.isPlaying && state.currentChunkStartedAt == 0L) {
            showingSynthesising = true
            _binding?.playerRemaining?.text = getString(R.string.player_synthesising)
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
        setupSpeedGesture()
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
        // Surface engine errors (e.g. TTS engine disabled) as a one-shot Snackbar with a
        // shortcut into Voice setup. Clearing the flag here makes this a single render fire,
        // and prevents the message from re-showing on every subsequent state emit.
        state.engineError?.let { msg ->
            Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG)
                .setAction(R.string.engine_error_action) {
                    startActivity(VoiceSetupActivity.intent(requireContext(), firstRun = false))
                }
                .show()
            container.narrator.clearEngineError()
        }
        binding.playerLoading.visibility = if (state.loading) View.VISIBLE else View.GONE
        val loaded = state.loaded
        if (loaded == null) {
            binding.playerEmpty.visibility = if (state.loading) View.GONE else View.VISIBLE
            binding.playerLoaded.visibility = View.GONE
            highlightHandler.removeCallbacks(highlightTick)
            return
        }
        binding.playerEmpty.visibility = View.GONE
        binding.playerLoaded.visibility = View.VISIBLE

        maybeShowSpeedTooltip()

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
        // Chapter boundary dots — running totals of chunks before each chapter, drop the
        // leading 0 (start of book; the thumb already conveys that) and the trailing total.
        val chapterStarts = loaded.chapterChunkCounts
            .runningFold(0) { acc, n -> acc + n }
            .drop(1)
            .dropLast(1)
        binding.playerScrub.setChapterStarts(chapterStarts)
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
        // The message replaces the remaining-time text in-place to avoid reserving extra space.
        synthesisingHandler.removeCallbacks(showSynthesisingRunnable)
        if (state.isPlaying && state.currentChunkStartedAt == 0L) {
            synthesisingHandler.postDelayed(showSynthesisingRunnable, SYNTHESISING_DELAY_MS)
        } else if (showingSynthesising) {
            showingSynthesising = false
            binding.playerRemaining.text = formatRemainingWithSleep(state.sleepTimer)
        }
    }

    private fun refreshHighlight() {
        val state = container.narrator.state.value
        // Show the segment currently being spoken (a sentence may be synthesised in several
        // segments); the highlight char-range / playback position refer to that same segment, so
        // text + highlight + audio stay in sync. Falls back to the whole sentence when nothing is
        // actively playing (e.g. paused before first play).
        val text = container.narrator.currentSpokenSegment()?.takeIf { it.isNotBlank() }
            ?: state.currentText
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
     * Speed control gesture: tap = reset to 1.0x; horizontal drag = adjust in 0.1x steps;
     * long-press = open a slider dialog for explicit value picking.
     *
     * We intercept all events (return true on DOWN), so Android's stock click and long-click
     * machinery doesn't run for this view. The handler tracks both itself via a postDelayed
     * runnable that fires after the standard long-press timeout if no drag happened.
     */
    @Suppress("ClickableViewAccessibility")
    private fun setupSpeedGesture() {
        val density = resources.displayMetrics.density
        val touchSlop = ViewConfiguration.get(requireContext()).scaledTouchSlop
        val pxPerStep = 30f * density   // 30dp horizontal drag = one 0.1x step
        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        val pressHandler = Handler(Looper.getMainLooper())

        var startX = 0f
        var startSpeed = 1.0f
        var dragging = false
        var longPressFired = false
        var downAt = 0L

        binding.playerSpeed.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startSpeed = container.narrator.state.value.speed
                    dragging = false
                    longPressFired = false
                    downAt = SystemClock.elapsedRealtime()
                    pressHandler.postDelayed({
                        if (!dragging) {
                            longPressFired = true
                            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                            openSpeedDialog()
                        }
                    }, longPressMs)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (longPressFired) return@setOnTouchListener true
                    val dx = event.rawX - startX
                    if (!dragging && abs(dx) > touchSlop) {
                        dragging = true
                        pressHandler.removeCallbacksAndMessages(null)
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) {
                        val steps = round(dx / pxPerStep).toInt()
                        val raw = startSpeed + steps * 0.1f
                        val target = (round(raw / 0.1f) * 0.1f).coerceIn(0.8f, 2.0f)
                        if (abs(target - container.narrator.state.value.speed) > 0.001f) {
                            container.narrator.setSpeed(target)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    pressHandler.removeCallbacksAndMessages(null)
                    if (!dragging && !longPressFired) {
                        val elapsed = SystemClock.elapsedRealtime() - downAt
                        if (elapsed < longPressMs) container.narrator.setSpeed(1.0f)
                    }
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    pressHandler.removeCallbacksAndMessages(null)
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }
                else -> false
            }
        }
    }

    private fun openSpeedDialog() {
        val ctx = requireContext()
        val slider = com.google.android.material.slider.Slider(ctx).apply {
            valueFrom = 0.8f
            valueTo = 2.0f
            stepSize = 0.1f
            value = container.narrator.state.value.speed.coerceIn(0.8f, 2.0f)
            addOnChangeListener { _, v, _ -> container.narrator.setSpeed(v) }
        }
        val wrapper = android.widget.FrameLayout(ctx).apply {
            val pad = (resources.displayMetrics.density * 24).toInt()
            setPadding(pad, pad, pad, 0)
            addView(slider)
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.player_speed_dialog_title)
            .setView(wrapper)
            .setPositiveButton(android.R.string.ok, null)
            .show()
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
                .setNegativeButton(R.string.bookmarks_export) { _, _ -> startExportBookmarks() }
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

    /** One-shot Snackbar that explains the speed-chip gestures the first time the user
     *  reaches the Player with a book loaded. Pref flag keeps it from re-firing. */
    private fun maybeShowSpeedTooltip() {
        val prefs = container.preferences
        if (prefs.speedTooltipShown) return
        prefs.speedTooltipShown = true
        Snackbar.make(binding.root, R.string.player_speed_tooltip, Snackbar.LENGTH_INDEFINITE)
            .setAction(R.string.player_speed_tooltip_ok) { /* dismiss */ }
            .show()
    }

    private fun startExportBookmarks() {
        val title = container.narrator.state.value.loaded?.title?.take(40) ?: "narrator"
        val safe = title.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifEmpty { "narrator" }
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        exportBookmarks.launch("${safe}-bookmarks-${date}.txt")
    }

    private fun writeBookmarksTo(uri: Uri) {
        val bookId = container.narrator.state.value.loaded?.bookId ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = container.narrator.state.value.loaded ?: return@launch
            val bookmarks = container.bookRepository.listBookmarks(bookId)
            if (bookmarks.isEmpty()) {
                Toast.makeText(requireContext(), R.string.bookmarks_export_empty, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val total = loaded.totalChunks.coerceAtLeast(1)
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            val text = buildString {
                appendLine("Narrator bookmarks")
                appendLine("Book: ${loaded.title}")
                appendLine("Author: ${loaded.author}")
                appendLine()
                for (bm in bookmarks) {
                    val pct = (bm.globalChunk.toDouble() / total * 100).toInt().coerceIn(0, 100)
                    val chapterTitle = loaded.chapterTitles.getOrNull(bm.chapterIndex).orEmpty()
                    val label = bm.label?.takeIf { it.isNotBlank() }
                    append("Chapter ${bm.chapterIndex + 1}")
                    if (chapterTitle.isNotEmpty()) append(" — $chapterTitle")
                    append(" · $pct%")
                    if (label != null) append(" · $label")
                    append(" · ${df.format(Date(bm.createdAt))}")
                    appendLine()
                }
            }
            runCatching {
                requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                }
            }.onSuccess {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.bookmarks_export_success, bookmarks.size),
                    Toast.LENGTH_SHORT,
                ).show()
            }.onFailure { e ->
                Toast.makeText(requireContext(), e.message ?: "Export failed", Toast.LENGTH_LONG).show()
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
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.player_chapter_dialog_title)
            .setSingleChoiceItems(labels, state.position.chapterIndex) { d, which ->
                val start = loaded.chapterChunkCounts.take(which).sum()
                container.narrator.seekToGlobalChunk(start)
                d.dismiss()
            }
            .setNegativeButton(R.string.bookmarks_close, null)
            .create()
        dialog.show()
        // Long-press a chapter row to bookmark the chapter's start position instead of
        // jumping to it. Matches the existing long-press-to-delete pattern in the
        // bookmarks dialog.
        dialog.listView?.setOnItemLongClickListener { _, _, which, _ ->
            val start = loaded.chapterChunkCounts.take(which).sum()
            val (chapter, chunk) = chapterAndLocalChunkFor(loaded, start)
            viewLifecycleOwner.lifecycleScope.launch {
                container.bookRepository.addBookmark(
                    bookId = loaded.bookId,
                    chapterIndex = chapter,
                    chunkIndex = chunk,
                    globalChunk = start,
                    label = null,
                )
                Toast.makeText(
                    requireContext(),
                    getString(R.string.chapter_bookmarked, which + 1),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            true
        }
    }

    /** Resolve (chapterIndex, localChunkIndex) for a global chunk position. Used by
     *  long-press-to-bookmark in the chapter navigator. */
    private fun chapterAndLocalChunkFor(
        loaded: com.example.narrator.tts.LoadedBook,
        globalChunk: Int,
    ): Pair<Int, Int> {
        var remaining = globalChunk
        for ((i, count) in loaded.chapterChunkCounts.withIndex()) {
            if (remaining < count) return i to remaining
            remaining -= count
        }
        return (loaded.chapterChunkCounts.size - 1).coerceAtLeast(0) to 0
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
        val selected = when (current) {
            SleepTimer.Off -> 0
            SleepTimer.EndOfChapter -> 1
            is SleepTimer.At, is SleepTimer.Paused -> -1
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
            is SleepTimer.At -> " · " + countdown(sleep.endsAtMs - SystemClock.elapsedRealtime())
            is SleepTimer.Paused -> " · " + countdown(sleep.remainingMs) + " (paused)"
        }
        return base + sleepPart
    }

    private fun countdown(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        val mins = (safe / 60_000L).toInt()
        val secs = ((safe % 60_000L) / 1000L).toInt()
        return getString(R.string.player_sleep_countdown_format, mins, secs)
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
