package com.example.narrator.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class NarratorDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_BOOKS)
        db.execSQL(SQL_CREATE_BOOKMARKS)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Single-version MVP — no migrations yet.
    }

    companion object {
        private const val DATABASE_NAME = "narrator.db"
        private const val DATABASE_VERSION = 1

        const val TABLE_BOOKS = "books"
        const val COL_ID = "id"
        const val COL_TITLE = "title"
        const val COL_AUTHOR = "author"
        const val COL_EPUB_PATH = "epub_path"
        const val COL_COVER_PATH = "cover_path"
        const val COL_TOTAL_CHUNKS = "total_chunks"
        const val COL_IMPORTED_AT = "imported_at"

        const val TABLE_BOOKMARKS = "bookmarks"
        const val COL_BOOK_ID = "book_id"
        const val COL_CHAPTER_INDEX = "chapter_index"
        const val COL_CHUNK_INDEX = "chunk_index"
        const val COL_GLOBAL_CHUNK = "global_chunk"
        const val COL_UPDATED_AT = "updated_at"

        private const val SQL_CREATE_BOOKS = """
            CREATE TABLE $TABLE_BOOKS (
              $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
              $COL_TITLE TEXT NOT NULL,
              $COL_AUTHOR TEXT NOT NULL,
              $COL_EPUB_PATH TEXT NOT NULL,
              $COL_COVER_PATH TEXT,
              $COL_TOTAL_CHUNKS INTEGER NOT NULL DEFAULT 0,
              $COL_IMPORTED_AT INTEGER NOT NULL
            )
        """

        private const val SQL_CREATE_BOOKMARKS = """
            CREATE TABLE $TABLE_BOOKMARKS (
              $COL_BOOK_ID INTEGER PRIMARY KEY,
              $COL_CHAPTER_INDEX INTEGER NOT NULL DEFAULT 0,
              $COL_CHUNK_INDEX INTEGER NOT NULL DEFAULT 0,
              $COL_GLOBAL_CHUNK INTEGER NOT NULL DEFAULT 0,
              $COL_UPDATED_AT INTEGER NOT NULL,
              FOREIGN KEY($COL_BOOK_ID) REFERENCES $TABLE_BOOKS($COL_ID) ON DELETE CASCADE
            )
        """
    }
}
