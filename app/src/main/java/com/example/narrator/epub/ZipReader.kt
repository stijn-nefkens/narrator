package com.example.narrator.epub

import java.io.InputStream
import java.util.zip.ZipInputStream

internal class ZipReader private constructor(
    private val entries: Map<String, ByteArray>,
) {
    fun read(path: String): ByteArray? = entries[normalize(path)]

    fun readText(path: String): String? = read(path)?.toString(Charsets.UTF_8)

    companion object {
        fun from(input: InputStream): ZipReader {
            val map = LinkedHashMap<String, ByteArray>()
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        map[normalize(entry.name)] = zip.readBytes()
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            return ZipReader(map)
        }

        private fun normalize(path: String): String =
            path.removePrefix("/").replace('\\', '/')
    }
}
