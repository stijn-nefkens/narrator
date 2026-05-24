package com.example.narrator.ui.voicesetup

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.narrator.NarratorApp
import com.example.narrator.R
import com.example.narrator.databinding.ActivityVoiceSetupBinding
import com.example.narrator.databinding.ItemVoiceEngineBinding
import com.example.narrator.tts.VoicePreferences
import java.util.Locale

class VoiceSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVoiceSetupBinding
    private var sampleTts: TextToSpeech? = null
    private var sampleEnginePkg: String? = null
    private val engineRows = linkedMapOf<String, ItemVoiceEngineBinding>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoiceSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val firstRun = intent.getBooleanExtra(EXTRA_FIRST_RUN, false)
        binding.voiceSetupSkip.visibility = if (firstRun) View.VISIBLE else View.GONE
        binding.voiceSetupSkip.setOnClickListener {
            VoicePreferences.markSetupDone(this)
            finish()
        }
        binding.voiceSetupDone.setOnClickListener { finish() }

        renderEngines()
    }

    override fun onDestroy() {
        super.onDestroy()
        sampleTts?.stop()
        sampleTts?.shutdown()
        sampleTts = null
    }

    /** Builds the engine rows once. Subsequent selections just toggle the existing switches. */
    private fun renderEngines() {
        val probe = TextToSpeech(this) { /* engines list works regardless of init status */ }
        val engines = probe.engines.orEmpty()
        probe.shutdown()

        binding.voiceSetupEngines.removeAllViews()
        engineRows.clear()
        if (engines.isEmpty()) {
            binding.voiceSetupNoEngines.visibility = View.VISIBLE
            return
        }
        binding.voiceSetupNoEngines.visibility = View.GONE

        val currentEnginePkg = VoicePreferences.enginePackage(this)
        for (engine in engines) {
            val row = ItemVoiceEngineBinding.inflate(layoutInflater, binding.voiceSetupEngines, false)
            val label = engine.label.ifBlank { engine.name }

            row.engineName.text = label
            row.enginePackage.text = engine.name
            row.engineSwitch.isChecked = engine.name == currentEnginePkg

            row.root.setOnClickListener { selectEngine(engine.name) }
            row.engineSample.setOnClickListener { playSample(engine.name) }
            row.engineOpenSettings.setOnClickListener { openEngineSettings(engine.name, label) }

            engineRows[engine.name] = row
            binding.voiceSetupEngines.addView(row.root)
        }
    }

    private fun selectEngine(enginePackage: String) {
        val currentPkg = VoicePreferences.enginePackage(this)
        if (enginePackage == currentPkg) return  // already selected; nothing to do
        VoicePreferences.setEngine(this, enginePackage)
        (application as NarratorApp).container.narrator.reinitEngine()
        // Animate the existing switches: turn off all, turn on the selected one. MaterialSwitch
        // animates this transition smoothly because the views are reused.
        for ((pkg, row) in engineRows) {
            row.engineSwitch.isChecked = (pkg == enginePackage)
        }
    }

    private fun playSample(enginePackage: String) {
        if (sampleEnginePkg == enginePackage && sampleTts != null) {
            sampleTts?.speak(
                getString(R.string.voice_setup_sample_sentence),
                TextToSpeech.QUEUE_FLUSH,
                null,
                "sample",
            )
            return
        }
        sampleTts?.stop()
        sampleTts?.shutdown()
        sampleEnginePkg = enginePackage
        sampleTts = TextToSpeech(
            this,
            { status ->
                if (status == TextToSpeech.SUCCESS) {
                    sampleTts?.language = Locale.US
                    sampleTts?.speak(
                        getString(R.string.voice_setup_sample_sentence),
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "sample",
                    )
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "Couldn't start $enginePackage", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            enginePackage,
        )
    }

    private fun openEngineSettings(pkg: String, label: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } else {
            runCatching {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            }.onFailure {
                Toast.makeText(this, getString(R.string.voice_setup_open_engine_failed, label), Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        const val EXTRA_FIRST_RUN = "first_run"

        fun intent(context: Context, firstRun: Boolean): Intent =
            Intent(context, VoiceSetupActivity::class.java).putExtra(EXTRA_FIRST_RUN, firstRun)
    }
}
