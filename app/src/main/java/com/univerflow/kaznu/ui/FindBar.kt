package com.univerflow.kaznu.ui

import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.univerflow.kaznu.R
import com.univerflow.kaznu.databinding.ActivityFileViewerBinding

/**
 * "Find in text" of the file viewer over the WebView's own find: the query, "3 of 12", the previous and
 * next match, and close. [text] gives a string in the app language; [count] formats "N of M".
 */
class FindBar(private val window: android.view.Window, private val binding: ActivityFileViewerBinding, private val text: (Int) -> String, private val count: (Int, Int) -> String) {
    /** Whether the open document has text to find (text files and Word; PDF pages and pictures are images). */
    var available = false

    val isOpen get() = binding.viewerFindBar.isVisible
    val query get() = binding.viewerFind.text?.toString().orEmpty()

    init {
        val web = binding.viewerWeb
        binding.viewerFind.doAfterTextChanged { find(it?.toString().orEmpty()) }
        binding.viewerFind.setOnEditorActionListener { _, _, _ -> web.findNext(true); true }
        binding.viewerFindNext.setOnClickListener { web.findNext(true) }
        binding.viewerFindPrevious.setOnClickListener { web.findNext(false) }
        binding.viewerFindClose.setOnClickListener { close() }
        web.setFindListener { active, matches, done ->
            if (!done || !isOpen) return@setFindListener
            binding.viewerFindCount.text = when {
                binding.viewerFind.text.isNullOrBlank() -> ""
                matches == 0 -> text(R.string.find_none)
                else -> count(active + 1, matches)
            }
        }
    }

    fun localize() {
        binding.viewerFind.hint = text(R.string.find_in_file)
        binding.viewerFindNext.contentDescription = text(R.string.find_next)
        binding.viewerFindPrevious.contentDescription = text(R.string.find_previous)
        binding.viewerFindClose.contentDescription = text(R.string.done)
    }

    /** Opens the bar with [query] found; an empty query brings up the keyboard. */
    fun open(query: String) {
        if (!available) return
        Motion.layout(binding.viewerRoot)
        binding.viewerFindBar.isVisible = true
        if (binding.viewerFind.text?.toString() != query) binding.viewerFind.setText(query) else find(query)
        binding.viewerFind.setSelection(binding.viewerFind.text?.length ?: 0)
        binding.viewerFind.requestFocus()
        if (query.isEmpty()) WindowCompat.getInsetsController(window, binding.viewerFind).show(WindowInsetsCompat.Type.ime())
    }

    fun close() {
        Motion.layout(binding.viewerRoot)
        binding.viewerFindBar.isVisible = false
        binding.viewerWeb.clearMatches()
        WindowCompat.getInsetsController(window, binding.viewerFind).hide(WindowInsetsCompat.Type.ime())
    }

    private fun find(query: String) {
        if (!isOpen) return
        if (query.isBlank()) { binding.viewerWeb.clearMatches(); binding.viewerFindCount.text = ""; return }
        binding.viewerWeb.findAllAsync(query.trim())
    }
}
