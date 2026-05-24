package com.example.narrator.ui.voicesetup

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
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
    private var cachedVoices: List<Voice> = emptyList()

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
        renderVoicesForCurrentEngine()
    }

    override fun onDestroy() {
        super.onDestroy()
        sampleTts?.stop()
        sampleTts?.shutdown()
        sampleTts = null
    }

    // --- engines ---------------------------------------------------------

    private fun renderEngines() {
        val probe = TextToSpeech(this) { /* engines list works regardless of init status */ }
        val engines = probe.engines.orEmpty()
        probe.shutdown()

        binding.voiceSetupEngines.removeAllViews()
        if (engines.isEmpty()) {
            binding.voiceSetupNoEngines.visibility = View.VISIBLE
            return
        }

        val currentEnginePkg = VoicePreferences.enginePackage(this)
        for (engine in engines) {
            val row = ItemVoiceEngineBinding.inflate(layoutInflater, binding.voiceSetupEngines, false)
            val label = engine.label.ifBlank { engine.name }
            row.engineName.text = if (engine.name == currentEnginePkg) "$label ✓" else label
            row.enginePackage.text = engine.name
            row.engineSample.setOnClickListener { playSample(engine.name, voiceName = null) }
            row.engineUse.setOnClickListener { chooseEngine(engine.name) }
            binding.voiceSetupEngines.addView(row.root)
        }
    }

    private fun chooseEngine(enginePackage: String) {
        VoicePreferences.setEngine(this, enginePackage)
        (application as NarratorApp).container.narrator.reinitEngine()
        renderEngines()
        renderVoicesForCurrentEngine()
    }

    // --- voices ----------------------------------------------------------

    private fun renderVoicesForCurrentEngine() {
        val enginePkg = VoicePreferences.enginePackage(this)
        if (enginePkg == null) {
            binding.voiceSetupVoicesHeader.text = ""
            binding.voiceSetupEngineSpeakersNote.visibility = View.GONE
            binding.voiceSetupOpenEngine.visibility = View.GONE
            binding.voiceSetupVoicesEmpty.visibility = View.VISIBLE
            binding.voiceSetupVoices.removeAllViews()
            return
        }
        val label = engineLabel(enginePkg)
        binding.voiceSetupVoicesHeader.text = getString(R.string.voice_setup_voices_for, label)
        binding.voiceSetupEngineSpeakersNote.visibility = View.VISIBLE
        binding.voiceSetupOpenEngine.apply {
            visibility = View.VISIBLE
            text = getString(R.string.voice_setup_open_engine_settings, label)
            setOnClickListener { openEngineSettings(enginePkg, label) }
        }
        binding.voiceSetupVoicesEmpty.visibility = View.VISIBLE
        binding.voiceSetupVoices.removeAllViews()

        // Spin up a TTS bound to this engine so we can enumerate its voices.
        sampleTts?.stop()
        sampleTts?.shutdown()
        sampleEnginePkg = enginePkg
        sampleTts = TextToSpeech(
            this,
            { status ->
                if (status == TextToSpeech.SUCCESS) {
                    runOnUiThread { populateVoices(enginePkg) }
                } else {
                    runOnUiThread {
                        binding.voiceSetupVoicesEmpty.visibility = View.VISIBLE
                        binding.voiceSetupVoicesEmpty.text = "Engine init failed for $enginePkg"
                    }
                }
            },
            enginePkg,
        )
    }

    private fun populateVoices(enginePkg: String) {
        val voices = runCatching { sampleTts?.voices }.getOrNull().orEmpty()
            .filterNotNull()
            .sortedWith(compareBy({ it.locale?.language ?: "" }, { it.name }))
        cachedVoices = voices
        binding.voiceSetupVoices.removeAllViews()
        if (voices.isEmpty()) {
            binding.voiceSetupVoicesEmpty.visibility = View.VISIBLE
            binding.voiceSetupVoicesEmpty.text = getString(R.string.voice_setup_voices_default)
            return
        }
        binding.voiceSetupVoicesEmpty.visibility = View.GONE

        val currentVoice = VoicePreferences.voiceName(this)

        // Always offer "engine default" as the first option.
        addVoiceRow(
            displayName = getString(R.string.voice_setup_voices_default),
            caption = "",
            isCurrent = currentVoice == null,
            onSample = { playSample(enginePkg, voiceName = null) },
            onUse = { chooseVoice(null) },
        )

        for (voice in voices) {
            val localeLabel = voice.locale?.displayName.orEmpty().ifBlank { voice.locale?.toString().orEmpty() }
            addVoiceRow(
                displayName = voice.name,
                caption = localeLabel,
                isCurrent = currentVoice == voice.name,
                onSample = { playSample(enginePkg, voiceName = voice.name) },
                onUse = { chooseVoice(voice.name) },
            )
        }
    }

    private fun addVoiceRow(
        displayName: String,
        caption: String,
        isCurrent: Boolean,
        onSample: () -> Unit,
        onUse: () -> Unit,
    ) {
        val row = ItemVoiceEngineBinding.inflate(layoutInflater, binding.voiceSetupVoices, false)
        row.engineName.text = if (isCurrent) "$displayName ✓" else displayName
        row.enginePackage.text = caption
        row.enginePackage.visibility = if (caption.isEmpty()) View.GONE else View.VISIBLE
        row.engineSample.setOnClickListener { onSample() }
        row.engineUse.setOnClickListener { onUse() }
        binding.voiceSetupVoices.addView(row.root)
    }

    private fun chooseVoice(voiceName: String?) {
        VoicePreferences.setVoice(this, voiceName)
        (application as NarratorApp).container.narrator.applyVoiceFromPreferences()
        renderVoicesForCurrentEngine()
    }

    // --- sampling --------------------------------------------------------

    private fun playSample(enginePackage: String, voiceName: String?) {
        // Reuse the engine-bound TTS when we can — saves init time.
        if (sampleEnginePkg == enginePackage && sampleTts != null) {
            speakSampleOn(sampleTts!!, voiceName)
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
                    speakSampleOn(sampleTts!!, voiceName)
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "Couldn't start $enginePackage", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            enginePackage,
        )
    }

    private fun speakSampleOn(tts: TextToSpeech, voiceName: String?) {
        if (voiceName != null) {
            val match = runCatching { tts.voices }.getOrNull()?.firstOrNull { it.name == voiceName }
            if (match != null) runCatching { tts.voice = match }
        } else {
            // engine default — try to reset by picking the default voice for the language
            runCatching { tts.defaultVoice?.let { tts.voice = it } }
        }
        val sample = getString(R.string.voice_setup_sample_sentence)
        tts.speak(sample, TextToSpeech.QUEUE_FLUSH, null, "sample")
    }

    private fun openEngineSettings(pkg: String, label: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } else {
            // Fallback: open the Android system TTS settings page.
            runCatching {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            }.onFailure {
                Toast.makeText(this, getString(R.string.voice_setup_open_engine_failed, label), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun engineLabel(pkg: String): String {
        val probe = TextToSpeech(this) { /* ignore */ }
        val match = probe.engines?.firstOrNull { it.name == pkg }
        val label = match?.label?.ifBlank { match.name } ?: pkg
        probe.shutdown()
        return label
    }

    companion object {
        const val EXTRA_FIRST_RUN = "first_run"

        fun intent(context: Context, firstRun: Boolean): Intent =
            Intent(context, VoiceSetupActivity::class.java).putExtra(EXTRA_FIRST_RUN, firstRun)
    }
}
