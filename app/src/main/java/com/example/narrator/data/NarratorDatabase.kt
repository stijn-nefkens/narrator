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
        db.execSQL(SQL_CREATE_SAVED_BOOKMARKS)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Per-book remembered playback speed (was on AppPreferences only).
            db.execSQL(
                "ALTER TABLE $TABLE_BOOKS ADD COLUMN $COL_PLAYBACK_SPEED REAL NOT NULL DEFAULT 1.0"
            )
            db.execSQL(SQL_CREATE_SAVED_BOOKMARKS)
        }
        if (oldVersion < 3) {
            // PDF escape hatches: page-range subset + per-book skip-pattern regexes.
            db.execSQL(
                "ALTER TABLE $TABLE_BOOKS ADD COLUMN $COL_PAGE_RANGE_START INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL(
                "ALTER TABLE $TABLE_BOOKS ADD COLUMN $COL_PAGE_RANGE_END INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL(
                "ALTER TABLE $TABLE_BOOKS ADD COLUMN $COL_SKIP_PATTERNS TEXT NOT NULL DEFAULT ''"
            )
        }
        if (oldVersion < 4) {
            // "Mark as finished" toggle so completed books can be visually distinguished
            // (and eventually filtered) without losing their entry in the library.
            db.execSQL(
                "ALTER TABLE $TABLE_BOOKS ADD COLUMN $COL_IS_FINISHED INTEGER NOT NULL DEFAULT 0"
            )
        }
        if (oldVersion < 5) {
            // One-time reset of resume positions. Up to 0.16 the parsers sub-chunked sentences
            // (many small chunks); from 0.16 they emit whole sentences (far fewer). A bookmark's
            // globalChunk recorded in the OLD unit is meaningless against the NEW totalChunks,
            // giving wrong progress % (e.g. 90% at the halfway mark) and a wrong resume position.
            // There's no precise conversion, so clear resume bookmarks once — books restart from
            // the beginning with correct counts. Named (saved) bookmarks are kept.
            db.execSQL("DELETE FROM $TABLE_BOOKMARKS")
        }
    }

    companion object {
        private const val DATABASE_NAME = "narrator.db"
        private const val DATABASE_VERSION = 5

        const val TABLE_BOOKS = "books"
        const val COL_ID = "id"
        const val COL_TITLE = "title"
        const val COL_AUTHOR = "author"
        const val COL_EPUB_PATH = "epub_path"
        const val COL_COVER_PATH = "cover_path"
        const val COL_TOTAL_CHUNKS = "total_chunks"
        const val COL_IMPORTED_AT = "imported_at"
        const val COL_PLAYBACK_SPEED = "playback_speed"
        /** Inclusive 1-based page range applied at parse time. 0 = unset (use whole doc). */
        const val COL_PAGE_RANGE_START = "page_range_start"
        const val COL_PAGE_RANGE_END = "page_range_end"
        /** Newline-separated regex patterns; chunks matching any are dropped post-parse. */
        const val COL_SKIP_PATTERNS = "skip_patterns"
        /** 1 if the user manually marked the book as finished. */
        const val COL_IS_FINISHED = "is_finished"

        /** Single resume position per book (the "where I left off" bookmark). */
        const val TABLE_BOOKMARKS = "bookmarks"
        const val COL_BOOK_ID = "book_id"
        const val COL_CHAPTER_INDEX = "chapter_index"
        const val COL_CHUNK_INDEX = "chunk_index"
        const val COL_GLOBAL_CHUNK = "global_chunk"
        const val COL_UPDATED_AT = "updated_at"

        /** Named bookmarks the user explicitly saves at specific positions. */
        const val TABLE_SAVED_BOOKMARKS = "saved_bookmarks"
        const val COL_SAVED_ID = "id"
        const val COL_SAVED_LABEL = "label"
        const val COL_SAVED_CREATED_AT = "created_at"

        private const val SQL_CREATE_BOOKS = """
            CREATE TABLE $TABLE_BOOKS (
              $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
              $COL_TITLE TEXT NOT NULL,
              $COL_AUTHOR TEXT NOT NULL,
              $COL_EPUB_PATH TEXT NOT NULL,
              $COL_COVER_PATH TEXT,
              $COL_TOTAL_CHUNKS INTEGER NOT NULL DEFAULT 0,
              $COL_IMPORTED_AT INTEGER NOT NULL,
              $COL_PLAYBACK_SPEED REAL NOT NULL DEFAULT 1.0,
              $COL_PAGE_RANGE_START INTEGER NOT NULL DEFAULT 0,
              $COL_PAGE_RANGE_END INTEGER NOT NULL DEFAULT 0,
              $COL_SKIP_PATTERNS TEXT NOT NULL DEFAULT '',
              $COL_IS_FINISHED INTEGER NOT NULL DEFAULT 0
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

        private const val SQL_CREATE_SAVED_BOOKMARKS = """
            CREATE TABLE $TABLE_SAVED_BOOKMARKS (
              $COL_SAVED_ID INTEGER PRIMARY KEY AUTOINCREMENT,
              $COL_BOOK_ID INTEGER NOT NULL,
              $COL_CHAPTER_INDEX INTEGER NOT NULL,
              $COL_CHUNK_INDEX INTEGER NOT NULL,
              $COL_GLOBAL_CHUNK INTEGER NOT NULL,
              $COL_SAVED_LABEL TEXT,
              $COL_SAVED_CREATED_AT INTEGER NOT NULL,
              FOREIGN KEY($COL_BOOK_ID) REFERENCES $TABLE_BOOKS($COL_ID) ON DELETE CASCADE
            )
        """
    }
}
