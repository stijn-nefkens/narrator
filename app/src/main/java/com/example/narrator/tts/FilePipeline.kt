package com.example.narrator.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.util.UUID

/**
 * Synthesise each chunk to a WAV file with [TextToSpeech.synthesizeToFile] (in parallel — the
 * next chunk's synth runs while the current is playing), then play each file with a single
 * reused [MediaPlayer]. The MP is `reset()` and re-prepared per chunk, so it never sits in
 * PREPARED state waiting — short clips were getting silently dropped on FP6/Android 15 when
 * the MP had been prepared more than ~1s before [MediaPlayer.start] was called.
 *
 * Tradeoff: ~10–50ms gap at chunk transitions (one MP `reset`+`prepareAsync` round-trip).
 * In exchange we get reliable playback, simple pause/resume, and no two-MP race conditions.
 */
internal class FilePipeline(
    context: Context,
    private val tts: TextToSpeech,
    private val onChunkStarted: (id: String) -> Unit,
    private val onChunkCompleted: (id: String) -> Unit,
    private val onSynthCascadeFailure: () -> Unit = {},
) {
    private val cacheDir = File(context.cacheDir, "narrator-chunks").apply {
        if (exists()) listFiles()?.forEach { it.delete() }
        mkdirs()
    }
    private val handler = Handler(Looper.getMainLooper())

    private val mp: MediaPlayer = MediaPlayer().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        setOnPreparedListener { handler.post(::onMpPrepared) }
        setOnCompletionListener { handler.post(::onMpCompletion) }
        setOnErrorListener { _, what, extra ->
            Log.w(TAG, "mp_error what=$what extra=$extra")
            handler.post(::onMpCompletion)
            true
        }
    }

    private enum class MpState { IDLE, PREPARING, PREPARED, PLAYING, PAUSED }
    private var mpState: MpState = MpState.IDLE
    private var activeChunk: PendingChunk? = null  // what the MP is currently bound to

    private val queue: ArrayDeque<PendingChunk> = ArrayDeque()
    private var paused = false
    private var speed: Float = 1.0f
    private var volume: Float = 1.0f
    /** Chapter of the most recently completed chunk; used to insert a pause at chapter boundaries. */
    private var lastCompletedChapter: Int = -1
    private var pendingChapterStart: Runnable? = null

    /**
     * Cascading-failure guard: if the TTS engine is disabled / unbound / broken,
     * synthesizeToFile returns ERROR for every call. Previously we treated each as a
     * completed chunk and auto-advanced — which made the book race forward with no audio
     * (observed by the user as "10x playback"). Now: after this many consecutive errors
     * with no successful synth between them, we halt the pipeline and notify the caller.
     */
    private var consecutiveSynthErrors = 0
    private val maxConsecutiveSynthErrors = 3

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                handler.post { handleSynthDone(utteranceId) }
            }
            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                handler.post { recordRangeEvent(utteranceId, start, end, frame) }
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                handler.post { handleSynthError(utteranceId, errorCode) }
            }
            @Deprecated("Required by API contract")
            override fun onError(utteranceId: String?) {
                handler.post { handleSynthError(utteranceId, -1) }
            }
        })
    }

    private fun recordRangeEvent(synthUtteranceId: String?, start: Int, end: Int, frame: Int) {
        val chunk = queue.firstOrNull { it.synthId == synthUtteranceId } ?: return
        chunk.rangeEvents.add(RangeEvent(start, end, frame))
    }

    // --- public API ------------------------------------------------------

    /**
     * Replace the queue with [text] as the new head chunk and start synthesising it. If
     * [autoplay] is true, playback begins as soon as synthesis is done; if false, the chunk is
     * synthesised and prepared but the MediaPlayer stays paused — useful for prefetching the
     * resume position as soon as a book is loaded, before the user has pressed play.
     */
    fun startChunk(text: String, id: String, chapterIndex: Int, autoplay: Boolean = true) {
        teardown()
        paused = !autoplay
        val chunk = PendingChunk(id, text, newFile(), chapterIndex = chapterIndex)
        queue.addLast(chunk)
        synthesise(chunk)
    }

    /** True if the head of the queue is the chunk with this id (and so it's already primed). */
    fun hasQueueHead(id: String): Boolean = queue.firstOrNull()?.id == id

    fun queueNext(text: String, id: String, chapterIndex: Int) {
        if (queue.isEmpty()) return
        val chunk = PendingChunk(id, text, newFile(), chapterIndex = chapterIndex)
        queue.addLast(chunk)
        synthesise(chunk)
    }

    fun pause() {
        paused = true
        // Hold off any deferred chapter start until the user resumes.
        pendingChapterStart?.let { handler.removeCallbacks(it) }
        pendingChapterStart = null
        if (mpState == MpState.PLAYING) {
            runCatching { mp.pause() }
            mpState = MpState.PAUSED
        }
    }

    fun resume() {
        paused = false
        when (mpState) {
            MpState.PAUSED -> {
                applySpeed()
                runCatching { mp.start() }
                mpState = MpState.PLAYING
                activeChunk?.let { onChunkStarted(it.id) }
            }
            MpState.PREPARED -> startReadyMp()
            MpState.IDLE -> tryStartNextFromQueue()
            else -> { /* PREPARING / PLAYING — nothing to do */ }
        }
    }

    fun stop() {
        teardown()
        paused = false
    }

    fun release() {
        teardown()
        runCatching { mp.release() }
    }

    fun setSpeed(value: Float) {
        speed = value.coerceIn(0.5f, 2.5f)
        if (mpState == MpState.PLAYING) {
            runCatching { applySpeed() }
        }
    }

    /** Stereo volume in [0, 1]. Used for sleep-timer fade-out and audio-focus ducking. */
    fun setVolume(value: Float) {
        val v = value.coerceIn(0f, 1f)
        volume = v
        if (mpState == MpState.PLAYING || mpState == MpState.PAUSED) {
            runCatching { mp.setVolume(v, v) }
        }
    }

    fun canResumeCurrent(id: String): Boolean =
        (mpState == MpState.PAUSED) && (activeChunk?.id == id)

    /** Current playback position in ms, or 0 if no chunk is active. Safe to call any time. */
    fun currentPositionMs(): Int = if (mpState == MpState.PLAYING || mpState == MpState.PAUSED) {
        runCatching { mp.currentPosition }.getOrDefault(0)
    } else 0

    /** Total duration of the chunk currently bound to the MP, or 0. */
    fun currentDurationMs(): Int = when (mpState) {
        MpState.PLAYING, MpState.PAUSED, MpState.PREPARED ->
            runCatching { mp.duration }.getOrDefault(0)
        else -> 0
    }

    // --- synthesis -------------------------------------------------------

    private fun synthesise(chunk: PendingChunk) {
        val synthId = "synth_${UUID.randomUUID()}"
        chunk.synthId = synthId
        // sherpa-onnx / Kokoro renders trailing quote characters as a small fricative "ktsh"
        // artifact after the last word. Strip them — the period or other terminator before the
        // quote still carries the correct sentence intonation.
        val text = chunk.text.trimEnd(*TRAILING_NOISE_CHARS)
        chunk.synthStartAt = android.os.SystemClock.elapsedRealtime()
        val result = tts.synthesizeToFile(text, Bundle(), chunk.file, synthId)
        Log.d(TAG, "synth_start id=${chunk.id} text_len=${text.length} result=$result")
        if (result != TextToSpeech.SUCCESS) {
            handler.post { handleSynthError(synthId, result) }
        }
    }

    private fun handleSynthDone(synthUtteranceId: String?) {
        val chunk = queue.firstOrNull { it.synthId == synthUtteranceId } ?: return
        chunk.synthDone = true
        consecutiveSynthErrors = 0
        chunk.synthEndAt = android.os.SystemClock.elapsedRealtime()
        val synthMs = chunk.synthEndAt - chunk.synthStartAt
        Log.d(TAG, "TIMING synth id=${chunk.id} text_len=${chunk.text.length} " +
            "synth_ms=$synthMs bytes=${chunk.file.length()} ranges=${chunk.rangeEvents.size}")
        chunk.sampleRate = WavInfo.sampleRate(chunk.file).takeIf { it > 0 } ?: chunk.sampleRate
        // Mask the leading/trailing sample transients sherpa-onnx writes per utterance.
        WavFadeOut.apply(chunk.file)
        // If the queue front is now ready and the MP is idle, start playing it.
        if (mpState == MpState.IDLE && chunk === queue.firstOrNull()) {
            tryStartNextFromQueue()
        }
    }

    private fun handleSynthError(synthUtteranceId: String?, code: Int) {
        val chunk = queue.firstOrNull { it.synthId == synthUtteranceId } ?: return
        consecutiveSynthErrors++
        Log.w(TAG, "synth_error id=${chunk.id} code=$code consecutive=$consecutiveSynthErrors")
        if (consecutiveSynthErrors >= maxConsecutiveSynthErrors) {
            Log.w(TAG, "synth cascade: halting pipeline to avoid runaway position advance")
            pause()
            // Drop the queued attempts so we don't replay these failures the moment the user
            // resumes after fixing the engine — primeFromCurrent will requeue from the (un-
            // advanced) position.
            for (c in queue) runCatching { c.file.delete() }
            queue.clear()
            consecutiveSynthErrors = 0
            onSynthCascadeFailure()
            return
        }
        // Single-chunk failure: skip past the bad chunk so playback continues with the next.
        queue.remove(chunk)
        runCatching { chunk.file.delete() }
        onChunkCompleted(chunk.id)
        if (mpState == MpState.IDLE) tryStartNextFromQueue()
    }

    // --- playback --------------------------------------------------------

    private fun tryStartNextFromQueue() {
        if (paused) return
        if (mpState != MpState.IDLE) return
        if (pendingChapterStart != null) return  // chapter pause already scheduled
        val next = queue.firstOrNull() ?: return
        if (!next.synthDone) return  // wait for synth_done

        // Insert a natural pause when crossing chapter boundaries during continuous playback.
        val crossingChapter = lastCompletedChapter >= 0 &&
            next.chapterIndex >= 0 &&
            next.chapterIndex != lastCompletedChapter
        if (crossingChapter) {
            lastCompletedChapter = -1  // consume; subsequent retries won't re-pause
            val r = Runnable {
                pendingChapterStart = null
                if (!paused && mpState == MpState.IDLE && queue.firstOrNull() === next) {
                    beginPrepare(next)
                }
            }
            pendingChapterStart = r
            handler.postDelayed(r, CHAPTER_PAUSE_MS)
            return
        }
        beginPrepare(next)
    }

    private fun beginPrepare(chunk: PendingChunk) {
        runCatching { mp.reset() }
        activeChunk = chunk
        mpState = MpState.PREPARING
        try {
            mp.setDataSource(chunk.file.absolutePath)
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "prepare_failed id=${chunk.id}", e)
            // Treat as completion so the caller can advance past it.
            mpState = MpState.IDLE
            activeChunk = null
            runCatching { chunk.file.delete() }
            queue.removeFirstOrNull()
            onChunkCompleted(chunk.id)
            tryStartNextFromQueue()
        }
    }

    private fun onMpPrepared() {
        if (mpState != MpState.PREPARING) return
        mpState = MpState.PREPARED
        if (paused) return  // user paused while preparing — wait for resume
        startReadyMp()
    }

    private fun startReadyMp() {
        runCatching { mp.start() }
        applySpeed()
        runCatching { mp.setVolume(volume, volume) }
        mpState = MpState.PLAYING
        activeChunk?.let {
            it.playStartAt = android.os.SystemClock.elapsedRealtime()
            onChunkStarted(it.id)
        }
    }

    private fun onMpCompletion() {
        val completed = activeChunk
        if (mpState == MpState.IDLE) return  // already torn down
        mpState = MpState.IDLE
        activeChunk = null
        completed?.let {
            val now = android.os.SystemClock.elapsedRealtime()
            val playMs = if (it.playStartAt > 0) now - it.playStartAt else -1
            Log.d(TAG, "TIMING play  id=${it.id} text_len=${it.text.length} " +
                "play_ms=$playMs synth_ms=${it.synthEndAt - it.synthStartAt}")
            lastCompletedChapter = it.chapterIndex
            runCatching { it.file.delete() }
            queue.removeFirstOrNull()  // the head was this chunk
            onChunkCompleted(it.id)
        }
        tryStartNextFromQueue()
    }

    private fun applySpeed() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            mp.playbackParams = PlaybackParams().setSpeed(speed)
        } catch (e: Exception) {
            Log.w(TAG, "setPlaybackParams failed", e)
        }
    }

    // --- teardown --------------------------------------------------------

    private fun teardown() {
        // Cancel any synthesis the engine is still grinding through. Without this, rapid skips
        // pile dozens of stale synth requests into sherpa-onnx's internal queue and the engine
        // works through them serially before reaching the user's actual position — observed as
        // a ~1-minute freeze after a handful of next-chapter taps.
        runCatching { tts.stop() }
        runCatching { mp.reset() }
        pendingChapterStart?.let { handler.removeCallbacks(it) }
        pendingChapterStart = null
        // After a skip/seek/teardown, the next start() is the user's action — no pause.
        lastCompletedChapter = -1
        mpState = MpState.IDLE
        activeChunk = null
        for (chunk in queue) {
            runCatching { chunk.file.delete() }
        }
        queue.clear()
    }

    private fun newFile(): File = File(cacheDir, "${UUID.randomUUID()}.wav")

    /** Char range the engine reports during synthesis via UtteranceProgressListener.onRangeStart. */
    data class RangeEvent(val charStart: Int, val charEnd: Int, val frame: Int)

    private data class PendingChunk(
        val id: String,
        val text: String,
        val file: File,
        val chapterIndex: Int = -1,
        var synthId: String? = null,
        var synthDone: Boolean = false,
        var synthStartAt: Long = 0L,
        var synthEndAt: Long = 0L,
        var playStartAt: Long = 0L,
        val rangeEvents: MutableList<RangeEvent> = mutableListOf(),
        var sampleRate: Int = 24000,
    )

    /** Range events for the chunk currently bound to the MediaPlayer, or empty if engine doesn't emit them. */
    fun activeChunkRangeEvents(): List<RangeEvent> = activeChunk?.rangeEvents?.toList().orEmpty()

    /** Sample rate of the WAV currently bound to the MediaPlayer (defaults to 24000). */
    fun activeChunkSampleRate(): Int = activeChunk?.sampleRate ?: 24000

    companion object {
        private const val TAG = "FilePipeline"
        /** Natural pause inserted between the last chunk of one chapter and the first of the next. */
        private const val CHAPTER_PAUSE_MS = 1500L
        // Trailing characters sherpa-onnx renders as audible artifacts (a "ktsh" or "snort"
        // sound after the last word). Include single AND double quote variants, plus their
        // curly / guillemet relatives. Apostrophes mid-word are unaffected — trimEnd only
        // touches the actual trailing chars.
        private val TRAILING_NOISE_CHARS = charArrayOf(
            '"',     // U+0022 straight double quote
            '\'',    // U+0027 straight single quote / apostrophe
            '“', // “ left double curly
            '”', // ” right double curly
            '‘', // ‘ left single curly
            '’', // ’ right single curly
            '«', // « left guillemet
            '»', // » right guillemet
            '‹', // ‹ single left guillemet
            '›', // › single right guillemet
            '„', // „ low double curly
            '‚', // ‚ low single curly
            ' ', '\t', '\n', '\r',
        )
    }
}
