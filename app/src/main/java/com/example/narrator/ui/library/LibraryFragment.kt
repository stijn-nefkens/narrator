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
import com.example.narrator.data.ChapterPreview
import com.example.narrator.data.LibrarySortOrder
import com.example.narrator.data.PendingImport
import com.example.narrator.data.PrepareResult
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
            openDocument.launch(
                arrayOf(
                    "application/epub+zip",
                    "application/pdf",
                    "application/octet-stream",
                )
            )
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
        val pad = (resources.displayMetrics.density * 24).toInt()
        val titleInput = android.widget.EditText(ctx).apply {
            setText(item.book.title); hint = getString(R.string.library_rename_title_hint)
        }
        val authorInput = android.widget.EditText(ctx).apply {
            setText(item.book.author); hint = getString(R.string.library_rename_author_hint)
        }
        val skipLabel = android.widget.TextView(ctx).apply {
            text = getString(R.string.library_skip_patterns_label)
            setPadding(0, pad, 0, 4)
        }
        val skipHelp = android.widget.TextView(ctx).apply {
            text = getString(R.string.library_skip_patterns_help)
            textSize = 12f
            setPadding(0, 0, 0, 4)
        }
        val skipInput = android.widget.EditText(ctx).apply {
            setText(item.book.skipPatterns)
            hint = getString(R.string.library_skip_patterns_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            isSingleLine = false
            minLines = 2
            setHorizontallyScrolling(false)
        }
        val column = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(titleInput)
            addView(authorInput)
            addView(skipLabel)
            addView(skipHelp)
            addView(skipInput)
        }
        val scroll = android.widget.ScrollView(ctx).apply { addView(column) }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.library_rename_title)
            .setView(scroll)
            .setPositiveButton(R.string.library_rename_save) { _, _ ->
                val newTitle = titleInput.text.toString().trim().ifBlank { item.book.title }
                val newAuthor = authorInput.text.toString().trim().ifBlank { item.book.author }
                val newSkip = skipInput.text.toString().trim()
                viewLifecycleOwner.lifecycleScope.launch {
                    container.bookRepository.updateBookDetails(item.book.id, newTitle, newAuthor)
                    if (newSkip != item.book.skipPatterns) {
                        container.bookRepository.updateSkipPatterns(item.book.id, newSkip)
                        // Re-parse next time the book is loaded by tapping; if it's the
                        // currently loaded book, reload it now so the new patterns take effect.
                        if (container.narrator.state.value.loaded?.bookId == item.book.id) {
                            container.narrator.loadBook(item.book.id)
                        }
                    }
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
            // For PDFs: peek the page count and offer a range selector before parsing.
            // Other formats skip straight to the parse step.
            val pageCount = container.bookImporter.peekPdfPageCount(uri)
            if (pageCount != null && pageCount > 1) {
                askPageRangeAndPrepare(uri, pageCount)
            } else {
                runPrepare(uri, pageRange = null)
            }
        }
    }

    /** Shows a PDF page-range picker. Empty fields = "all pages". On confirm, kicks off
     *  the prepare/preview flow with the selected range. */
    private fun askPageRangeAndPrepare(uri: Uri, pageCount: Int) {
        val ctx = requireContext()
        val pad = (resources.displayMetrics.density * 24).toInt()
        val startInput = android.widget.EditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "1"
        }
        val endInput = android.widget.EditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = pageCount.toString()
        }
        val row = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            addView(startInput, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
            val sep = android.widget.TextView(ctx).apply {
                text = " – "
                setPadding(pad / 2, 0, pad / 2, 0)
            }
            addView(sep)
            addView(endInput, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
        }
        val wrapper = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(android.widget.TextView(ctx).apply {
                text = getString(R.string.import_page_range_hint, pageCount)
            })
            addView(row)
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.import_page_range_title)
            .setView(wrapper)
            .setPositiveButton(R.string.import_continue) { _, _ ->
                val start = startInput.text.toString().toIntOrNull()
                val end = endInput.text.toString().toIntOrNull()
                val range = when {
                    start == null && end == null -> null
                    else -> {
                        val s = (start ?: 1).coerceIn(1, pageCount)
                        val e = (end ?: pageCount).coerceIn(s, pageCount)
                        s..e
                    }
                }
                viewLifecycleOwner.lifecycleScope.launch { runPrepare(uri, range) }
            }
            .setNeutralButton(R.string.import_all_pages) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch { runPrepare(uri, null) }
            }
            .setNegativeButton(R.string.library_cancel, null)
            .show()
    }

    /** Runs the prepare step (copy + parse) and routes the result into preview or dup
     *  dialogs. */
    private suspend fun runPrepare(uri: Uri, pageRange: IntRange?) {
        when (val r = container.bookImporter.prepareImport(uri, pageRange)) {
            is PrepareResult.Ready -> showImportPreview(r.title, r.author, r.chapterPreviews, r.pending)
            is PrepareResult.Duplicate -> promptDuplicate(r.existing, r.pending)
            is PrepareResult.Failed -> toast(getString(R.string.import_failed, r.reason))
        }
    }

    /** Preview dialog listing the parsed chapters with their first ~160 characters of body.
     *  Lets the user back out if extraction looks broken before committing the book. */
    private fun showImportPreview(
        title: String,
        author: String,
        chapters: List<ChapterPreview>,
        pending: PendingImport,
    ) {
        val ctx = requireContext()
        val pad = (resources.displayMetrics.density * 16).toInt()
        val column = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            for (ch in chapters.take(20)) {
                addView(android.widget.TextView(ctx).apply {
                    text = "• ${ch.title} (${ch.chunkCount})"
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, pad / 2, 0, 0)
                })
                if (ch.firstChars.isNotBlank()) {
                    addView(android.widget.TextView(ctx).apply {
                        text = ch.firstChars + if (ch.firstChars.length >= 160) "…" else ""
                        textSize = 13f
                        setPadding(0, 2, 0, 0)
                    })
                }
            }
            if (chapters.size > 20) {
                addView(android.widget.TextView(ctx).apply {
                    text = getString(R.string.import_preview_more, chapters.size - 20)
                    setPadding(0, pad, 0, 0)
                })
            }
        }
        val scroll = android.widget.ScrollView(ctx).apply { addView(column) }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.import_preview_title_format, title, author))
            .setView(scroll)
            .setPositiveButton(R.string.import_add_to_library) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch { container.bookImporter.commit(pending) }
            }
            .setNegativeButton(R.string.library_cancel) { _, _ ->
                container.bookImporter.cancel(pending)
            }
            .setOnCancelListener { container.bookImporter.cancel(pending) }
            .show()
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
