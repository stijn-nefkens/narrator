package com.example.narrator.ui.about

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.narrator.BuildConfig
import com.example.narrator.NarratorApp
import com.example.narrator.R
import com.example.narrator.databinding.ActivityAboutBinding

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        NarratorApp.applyThemeOverlay(this)
        super.onCreate(savedInstanceState)
        val binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.aboutVersion.text = getString(
            R.string.about_version_format,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
        )

        binding.aboutSource.setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL)))
            }
        }

        binding.aboutPrivacy.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.privacy_title)
                .setMessage(R.string.privacy_body)
                .setPositiveButton(R.string.privacy_close, null)
                .show()
        }
    }

    companion object {
        private const val SOURCE_URL = "https://github.com/stijn-nefkens/narrator"

        fun intent(context: Context): Intent = Intent(context, AboutActivity::class.java)
    }
}
