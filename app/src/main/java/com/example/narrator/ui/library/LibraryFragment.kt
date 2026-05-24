package com.example.narrator.ui.library

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.narrator.MainActivity
import com.example.narrator.NarratorApp
import com.example.narrator.R
import com.example.narrator.data.BookEntity
import com.example.narrator.data.BookWithProgress
import com.example.narrator.data.ImportResult
import com.example.narrator.data.PendingImport
import com.example.narrator.databinding.FragmentLibraryBinding
import kotlinx.coroutines.launch

class LibraryFragment : Fragment() {
    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private val container get() = (requireActivity().application as NarratorApp).container

    private val openDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? -> uri?.let(::handlePickedUri) }

    private lateinit var adapter: BookAdapter

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
            onClick = ::openBook,
            onLongClick = ::confirmDelete,
        )
        binding.libraryList.layoutManager = LinearLayoutManager(requireContext())
        binding.libraryList.adapter = adapter

        binding.libraryFab.setOnClickListener {
            openDocument.launch(arrayOf("application/epub+zip", "application/octet-stream"))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                container.bookRepository.books.collect(::render)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun render(books: List<BookWithProgress>) {
        binding.libraryEmpty.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
        binding.libraryList.visibility = if (books.isEmpty()) View.GONE else View.VISIBLE
        adapter.submitList(books)
    }

    private fun openBook(item: BookWithProgress) {
        viewLifecycleOwner.lifecycleScope.launch {
            container.narrator.loadBook(item.book.id)
            (activity as? MainActivity)?.showPlayerTab()
        }
    }

    private fun confirmDelete(item: BookWithProgress) {
        AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.library_delete_confirm, item.book.title))
            .setPositiveButton(R.string.library_delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    container.bookRepository.deleteBook(item.book.id)
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
