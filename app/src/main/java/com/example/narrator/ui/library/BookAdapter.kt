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
            binding.itemTitle.text = item.book.title
            binding.itemAuthor.text = item.book.author
            binding.itemProgress.text = binding.root.context.getString(
                R.string.library_progress_format, item.progressPercent,
            )
            binding.itemProgressBar.progress = item.progressPercent

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

    private object Diff : DiffUtil.ItemCallback<BookWithProgress>() {
        override fun areItemsTheSame(old: BookWithProgress, new: BookWithProgress) =
            old.book.id == new.book.id

        override fun areContentsTheSame(old: BookWithProgress, new: BookWithProgress) =
            old == new
    }
}
