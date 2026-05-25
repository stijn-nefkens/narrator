package com.example.narrator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.narrator.data.ImportResult
import kotlinx.coroutines.launch
import com.example.narrator.databinding.ActivityMainBinding
import com.example.narrator.tts.NarratorState
import com.example.narrator.tts.VoicePreferences
import com.example.narrator.ui.library.LibraryFragment
import com.example.narrator.ui.player.PlayerFragment
import com.example.narrator.ui.settings.SettingsFragment
import com.example.narrator.ui.voicesetup.VoiceSetupActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val fragmentsByTag = linkedMapOf<String, Fragment>()
    private var currentTag: String? = null

    private val requestNotificationPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        NarratorApp.applyThemeOverlay(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            attachIfMissing(TAG_PLAYER) { PlayerFragment() }
            attachIfMissing(TAG_LIBRARY) { LibraryFragment() }
            attachIfMissing(TAG_SETTINGS) { SettingsFragment() }
            // Open Player on launch if there's a previously-loaded book; the auto-load happens
            // asynchronously in NarratorApp.onCreate so by the time the fragment renders the
            // book state is usually ready.
            val container = (application as NarratorApp).container
            val hasLastBook = container.preferences.lastOpenedBookId > 0
            if (hasLastBook) {
                switchTo(TAG_PLAYER)
                binding.bottomNav.selectedItemId = R.id.nav_player
            } else {
                switchTo(TAG_LIBRARY)
                binding.bottomNav.selectedItemId = R.id.nav_library
            }
        } else {
            // Re-attach references to surviving fragments
            for (tag in listOf(TAG_PLAYER, TAG_LIBRARY, TAG_SETTINGS)) {
                supportFragmentManager.findFragmentByTag(tag)?.let { fragmentsByTag[tag] = it }
            }
            currentTag = fragmentsByTag.entries.firstOrNull { !it.value.isHidden }?.key
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            val tag = when (item.itemId) {
                R.id.nav_player -> TAG_PLAYER
                R.id.nav_library -> TAG_LIBRARY
                R.id.nav_settings -> TAG_SETTINGS
                else -> return@setOnItemSelectedListener false
            }
            switchTo(tag)
            renderMiniPlayer((application as NarratorApp).container.narrator.state.value)
            true
        }

        wireMiniPlayer()

        if (savedInstanceState == null && !VoicePreferences.isSetupDone(this)) {
            startActivity(VoiceSetupActivity.intent(this, firstRun = true))
        }
        maybeRequestNotificationPermission()

        // Handle a VIEW/SEND intent pointed at an EPUB file (file manager / share menu).
        handleEpubIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask launchMode re-uses this instance for new intents.
        setIntent(intent)
        handleEpubIntent(intent)
    }

    private fun handleEpubIntent(intent: Intent?) {
        intent ?: return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> null
        }
        if (uri == null) return
        // Defuse the action so we don't re-import on configuration change / back-stack reuse.
        intent.action = null
        importFromExternalUri(uri)
    }

    private fun importFromExternalUri(uri: Uri) {
        // The grant from the launching app is for THIS intent only; we copy to app-private
        // storage immediately so no persistable URI permission is required.
        val container = (application as NarratorApp).container
        showPlayerTab().let { /* no-op; we'll switch to Library after */ }
        binding.bottomNav.selectedItemId = R.id.nav_library
        lifecycleScope.launch {
            when (val result = container.bookImporter.importFromUri(uri)) {
                is ImportResult.Inserted -> {
                    Toast.makeText(this@MainActivity,
                        getString(R.string.import_success, result.book.title), Toast.LENGTH_SHORT).show()
                }
                is ImportResult.Duplicate -> {
                    // Already-imported book: keep what's there, drop the freshly-staged copy.
                    container.bookImporter.confirmDuplicate(result.existing, result.pending, replace = false)
                    Toast.makeText(this@MainActivity,
                        getString(R.string.import_already_present, result.existing.title), Toast.LENGTH_SHORT).show()
                }
                is ImportResult.Failed -> {
                    Toast.makeText(this@MainActivity,
                        getString(R.string.import_failed, result.reason), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotificationPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Called by LibraryFragment when the user taps a book — switches to Player tab. */
    fun showPlayerTab() {
        binding.bottomNav.selectedItemId = R.id.nav_player
    }

    /** Shows the mini-player above the bottom nav when a book is loaded and the user is
     *  on any tab other than Player. Cover tap → jump to Player; play button toggles
     *  without leaving the current tab. */
    private fun wireMiniPlayer() {
        val container = (application as NarratorApp).container
        binding.miniPlayer.setOnClickListener { showPlayerTab() }
        binding.miniPlay.setOnClickListener { container.narrator.togglePlayPause() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.narrator.state.collect(::renderMiniPlayer)
            }
        }
    }

    private fun renderMiniPlayer(state: NarratorState) {
        val loaded = state.loaded
        val onPlayerTab = currentTag == TAG_PLAYER
        if (loaded == null || onPlayerTab) {
            binding.miniPlayer.visibility = View.GONE
            return
        }
        binding.miniPlayer.visibility = View.VISIBLE
        binding.miniTitle.text = loaded.title
        binding.miniSubtitle.text = getString(
            R.string.mini_player_subtitle,
            loaded.author,
            state.position.chapterIndex + 1,
            loaded.chapterTitles.size,
        )
        val bitmap = loaded.coverPath?.let {
            runCatching { BitmapFactory.decodeFile(it) }.getOrNull()
        }
        if (bitmap != null) binding.miniCover.setImageBitmap(bitmap)
        else binding.miniCover.setImageResource(R.drawable.ic_book_placeholder)
        binding.miniPlay.setIconResource(
            if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
        )
        binding.miniPlay.contentDescription = getString(
            if (state.isPlaying) R.string.player_pause else R.string.player_play,
        )
    }

    private fun attachIfMissing(tag: String, factory: () -> Fragment) {
        val existing = supportFragmentManager.findFragmentByTag(tag)
        if (existing != null) {
            fragmentsByTag[tag] = existing
            return
        }
        val fragment = factory()
        fragmentsByTag[tag] = fragment
        supportFragmentManager.beginTransaction()
            .add(R.id.fragment_container, fragment, tag)
            .hide(fragment)
            .commitNow()
    }

    private fun switchTo(tag: String) {
        if (tag == currentTag) return
        val tx = supportFragmentManager.beginTransaction()
        currentTag?.let { fragmentsByTag[it]?.let(tx::hide) }
        fragmentsByTag[tag]?.let(tx::show)
        tx.commit()
        currentTag = tag
    }

    private companion object {
        const val TAG_PLAYER = "player"
        const val TAG_LIBRARY = "library"
        const val TAG_SETTINGS = "settings"
    }
}
