package com.example.narrator.data

import com.example.narrator.epub.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ParsedBookCacheTest {

    @get:Rule val tmp = TemporaryFolder()

    private val chapters = listOf(
        Chapter("Chapter One", listOf("First sentence.", "Second sentence.")),
        Chapter("Chapter Two", listOf("Only one here.")),
        Chapter("", emptyList()),
    )

    @Test fun `round-trips chapters through serialize and deserialize`() {
        val bytes = ParsedBookCache.serialize("sig-A", chapters)
        assertEquals(chapters, ParsedBookCache.deserialize(bytes, "sig-A"))
    }

    @Test fun `rejects a different signature`() {
        val bytes = ParsedBookCache.serialize("sig-A", chapters)
        assertNull(ParsedBookCache.deserialize(bytes, "sig-B"))
    }

    @Test fun `rejects corrupt bytes`() {
        assertNull(ParsedBookCache.deserialize(byteArrayOf(1, 2, 3, 4, 5), "sig-A"))
        assertNull(ParsedBookCache.deserialize(ByteArray(0), "sig-A"))
    }

    @Test fun `preserves text with awkward characters`() {
        val tricky = listOf(
            Chapter("Title — with dash", listOf("Curly “quotes” and accents: café.", "Newline\ninside.")),
        )
        val bytes = ParsedBookCache.serialize("sig", tricky)
        assertEquals(tricky, ParsedBookCache.deserialize(bytes, "sig"))
    }

    @Test fun `writes and reads back via a file`() {
        val dir = tmp.newFolder("parsed-cache")
        ParsedBookCache.write(dir, bookId = 7L, signature = "sig-7", chapters = chapters)
        assertEquals(chapters, ParsedBookCache.read(dir, 7L, "sig-7"))
    }

    @Test fun `read returns null for a missing file or stale signature`() {
        val dir = tmp.newFolder("parsed-cache")
        assertNull(ParsedBookCache.read(dir, 99L, "sig"))
        ParsedBookCache.write(dir, 7L, "sig-7", chapters)
        assertNull(ParsedBookCache.read(dir, 7L, "different-sig"))
    }

    @Test fun `delete removes the cache file`() {
        val dir = tmp.newFolder("parsed-cache")
        ParsedBookCache.write(dir, 7L, "sig-7", chapters)
        ParsedBookCache.delete(dir, 7L)
        assertNull(ParsedBookCache.read(dir, 7L, "sig-7"))
    }
}
