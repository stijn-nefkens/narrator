package com.example.narrator.data

import com.example.narrator.epub.Chapter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * On-disk cache of a parsed book's chapter/chunk structure, so re-opening a book — including the
 * auto-loaded last book on a COLD app start — skips the multi-second parse (PDFBox extraction +
 * cleaning + sentence splitting) that otherwise runs every time. Narrator keeps a small in-memory
 * LRU too, but that dies with the process; this survives, which is what fixes "opening the app
 * takes >10s for a large PDF".
 *
 * Only the content structure is cached (chapter titles + per-chapter chunk lists). Title/author/
 * cover come from the DB row, so they're deliberately not stored here.
 *
 * The serialised blob is self-describing: a magic number, a [PARSER_VERSION] stamp (bump it
 * whenever parser/normalisation output changes so stale caches are ignored), and the caller's
 * [signature] (file path + page range + skip patterns). [deserialize] returns null — forcing a
 * fresh parse — on any mismatch or corruption, so a stale cache can never serve wrong text.
 */
internal object ParsedBookCache {
    private const val MAGIC = 0x4E504243 // "NPBC"

    /** Bump when the parsers or [com.example.narrator.epub.TextNormalize] change their output, so
     *  caches written by an older build are rejected and the book is re-parsed once. */
    const val PARSER_VERSION = 1

    fun serialize(signature: String, chapters: List<Chapter>): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(PARSER_VERSION)
            out.writeUTF(signature)
            out.writeInt(chapters.size)
            for (ch in chapters) {
                out.writeUTF(ch.title)
                out.writeInt(ch.chunks.size)
                for (chunk in ch.chunks) out.writeUTF(chunk)
            }
        }
        return bos.toByteArray()
    }

    /** Parses [bytes] back into chapters, or null if the blob is corrupt, from a different parser
     *  version, or was written for a different [expectedSignature]. */
    fun deserialize(bytes: ByteArray, expectedSignature: String): List<Chapter>? = runCatching {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            if (input.readInt() != MAGIC) return null
            if (input.readInt() != PARSER_VERSION) return null
            if (input.readUTF() != expectedSignature) return null
            val chapterCount = input.readInt()
            if (chapterCount < 0) return null
            val chapters = ArrayList<Chapter>(chapterCount)
            repeat(chapterCount) {
                val title = input.readUTF()
                val chunkCount = input.readInt()
                if (chunkCount < 0) return null
                val chunks = ArrayList<String>(chunkCount)
                repeat(chunkCount) { chunks.add(input.readUTF()) }
                chapters.add(Chapter(title, chunks))
            }
            chapters
        }
    }.getOrNull()

    // --- file helpers ----------------------------------------------------

    private fun fileFor(dir: File, bookId: Long): File = File(dir, "$bookId.npbc")

    /** Reads + validates the cache for [bookId], or null if absent/stale/corrupt. */
    fun read(dir: File, bookId: Long, signature: String): List<Chapter>? {
        val f = fileFor(dir, bookId)
        if (!f.exists()) return null
        val bytes = runCatching { f.readBytes() }.getOrNull()
        return bytes?.let { deserialize(it, signature) }
    }

    /** Writes the cache for [bookId] (best-effort; failures are swallowed — the cache is an
     *  optimisation, never load-bearing). */
    fun write(dir: File, bookId: Long, signature: String, chapters: List<Chapter>) {
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            fileFor(dir, bookId).writeBytes(serialize(signature, chapters))
        }
    }

    fun delete(dir: File, bookId: Long) {
        runCatching { fileFor(dir, bookId).delete() }
    }
}
