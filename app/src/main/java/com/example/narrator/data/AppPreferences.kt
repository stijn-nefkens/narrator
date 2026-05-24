package com.example.narrator.data

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

enum class SkipIncrement { SENTENCE, PARAGRAPH }

enum class ThemeMode {
    LIGHT, DARK, SYSTEM;

    fun nightModeFlag(): Int = when (this) {
        LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        DARK -> AppCompatDelegate.MODE_NIGHT_YES
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
    }
}
