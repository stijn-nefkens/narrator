package com.example.narrator.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import com.example.narrator.data.AppPreferences
import com.example.narrator.data.BookEntity
import com.example.narrator.data.BookRepository
import com.example.narrator.data.SkipIncrement
import com.example.narrator.epub.Book
import com.example.narrator.epub.EpubParser
import com.example.narrator.epub.Sentences
import com.example.narrator.pdf.PdfParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class Position(val chapterIndex: Int, val chunkIndex: Int, val globalChunk: Int)

data class LoadedBook(
    val bookId: Long,
    val title: String,
    val author: String,
    val coverPath: String?,
    val chapterTitles: List<String>,
    val chapterChunkCounts: List<Int>,
    val totalChunks: Int,
) {
    companion object {
        /**
         * Builds a [LoadedBook] from the database row [book] and the parsed file [parsed].
         *
         * Title, author and cover come from the DATABASE — they're user-editable in the Library,
         * so the DB is the single source of truth and edits flow straight to the Player. The
         * parsed file is used only for *content* (chapter titles + per-chapter chunk lists); its
         * embedded title/author are consumed once at import time (BookImporter writes them into
         * the DB) and never read here. Keeping these split was the bug: the Player used to show
         * the parsed (file) title while the Library showed the DB title, so edits never matched.
         */
        fun from(
            book: com.example.narrator.data.BookEntity,
            parsed: Book,
            totalChunks: Int,
        ): LoadedBook = LoadedBook(
            bookId = book.id,
            title = book.title,
            author = book.author,
            coverPath = book.coverPath,
            chapterTitles = parsed.chapters.map { it.title },
            chapterChunkCounts = parsed.chapters.map { it.chunks.size },
            totalChunks = totalChunks,
        )
    }
}

data class NarratorState(
    val loaded: LoadedBook? = null,
    val position: Position = Position(0, 0, 0),
    val isPlaying: Boolean = false,
    val speed: Float = 1.0f,
    val currentText: String = "",
    val nextText: String = "",
    /** SystemClock.elapsedRealtime() when the current chunk started playing; 0 = not yet. */
    val currentChunkStartedAt: Long = 0L,
    val sleepTimer: SleepTimer = SleepTimer.Off,
    /** Non-null after the TTS engine refuses to synth several chunks in a row. UI surfaces
     *  this as a Snackbar pointing at Voice setup. Set back to null on the next successful
     *  prime/play. */
    val engineError: String? = null,
    /** True while a book is being parsed/loaded. The Player shows a spinner so opening a large
     *  book (especially a PDF) doesn't look frozen. */
    val loading: Boolean = false,
)

sealed class SleepTimer {
    data object Off : SleepTimer()
    /** Pause at the chapter boundary in the current chunk's chapter (not the next). */
    data object EndOfChapter : SleepTimer()
    /** Pause when SystemClock.elapsedRealtime() reaches [endsAtMs]. Only ticks while playing. */
    data class At(val endsAtMs: Long) : SleepTimer()
    /** The timer is suspended (narrator paused). Holds the unconsumed time for later resume. */
    data class Paused(val remainingMs: Long) : SleepTimer()
}

