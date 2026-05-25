package com.example.narrator.ui.settings

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.narrator.NarratorApp
import com.example.narrator.R
import com.example.narrator.data.AppPreferences
import com.example.narrator.data.SkipIncrement
import com.example.narrator.data.ThemeMode
import com.example.narrator.databinding.FragmentSettingsBinding
import com.example.narrator.ui.about.AboutActivity
import com.example.narrator.ui.voicesetup.VoiceSetupActivity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val container get() = (requireActivity().application as NarratorApp).container
    private val prefs: AppPreferences get() = container.preferences

    private val createBackup = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? -> uri?.let(::performBackup) }

    private val openBackup = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? -> uri?.let(::confirmRestore) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        wireSpeed()
        wirePitch()
        wireSkipIncrement()
        wireTheme()

        binding.settingsVoiceSetupRow.setOnClickListener {
            startActivity(VoiceSetupActivity.intent(requireContext(), firstRun = false))
        }
        binding.settingsBackupRow.setOnClickListener {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            createBackup.launch(getString(R.string.backup_default_name, date))
        }
        binding.settingsRestoreRow.setOnClickListener {
            openBackup.launch(arrayOf("application/zip", "application/octet-stream"))
        }
        binding.settingsAboutRow.setOnClickListener {
            startActivity(AboutActivity.intent(requireContext()))
        }
    }

    private fun performBackup(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { container.backupManager.backupTo(uri) }
                .onSuccess { s ->
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.backup_success, s.bookFiles),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                .onFailure { e ->
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.backup_failed, e.message ?: "unknown error"),
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }

    private fun confirmRestore(uri: Uri) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.restore_confirm_title)
            .setMessage(R.string.restore_confirm_message)
            .setPositiveButton(R.string.restore_proceed) { _, _ -> performRestore(uri) }
            .setNegativeButton(R.string.library_cancel, null)
            .show()
    }

    private fun performRestore(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { container.backupManager.restoreFrom(uri) }
                .onSuccess { s ->
                    container.bookRepository.refresh()
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.restore_success, s.bookFiles),
                        Toast.LENGTH_SHORT,
                    ).show()
                    // Drop any currently-loaded book from memory: the source file may have been
                    // replaced or the book may no longer exist. The easiest correct thing is to
                    // recreate the activity so every fragment re-reads from the new DB.
                    requireActivity().recreate()
                }
                .onFailure { e ->
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.restore_failed, e.message ?: "unknown error"),
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // 0.8 + (progress * 0.1) — progress 0..12 → 0.8..2.0
    private fun wireSpeed() {
        val current = prefs.defaultSpeed
        binding.settingsSpeedSeek.progress = ((current - 0.8f) / 0.1f).toInt().coerceIn(0, 12)
        binding.settingsSpeedValue.text = getString(R.string.settings_default_speed_format, current)
        binding.settingsSpeedSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = 0.8f + progress * 0.1f
                binding.settingsSpeedValue.text = getString(R.string.settings_default_speed_format, v)
                if (fromUser) prefs.defaultSpeed = v
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    // 0.5 + (progress * 0.05) — progress 0..20 → 0.5..1.5
    private fun wirePitch() {
        val current = prefs.pitch
        binding.settingsPitchSeek.progress = ((current - 0.5f) / 0.05f).toInt().coerceIn(0, 20)
        binding.settingsPitchValue.text = getString(R.string.settings_pitch_format, current)
        binding.settingsPitchSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = 0.5f + progress * 0.05f
                binding.settingsPitchValue.text = getString(R.string.settings_pitch_format, v)
                if (fromUser) {
                    prefs.pitch = v
                    container.narrator.applyPitchFromPreferences()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun wireSkipIncrement() {
        val checkedId = when (prefs.skipIncrement) {
            SkipIncrement.SENTENCE -> R.id.settings_skip_sentence
            SkipIncrement.PARAGRAPH -> R.id.settings_skip_paragraph
        }
        binding.settingsSkipGroup.check(checkedId)
        binding.settingsSkipGroup.setOnCheckedChangeListener { _, id ->
            prefs.skipIncrement = when (id) {
                R.id.settings_skip_paragraph -> SkipIncrement.PARAGRAPH
                else -> SkipIncrement.SENTENCE
            }
        }
    }

    private fun wireTheme() {
        val checkedId = when (prefs.theme) {
            ThemeMode.LIGHT -> R.id.settings_theme_light
            ThemeMode.DARK -> R.id.settings_theme_dark
            ThemeMode.SYSTEM -> R.id.settings_theme_system
            ThemeMode.AMOLED -> R.id.settings_theme_amoled
        }
        binding.settingsThemeGroup.check(checkedId)
        binding.settingsThemeGroup.addOnButtonCheckedListener { _, id, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (id) {
                R.id.settings_theme_light -> ThemeMode.LIGHT
                R.id.settings_theme_dark -> ThemeMode.DARK
                R.id.settings_theme_amoled -> ThemeMode.AMOLED
                else -> ThemeMode.SYSTEM
            }
            val previous = prefs.theme
            if (mode == previous) return@addOnButtonCheckedListener
            prefs.theme = mode
            prefs.applyTheme()
            // AMOLED is applied via setTheme() at activity onCreate, so a recreate is required
            // when crossing into or out of it. Other transitions are handled by night mode alone.
            if (previous == ThemeMode.AMOLED || mode == ThemeMode.AMOLED) {
                requireActivity().recreate()
            }
        }
    }

}
