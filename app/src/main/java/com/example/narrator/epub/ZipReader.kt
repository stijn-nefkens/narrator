package com.example.narrator.epub

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Read-only view of an EPUB's ZIP entries by path.
 *
 * [open] (the on-device path) reads lazily through [ZipFile]'s central directory, so only the
 * OPF / TOC / spine documents actually requested are inflated — images, fonts and media stay on
 * disk. The old implementation slurped every entry into memory up front, which could OOM on a
 * heavily illustrated book. [from] (streams, used by tests) still buffers everything.
 *
 * Lookups fall back to the percent-decoded path: manifest hrefs are URLs, so a spine file named
 * "chapter 1.xhtml" is referenced as "chapter%201.xhtml" and was silently skipped before.
 */
internal class ZipReader private constructor(
    private val lookup: (String) -> ByteArray?,
    private val closer: () -> Unit,
) : Closeable {
    fun read(path: String): ByteArray? {
        val direct = normalize(path)
        return lookup(direct) ?: Paths.percentDecode(direct).takeIf { it != direct }?.let(lookup)
    }

    fun readText(path: String): String? = read(path)?.toString(Charsets.UTF_8)

    override fun close() = closer()

    companion object {
        /** Lazily-reading reader over [file]. Falls back to a streaming read if the central
         *  directory can't be parsed (some malformed EPUBs only read sequentially). */
        fun open(file: File): ZipReader {
            val zip = try {
                ZipFile(file)
            } catch (_: java.io.IOException) {
                return file.inputStream().use { from(it) }
            }
            val index = HashMap<String, java.util.zip.ZipEntry>()
            for (entry in zip.entries()) {
                if (!entry.isDirectory) index[normalize(entry.name)] = entry
            }
            return ZipReader(
                lookup = { path -> index[path]?.let { e -> zip.getInputStream(e).use { it.readBytes() } } },
                closer = { zip.close() },
            )
        }

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
            return ZipReader(lookup = map::get, closer = {})
        }

        private fun normalize(path: String): String =
            path.removePrefix("/").replace('\\', '/')
    }
}
