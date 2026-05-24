package com.example.narrator.ui.library

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.narrator.MainActivity
import com.example.narrator.NarratorApp
import com.example.narrator.R
import com.example.narrator.data.BookEntity
import com.example.narrator.data.BookWithProgress
import com.example.narrator.data.ImportResult
import com.example.narrator.data.LibrarySortOrder
import com.example.narrator.data.PendingImport
import com.example.narrator.databinding.FragmentLibraryBinding
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class LibraryFragment : Fragment() {
    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private val container get() = (requireActivity().application as NarratorApp).container

    private val openDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? -> uri?.let(::handlePickedUri) }

    private lateinit var adapter: BookAdapter
    private var allBooks: List<BookWithProgress> = emptyList()
    private var query: String = ""

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            exitSelectionMode()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = BookAdapter(
            onClick = ::onBookClicked,
            onLongClick = ::onBookLongClicked,
        )
        binding.libraryList.layoutManager = LinearLayoutManager(requireContext())
        binding.libraryList.adapter = adapter

        binding.libraryFab.setOnClickListener {
            openDocument.launch(arrayOf("application/epub+zip", "application/octet-stream"))
        }

        binding.librarySearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                applyFilterSort()
            }
        })

        binding.librarySort.setOnClickListener(::showSortMenu)

        binding.libraryContinueCard.setOnClickListener { openCurrentBook() }
        binding.libraryContinuePlay.setOnClickListener { openCurrentBook(autoplay = true) }

        binding.libraryActionMode.setNavigationOnClickListener { exitSelectionMode() }
        binding.libraryActionMode.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_delete) {
                confirmDeleteSelected()
                true
            } else false
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.bookRepository.books.combine(container.narrator.state) { books, narrState ->
                    books to narrState.loaded?.bookId
                }.collect { (books, currentId) ->
                    allBooks = books
                    renderContinueCard(currentId, books)
                    applyFilterSort()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun applyFilterSort() {
        val sort = container.preferences.librarySort
        val q = query.trim().lowercase()
        val filtered = if (q.isBlank()) allBooks else allBooks.filter { item ->
            item.book.title.lowercase().contains(q) || item.book.author.lowercase().contains(q)
        }
        val sorted = when (sort) {
            LibrarySortOrder.RECENTLY_PLAYED -> filtered.sortedByDescending { it.bookmark?.updatedAt ?: 0L }
            LibrarySortOrder.RECENTLY_ADDED -> filtered.sortedByDescending { it.book.importedAt }
            LibrarySortOrder.TITLE -> filtered.sortedBy { it.book.title.lowercase() }
            LibrarySortOrder.AUTHOR -> filtered.sortedBy { it.book.author.lowercase() }
        }
        val hasBooks = allBooks.isNotEmpty()
        val hasMatches = sorted.isNotEmpty()
        binding.libraryEmpty.visibility = if (!hasBooks) View.VISIBLE else View.GONE
        binding.libraryNoMatches.visibility = if (hasBooks && !hasMatches && q.isNotBlank()) View.VISIBLE else View.GONE
        binding.libraryList.visibility = if (hasBooks && hasMatches) View.VISIBLE else View.GONE
        if (binding.libraryNoMatches.visibility == View.VISIBLE) {
            binding.libraryNoMatches.text = getString(R.string.library_no_matches, query.trim())
        }
        adapter.submitList(sorted)
    }

    private fun renderContinueCard(currentId: Long?, all: List<BookWithProgress>) {
        val current = all.firstOrNull { it.book.id == currentId }
        if (current == null) {
            binding.libraryContinueCard.visibility = View.GONE
            return
        }
        binding.libraryContinueCard.visibility = View.VISIBLE
        binding.libraryContinueTitle.text = current.book.title
        binding.libraryContinueAuthor.text = current.book.author
        val bitmap = current.book.coverPath?.let {
            runCatching { BitmapFactory.decodeFile(it) }.getOrNull()
        }
        if (bitmap != null) binding.libraryContinueCover.setImageBitmap(bitmap)
        else binding.libraryContinueCover.setImageResource(R.drawable.ic_book_placeholder)
    }

    private fun showSortMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        val options = listOf(
            LibrarySortOrder.RECENTLY_PLAYED to R.string.library_sort_recent,
            LibrarySortOrder.RECENTLY_ADDED to R.string.library_sort_added,
            LibrarySortOrder.TITLE to R.string.library_sort_title,
            LibrarySortOrder.AUTHOR to R.string.library_sort_author,
        )
        options.forEachIndexed { i, (_, str) -> popup.menu.add(0, i, i, getString(str)) }
        val current = container.preferences.librarySort
        popup.setOnMenuItemClickListener { item ->
            val (chosen, _) = options[item.itemId]
            if (chosen != current) {
                container.preferences.librarySort = chosen
                applyFilterSort()
            }
            true
        }
        popup.show()
    }

    private fun onBookClicked(item: BookWithProgress) {
        if (adapter.selectionEnabled) {
            adapter.toggleSelected(item)
            updateActionModeTitle()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            container.narrator.loadBook(item.book.id)
            (activity as? MainActivity)?.showPlayerTab()
        }
    }

    private fun onBookLongClicked(item: BookWithProgress) {
        // In selection mode, long-press just toggles like a tap.
        if (adapter.selectionEnabled) {
            adapter.toggleSelected(item)
            updateActionModeTitle()
            return
        }
        // Otherwise show a small menu: Edit details / Enter selection mode.
        val options = arrayOf(
            getString(R.string.library_long_press_edit),
            getString(R.string.library_long_press_select),
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.library_long_press_action)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openRenameDialog(item)
                    1 -> {
                        enterSelectionMode()
                        adapter.toggleSelected(item)
                        updateActionModeTitle()
                    }
                }
            }
            .show()
    }

    private fun openRenameDialog(item: BookWithProgress) {
        val ctx = requireContext()
        val titleInput = android.widget.EditText(ctx).apply {
            setText(item.book.title); hint = getString(R.string.library_rename_title_hint)
        }
        val authorInput = android.widget.EditText(ctx).apply {
            setText(item.book.author); hint = getString(R.string.library_rename_author_hint)
        }
        val column = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (resources.displayMetrics.density * 24).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(titleInput)
            addView(authorInput)
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.library_rename_title)
            .setView(column)
            .setPositiveButton(R.string.library_rename_save) { _, _ ->
                val newTitle = titleInput.text.toString().trim().ifBlank { item.book.title }
                val newAuthor = authorInput.text.toString().trim().ifBlank { item.book.author }
                viewLifecycleOwner.lifecycleScope.launch {
                    container.bookRepository.updateBookDetails(item.book.id, newTitle, newAuthor)
                }
            }
            .setNegativeButton(R.string.library_cancel, null)
            .show()
    }

    private fun openCurrentBook(autoplay: Boolean = false) {
        (activity as? MainActivity)?.showPlayerTab()
        if (autoplay) container.narrator.togglePlayPause()
    }

    private fun enterSelectionMode() {
        adapter.setSelectionMode(true)
        binding.libraryActionMode.visibility = View.VISIBLE
        backCallback.isEnabled = true
        updateActionModeTitle()
    }

    private fun exitSelectionMode() {
        adapter.setSelectionMode(false)
        binding.libraryActionMode.visibility = View.GONE
        backCallback.isEnabled = false
    }

    private fun updateActionModeTitle() {
        val n = adapter.selectedIds().size
        binding.libraryActionMode.title = getString(R.string.library_selected_count, n)
        if (n == 0) exitSelectionMode()
    }

    private fun confirmDeleteSelected() {
        val ids = adapter.selectedIds().toList()
        if (ids.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setMessage(R.string.library_delete_selected)
            .setPositiveButton(R.string.library_delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    ids.forEach { container.bookRepository.deleteBook(it) }
                    exitSelectionMode()
                }
            }
            .setNegativeButton(R.string.library_cancel, null)
            .show()
    }

    private fun handlePickedUri(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = container.bookImporter.importFromUri(uri)) {
                is ImportResult.Inserted -> { /* list updates via StateFlow */ }
                is ImportResult.Duplicate -> promptDuplicate(result.existing, result.pending)
                is ImportResult.Failed -> toast(getString(R.string.import_failed, result.reason))
            }
        }
    }

    private fun promptDuplicate(existing: BookEntity, pending: PendingImport) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.dup_title)
            .setMessage(getString(R.string.dup_message, existing.title, existing.author))
            .setPositiveButton(R.string.dup_replace) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    container.bookImporter.confirmDuplicate(existing, pending, replace = true)
                }
            }
            .setNegativeButton(R.string.dup_keep_existing) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    container.bookImporter.confirmDuplicate(existing, pending, replace = false)
                }
            }
            .setCancelable(false)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }
}
