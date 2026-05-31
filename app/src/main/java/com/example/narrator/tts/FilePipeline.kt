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
    /** A scheduled, delayed start of the next chunk — used for both the inter-chapter pause (before
     *  a new chapter's first chunk) and the post-title pause (after a chapter heading). Only one is
     *  ever pending at a time; the two transitions never overlap. */
    private var pendingDelayedStart: Runnable? = null

    /** The segment currently bound to the MediaPlayer. A sentence may be synthesised as several
     *  segments; the caption always shows the WHOLE sentence (stable, no chopping flicker) while
     *  the highlight is positioned at [currentSegmentOffset] + progress-within-this-segment, so it
     *  flows continuously across the displayed sentence regardless of how many pieces the synth
     *  cut it into. Held across the brief inter-segment MP-reset gap; cleared on teardown. */
    private var currentSegmentText: String? = null
    /** Char offset of [currentSegmentText] within its whole sentence (0 for a single-segment
     *  sentence, or the start index of this piece for a multi-segment one). */
    private var currentSegmentOffset: Int = 0
    /** Position id (sentence) the current segment belongs to. The caption highlighter uses this
     *  to ignore stale segment coordinates during the gap between sentences (when the caption has
     *  already advanced but the next sentence's audio hasn't started). */
    private var currentSegmentPositionId: String? = null

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
     * Replace the queue with one sentence (given as its already-cut [subTexts] segments) as the
     * new head, and start synthesising. One Narrator position = one sentence = 1..N audio
     * segments; the segments play back-to-back and Narrator is notified once (started on the
     * first segment, completed on the last). If [autoplay] is false the segments are synthesised
     * and prepared but the MediaPlayer stays paused (prefetch before the user presses play).
     */
    /** One synthesis segment of a sentence: its [text] and the char [offset] at which that text
     *  begins within the whole sentence (so the caption highlight can be positioned absolutely). */
    data class SubSegment(val text: String, val offset: Int)

    fun startSentence(
        segments: List<SubSegment>,
        positionId: String,
        chapterIndex: Int,
        autoplay: Boolean = true,
        isChapterTitle: Boolean = false,
    ) {
        teardown()
        paused = !autoplay
        enqueueSentence(segments, positionId, chapterIndex, isChapterTitle)
    }

    /** True if a sentence with this position id is already queued (so it's primed). */
    fun hasQueuedSentence(positionId: String): Boolean =
        queue.any { it.positionId == positionId }

    fun queueSentence(
        segments: List<SubSegment>,
        positionId: String,
        chapterIndex: Int,
        isChapterTitle: Boolean = false,
    ) {
        if (queue.isEmpty()) return
        enqueueSentence(segments, positionId, chapterIndex, isChapterTitle)
    }

    /** Expand a sentence into its segment PendingChunks and synthesise each. The first segment
     *  carries the onChunkStarted notification, the last the onChunkCompleted + any post-title
     *  pause; intermediate segments are silent to Narrator (same position). */
    private fun enqueueSentence(
        segments: List<SubSegment>,
        positionId: String,
        chapterIndex: Int,
        isChapterTitle: Boolean,
    ) {
        val segs = segments.filter { it.text.isNotBlank() }
        if (segs.isEmpty()) return
        val last = segs.lastIndex
        segs.forEachIndexed { i, seg ->
            val chunk = PendingChunk(
                id = "$positionId#$i",
                positionId = positionId,
                text = seg.text,
                segmentOffset = seg.offset,
                file = newFile(),
                chapterIndex = chapterIndex,
                isFirstSub = i == 0,
                isLastSub = i == last,
                // The post-title pause must follow the whole title sentence, so only the last
                // segment carries the flag.
                isChapterTitle = isChapterTitle && i == last,
            )
            queue.addLast(chunk)
            synthesise(chunk)
        }
    }

    fun pause() {
        paused = true
        // Hold off any deferred start (chapter / post-title pause) until the user resumes.
        pendingDelayedStart?.let { handler.removeCallbacks(it) }
        pendingDelayedStart = null
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
                activeChunk?.let { onChunkStarted(it.positionId) }
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

    fun canResumeCurrent(positionId: String): Boolean =
        (mpState == MpState.PAUSED) && (activeChunk?.positionId == positionId)

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
        // Single-segment failure: skip past the bad segment so playback continues with the next.
        // Only notify Narrator if this was the sentence's last segment (a position boundary);
        // a failed mid-sentence segment just drops that fragment of audio.
        queue.remove(chunk)
        runCatching { chunk.file.delete() }
        if (chunk.isLastSub) onChunkCompleted(chunk.positionId)
        if (mpState == MpState.IDLE) tryStartNextFromQueue()
    }

    // --- playback --------------------------------------------------------

    private fun tryStartNextFromQueue() {
        if (paused) return
        if (mpState != MpState.IDLE) return
        if (pendingDelayedStart != null) return  // a delayed start is already scheduled
        val next = queue.firstOrNull() ?: return
        if (!next.synthDone) return  // wait for synth_done

        // Insert a natural pause when crossing chapter boundaries during continuous playback.
        val crossingChapter = lastCompletedChapter >= 0 &&
            next.chapterIndex >= 0 &&
            next.chapterIndex != lastCompletedChapter
        if (crossingChapter) {
            lastCompletedChapter = -1  // consume; subsequent retries won't re-pause
            scheduleDelayedStart(next, CHAPTER_PAUSE_MS)
            return
        }
        beginPrepare(next)
    }

    /** Posts a delayed start of [next], holding the MP idle for [delayMs] (a chapter boundary or
     *  post-title beat). Re-checks state when it fires so a pause/skip in the meantime wins. */
    private fun scheduleDelayedStart(next: PendingChunk, delayMs: Long) {
        val r = Runnable {
            pendingDelayedStart = null
            if (!paused && mpState == MpState.IDLE && queue.firstOrNull() === next) {
                beginPrepare(next)
            }
        }
        pendingDelayedStart = r
        handler.postDelayed(r, delayMs)
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
            // Treat as completion so the caller can advance past it (only at a position boundary).
            mpState = MpState.IDLE
            activeChunk = null
            runCatching { chunk.file.delete() }
            queue.removeFirstOrNull()
            if (chunk.isLastSub) onChunkCompleted(chunk.positionId)
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
            // Track the spoken segment + its offset so the highlight maps to the right span of
            // the whole-sentence caption (see currentSegmentOffset).
            currentSegmentText = it.text
            currentSegmentOffset = it.segmentOffset
            currentSegmentPositionId = it.positionId
            // Notify the position only when its first segment begins; later segments of the same
            // sentence keep the same position (Narrator shouldn't re-advance mid-sentence).
            if (it.isFirstSub) onChunkStarted(it.positionId)
        }
    }

    private fun onMpCompletion() {
        val completed = activeChunk
        if (mpState == MpState.IDLE) return  // already torn down
        mpState = MpState.IDLE
        activeChunk = null
        var titlePause = false
        completed?.let {
            val now = android.os.SystemClock.elapsedRealtime()
            val playMs = if (it.playStartAt > 0) now - it.playStartAt else -1
            Log.d(TAG, "TIMING play  id=${it.id} text_len=${it.text.length} " +
                "play_ms=$playMs synth_ms=${it.synthEndAt - it.synthStartAt}")
            lastCompletedChapter = it.chapterIndex
            titlePause = it.isChapterTitle
            runCatching { it.file.delete() }
            queue.removeFirstOrNull()  // the head was this segment
            // Advance the Narrator position only when a sentence's LAST segment finishes.
            if (it.isLastSub) onChunkCompleted(it.positionId)
        }
        // A chapter title just finished: hold a short beat before the body so the heading reads
        // as its own line. The next chunk is in the same chapter (chunk 1), so the inter-chapter
        // pause in tryStartNextFromQueue won't also fire.
        val next = queue.firstOrNull()
        if (titlePause && next != null && next.synthDone && !paused && pendingDelayedStart == null) {
            scheduleDelayedStart(next, TITLE_PAUSE_MS)
            return
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
        pendingDelayedStart?.let { handler.removeCallbacks(it) }
        pendingDelayedStart = null
        // After a skip/seek/teardown, the next start() is the user's action — no pause.
        lastCompletedChapter = -1
        mpState = MpState.IDLE
        activeChunk = null
        currentSegmentText = null
        currentSegmentOffset = 0
        currentSegmentPositionId = null
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
        /** Narrator position id (one sentence). Shared by all segments of that sentence. */
        val positionId: String,
        val text: String,
        /** Char offset of [text] within the whole sentence (0 for a single-segment sentence). */
        val segmentOffset: Int = 0,
        val file: File,
        val chapterIndex: Int = -1,
        /** First / last segment of the owning sentence. onChunkStarted fires on the first,
         *  onChunkCompleted on the last; a single-segment sentence has both true. */
        val isFirstSub: Boolean = true,
        val isLastSub: Boolean = true,
        /** True if this segment ends a chapter heading; a short pause follows it before the body. */
        val isChapterTitle: Boolean = false,
        var synthId: String? = null,
        var synthDone: Boolean = false,
        var synthStartAt: Long = 0L,
        var synthEndAt: Long = 0L,
        var playStartAt: Long = 0L,
        val rangeEvents: MutableList<RangeEvent> = mutableListOf(),
        var sampleRate: Int = 24000,
    )

    /** Length (chars) of the segment currently being spoken; 0 before playback / after teardown.
     *  Used to scale the time-based highlight estimate within the segment's span. */
    fun activeSegmentLength(): Int = currentSegmentText?.length ?: 0

    /** Char offset of the current segment within its whole sentence; 0 if none active. */
    fun activeSegmentOffset(): Int = currentSegmentOffset

    /** Position id (sentence) the current segment belongs to, or null if none is bound. */
    fun activeSegmentPositionId(): String? = currentSegmentPositionId

    /** Range events for the chunk currently bound to the MediaPlayer, or empty if engine doesn't emit them. */
    fun activeChunkRangeEvents(): List<RangeEvent> = activeChunk?.rangeEvents?.toList().orEmpty()

    /** Sample rate of the WAV currently bound to the MediaPlayer (defaults to 24000). */
    fun activeChunkSampleRate(): Int = activeChunk?.sampleRate ?: 24000

    companion object {
        private const val TAG = "FilePipeline"
        /** Natural pause inserted between the last chunk of one chapter and the first of the next. */
        private const val CHAPTER_PAUSE_MS = 1500L
        /** Shorter beat after a chapter heading, before the chapter body begins. */
        private const val TITLE_PAUSE_MS = 900L
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
