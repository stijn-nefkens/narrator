package com.example.narrator.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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

    init {
        createTts()
    }

    private fun createTts() {
        ttsReady = false
        val enginePkg = VoicePreferences.enginePackage(context)
        tts = TextToSpeech(
            context.applicationContext,
            { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.language = Locale.US
                    applyStoredVoice()
                    tts?.setSpeechRate(_state.value.speed)
                    tts?.setPitch(preferences.pitch)
                    tts?.setOnUtteranceProgressListener(progressListener)
                    if (pendingPlay) {
                        pendingPlay = false
                        scope.launch { speakCurrent() }
                    }
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

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) {
            scope.launch { onChunkComplete() }
        }

        @Deprecated("Required by API contract")
        override fun onError(utteranceId: String?) {}
        override fun onError(utteranceId: String?, errorCode: Int) {}
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

        tts?.stop()
        val startSpeed = preferences.defaultSpeed
        tts?.setSpeechRate(startSpeed)
        tts?.setPitch(preferences.pitch)
        _state.value = NarratorState(
            loaded = loaded,
            position = pos,
            isPlaying = false,
            speed = startSpeed,
        )
    }

    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.8f, 2.0f)
        tts?.setSpeechRate(clamped)
        _state.value = _state.value.copy(speed = clamped)
    }

    fun applyPitchFromPreferences() {
        tts?.setPitch(preferences.pitch)
    }

    fun applyVoiceFromPreferences() {
        applyStoredVoice()
    }

    fun seekToGlobalChunk(globalChunk: Int) {
        val s = _state.value
        val loaded = s.loaded ?: return
        val target = positionFromGlobal(loaded, globalChunk.coerceIn(0, (loaded.totalChunks - 1).coerceAtLeast(0)))
        val wasPlaying = s.isPlaying
        if (wasPlaying) tts?.stop()
        _state.value = s.copy(position = target)
        if (wasPlaying) speakCurrent()
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
            speakCurrent()
        }
    }

    private fun pause() {
        tts?.stop()
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
        if (wasPlaying) tts?.stop()
        val newPos = when {
            chapterDelta != 0 -> positionFor(loaded, s.position.chapterIndex + chapterDelta, 0)
            chunkDelta > 0 -> advanceChunk(loaded, s.position, chunkDelta)
            chunkDelta < 0 -> retreatChunk(loaded, s.position, -chunkDelta)
            else -> s.position
        }
        _state.value = s.copy(position = newPos)
        if (wasPlaying) speakCurrent()
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

    private fun speakCurrent() {
        val s = _state.value
        if (!s.isPlaying || !ttsReady) return
        val text = chunksByChapter
            .getOrNull(s.position.chapterIndex)
            ?.getOrNull(s.position.chunkIndex)
        if (text.isNullOrBlank()) {
            _state.value = s.copy(isPlaying = false)
            return
        }
        val id = "n_${s.position.chapterIndex}_${s.position.chunkIndex}"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
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
            // Stop at chapter end. Park position at the start of the next chapter so the
            // user resumes there when they press play again.
            _state.value = s.copy(position = nextPos, isPlaying = false)
            persistBookmark()
            return
        }
        _state.value = s.copy(position = nextPos)
        speakCurrent()
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
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
