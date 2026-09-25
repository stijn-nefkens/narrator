package com.example.narrator.tts

import android.content.Context

object VoicePreferences {
    private const val PREFS = "voice_preferences"
    private const val KEY_ENGINE = "engine_package"
    private const val LEGACY_KEY_VOICE = "voice_name"
    private const val KEY_SETUP_DONE = "voice_setup_done"

    fun enginePackage(context: Context): String? =
        prefs(context).getString(KEY_ENGINE, null)

    fun setEngine(context: Context, pkg: String?) {
        prefs(context).edit()
            .putString(KEY_ENGINE, pkg)
            // Legacy per-engine voice choice: nothing has written it for several releases (the
            // app never exposed a voice picker), so drop any leftover when switching engines.
            .remove(LEGACY_KEY_VOICE)
            .putBoolean(KEY_SETUP_DONE, true)
            .apply()
    }

    fun isSetupDone(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SETUP_DONE, false)

    fun markSetupDone(context: Context) {
        prefs(context).edit().putBoolean(KEY_SETUP_DONE, true).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
