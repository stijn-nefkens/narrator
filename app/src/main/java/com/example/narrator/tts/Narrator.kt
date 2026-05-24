package com.example.narrator.tts

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import com.example.narrator.data.AppPreferences
import com.example.narrator.data.BookRepository
import com.example.narrator.data.SkipIncrement
import com.example.narrator.epub.EpubParser
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
)

data class NarratorState(
    val loaded: LoadedBook? = null,
    val position: Position = Position(0, 0, 0),
    val isPlaying: Boolean = false,
    val speed: Float = 1.0f,
    val currentText: String = "",
    val nextText: String = "",
    /** SystemClock.elapsedRealtime() when the current chunk started playing; 0 = not yet. */
    val currentChunkStartedAt: Long = 0L,
)

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

    suspend fun loadBook(bookId: Long) {
        val book = repository.getBook(bookId) ?: return
        val bookmark = repository.getBookmark(bookId)

        val parsed = withContext(Dispatchers.IO) { EpubParser.parse(File(book.epubPath)) }
        chunksByChapter = parsed.chapters.map { it.chunks }
        val total = chunksByChapter.sumOf { it.size }
        if (total != book.totalChunks) repository.updateTotalChunks(bookId, total)

        val loaded = LoadedBook(
            bookId = bookId,
            title = parsed.title,
            author = parsed.author,
            coverPath = book.coverPath,
            chapterTitles = parsed.chapters.map { it.title },
            chapterChunkCounts = parsed.chapters.map { it.chunks.size },
            totalChunks = total,
        )
        val pos = if (bookmark != null) {
            positionFor(loaded, bookmark.chapterIndex, bookmark.chunkIndex)
        } else {
            Position(0, 0, 0)
        }

        pipeline?.stop()
        val startSpeed = preferences.defaultSpeed
        pipeline?.setSpeed(startSpeed)
        tts?.setPitch(preferences.pitch)
        _state.value = NarratorState(
            loaded = loaded,
            position = pos,
            isPlaying = false,
            speed = startSpeed,
        )
        updateCurrentTexts()
        // Start prefetching as soon as the book is loaded so the first chunk is already
        // synthesised by the time the user presses play.
        primeFromCurrent(autoplay = false)
    }

    private fun primeFromCurrent(autoplay: Boolean) {
        if (!ttsReady) return
        val s = _state.value
        val text = chunkTextAt(s.position) ?: return
        pipeline?.startChunk(
            text = text,
            id = utteranceId(s.position),
            chapterIndex = s.position.chapterIndex,
            autoplay = autoplay,
        )
        queueAheadCount(from = s.position, count = PREFETCH_DEPTH)
    }

    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.8f, 2.0f)
        pipeline?.setSpeed(clamped)
        _state.value = _state.value.copy(speed = clamped)
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
        _state.value = s.copy(isPlaying = true)
        NarrationService.start(context)
        if (!ttsReady) {
            pendingPlay = true
        } else {
            val pipe = pipeline
            val id = utteranceId(s.position)
            // If the pipeline is already primed for this position (paused mid-chunk, or
            // pre-synthesised by loadBook), just unpause — we skip resynthesis and the audio
            // is ready to go immediately.
            if (pipe != null && (pipe.canResumeCurrent(id) || pipe.hasQueueHead(id))) {
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
        persistBookmark()
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
        repeat(count) {
            val next = advanceChunk(loaded, pos, 1)
            if (next == pos) return
            if (next.chapterIndex != pos.chapterIndex && !preferences.continueThroughChapters) return
            val text = chunkTextAt(next) ?: return
            pipeline?.queueNext(text, utteranceId(next), next.chapterIndex)
            pos = next
        }
    }

    /** Position [ahead] chunks past [from], or null if we'd cross a boundary that blocks us. */
    private fun positionAhead(from: Position, ahead: Int): Position? {
        val loaded = _state.value.loaded ?: return null
        var pos = from
        repeat(ahead) {
            val next = advanceChunk(loaded, pos, 1)
            if (next == pos) return null
            if (next.chapterIndex != pos.chapterIndex && !preferences.continueThroughChapters) return null
            pos = next
        }
        return pos
    }

    private companion object {
        const val PREFETCH_DEPTH = 2
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
        _state.value = s.copy(currentText = current, nextText = next, currentChunkStartedAt = 0L)
    }

    private fun onChunkComplete() {
        val s = _state.value
        if (!s.isPlaying) return
        val loaded = s.loaded ?: return
        val nextPos = advanceChunk(loaded, s.position, 1)
        if (nextPos == s.position) {
            // End of book.
            _state.value = s.copy(isPlaying = false)
            persistBookmark()
            return
        }
        val crossedChapter = nextPos.chapterIndex != s.position.chapterIndex
        if (crossedChapter && !preferences.continueThroughChapters) {
            // Park at start of next chapter. We did not prefetch across the boundary, so the
            // engine queue is empty and playback genuinely stops here.
            _state.value = s.copy(position = nextPos, isPlaying = false)
            persistBookmark()
            return
        }
        // The next chunk was prefetched and the engine is already speaking it. Move state forward
        // and top the prefetch buffer back up with one more chunk at the tail end.
        _state.value = s.copy(position = nextPos)
        updateCurrentTexts()
        val tail = positionAhead(nextPos, PREFETCH_DEPTH)
        if (tail != null) {
            chunkTextAt(tail)?.let { pipeline?.queueNext(it, utteranceId(tail), tail.chapterIndex) }
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
        pipeline?.release()
        pipeline = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
