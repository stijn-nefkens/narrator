package com.example.narrator.tts

import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * Apply a short linear fade at both ends of a 16-bit PCM WAV file in-place.
 *
 * sherpa-onnx / Kokoro writes click-like transients at the leading and trailing samples of each
 * synthesised utterance (model warm-up / cool-down artefacts). Ramping the boundary samples to
 * zero masks the clicks without noticeably affecting the spoken audio (10–30ms is well below
 * the audibility threshold).
 *
 * No-ops if the file isn't a 16-bit PCM WAV we can parse.
 */
internal object WavFadeOut {
    private const val TAG = "WavFadeOut"
    private const val FADE_OUT_MS = 30
    private const val FADE_IN_MS = 10

    fun apply(file: File) {
        try {
            RandomAccessFile(file, "rw").use { raf -> applyFades(raf) }
        } catch (e: Exception) {
            Log.w(TAG, "fades failed for ${file.name}", e)
        }
    }

    private fun applyFades(raf: RandomAccessFile) {
        if (raf.length() < 44) return
        val riff = ByteArray(4); raf.read(riff)
        if (String(riff, Charsets.US_ASCII) != "RIFF") return
        raf.skipBytes(8)  // file size (4) + "WAVE" (4)

        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        var dataStart = -1L
        var dataSize = 0

        while (raf.filePointer + 8 <= raf.length()) {
            val chunkId = ByteArray(4); raf.read(chunkId)
            val chunkSize = readLE32(raf)
            val id = String(chunkId, Charsets.US_ASCII)
            when (id) {
                "fmt " -> {
                    val fmtStart = raf.filePointer
                    raf.skipBytes(2)  // PCM format tag
                    channels = readLE16(raf)
                    sampleRate = readLE32(raf)
                    raf.skipBytes(6)  // byte rate (4) + block align (2)
                    bitsPerSample = readLE16(raf)
                    raf.seek(fmtStart + chunkSize)
                }
                "data" -> {
                    dataStart = raf.filePointer
                    dataSize = chunkSize
                    break
                }
                else -> raf.skipBytes(chunkSize)
            }
        }

        Log.d(TAG, "parsed sampleRate=$sampleRate channels=$channels bits=$bitsPerSample " +
            "dataStart=$dataStart dataSize=$dataSize fileLen=${raf.length()}")

        if (dataStart < 0 || sampleRate == 0 || channels == 0 || bitsPerSample != 16) {
            Log.w(TAG, "skipping fade — unsupported WAV format")
            return
        }

        val bytesPerSample = bitsPerSample / 8
        val totalSamples = dataSize / (bytesPerSample * channels)

        // Fade-in at the start.
        val fadeInSamples = ((sampleRate.toLong() * FADE_IN_MS) / 1000).toInt().coerceAtMost(totalSamples)
        if (fadeInSamples > 0) {
            val bytes = fadeInSamples * bytesPerSample * channels
            raf.seek(dataStart)
            val buf = ByteArray(bytes)
            raf.readFully(buf)
            for (i in 0 until fadeInSamples) {
                val factor = i.toFloat() / fadeInSamples  // 0 → 1
                applyGain(buf, i, channels, bytesPerSample, factor)
            }
            raf.seek(dataStart)
            raf.write(buf)
        }

        // Fade-out at the end.
        val fadeOutSamples = ((sampleRate.toLong() * FADE_OUT_MS) / 1000).toInt().coerceAtMost(totalSamples)
        if (fadeOutSamples > 0) {
            val bytes = fadeOutSamples * bytesPerSample * channels
            val start = dataStart + dataSize - bytes
            raf.seek(start)
            val buf = ByteArray(bytes)
            raf.readFully(buf)
            for (i in 0 until fadeOutSamples) {
                val factor = 1.0f - i.toFloat() / fadeOutSamples  // 1 → 0
                applyGain(buf, i, channels, bytesPerSample, factor)
            }
            raf.seek(start)
            raf.write(buf)
        }
    }

    private fun applyGain(buf: ByteArray, sampleIdx: Int, channels: Int, bytesPerSample: Int, factor: Float) {
        for (ch in 0 until channels) {
            val idx = (sampleIdx * channels + ch) * bytesPerSample
            val lo = buf[idx].toInt() and 0xFF
            val hi = buf[idx + 1].toInt()
            val signed = (hi shl 8) or lo
            val faded = (signed * factor).toInt().coerceIn(-32768, 32767)
            buf[idx] = (faded and 0xFF).toByte()
            buf[idx + 1] = ((faded shr 8) and 0xFF).toByte()
        }
    }

    private fun readLE32(raf: RandomAccessFile): Int {
        val b = ByteArray(4); raf.readFully(b)
        return (b[0].toInt() and 0xFF) or
            ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or
            ((b[3].toInt() and 0xFF) shl 24)
    }

    private fun readLE16(raf: RandomAccessFile): Int {
        val b = ByteArray(2); raf.readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
    }
}
