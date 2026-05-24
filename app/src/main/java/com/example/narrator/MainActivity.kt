package com.example.narrator

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.narrator.databinding.ActivityMainBinding
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
            true
        }

        if (savedInstanceState == null && !VoicePreferences.isSetupDone(this)) {
            startActivity(VoiceSetupActivity.intent(this, firstRun = true))
        }
        maybeRequestNotificationPermission()
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
