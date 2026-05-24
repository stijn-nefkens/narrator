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
import java.io.File

class BookAdapter(
    private val onClick: (BookWithProgress) -> Unit,
    private val onLongClick: (BookWithProgress) -> Unit,
) : ListAdapter<BookWithProgress, BookAdapter.ViewHolder>(Diff) {

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

            val coverPath = item.book.coverPath
            val bitmap = coverPath?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
            if (bitmap != null) {
                binding.itemCover.setImageBitmap(bitmap)
            } else {
                binding.itemCover.setImageResource(R.drawable.ic_book_placeholder)
            }

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
