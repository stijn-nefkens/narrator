package com.example.narrator.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class BackupArchiveTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `round-trip preserves db and all source and cover files`() {
        // --- arrange: build a fake library on disk ---
        val src = tmp.newFolder("src")
        val dbFile = File(src, "narrator.db").apply { writeBytes(byteArrayOf(0x53, 0x51, 0x4C, 0x69)) }
        val epubDir = File(src, "epubs").apply { mkdirs() }
        val coverDir = File(src, "covers").apply { mkdirs() }
        File(epubDir, "a.epub").writeText("first book bytes")
        File(epubDir, "b.pdf").writeText("second book bytes")
        File(coverDir, "a.jpg").writeBytes(byteArrayOf(1, 2, 3, 4, 5))

        // --- act: write to a byte buffer, read back into a fresh dir ---
        val buf = ByteArrayOutputStream()
        val w = BackupArchive.write(buf, dbFile, epubDir, coverDir)

        val staging = tmp.newFolder("staging")
        val r = BackupArchive.read(ByteArrayInputStream(buf.toByteArray()), staging)

        // --- assert: counts and bytes match ---
        assertEquals(2, w.bookFiles)
        assertEquals(1, w.coverFiles)
        assertEquals(2, r.bookFiles)
        assertEquals(1, r.coverFiles)

        assertArrayEquals(dbFile.readBytes(), File(staging, "narrator.db").readBytes())
        assertEquals("first book bytes", File(staging, "epubs/a.epub").readText())
        assertEquals("second book bytes", File(staging, "epubs/b.pdf").readText())
        assertArrayEquals(
            byteArrayOf(1, 2, 3, 4, 5),
            File(staging, "covers/a.jpg").readBytes(),
        )
    }

    @Test fun `empty library round-trips with zero counts`() {
        val src = tmp.newFolder("src")
        val dbFile = File(src, "narrator.db").apply { writeText("schema only") }
        val epubDir = File(src, "epubs").apply { mkdirs() }
        val coverDir = File(src, "covers").apply { mkdirs() }

        val buf = ByteArrayOutputStream()
        val w = BackupArchive.write(buf, dbFile, epubDir, coverDir)
        val staging = tmp.newFolder("staging")
        val r = BackupArchive.read(ByteArrayInputStream(buf.toByteArray()), staging)

        assertEquals(0, w.bookFiles)
        assertEquals(0, w.coverFiles)
        assertEquals(0, r.bookFiles)
        assertEquals(0, r.coverFiles)
        assertTrue(File(staging, "narrator.db").exists())
    }

    @Test fun `read rejects archive without narrator_db before touching staging`() {
        // Build a ZIP that has epubs but no DB entry — simulates a corrupted/wrong file.
        val src = tmp.newFolder("src")
        val dbFile = File(src, "narrator.db").apply { writeText("decoy") }
        val epubDir = File(src, "epubs").apply { mkdirs() }
        val coverDir = File(src, "covers").apply { mkdirs() }
        File(epubDir, "x.epub").writeText("payload")
        val buf = ByteArrayOutputStream()
        BackupArchive.write(buf, dbFile, epubDir, coverDir)

        // Manually rewrite the archive to drop narrator.db by re-extracting + re-zipping
        // without it. Simpler: just write a sentinel ZIP with only epubs/.
        val badBuf = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(badBuf).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("epubs/lone.epub"))
            zip.write("nope".toByteArray())
            zip.closeEntry()
        }

        val staging = tmp.newFolder("staging")
        try {
            BackupArchive.read(ByteArrayInputStream(badBuf.toByteArray()), staging)
            fail("expected IllegalStateException for missing narrator.db")
        } catch (e: IllegalStateException) {
            assertTrue("error message should mention narrator.db: ${e.message}",
                e.message?.contains("narrator.db") == true)
        }
    }

    @Test fun `read ignores zip-slip entries trying to escape staging`() {
        // Build a malicious ZIP that includes a "../etc/passwd" entry.
        val buf = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(buf).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("narrator.db"))
            zip.write("db".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("../escape.txt"))
            zip.write("oops".toByteArray())
            zip.closeEntry()
        }

        val staging = tmp.newFolder("staging")
        BackupArchive.read(ByteArrayInputStream(buf.toByteArray()), staging)

        assertTrue(File(staging, "narrator.db").exists())
        // The malicious entry should NOT have escaped the staging directory.
        assertTrue(!File(staging.parentFile, "escape.txt").exists())
    }

    /** A minimal SQLite-shaped file: the 16-byte magic, user_version at offset 60, padding. */
    private fun fakeSqlite(userVersion: Int): File {
        val header = ByteArray(4096)
        "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII).copyInto(header)
        java.nio.ByteBuffer.wrap(header, 60, 4).putInt(userVersion)
        return tmp.newFile().apply { writeBytes(header) }
    }

    @Test fun `sqliteUserVersion reads the schema version from the header`() {
        assertEquals(5, BackupArchive.sqliteUserVersion(fakeSqlite(5)))
        assertEquals(42, BackupArchive.sqliteUserVersion(fakeSqlite(42)))
    }

    @Test fun `sqliteUserVersion rejects non-SQLite and truncated files`() {
        assertEquals(null, BackupArchive.sqliteUserVersion(tmp.newFile().apply { writeBytes("db".toByteArray()) }))
        assertEquals(null, BackupArchive.sqliteUserVersion(tmp.newFile().apply { writeBytes(ByteArray(200)) }))
    }

    @Test fun `relocatedPath rebases a foreign device's absolute path onto the current dir`() {
        val epubDir = tmp.newFolder("epubs")
        // Written on another user / work profile / applicationId.
        val stored = "/data/user/10/com.other.app/files/epubs/3f2a.pdf"
        assertEquals(
            File(epubDir, "3f2a.pdf").absolutePath,
            BackupArchive.relocatedPath(stored, epubDir),
        )
    }

    @Test fun `relocatedPath is a no-op for a path already in the current dir`() {
        val coverDir = tmp.newFolder("covers")
        val stored = File(coverDir, "c.png").absolutePath
        assertEquals(stored, BackupArchive.relocatedPath(stored, coverDir))
    }
}
