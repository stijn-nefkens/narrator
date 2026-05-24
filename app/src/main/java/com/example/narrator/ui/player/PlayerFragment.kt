package com.example.narrator.ui.player

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.narrator.NarratorApp
import com.example.narrator.R
import com.example.narrator.databinding.FragmentPlayerBinding
import com.example.narrator.tts.NarratorState
import kotlinx.coroutines.launch

class PlayerFragment : Fragment() {
    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!

    private val container get() = (requireActivity().application as NarratorApp).container

    private var scrubbing = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.playerPlayPause.setOnClickListener { container.narrator.togglePlayPause() }
        binding.playerPrevChapter.setOnClickListener { container.narrator.skipChapterPrev() }
        binding.playerNextChapter.setOnClickListener { container.narrator.skipChapterNext() }
        binding.playerPrevStep.setOnClickListener { container.narrator.skipStepPrev() }
        binding.playerNextStep.setOnClickListener { container.narrator.skipStepNext() }
        binding.playerSpeed.setOnClickListener { cycleSpeed() }

        binding.playerScrub.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) updateProgressText(progress, seekBar.max)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) { scrubbing = true }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                scrubbing = false
                container.narrator.seekToGlobalChunk(seekBar.progress)
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.narrator.state.collect(::render)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun render(state: NarratorState) {
        val loaded = state.loaded
        if (loaded == null) {
            binding.playerEmpty.visibility = View.VISIBLE
            binding.playerLoaded.visibility = View.GONE
            return
        }
        binding.playerEmpty.visibility = View.GONE
        binding.playerLoaded.visibility = View.VISIBLE

        binding.playerTitle.text = loaded.title
        binding.playerAuthor.text = loaded.author

        val chapterTitle = loaded.chapterTitles.getOrNull(state.position.chapterIndex).orEmpty()
        binding.playerChapter.text = getString(
            R.string.player_chapter_format,
            state.position.chapterIndex + 1,
            loaded.chapterTitles.size,
            chapterTitle,
        )

        val bitmap = loaded.coverPath?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
        if (bitmap != null) {
            binding.playerCover.setImageBitmap(bitmap)
        } else {
            binding.playerCover.setImageResource(R.drawable.ic_book_placeholder)
        }

        binding.playerPlayPause.setIconResource(
            if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
        )
        binding.playerPlayPause.contentDescription = getString(
            if (state.isPlaying) R.string.player_pause else R.string.player_play,
        )

        val total = (loaded.totalChunks - 1).coerceAtLeast(0)
        binding.playerScrub.max = total
        if (!scrubbing) {
            binding.playerScrub.progress = state.position.globalChunk.coerceIn(0, total)
            updateProgressText(state.position.globalChunk, total)
        }

        binding.playerSpeed.text = getString(R.string.player_speed_format, state.speed)
    }

    private fun updateProgressText(current: Int, total: Int) {
        val pct = if (total <= 0) 0 else (current.toDouble() / total * 100).toInt().coerceIn(0, 100)
        binding.playerProgressText.text = getString(R.string.library_progress_format, pct)
    }

    private fun cycleSpeed() {
        val current = container.narrator.state.value.speed
        val next = SPEED_CYCLE.firstOrNull { it > current + 0.001f } ?: SPEED_CYCLE.first()
        container.narrator.setSpeed(next)
    }

    private companion object {
        val SPEED_CYCLE = listOf(0.8f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
    }
}
