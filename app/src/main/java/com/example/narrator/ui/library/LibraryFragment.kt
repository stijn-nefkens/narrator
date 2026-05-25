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
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
import com.google.android.material.snackbar.Snackbar
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
        attachSwipeToDelete()

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

        binding.libraryActionMode.setNavigationOnClickListener { exitSelectionMode() }
        binding.libraryActionMode.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_delete -> { confirmDeleteSelected(); true }
                R.id.action_edit -> { editSingleSelected(); true }
                R.id.action_select_all -> {
                    adapter.selectAll()
                    updateActionModeTitle()
                    true
                }
                else -> false
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.bookRepository.books.collect { books ->
                    allBooks = books
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
        val base = if (pendingDeleteIds.isEmpty()) allBooks
            else allBooks.filterNot { it.book.id in pendingDeleteIds }
        val filtered = if (q.isBlank()) base else base.filter { item ->
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
        // Always: enter selection mode and tick this row. No intermediate menu.
        // (Edit moved to the pen icon in the action mode toolbar; Delete is via swipe
        // for a single book or via the action-mode trash for many.)
        if (!adapter.selectionEnabled) enterSelectionMode()
        adapter.toggleSelected(item)
        updateActionModeTitle()
    }

    private fun editSingleSelected() {
        val ids = adapter.selectedIds()
        if (ids.size != 1) return
        val target = allBooks.firstOrNull { it.book.id == ids.first() } ?: return
        openRenameDialog(target)
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

    private fun enterSelectionMode() {
        adapter.setSelectionMode(true)
        binding.libraryActionMode.visibility = View.VISIBLE
        backCallback.isEnabled = true
        // Don't call updateActionModeTitle here — the caller toggles AFTER entering mode,
        // and updateActionModeTitle would see 0 selected and immediately exit again.
    }

    private fun exitSelectionMode() {
        adapter.setSelectionMode(false)
        binding.libraryActionMode.visibility = View.GONE
        backCallback.isEnabled = false
    }

    private fun updateActionModeTitle() {
        val n = adapter.selectedIds().size
        binding.libraryActionMode.title = getString(R.string.library_selected_count, n)
        // Edit pen only makes sense for a single selection. MaterialToolbar caches the menu
        // layout, so visibility changes need invalidateMenu() to actually take effect.
        binding.libraryActionMode.menu.findItem(R.id.action_edit)?.isVisible = (n == 1)
        binding.libraryActionMode.invalidateMenu()
        if (n == 0) exitSelectionMode()
    }

    private fun confirmDeleteSelected() {
        val ids = adapter.selectedIds().toList()
        if (ids.isEmpty()) return
        // For multi-delete the confirm step is more important — easier to misjudge how
        // many rows you've ticked, and one Snackbar can't undo N independent deletes.
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

    /** Left-swipe = delete with Snackbar undo. The row disappears from the list
     *  immediately so the gesture feels decisive. A 5-second Snackbar with Undo lets the
     *  user reverse it; if it times out we actually delete from the DB. Pending deletes
     *  are queued by book id so multiple rapid swipes each get their own undo. */
    private fun attachSwipeToDelete() {
        val callback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean = false

            override fun getSwipeDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ): Int = if (adapter.selectionEnabled) 0 else ItemTouchHelper.LEFT

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val pos = viewHolder.bindingAdapterPosition
                val item = adapter.itemAt(pos) ?: return
                queueDeleteWithUndo(item.book.id, item.book.title)
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.libraryList)
    }

    /** Books waiting on a possible Undo; the StateFlow filters them out of the visible
     *  list, and the dispose callback either commits the delete or restores them. */
    private val pendingDeleteIds: MutableSet<Long> = mutableSetOf()

    private fun queueDeleteWithUndo(bookId: Long, title: String) {
        pendingDeleteIds.add(bookId)
        applyFilterSort()  // hide the row now
        val snack = Snackbar.make(
            binding.root,
            getString(R.string.library_deleted_one, title),
            Snackbar.LENGTH_LONG,
        )
        var undone = false
        snack.setAction(R.string.library_undo) {
            undone = true
            pendingDeleteIds.remove(bookId)
            applyFilterSort()
        }
        snack.addCallback(object : Snackbar.Callback() {
            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                if (!undone && pendingDeleteIds.remove(bookId)) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        container.bookRepository.deleteBook(bookId)
                    }
                }
            }
        })
        snack.show()
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
