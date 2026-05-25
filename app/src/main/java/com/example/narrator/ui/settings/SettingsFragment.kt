package com.example.narrator.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import com.example.narrator.NarratorApp
import com.example.narrator.R
import com.example.narrator.data.AppPreferences
import com.example.narrator.data.SkipIncrement
import com.example.narrator.data.ThemeMode
import com.example.narrator.databinding.FragmentSettingsBinding
import com.example.narrator.ui.about.AboutActivity
import com.example.narrator.ui.voicesetup.VoiceSetupActivity

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val container get() = (requireActivity().application as NarratorApp).container
    private val prefs: AppPreferences get() = container.preferences

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
        binding.settingsAboutRow.setOnClickListener {
            startActivity(AboutActivity.intent(requireContext()))
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
