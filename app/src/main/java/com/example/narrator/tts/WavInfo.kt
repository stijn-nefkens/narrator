package com.example.narrator.tts

import java.io.File
import java.io.RandomAccessFile

/** Parses just the sample rate of a 16-bit PCM WAV file. Returns 0 if not parseable. */
internal object WavInfo {
    fun sampleRate(file: File): Int = try {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 44) return@use 0
            val riff = ByteArray(4); raf.read(riff)
            if (String(riff, Charsets.US_ASCII) != "RIFF") return@use 0
            raf.skipBytes(8)  // file size + "WAVE"
            while (raf.filePointer + 8 <= raf.length()) {
                val id = ByteArray(4); raf.read(id)
                val chunkSize = readLE32(raf)
                if (String(id, Charsets.US_ASCII) == "fmt ") {
                    raf.skipBytes(4)  // PCM tag (2) + channels (2)
                    return@use readLE32(raf)
                } else {
                    raf.skipBytes(chunkSize)
                }
            }
            0
        }
    } catch (_: Exception) {
        0
    }

    private fun readLE32(raf: RandomAccessFile): Int {
        val b = ByteArray(4); raf.readFully(b)
        return (b[0].toInt() and 0xFF) or
            ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or
            ((b[3].toInt() and 0xFF) shl 24)
    }
}
