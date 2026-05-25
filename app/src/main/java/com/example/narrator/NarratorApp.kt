package com.example.narrator

import android.app.Application
import com.example.narrator.data.AppPreferences
import com.example.narrator.data.BookImporter
import com.example.narrator.data.BookRepository
import com.example.narrator.data.NarratorDatabase
import com.example.narrator.data.ThemeMode
import com.example.narrator.tts.Narrator
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NarratorApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // PDFBox-Android needs its resource loader initialised once on app startup; without
        // this PDDocument.load() throws when it tries to read its bundled CMaps and font
        // fallbacks. Safe to call multiple times.
        PDFBoxResourceLoader.init(applicationContext)
        container = AppContainer(this)
        container.preferences.applyTheme()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            container.bookRepository.refresh()
            // Auto-load the last opened book so the Player isn't empty on cold start.
            val lastId = container.preferences.lastOpenedBookId
            if (lastId > 0 && container.bookRepository.getBook(lastId) != null) {
                runCatching { container.narrator.loadBook(lastId) }
            }
        }
    }

    companion object {
        /**
         * Activities call this in onCreate (BEFORE super.onCreate / setContentView) to apply
         * the AMOLED true-black theme overlay when the user has selected ThemeMode.AMOLED.
         * Other modes inherit the default Theme.Narrator.
         */
        fun applyThemeOverlay(activity: androidx.appcompat.app.AppCompatActivity) {
            val prefs = (activity.application as NarratorApp).container.preferences
            if (prefs.theme == ThemeMode.AMOLED) {
                activity.setTheme(R.style.Theme_Narrator_Black)
            }
        }
    }
}

class AppContainer(app: Application) {
    private val appContext = app.applicationContext

    val preferences = AppPreferences(appContext)
    val database = NarratorDatabase(appContext)
    val bookRepository = BookRepository(appContext, database)
    val bookImporter = BookImporter(appContext, bookRepository)
    val narrator = Narrator(appContext, bookRepository, preferences)
}
