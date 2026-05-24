package com.example.narrator.epub

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal class EpubBuilder {
    private val files = LinkedHashMap<String, ByteArray>()

    fun add(path: String, content: String) = apply {
        files[path] = content.toByteArray(Charsets.UTF_8)
    }

    fun add(path: String, content: ByteArray) = apply {
        files[path] = content
    }

    fun build(): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            for ((path, bytes) in files) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return baos.toByteArray()
    }
}

internal fun fakePng(): ByteArray = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
)
