package com.example.narrator.data

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

enum class SkipIncrement { SENTENCE, PARAGRAPH }

enum class LibrarySortOrder { RECENTLY_PLAYED, RECENTLY_ADDED, TITLE, AUTHOR }

enum class ThemeMode {
    LIGHT, DARK, SYSTEM, AMOLED;

    fun nightModeFlag(): Int = when (this) {
        LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        DARK, AMOLED -> AppCompatDelegate.MODE_NIGHT_YES
        SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }
}

class AppPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var defaultSpeed: Float
        get() = prefs.getFloat(KEY_DEFAULT_SPEED, 1.0f).coerceIn(0.8f, 2.0f)
        set(value) = prefs.edit().putFloat(KEY_DEFAULT_SPEED, value.coerceIn(0.8f, 2.0f)).apply()

    var pitch: Float
        get() = prefs.getFloat(KEY_PITCH, 1.0f).coerceIn(0.5f, 1.5f)
        set(value) = prefs.edit().putFloat(KEY_PITCH, value.coerceIn(0.5f, 1.5f)).apply()

    var skipIncrement: SkipIncrement
        get() = SkipIncrement.valueOf(prefs.getString(KEY_SKIP, SkipIncrement.SENTENCE.name)!!)
        set(value) = prefs.edit().putString(KEY_SKIP, value.name).apply()

    var theme: ThemeMode
        get() = ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name)!!)
        set(value) = prefs.edit().putString(KEY_THEME, value.name).apply()

    var continueThroughChapters: Boolean
        get() = prefs.getBoolean(KEY_CONTINUE, true)
        set(value) = prefs.edit().putBoolean(KEY_CONTINUE, value).apply()

    /** Id of the last book that was loaded into the player, or -1 if none. */
    var lastOpenedBookId: Long
        get() = prefs.getLong(KEY_LAST_BOOK, -1L)
        set(value) = prefs.edit().putLong(KEY_LAST_BOOK, value).apply()

    var librarySort: LibrarySortOrder
        get() = LibrarySortOrder.valueOf(
            prefs.getString(KEY_LIB_SORT, LibrarySortOrder.RECENTLY_PLAYED.name)!!
        )
        set(value) = prefs.edit().putString(KEY_LIB_SORT, value.name).apply()

    /** Whether we've already shown the "drag/long-press the speed chip" hint. One-shot. */
    var speedTooltipShown: Boolean
        get() = prefs.getBoolean(KEY_SPEED_TOOLTIP, false)
        set(value) = prefs.edit().putBoolean(KEY_SPEED_TOOLTIP, value).apply()

    fun applyTheme() {
        AppCompatDelegate.setDefaultNightMode(theme.nightModeFlag())
    }

    companion object {
        private const val NAME = "narrator_settings"
        private const val KEY_DEFAULT_SPEED = "default_speed"
        private const val KEY_PITCH = "pitch"
        private const val KEY_SKIP = "skip_increment"
        private const val KEY_THEME = "theme"
        private const val KEY_CONTINUE = "continue_through_chapters"
        private const val KEY_LAST_BOOK = "last_opened_book_id"
        private const val KEY_LIB_SORT = "library_sort_order"
        private const val KEY_SPEED_TOOLTIP = "speed_tooltip_shown"
    }
}
