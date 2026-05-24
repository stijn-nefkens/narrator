package com.example.narrator.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class BookRepository(
    private val context: Context,
    private val database: NarratorDatabase,
) {
    private val _books = MutableStateFlow<List<BookWithProgress>>(emptyList())
    val books: StateFlow<List<BookWithProgress>> = _books.asStateFlow()

    val epubDir: File by lazy { File(context.filesDir, "epubs").apply { mkdirs() } }
    val coverDir: File by lazy { File(context.filesDir, "covers").apply { mkdirs() } }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _books.value = queryAllBooksWithProgress()
    }

    suspend fun getBook(id: Long): BookEntity? = withContext(Dispatchers.IO) {
        readDb().query(
            NarratorDatabase.TABLE_BOOKS, null,
            "${NarratorDatabase.COL_ID} = ?", arrayOf(id.toString()),
            null, null, null,
        ).use { c -> if (c.moveToFirst()) c.toBook() else null }
    }

    suspend fun findByTitleAndAuthor(title: String, author: String): BookEntity? =
        withContext(Dispatchers.IO) {
            readDb().query(
                NarratorDatabase.TABLE_BOOKS, null,
                "${NarratorDatabase.COL_TITLE} = ? AND ${NarratorDatabase.COL_AUTHOR} = ?",
                arrayOf(title, author),
                null, null, null,
            ).use { c -> if (c.moveToFirst()) c.toBook() else null }
        }

    suspend fun insertBook(
        title: String,
        author: String,
        epubPath: String,
        coverPath: String?,
        totalChunks: Int,
    ): Long = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(NarratorDatabase.COL_TITLE, title)
            put(NarratorDatabase.COL_AUTHOR, author)
            put(NarratorDatabase.COL_EPUB_PATH, epubPath)
            put(NarratorDatabase.COL_COVER_PATH, coverPath)
            put(NarratorDatabase.COL_TOTAL_CHUNKS, totalChunks)
            put(NarratorDatabase.COL_IMPORTED_AT, System.currentTimeMillis())
        }
        val id = writeDb().insert(NarratorDatabase.TABLE_BOOKS, null, values)
        refresh()
        id
    }

    suspend fun replaceBookFiles(
        existingId: Long,
        title: String,
        author: String,
        epubPath: String,
        coverPath: String?,
        totalChunks: Int,
    ) = withContext(Dispatchers.IO) {
        val existing = getBook(existingId) ?: return@withContext
        deleteFilesQuietly(existing)
        val values = ContentValues().apply {
            put(NarratorDatabase.COL_TITLE, title)
            put(NarratorDatabase.COL_AUTHOR, author)
            put(NarratorDatabase.COL_EPUB_PATH, epubPath)
            put(NarratorDatabase.COL_COVER_PATH, coverPath)
            put(NarratorDatabase.COL_TOTAL_CHUNKS, totalChunks)
            put(NarratorDatabase.COL_IMPORTED_AT, System.currentTimeMillis())
        }
        writeDb().update(
            NarratorDatabase.TABLE_BOOKS, values,
            "${NarratorDatabase.COL_ID} = ?", arrayOf(existingId.toString()),
        )
        writeDb().delete(
            NarratorDatabase.TABLE_BOOKMARKS,
            "${NarratorDatabase.COL_BOOK_ID} = ?", arrayOf(existingId.toString()),
        )
        refresh()
    }

    suspend fun deleteBook(id: Long) = withContext(Dispatchers.IO) {
        val book = getBook(id) ?: return@withContext
        deleteFilesQuietly(book)
        writeDb().delete(
            NarratorDatabase.TABLE_BOOKS,
            "${NarratorDatabase.COL_ID} = ?", arrayOf(id.toString()),
        )
        refresh()
    }

    suspend fun updateTotalChunks(bookId: Long, total: Int) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply { put(NarratorDatabase.COL_TOTAL_CHUNKS, total) }
        writeDb().update(
            NarratorDatabase.TABLE_BOOKS, values,
            "${NarratorDatabase.COL_ID} = ?", arrayOf(bookId.toString()),
        )
        refresh()
    }

    suspend fun getBookmark(bookId: Long): Bookmark? = withContext(Dispatchers.IO) {
        readDb().query(
            NarratorDatabase.TABLE_BOOKMARKS, null,
            "${NarratorDatabase.COL_BOOK_ID} = ?", arrayOf(bookId.toString()),
            null, null, null,
        ).use { c -> if (c.moveToFirst()) c.toBookmark() else null }
    }

    suspend fun upsertBookmark(
        bookId: Long,
        chapterIndex: Int,
        chunkIndex: Int,
        globalChunk: Int,
    ) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(NarratorDatabase.COL_BOOK_ID, bookId)
            put(NarratorDatabase.COL_CHAPTER_INDEX, chapterIndex)
            put(NarratorDatabase.COL_CHUNK_INDEX, chunkIndex)
            put(NarratorDatabase.COL_GLOBAL_CHUNK, globalChunk)
            put(NarratorDatabase.COL_UPDATED_AT, System.currentTimeMillis())
        }
        writeDb().insertWithOnConflict(
            NarratorDatabase.TABLE_BOOKMARKS, null, values,
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
        )
        refresh()
    }

    /** Generates a unique on-disk path for a freshly imported EPUB file. */
    fun newEpubFile(): File = File(epubDir, "${UUID.randomUUID()}.epub")

    /** Generates a unique on-disk path for a cover image. */
    fun newCoverFile(extension: String): File =
        File(coverDir, "${UUID.randomUUID()}.${extension.trimStart('.')}")

    // --- internals -------------------------------------------------------

    private fun readDb() = database.readableDatabase
    private fun writeDb() = database.writableDatabase

    private fun queryAllBooksWithProgress(): List<BookWithProgress> {
        val sql = """
            SELECT b.*, m.${NarratorDatabase.COL_CHAPTER_INDEX} AS m_chapter,
                   m.${NarratorDatabase.COL_CHUNK_INDEX} AS m_chunk,
                   m.${NarratorDatabase.COL_GLOBAL_CHUNK} AS m_global,
                   m.${NarratorDatabase.COL_UPDATED_AT} AS m_updated
            FROM ${NarratorDatabase.TABLE_BOOKS} b
            LEFT JOIN ${NarratorDatabase.TABLE_BOOKMARKS} m
              ON b.${NarratorDatabase.COL_ID} = m.${NarratorDatabase.COL_BOOK_ID}
            ORDER BY b.${NarratorDatabase.COL_IMPORTED_AT} DESC
        """.trimIndent()
        val out = mutableListOf<BookWithProgress>()
        readDb().rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                val book = c.toBook()
                val bookmark = if (c.isNull(c.getColumnIndexOrThrow("m_updated"))) null else Bookmark(
                    bookId = book.id,
                    chapterIndex = c.getInt(c.getColumnIndexOrThrow("m_chapter")),
                    chunkIndex = c.getInt(c.getColumnIndexOrThrow("m_chunk")),
                    globalChunk = c.getInt(c.getColumnIndexOrThrow("m_global")),
                    updatedAt = c.getLong(c.getColumnIndexOrThrow("m_updated")),
                )
                out.add(BookWithProgress(book, bookmark))
            }
        }
        return out
    }

    private fun Cursor.toBook(): BookEntity = BookEntity(
        id = getLong(getColumnIndexOrThrow(NarratorDatabase.COL_ID)),
        title = getString(getColumnIndexOrThrow(NarratorDatabase.COL_TITLE)),
        author = getString(getColumnIndexOrThrow(NarratorDatabase.COL_AUTHOR)),
        epubPath = getString(getColumnIndexOrThrow(NarratorDatabase.COL_EPUB_PATH)),
        coverPath = if (isNull(getColumnIndexOrThrow(NarratorDatabase.COL_COVER_PATH))) null
            else getString(getColumnIndexOrThrow(NarratorDatabase.COL_COVER_PATH)),
        totalChunks = getInt(getColumnIndexOrThrow(NarratorDatabase.COL_TOTAL_CHUNKS)),
        importedAt = getLong(getColumnIndexOrThrow(NarratorDatabase.COL_IMPORTED_AT)),
    )

    private fun Cursor.toBookmark(): Bookmark = Bookmark(
        bookId = getLong(getColumnIndexOrThrow(NarratorDatabase.COL_BOOK_ID)),
        chapterIndex = getInt(getColumnIndexOrThrow(NarratorDatabase.COL_CHAPTER_INDEX)),
        chunkIndex = getInt(getColumnIndexOrThrow(NarratorDatabase.COL_CHUNK_INDEX)),
        globalChunk = getInt(getColumnIndexOrThrow(NarratorDatabase.COL_GLOBAL_CHUNK)),
        updatedAt = getLong(getColumnIndexOrThrow(NarratorDatabase.COL_UPDATED_AT)),
    )

    private fun deleteFilesQuietly(book: BookEntity) {
        runCatching { File(book.epubPath).delete() }
        book.coverPath?.let { runCatching { File(it).delete() } }
    }
}