class Narrator(
    private val context: Context,
    private val repository: BookRepository,
    private val preferences: AppPreferences,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(NarratorState())
    val state: StateFlow<NarratorState> = _state.asStateFlow()

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingPlay = false
    private var chunksByChapter: List<List<String>> = emptyList()
    private var pipeline: FilePipeline? = null

    /**
     * LRU cache of parsed books, keyed by bookId. Re-opening a recently-read book skips the
     * (multi-second, for large PDFs) parse entirely, so the switch is near-instant. Entries are
     * invalidated by [cacheSignature], so a skip-pattern edit, page-range change, or file
     * replacement re-parses. Finished books are never cached (the user has moved on). All access
     * is on the main thread — both loadBook and warmRecentBooks write the map on Main.
     */
    private data class CachedParse(val signature: String, val book: Book)
    private val parseCache = object : LinkedHashMap<Long, CachedParse>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, CachedParse>): Boolean =
            size > MAX_PARSE_CACHE
    }

    // Running totals used to estimate remaining time. Reset on book load.
    private var statsCompletedMs: Long = 0L
    private var statsCompletedChunks: Int = 0

    // Audio focus: pause playback when something else takes audio (call, alarm, assistant),
    // resume when we get focus back from a transient loss.
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var pausedByFocusLoss: Boolean = false
    private var duckedByFocusLoss: Boolean = false
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Permanent loss (call answered, etc.). Pause; don't auto-resume.
                pausedByFocusLoss = false
                if (_state.value.isPlaying) pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Quiet, brief interruption (notification chime). Ducking is more polite
                // than pausing — the listener barely notices the dip.
                duckedByFocusLoss = true
                pipeline?.setVolume(DUCK_VOLUME)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Loud, longer interruption (alarm, incoming Maps direction). Pause and
                // resume when focus returns.
                if (_state.value.isPlaying) {
                    pausedByFocusLoss = true
                    pause()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (duckedByFocusLoss) {
                    duckedByFocusLoss = false
                    pipeline?.setVolume(1f)
                }
                if (pausedByFocusLoss) {
                    pausedByFocusLoss = false
                    play()
                }
            }
        }
    }

    init {
        createTts()
    }

    private fun createTts() {
        ttsReady = false
        // Drop the previous pipeline; it holds a MediaPlayer + listener bound to the old TTS.
        pipeline?.release()
        pipeline = null
        val enginePkg = VoicePreferences.enginePackage(context)
        tts = TextToSpeech(
            context.applicationContext,
            { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    val readyTts = tts ?: return@TextToSpeech
                    readyTts.language = Locale.US
                    applyStoredVoice()
                    // Synthesise at 1.0x; speed is applied at playback via MediaPlayer.
                    readyTts.setSpeechRate(1.0f)
                    readyTts.setPitch(preferences.pitch)
                    pipeline = FilePipeline(
                        context = context.applicationContext,
                        tts = readyTts,
                        onChunkStarted = ::onPipelineChunkStarted,
                        onChunkCompleted = ::onPipelineChunkCompleted,
                        onSynthCascadeFailure = ::onPipelineSynthCascade,
                    ).also { it.setSpeed(_state.value.speed) }
                    // TTS is now ready: prime the pipeline from the current position so the
                    // first chunk is ready as soon as the user presses play.
                    scope.launch { primeFromCurrent(autoplay = pendingPlay) }
                    pendingPlay = false
                }
            },
            enginePkg,
        )
    }

    private fun applyStoredVoice() {
        val stored = VoicePreferences.voiceName(context) ?: return
        val match = runCatching { tts?.voices }.getOrNull()?.firstOrNull { it.name == stored } ?: return
        runCatching { tts?.voice = match }
    }

    /** Recreates the TTS instance after the user switches engines in Voice Setup. */
    fun reinitEngine() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        _state.value = _state.value.copy(isPlaying = false)
        createTts()
    }

    private fun onPipelineChunkStarted(id: String) {
        val parsed = parseUtteranceId(id) ?: return
        val now = SystemClock.elapsedRealtime()
        scope.launch { onChunkStartPlayback(parsed, now) }
    }

    private fun onPipelineChunkCompleted(@Suppress("UNUSED_PARAMETER") id: String) {
        scope.launch { onChunkComplete() }
    }

    private fun onPipelineSynthCascade() {
        // FilePipeline has already paused itself and dropped its queue. Mirror that into
        // NarratorState so the UI shows the paused state, and surface a one-shot error
        // message — the Player UI consumes this with a Snackbar pointing at Voice setup.
        suspendSleepCountdown()
        _state.value = _state.value.copy(
            isPlaying = false,
            engineError = "TTS engine isn't responding. Check Voice setup.",
        )
    }

    /** Clears the engineError flag — call this after the UI has shown it once. */
    fun clearEngineError() {
        if (_state.value.engineError != null) {
            _state.value = _state.value.copy(engineError = null)
        }
    }

    suspend fun loadBook(bookId: Long) {
        val book = repository.getBook(bookId) ?: return
        preferences.lastOpenedBookId = bookId
        val bookmark = repository.getBookmark(bookId)

        val signature = cacheSignature(book)
        val cached = parseCache[bookId]?.takeIf { it.signature == signature }?.book
        val parsed: Book = if (cached != null) {
            // Cache hit — no parse, no spinner. The switch is instant.
            cached
        } else {
            // Surface a spinner immediately — PDF parsing of a large book can take a few
            // seconds, and without feedback the tap into the Player looks like nothing happened.
            _state.value = _state.value.copy(loading = true)
            val p = try {
                withContext(Dispatchers.IO) { parseBookFile(File(book.epubPath), book) }
            } catch (e: Exception) {
                android.util.Log.w("Narrator", "Failed to parse book $bookId at ${book.epubPath}", e)
                // Leave state unchanged so the player keeps whatever was previously loaded.
                _state.value = _state.value.copy(loading = false)
                return
            }
            cachePut(bookId, signature, p, book.isFinished)
            p
        }
        chunksByChapter = parsed.chapters.map { it.chunks }
        val total = chunksByChapter.sumOf { it.size }
        if (total != book.totalChunks) repository.updateTotalChunks(bookId, total)

        // Title/author/cover come from the DB row (user-editable), content from the parsed file.
        val loaded = LoadedBook.from(book, parsed, total)
        val pos = if (bookmark != null) {
            positionFor(loaded, bookmark.chapterIndex, bookmark.chunkIndex)
        } else {
            Position(0, 0, 0)
        }

        pipeline?.stop()
        // Per-book remembered speed wins over the global default — readers calibrate speed
        // per book (slow for poetry, fast for filler).
        val startSpeed = if (book.playbackSpeed > 0f) book.playbackSpeed else preferences.defaultSpeed
        pipeline?.setSpeed(startSpeed)
        tts?.setPitch(preferences.pitch)
        _state.value = NarratorState(
            loaded = loaded,
            position = pos,
            isPlaying = false,
            speed = startSpeed,
        )
        updateCurrentTexts()
        statsCompletedMs = 0L
        statsCompletedChunks = 0
        // Start prefetching as soon as the book is loaded so the first chunk is already
        // synthesised by the time the user presses play.
        primeFromCurrent(autoplay = false)
    }

    /**
     * Refresh the loaded book's editable metadata (title / author / cover) from the DB without
     * re-parsing or interrupting playback. Call after the user edits these for the currently
     * loaded book in the Library, so the Player, mini-player and notification update live. No-op
     * if a different book (or none) is loaded.
     */
    suspend fun refreshLoadedMetadata(bookId: Long) {
        if (_state.value.loaded?.bookId != bookId) return
        val book = repository.getBook(bookId) ?: return
        _state.value.loaded?.let { current ->
            _state.value = _state.value.copy(
                loaded = current.copy(
                    title = book.title,
                    author = book.author,
                    coverPath = book.coverPath,
                ),
            )
        }
    }

    private fun cacheSignature(book: BookEntity): String =
        "${book.epubPath}|${book.pageRangeStart}|${book.pageRangeEnd}|${book.skipPatterns}"

    private fun cachePut(bookId: Long, signature: String, book: Book, finished: Boolean) {
        if (finished) {
            parseCache.remove(bookId)
            return
        }
        // Drop cover bytes before caching — the Player loads the cover from disk via coverPath,
        // so the parsed Book's coverImage is dead weight that would bloat the cache.
        parseCache[bookId] = CachedParse(signature, book.copy(coverImage = null, coverMimeType = null))
    }

    /**
     * Pre-parse the most recently-played, not-yet-finished books into [parseCache] so switching
     * to one of them opens instantly. Runs in the background and never touches the TTS engine,
     * so it can't disturb live playback — it only fills the parse cache. Parsing is sequential
     * to stay gentle on a phone that may be mid-playback. Call after the repository has been
     * refreshed (e.g. app startup).
     */
    fun warmRecentBooks() {
        scope.launch {
            val recent = repository.books.value
                .filterNot { it.book.isFinished }
                .sortedByDescending { it.bookmark?.updatedAt ?: 0L }
                .take(MAX_PARSE_CACHE)
            for (bwp in recent) {
                val book = bwp.book
                val signature = cacheSignature(book)
                if (parseCache[book.id]?.signature == signature) continue  // already warm
                val parsed = withContext(Dispatchers.IO) {
                    runCatching { parseBookFile(File(book.epubPath), book) }.getOrNull()
                } ?: continue
                cachePut(book.id, signature, parsed, book.isFinished)
            }
        }
    }

    private var sleepTimerJob: kotlinx.coroutines.Job? = null

    fun setSleepTimer(option: SleepTimer) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        pipeline?.setVolume(1f)  // cancel any in-progress fade-out
        // If the narrator isn't playing yet, start in Paused so the countdown doesn't run
        // before the user actually presses play.
        val effective = if (option is SleepTimer.At && !_state.value.isPlaying) {
            val remaining = (option.endsAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            SleepTimer.Paused(remaining)
        } else option
        _state.value = _state.value.copy(sleepTimer = effective)
        if (effective is SleepTimer.At) startSleepCountdown(effective)
    }

    private fun startSleepCountdown(at: SleepTimer.At) {
        val totalRemaining = (at.endsAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        sleepTimerJob = scope.launch {
            val fadeDuration = SLEEP_FADE_MS
            val waitBeforeFade = (totalRemaining - fadeDuration).coerceAtLeast(0L)
            if (waitBeforeFade > 0) kotlinx.coroutines.delay(waitBeforeFade)
            // Final fade window: ramp volume down so the cut doesn't land in the middle of
            // a word. Steps are coarse (200ms) — finer than that doesn't matter perceptually
            // and burns CPU on a phone meant to be at rest.
            val stepMs = 200L
            val steps = (fadeDuration / stepMs).toInt().coerceAtLeast(1)
            for (i in 0 until steps) {
                val v = 1f - (i + 1).toFloat() / steps
                pipeline?.setVolume(v.coerceAtLeast(0f))
                kotlinx.coroutines.delay(stepMs)
            }
            pause()
            pipeline?.setVolume(1f)
            _state.value = _state.value.copy(sleepTimer = SleepTimer.Off)
        }
    }

    /** Freeze the countdown timer when narrator pauses; saved time resumes on play. */
    private fun suspendSleepCountdown() {
        val s = _state.value.sleepTimer
        if (s is SleepTimer.At) {
            sleepTimerJob?.cancel()
            sleepTimerJob = null
            // Restore volume in case we were mid-fade — otherwise resume picks back up
            // at a near-zero volume and silently "plays".
            pipeline?.setVolume(1f)
            val remaining = (s.endsAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            _state.value = _state.value.copy(sleepTimer = SleepTimer.Paused(remaining))
        }
    }

    /** Convert a paused sleep timer back into an active deadline when narrator resumes. */
    private fun resumeSleepCountdown() {
        val s = _state.value.sleepTimer
        if (s is SleepTimer.Paused) {
            val endsAt = SystemClock.elapsedRealtime() + s.remainingMs
            val at = SleepTimer.At(endsAt)
            _state.value = _state.value.copy(sleepTimer = at)
            startSleepCountdown(at)
        }
    }

    /**
     * Rough remaining playback in milliseconds at current speed, or 0 if we don't yet have
     * enough samples (need ~3 chunks completed for a meaningful estimate).
     */
    fun remainingMs(): Long {
        val s = _state.value
        val loaded = s.loaded ?: return 0L
        if (statsCompletedChunks < 3) return 0L
        val avgMsPerChunk = statsCompletedMs / statsCompletedChunks
        val remainingChunks = (loaded.totalChunks - s.position.globalChunk).coerceAtLeast(0)
        val raw = remainingChunks.toLong() * avgMsPerChunk
        return (raw / s.speed.coerceAtLeast(0.1f)).toLong()
    }

    private fun primeFromCurrent(autoplay: Boolean) {
        if (!ttsReady) return
        val s = _state.value
        val text = chunkTextAt(s.position) ?: return
        // The head sentence is at the playhead (depth 0): cut for fast first audio.
        pipeline?.startSentence(
            segments = segmentsFor(text, Sentences.budgetForDepth(0)),
            positionId = utteranceId(s.position),
            chapterIndex = s.position.chapterIndex,
            autoplay = autoplay,
            isChapterTitle = isChapterTitlePosition(s.position),
        )
        queueAheadCount(from = s.position, count = PREFETCH_DEPTH)
    }

    /** Sub-chunk [text] at [budget] and adapt the result to the pipeline's segment type, carrying
     *  each segment's offset within the sentence for the follow-along highlight. */
    private fun segmentsFor(text: String, budget: Sentences.CutBudget): List<FilePipeline.SubSegment> =
        Sentences.subChunkWithOffsets(text, budget).map { FilePipeline.SubSegment(it.text, it.offset) }

    /**
     * True if [pos] is a chapter's spoken heading — the short first chunk whose text matches the
     * chapter title — so the pipeline inserts a brief pause after it before the body. Guarded so
     * it never fires after an ordinary short opening sentence: must be chunk 0, the chapter must
     * have a body chunk after it, the chunk must be short, and its text must look like the title.
     */
    private fun isChapterTitlePosition(pos: Position): Boolean {
        if (pos.chunkIndex != 0) return false
        val loaded = _state.value.loaded ?: return false
        if ((loaded.chapterChunkCounts.getOrNull(pos.chapterIndex) ?: 0) < 2) return false
        val text = chunkTextAt(pos) ?: return false
        if (text.length > TITLE_MAX_CHARS) return false
        val title = loaded.chapterTitles.getOrNull(pos.chapterIndex) ?: return false
        return titleLike(text, title)
    }

    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.8f, 2.0f)
        pipeline?.setSpeed(clamped)
        _state.value = _state.value.copy(speed = clamped)
        // Remember per-book so it sticks across loads of the same book.
        _state.value.loaded?.bookId?.let { id ->
            scope.launch { repository.updatePlaybackSpeed(id, clamped) }
        }
    }

    fun applyPitchFromPreferences() {
        tts?.setPitch(preferences.pitch)
    }

    fun applyVoiceFromPreferences() {
        applyStoredVoice()
    }

    /** Current chunk's MediaPlayer position, in ms. 0 if no playback. */
    fun playbackPositionMs(): Int = pipeline?.currentPositionMs() ?: 0

    /** Current chunk's MediaPlayer total duration, in ms. 0 if not yet known. */
    fun playbackDurationMs(): Int = pipeline?.currentDurationMs() ?: 0

    /** Monotonic highlight floor for the current sentence: the furthest char reached so far. The
     *  highlight never moves backward within a sentence — without this it snaps back to the start
     *  of a finished segment during the synth gap before the next segment plays. Reset to 0 on
     *  every position change (updateCurrentTexts). */
    private var highlightFloorChars = 0

    /**
     * Char index up-to-and-including which the CURRENT SENTENCE is being spoken, for the
     * follow-along highlight over the whole-sentence caption ([NarratorState.currentText]).
     *
     * The caption is always the whole sentence; the audio, though, may be one of several
     * synthesis segments. So the raw position = the current segment's start offset within the
     * sentence + how far we are through that segment (engine onRangeStart char ranges when exact,
     * else MediaPlayer position ÷ duration scaled by the segment's char length — sherpa-onnx on
     * the FP6 emits no ranges).
     *
     * Two guards keep it from jittering backward:
     *  - Position match: if the bound segment belongs to a different sentence than the one on
     *    screen (the brief gap between sentences), don't apply its coordinates — hold the floor.
     *  - Monotonic floor: never return less than the furthest char already reached this sentence,
     *    so the inter-segment synth gap (MediaPlayer idle, position 0) can't snap the highlight
     *    back to the start of the segment that just finished.
     *
     * Returns -1 only when nothing is playing AND nothing has been highlighted yet.
     */
    fun currentSpokenCharEnd(): Int {
        val pipe = pipeline ?: return -1
        val segLen = pipe.activeSegmentLength()
        val activePos = pipe.activeSegmentPositionId()
        val curId = utteranceId(_state.value.position)

        // Bound segment is from another sentence (or none) — hold whatever we've highlighted.
        if (segLen <= 0 || activePos != curId) {
            return if (highlightFloorChars > 0) highlightFloorChars else -1
        }

        val offset = pipe.activeSegmentOffset()
        val withinSegment = run {
            val events = pipe.activeChunkRangeEvents()
            val sampleRate = pipe.activeChunkSampleRate()
            if (events.isNotEmpty() && sampleRate > 0) {
                val frame = (pipe.currentPositionMs().toLong() * sampleRate / 1000L).toInt()
                events.lastOrNull { it.frame <= frame }?.charEnd ?: 0
            } else {
                val pos = pipe.currentPositionMs()
                val dur = pipe.currentDurationMs()
                if (dur > 0 && pos > 0) (pos.toFloat() / dur * segLen).toInt() else 0
            }
        }.coerceIn(0, segLen)
        highlightFloorChars = maxOf(highlightFloorChars, offset + withinSegment)
        return highlightFloorChars
    }

    fun seekToGlobalChunk(globalChunk: Int) {
        val s = _state.value
        val loaded = s.loaded ?: return
        val target = positionFromGlobal(loaded, globalChunk.coerceIn(0, (loaded.totalChunks - 1).coerceAtLeast(0)))
        val wasPlaying = s.isPlaying
        pipeline?.stop()
        _state.value = s.copy(position = target)
        updateCurrentTexts()
        // Re-prime from the new position regardless of play state, so the next play is instant.
        primeFromCurrent(autoplay = wasPlaying)
        persistBookmark()
    }

    private fun positionFromGlobal(loaded: LoadedBook, global: Int): Position {
        var remaining = global
        for ((chapterIdx, count) in loaded.chapterChunkCounts.withIndex()) {
            if (remaining < count) return Position(chapterIdx, remaining, global)
            remaining -= count
        }
        val lastChapter = loaded.chapterChunkCounts.lastIndex.coerceAtLeast(0)
        val lastChunk = (loaded.chapterChunkCounts.getOrNull(lastChapter) ?: 1) - 1
        return positionFor(loaded, lastChapter, lastChunk.coerceAtLeast(0))
    }

    fun togglePlayPause() {
        val s = _state.value
        if (s.loaded == null) return
        if (s.isPlaying) pause() else play()
    }

    private fun play() {
        val s = _state.value
        if (s.loaded == null) return
        if (!requestAudioFocus()) {
            // Couldn't get focus (rare — usually only fails during an active call).
            return
        }
        _state.value = s.copy(isPlaying = true)
        resumeSleepCountdown()
        NarrationService.start(context)
        if (!ttsReady) {
            pendingPlay = true
        } else {
            val pipe = pipeline
            val id = utteranceId(s.position)
            // If the pipeline is already primed for this position (paused mid-chunk, or
            // pre-synthesised by loadBook), just unpause — we skip resynthesis and the audio
            // is ready to go immediately.
            if (pipe != null && (pipe.canResumeCurrent(id) || pipe.hasQueuedSentence(id))) {
                pipe.resume()
            } else {
                primeFromCurrent(autoplay = true)
            }
        }
    }

    private fun pause() {
        pipeline?.pause()
        pendingPlay = false
        _state.value = _state.value.copy(isPlaying = false)
        suspendSleepCountdown()
        // Only abandon focus on a "real" pause — if we paused due to a transient loss we want
        // to keep our claim so we get GAIN back when the interruption ends.
        if (!pausedByFocusLoss) abandonAudioFocus()
        persistBookmark()
    }

    private fun requestAudioFocus(): Boolean {
        if (audioFocusRequest != null) return true
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(focusListener)
            .setWillPauseWhenDucked(false)  // we handle ducking ourselves via setVolume
            .build()
        val result = audioManager.requestAudioFocus(req)
        return if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioFocusRequest = req
            true
        } else {
            false
        }
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
    }

    fun skipChapterNext() = jumpBy(chapterDelta = +1)
    fun skipChapterPrev() = jumpBy(chapterDelta = -1)
    fun skipSentenceNext() = jumpBy(chunkDelta = +1)
    fun skipSentencePrev() = jumpBy(chunkDelta = -1)

    fun skipStepNext() = jumpBy(chunkDelta = +stepSize())
    fun skipStepPrev() = jumpBy(chunkDelta = -stepSize())

    private fun stepSize(): Int = when (preferences.skipIncrement) {
        SkipIncrement.SENTENCE -> 1
        SkipIncrement.PARAGRAPH -> 3
    }

    private fun jumpBy(chapterDelta: Int = 0, chunkDelta: Int = 0) {
        val s = _state.value
        val loaded = s.loaded ?: return
        val wasPlaying = s.isPlaying
        pipeline?.stop()
        val newPos = when {
            chapterDelta != 0 -> positionFor(loaded, s.position.chapterIndex + chapterDelta, 0)
            chunkDelta > 0 -> advanceChunk(loaded, s.position, chunkDelta)
            chunkDelta < 0 -> retreatChunk(loaded, s.position, -chunkDelta)
            else -> s.position
        }
        _state.value = s.copy(position = newPos)
        updateCurrentTexts()
        // Re-prime from the new position even when paused, so the next play is instant.
        primeFromCurrent(autoplay = wasPlaying)
        persistBookmark()
    }

    private fun advanceChunk(loaded: LoadedBook, p: Position, n: Int): Position {
        var chapterIdx = p.chapterIndex
        var chunkIdx = p.chunkIndex + n
        while (chapterIdx < loaded.chapterChunkCounts.size &&
            chunkIdx >= loaded.chapterChunkCounts[chapterIdx]
        ) {
            chunkIdx -= loaded.chapterChunkCounts[chapterIdx]
            chapterIdx++
        }
        if (chapterIdx >= loaded.chapterChunkCounts.size) {
            val lastChapter = loaded.chapterChunkCounts.lastIndex.coerceAtLeast(0)
            val lastChunk = (loaded.chapterChunkCounts.getOrNull(lastChapter) ?: 1) - 1
            return positionFor(loaded, lastChapter, lastChunk.coerceAtLeast(0))
        }
        return positionFor(loaded, chapterIdx, chunkIdx)
    }

    private fun retreatChunk(loaded: LoadedBook, p: Position, n: Int): Position {
        var chapterIdx = p.chapterIndex
        var chunkIdx = p.chunkIndex - n
        while (chunkIdx < 0 && chapterIdx > 0) {
            chapterIdx--
            chunkIdx += loaded.chapterChunkCounts[chapterIdx]
        }
        if (chunkIdx < 0) chunkIdx = 0
        return positionFor(loaded, chapterIdx, chunkIdx)
    }

    private fun positionFor(loaded: LoadedBook, chapterIdx: Int, chunkIdx: Int): Position {
        val lastChapter = loaded.chapterTitles.lastIndex.coerceAtLeast(0)
        val safeChapter = chapterIdx.coerceIn(0, lastChapter)
        val chapterSize = loaded.chapterChunkCounts.getOrNull(safeChapter) ?: 1
        val safeChunk = chunkIdx.coerceIn(0, (chapterSize - 1).coerceAtLeast(0))
        val global = loaded.chapterChunkCounts.take(safeChapter).sum() + safeChunk
        return Position(safeChapter, safeChunk, global)
    }

    private fun queueAheadCount(from: Position, count: Int) {
        val loaded = _state.value.loaded ?: return
        var pos = from
        repeat(count) { i ->
            val next = advanceChunk(loaded, pos, 1)
            if (next == pos) return
            val text = chunkTextAt(next) ?: return
            // Distance from the head grows 1..count; deeper = more buffered = cut less.
            val budget = Sentences.budgetForDepth(i + 1)
            pipeline?.queueSentence(
                segmentsFor(text, budget),
                utteranceId(next), next.chapterIndex, isChapterTitlePosition(next),
            )
            pos = next
        }
    }

    /** Position [ahead] chunks past [from], or null if past the end. */
    private fun positionAhead(from: Position, ahead: Int): Position? {
        val loaded = _state.value.loaded ?: return null
        var pos = from
        repeat(ahead) {
            val next = advanceChunk(loaded, pos, 1)
            if (next == pos) return null
            pos = next
        }
        return pos
    }

    internal companion object {
        // Deeper than strictly needed for gapless playback: with the shorter chunks from the
        // 0.11 sentence-cutting change, each synth is faster, so keeping more ready ahead of
        // the playhead smooths transitions and absorbs the occasional slow chunk.
        const val PREFETCH_DEPTH = 4
        /** Number of recently-read, non-finished books whose parse is kept warm in memory. */
        const val MAX_PARSE_CACHE = 5
        /** A chapter's first chunk is treated as its spoken heading only if it's at most this
         *  long — guards the post-title pause against firing after a long opening sentence. */
        const val TITLE_MAX_CHARS = 80

        /** True if [chunk] reads like the chapter [title] — exact match after normalisation, or
         *  one contained in the other (the in-body heading and the TOC title often differ in
         *  punctuation / a "Chapter N" prefix). Pure + static so it's unit-testable. */
        @androidx.annotation.VisibleForTesting
        internal fun titleLike(chunk: String, title: String): Boolean {
            val a = normalizeForTitle(chunk)
            val b = normalizeForTitle(title)
            if (a.isEmpty() || b.isEmpty()) return false
            return a == b || a.contains(b) || b.contains(a)
        }

        private fun normalizeForTitle(s: String): String =
            s.lowercase(Locale.US).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        /** Length of the gentle volume ramp at the end of a sleep timer. */
        const val SLEEP_FADE_MS = 15_000L
        /** Volume MediaPlayer is set to while another app is ducking us (notification etc.). */
        const val DUCK_VOLUME = 0.3f
    }

    private fun chunkTextAt(p: Position): String? =
        chunksByChapter.getOrNull(p.chapterIndex)?.getOrNull(p.chunkIndex)?.takeIf { it.isNotBlank() }

    private fun utteranceId(p: Position): String = "n_${p.chapterIndex}_${p.chunkIndex}"

    private data class ParsedId(val chapterIndex: Int, val chunkIndex: Int)

    private fun parseUtteranceId(id: String?): ParsedId? {
        if (id == null || !id.startsWith("n_")) return null
        val parts = id.removePrefix("n_").split("_")
        if (parts.size != 2) return null
        val ch = parts[0].toIntOrNull() ?: return null
        val ck = parts[1].toIntOrNull() ?: return null
        return ParsedId(ch, ck)
    }

    private fun onChunkStartPlayback(parsed: ParsedId, startedAt: Long) {
        val s = _state.value
        if (s.position.chapterIndex == parsed.chapterIndex &&
            s.position.chunkIndex == parsed.chunkIndex
        ) {
            _state.value = s.copy(currentChunkStartedAt = startedAt)
        }
    }

    private fun updateCurrentTexts() {
        val s = _state.value
        val loaded = s.loaded ?: return
        val current = chunkTextAt(s.position).orEmpty()
        val nextPos = advanceChunk(loaded, s.position, 1)
        val next = if (nextPos != s.position) chunkTextAt(nextPos).orEmpty() else ""
        // New sentence on screen → restart the monotonic highlight floor.
        highlightFloorChars = 0
        _state.value = s.copy(currentText = current, nextText = next, currentChunkStartedAt = 0L)
    }

    private fun onChunkComplete() {
        val s = _state.value
        if (!s.isPlaying) return
        val loaded = s.loaded ?: return
        // Record timing for remainingMs() before any state changes.
        if (s.currentChunkStartedAt > 0L) {
            val playMs = SystemClock.elapsedRealtime() - s.currentChunkStartedAt
            if (playMs in 100L..30_000L) {
                statsCompletedMs += playMs
                statsCompletedChunks++
            }
        }
        val nextPos = advanceChunk(loaded, s.position, 1)
        if (nextPos == s.position) {
            // End of book: stop and auto-mark finished so the library shows 100% (the playhead
            // only ever reaches the last sentence index = ~99%, so without this a fully-read book
            // sticks below 100). Mirrors the manual "mark as finished" toggle.
            _state.value = s.copy(isPlaying = false)
            persistBookmark()
            scope.launch { repository.setFinished(loaded.bookId, true) }
            return
        }
        val crossedChapter = nextPos.chapterIndex != s.position.chapterIndex
        // Sleep timer "End of chapter": pause at the boundary, then clear the timer.
        if (crossedChapter && s.sleepTimer is SleepTimer.EndOfChapter) {
            _state.value = s.copy(
                position = nextPos,
                isPlaying = false,
                sleepTimer = SleepTimer.Off,
            )
            pipeline?.pause()
            persistBookmark()
            return
        }
        // The next chunk was prefetched and the engine is already speaking it. Move state forward
        // and top the prefetch buffer back up with one more chunk at the tail end.
        _state.value = s.copy(position = nextPos)
        updateCurrentTexts()
        val tail = positionAhead(nextPos, PREFETCH_DEPTH)
        if (tail != null) {
            chunkTextAt(tail)?.let {
                // Tail sits PREFETCH_DEPTH sentences ahead — a full buffer — so read it whole.
                pipeline?.queueSentence(
                    segmentsFor(it, Sentences.budgetForDepth(PREFETCH_DEPTH)),
                    utteranceId(tail), tail.chapterIndex, isChapterTitlePosition(tail),
                )
            }
        }
        persistBookmark()
    }

    private fun persistBookmark() {
        val s = _state.value
        val loaded = s.loaded ?: return
        scope.launch {
            repository.upsertBookmark(
                bookId = loaded.bookId,
                chapterIndex = s.position.chapterIndex,
                chunkIndex = s.position.chunkIndex,
                globalChunk = s.position.globalChunk,
            )
        }
    }

    fun shutdown() {
        abandonAudioFocus()
        pipeline?.release()
        pipeline = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    /** Picks the parser by source file extension. Books are stored on disk with their
     *  original extension preserved, so the source format is recoverable at load time.
     *  Applies the per-book page range (PDF only) and skip-pattern filter. */
    private fun parseBookFile(file: File, book: com.example.narrator.data.BookEntity): Book {
        val ext = file.extension.lowercase()
        val raw = when (ext) {
            "pdf" -> {
                val range = if (book.pageRangeStart > 0 && book.pageRangeEnd >= book.pageRangeStart)
                    book.pageRangeStart..book.pageRangeEnd else null
                PdfParser.parse(file, range)
            }
            else -> EpubParser.parse(file)
        }
        val patterns = book.skipPatterns.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { runCatching { Regex(it) }.getOrNull() }
        if (patterns.isEmpty()) return raw
        // Drop any chunk that matches any skip pattern.
        val filteredChapters = raw.chapters.map { ch ->
            ch.copy(chunks = ch.chunks.filterNot { chunk -> patterns.any { it.containsMatchIn(chunk) } })
        }.filter { it.chunks.isNotEmpty() }
        return raw.copy(chapters = filteredChapters)
    }
}
