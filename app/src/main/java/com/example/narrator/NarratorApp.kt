package com.example.narrator

import android.app.Application
import com.example.narrator.data.AppPreferences
import com.example.narrator.data.BookImporter
import com.example.narrator.data.BookRepository
import com.example.narrator.data.NarratorDatabase
import com.example.narrator.tts.Narrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NarratorApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.preferences.applyTheme()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            container.bookRepository.refresh()
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
