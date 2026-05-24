package com.example.narrator.ui.library

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.narrator.R
import com.example.narrator.data.BookWithProgress
import com.example.narrator.databinding.ItemBookBinding

class BookAdapter(
    private val onClick: (BookWithProgress) -> Unit,
    private val onLongClick: (BookWithProgress) -> Unit,
) : ListAdapter<BookWithProgress, BookAdapter.ViewHolder>(Diff) {

    private val selectedIds: MutableSet<Long> = mutableSetOf()
    var selectionEnabled: Boolean = false
        private set

    fun setSelectionMode(enabled: Boolean) {
        if (selectionEnabled == enabled) return
        selectionEnabled = enabled
        if (!enabled) selectedIds.clear()
        notifyItemRangeChanged(0, itemCount)
    }

    fun toggleSelected(item: BookWithProgress) {
        val id = item.book.id
        if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
        val idx = currentList.indexOfFirst { it.book.id == id }
        if (idx >= 0) notifyItemChanged(idx)
    }

    fun selectedIds(): Set<Long> = selectedIds.toSet()

    fun clearSelection() {
        selectedIds.clear()
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBookBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemBookBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: BookWithProgress) {
            val ctx = binding.root.context
            binding.itemTitle.text = item.book.title
            binding.itemAuthor.text = item.book.author
            binding.itemProgress.text = ctx.getString(
                R.string.library_progress_format, item.progressPercent,
            )
            binding.itemProgressBar.progress = item.progressPercent
            binding.itemLastOpened.text = formatLastOpened(ctx, item.bookmark?.updatedAt)

            val coverPath = item.book.coverPath
            val bitmap = coverPath?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
            if (bitmap != null) {
                binding.itemCover.setImageBitmap(bitmap)
            } else {
                binding.itemCover.setImageResource(R.drawable.ic_book_placeholder)
            }

            // Visual selection: dim when others are selected, accent border when this one is.
            val isSelected = selectedIds.contains(item.book.id)
            binding.root.alpha = if (!selectionEnabled || isSelected) 1f else 0.6f
            binding.root.isActivated = isSelected

            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener { onLongClick(item); true }
        }
    }

    private fun formatLastOpened(ctx: android.content.Context, updatedAtMs: Long?): String {
        if (updatedAtMs == null || updatedAtMs <= 0L) return ctx.getString(R.string.library_last_opened_never)
        val deltaMs = (System.currentTimeMillis() - updatedAtMs).coerceAtLeast(0L)
        val minutes = deltaMs / 60_000L
        val hours = deltaMs / 3_600_000L
        val days = deltaMs / 86_400_000L
        return when {
            minutes < 2L -> ctx.getString(R.string.library_last_opened_just_now)
            minutes < 60L -> ctx.getString(R.string.library_last_opened_minutes, minutes.toInt())
            hours < 24L -> ctx.getString(R.string.library_last_opened_hours, hours.toInt())
            days < 7L -> ctx.getString(R.string.library_last_opened_days, days.toInt())
            else -> ctx.getString(R.string.library_last_opened_weeks, (days / 7L).toInt())
        }
    }

    private object Diff : DiffUtil.ItemCallback<BookWithProgress>() {
        override fun areItemsTheSame(old: BookWithProgress, new: BookWithProgress) =
            old.book.id == new.book.id

        override fun areContentsTheSame(old: BookWithProgress, new: BookWithProgress) =
            old == new
    }
}
