package com.example.narrator.ui.voicesetup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
        NarratorApp.applyThemeOverlay(this)
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
        binding.voiceSetupGetMore.setOnClickListener { promptInstallKokoro() }

        renderEngines()
    }

    private fun promptInstallKokoro() {
        AlertDialog.Builder(this)
            .setTitle(R.string.voice_setup_install_title)
            .setMessage(R.string.voice_setup_install_message)
            .setPositiveButton(R.string.voice_setup_install_download) { _, _ -> startKokoroDownload() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startKokoroDownload() {
        val abis = Build.SUPPORTED_ABIS.orEmpty()
        val url = if (abis.contains("arm64-v8a")) {
            KOKORO_APK_ARM64
        } else {
            // No non-arm64 build exists for Kokoro; send them to the docs page so they can see
            // what their device supports.
            Toast.makeText(this, R.string.voice_setup_install_unsupported, Toast.LENGTH_LONG).show()
            KOKORO_FALLBACK_PAGE
        }
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
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
                    // Use applicationContext so the toast survives even if this activity is
                    // already in the process of being destroyed when the engine init callback
                    // fires (TTS init runs on a background thread without lifecycle awareness).
                    val appCtx = applicationContext
                    appCtx.let {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            Toast.makeText(it, "Couldn't start $enginePackage", Toast.LENGTH_SHORT).show()
                        }
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

        // Direct APK from the official k2-fsa HuggingFace mirror. sherpa-onnx isn't on the Play
        // Store or F-Droid, and the user pointed at this specific build on apk-engine.html, so we
        // bypass any redirector and start the download in the system browser. The user gets the
        // standard Android "install from unknown source" prompt — one tap on the URL, one tap to
        // open with the package installer, done.
        private const val KOKORO_APK_ARM64 =
            "https://huggingface.co/csukuangfj2/sherpa-onnx-apk/resolve/main/tts-engine-new/1.13.1/sherpa-onnx-1.13.1-arm64-v8a-en-tts-engine-kokoro-en-v0_19.apk"
        private const val KOKORO_FALLBACK_PAGE =
            "https://k2-fsa.github.io/sherpa/onnx/tts/apk-engine.html"

        fun intent(context: Context, firstRun: Boolean): Intent =
            Intent(context, VoiceSetupActivity::class.java).putExtra(EXTRA_FIRST_RUN, firstRun)
    }
}
