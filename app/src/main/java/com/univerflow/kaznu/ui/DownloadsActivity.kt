package com.univerflow.kaznu.ui

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.univerflow.kaznu.R
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.data.DownloadRepository
import com.univerflow.kaznu.data.DownloadTextIndex
import com.univerflow.kaznu.databinding.ActivityDownloadsBinding
import com.univerflow.kaznu.databinding.ItemDownloadBinding
import com.univerflow.kaznu.download.DownloadService
import com.univerflow.kaznu.domain.DownloadList
import com.univerflow.kaznu.domain.DownloadRecord
import com.univerflow.kaznu.domain.DownloadState
import com.univerflow.kaznu.domain.FileKind
import com.univerflow.kaznu.domain.FileKinds
import com.univerflow.kaznu.domain.TextSearch
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Downloads": every file saved through the app, with search (by name and course, and optionally inside the files'
 * text), type and course filters, progress and in-app viewing.
 */
class DownloadsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDownloadsBinding
    private lateinit var repository: DownloadRepository
    private lateinit var language: AppLanguage
    private val adapter = Adapter()
    private var query = ""
    private var filter: FileKind? = null
    private var subject: String? = null
    private var sort = R.id.downloads_sort_date
    private var records: List<DownloadRecord> = emptyList()
    /** How many records the last render put in the list, for the count line. */
    private var shown = 0
    private val missing = mutableSetOf<String>()
    private val checked = mutableSetOf<String>()
    private var searchOpen = false
    private var textSearch = false
    private val textIndex by lazy { DownloadTextIndex(this) }
    // The text of files read so far (bounded, least recently used first out) and the words found in each shown file.
    private val texts = DownloadList.TextCache()
    private var snippets: Map<String, TextSearch.Snippet> = emptyMap()
    private var indexJob: Job? = null
    private var indexProgress: Pair<Int, Int>? = null
    private var subjectNames: List<String> = emptyList()
    // Back closes an open search before leaving the screen.
    private val closeSearch = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = showSearch(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        AppLockSession.secondaryScreen(this, binding.root, ime = true)
        language = AppLanguage.fromCode(intent.getStringExtra(EXTRA_LANGUAGE))
        repository = DownloadRepository.get(this)
        restoreChoices(savedInstanceState)
        binding.downloadsToolbar.setNavigationOnClickListener { finish() }
        onBackPressedDispatcher.addCallback(this, closeSearch)
        binding.downloadsToolbar.setOnMenuItemClickListener(::menuAction)
        binding.downloadsList.layoutManager = LinearLayoutManager(this)
        binding.downloadsList.adapter = adapter
        binding.downloadsList.itemAnimator?.changeDuration = 0 // Progress updates must not flash the card.
        bindSearch()
        showSearch(searchOpen, focus = false)
        buildFilters()
        localize()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.records.collect { list ->
                    if (isFinishing || AppLockSession.locked) { finish(); return@collect }
                    records = list; buildSubjects(); render(); verify(list); readTexts()
                }
            }
        }
        lifecycleScope.launch {
            repository.awaitLoaded()
            textIndex.prune(repository.records.value.map { it.id }.toSet())
        }
        if (AppLockSession.locked) finish()
    }

    /** The query, filters, sort order and search options the screen had before a recreation. */
    private fun restoreChoices(savedInstanceState: Bundle?) {
        query = savedInstanceState?.getString("query").orEmpty()
        filter = savedInstanceState?.getString("filter")?.let { runCatching { FileKind.valueOf(it) }.getOrNull() }
        subject = savedInstanceState?.getString("subject")
        textSearch = savedInstanceState?.getBoolean("text_search") ?: false
        searchOpen = savedInstanceState?.getBoolean("search_open") ?: false || query.isNotEmpty()
        sort = savedInstanceState?.getInt("sort", R.id.downloads_sort_date) ?: R.id.downloads_sort_date
    }

    private fun menuAction(item: MenuItem): Boolean {
        if (item.itemId in listOf(R.id.downloads_sort_date, R.id.downloads_sort_name, R.id.downloads_sort_size)) {
            sort = item.itemId; item.isChecked = true; render(); return true
        }
        if (item.itemId == R.id.downloads_search_toggle) { showSearch(!searchOpen); return true }
        if (item.itemId != R.id.downloads_folder) return false
        try { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) } catch (_: ActivityNotFoundException) { message(R.string.open_failed) }
        return true
    }

    /** The search field and "also search inside files": each change filters the list and reads missing file texts. */
    private fun bindSearch() {
        binding.downloadsSearch.setText(query)
        binding.downloadsSearch.doAfterTextChanged { query = it?.toString().orEmpty(); render(); readTexts() }
        binding.downloadsTextSearch.isChecked = textSearch
        binding.downloadsTextSearch.setOnCheckedChangeListener { _, checked ->
            if (checked == textSearch) return@setOnCheckedChangeListener // Restored after rotation.
            textSearch = checked
            render(); readTexts()
        }
    }

    override fun onStart() {
        super.onStart()
        if (isFinishing || AppLockSession.locked) {
            if (!isFinishing) finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || AppLockSession.locked) return
        // Files can be moved or deleted in other apps while this screen is hidden.
        checked.clear()
        verify(records)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("query", query)
        outState.putString("filter", filter?.name)
        outState.putString("subject", subject)
        outState.putBoolean("text_search", textSearch)
        outState.putBoolean("search_open", searchOpen)
        outState.putInt("sort", sort)
        super.onSaveInstanceState(outState)
    }

    /** The search icon opens the search field and its "inside the files" switch; closing clears the query. */
    private fun showSearch(open: Boolean, focus: Boolean = true) {
        searchOpen = open
        closeSearch.isEnabled = open
        binding.downloadsSearchLayout.isVisible = open
        binding.downloadsTextSearch.isVisible = open
        val keyboard = WindowCompat.getInsetsController(window, binding.downloadsSearch)
        if (open && focus) {
            binding.downloadsSearch.requestFocus()
            keyboard.show(WindowInsetsCompat.Type.ime())
        } else if (!open) {
            keyboard.hide(WindowInsetsCompat.Type.ime())
            if (query.isNotEmpty()) binding.downloadsSearch.setText("")
        }
    }

    /** Course chips follow the list: "All courses" plus every course a file came from. */
    private fun buildSubjects() {
        val names = FileKinds.subjects(records)
        if (subject != null && subject !in names) subject = null
        binding.downloadsSubjectsScroll.isVisible = names.isNotEmpty()
        if (names == subjectNames && binding.downloadsSubjects.childCount == names.size + 1) return
        subjectNames = names
        binding.downloadsSubjects.removeAllViews()
        (listOf<String?>(null) + names).forEach { name ->
            val chip = LayoutInflater.from(this).inflate(R.layout.item_filter_chip, binding.downloadsSubjects, false) as Chip
            chip.id = android.view.View.generateViewId()
            chip.text = name ?: text(R.string.filter_all_subjects)
            chip.isChecked = name == subject
            chip.setOnCheckedChangeListener { _, isChecked -> if (isChecked) { subject = name; render() } }
            binding.downloadsSubjects.addView(chip)
        }
    }

    /**
     * Reads unread file texts one at a time while the search looks inside files. The list is rebuilt only when a file
     * matches, and progress is throttled: otherwise a large library redraws a thousand cards per document.
     */
    private fun readTexts() {
        if (!textSearch || query.isBlank() || indexJob?.isActive == true) return
        fun pending() = records.filter { it.id !in texts && it.id !in missing && textIndex.searchable(it) }
        if (pending().isEmpty()) return
        indexJob = lifecycleScope.launch {
            // Files finished while reading are picked up by the next round.
            while (true) {
                val round = pending().takeIf { it.isNotEmpty() } ?: break
                val terms = query
                round.forEachIndexed { index, record ->
                    if (terms != query) return@forEachIndexed // Another query: this round's texts are stale.
                    indexProgress = index to round.size
                    val entry = texts.put(record.id, DownloadList.FileText(textIndex.text(record) ?: ""))
                    if (TextSearch.matches(entry.folded, terms) || FileKinds.matchesQuery(record, terms)) render()
                    else renderSubtitle()
                }
            }
            indexProgress = null
            render()
        }
    }

    /** The app language's resources; built once, because card binding asks for strings per visible item. */
    private val localized by lazy { language.context(this) }

    private fun text(id: Int) = localized.getString(id)

    private fun buildFilters() {
        val kinds = listOf<FileKind?>(null, FileKind.PDF, FileKind.WORD, FileKind.EXCEL, FileKind.POWERPOINT, FileKind.IMAGE, FileKind.OTHER)
        kinds.forEach { kind ->
            val chip = LayoutInflater.from(this).inflate(R.layout.item_filter_chip, binding.downloadsFilters, false) as Chip
            chip.id = android.view.View.generateViewId()
            chip.tag = kind
            chip.isChecked = kind == filter
            chip.setOnCheckedChangeListener { _, isChecked -> if (isChecked) { filter = kind; render() } }
            binding.downloadsFilters.addView(chip)
        }
    }

    private fun localize() {
        binding.downloadsToolbar.title = text(R.string.downloads)
        binding.downloadsToolbar.navigationContentDescription = text(R.string.back)
        binding.downloadsToolbar.menu.findItem(R.id.downloads_folder).title = text(R.string.open_folder)
        listOf(R.id.downloads_sort_date to R.string.sort_date, R.id.downloads_sort_name to R.string.sort_name,
            R.id.downloads_sort_size to R.string.sort_size).forEach { (id, label) ->
            binding.downloadsToolbar.menu.findItem(id).apply { title = text(label); isChecked = id == sort }
        }
        binding.downloadsSearch.hint = text(R.string.downloads_search)
        binding.downloadsSearchLayout.endIconContentDescription = text(R.string.clear_search)
        binding.downloadsToolbar.menu.findItem(R.id.downloads_search_toggle).title = text(R.string.search)
        binding.downloadsTextSearch.text = text(R.string.search_in_text)
        for (index in 0 until binding.downloadsFilters.childCount) {
            val chip = binding.downloadsFilters.getChildAt(index) as Chip
            chip.text = when (chip.tag as FileKind?) {
                null -> text(R.string.filter_all)
                FileKind.PDF -> "PDF"
                FileKind.WORD -> "Word"
                FileKind.EXCEL -> "Excel"
                FileKind.POWERPOINT -> "PowerPoint"
                FileKind.IMAGE -> text(R.string.filter_images)
                else -> text(R.string.filter_other)
            }
        }
    }

    private fun render() {
        if (AppLockSession.locked) { finish(); return }
        val inside = textSearch && query.isNotBlank()
        val order = when (sort) { R.id.downloads_sort_name -> DownloadList.Order.NAME; R.id.downloads_sort_size -> DownloadList.Order.SIZE; else -> DownloadList.Order.DATE }
        val view = DownloadList.view(records, query, filter, subject, texts.takeIf { inside }?.snapshot(), order)
        val visible = view.records
        shown = visible.size
        val changedSnippets = view.snippets != snippets
        snippets = view.snippets
        // Snippets are not part of the records, so cards whose found words changed are redrawn explicitly.
        adapter.submitList(visible) { if (changedSnippets) adapter.notifyItemRangeChanged(0, adapter.itemCount) }
        renderSubtitle()
        binding.downloadsEmpty.isVisible = visible.isEmpty()
        binding.downloadsEmptyText.text = text(if (records.isEmpty()) R.string.downloads_empty else R.string.downloads_no_match)
    }

    /** The count line above the list: how many files are shown, or how much of the library has been read. */
    private fun renderSubtitle() {
        val inside = textSearch && query.isNotBlank()
        binding.downloadsToolbar.subtitle = indexProgress?.takeIf { inside }?.let { (done, total) -> localized.getString(R.string.text_indexing, done, total) }
            ?: if (shown == records.size) localized.getString(R.string.downloads_count, records.size)
            else localized.getString(R.string.downloads_shown, shown, records.size)
    }

    private fun verify(list: List<DownloadRecord>) {
        val pending = list.filter { it.state == DownloadState.COMPLETED && it.id !in checked }
        if (pending.isEmpty()) return
        checked.addAll(pending.map { it.id })
        lifecycleScope.launch {
            val absent = withContext(Dispatchers.IO) { pending.filterNot { FileActions.exists(this@DownloadsActivity, it) }.map { it.id }.toSet() }
            val changed = pending.map { it.id }.filter { (it in absent) != (it in missing) }
            pending.forEach { if (it.id in absent) missing.add(it.id) else missing.remove(it.id) }
            if (changed.isNotEmpty()) adapter.notifyItemRangeChanged(0, adapter.itemCount)
        }
    }

    private fun available(record: DownloadRecord) = record.state == DownloadState.COMPLETED && record.uri != null && record.id !in missing

    private fun open(record: DownloadRecord) {
        if (isFinishing || AppLockSession.locked) return
        if (!available(record)) { if (record.state == DownloadState.COMPLETED) message(R.string.state_missing); return }
        // A file found by its text opens with the found word highlighted, spelled as in the file (the viewer's find
        // matches a whole phrase literally, while the list matched each word with "ё" folded).
        val found = snippets[record.id]?.let { it.text.substring(it.start, it.end) }
        startActivity(FileViewerActivity.intent(this, record.id, language, found))
    }

    private fun showActions(anchor: android.view.View, record: DownloadRecord) {
        PopupMenu(this, anchor).apply {
            if (available(record)) {
                menu.add(0, 1, 0, localized.getString(R.string.open_inside))
                menu.add(0, 2, 1, localized.getString(R.string.open_external))
                menu.add(0, 3, 2, localized.getString(R.string.share))
            }
            menu.add(0, 4, 3, localized.getString(R.string.remove_from_list))
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> open(record)
                    2 -> if (!FileActions.openExternal(this@DownloadsActivity, record)) message(R.string.open_failed)
                    3 -> if (!FileActions.share(this@DownloadsActivity, record, language)) message(R.string.open_failed)
                    4 -> { repository.remove(record.id); textIndex.forget(record.id); texts.remove(record.id); message(R.string.removed_from_list) }
                }
                true
            }
        }.show()
    }

    private fun message(id: Int) = Snackbar.make(binding.root, text(id), Snackbar.LENGTH_LONG).show()

    /**
     * The date and time of a card. `SimpleDateFormat` is not cheap to build and a list of a thousand cards formats
     * on every scroll, so one formatter lives as long as the screen's language does.
     */
    private var dateFormat: SimpleDateFormat? = null
    private var dateFormatLocale: String? = null

    private fun formatDate(milliseconds: Long): String {
        val tag = language.localeCode
        val format = dateFormat?.takeIf { dateFormatLocale == tag }
            ?: SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.forLanguageTag(tag)).also { dateFormat = it; dateFormatLocale = tag }
        return format.format(Date(milliseconds))
    }

    private fun status(record: DownloadRecord): String {
        val parts = mutableListOf<String>()
        parts += when (record.state) {
            DownloadState.RUNNING -> {
                val received = FileKinds.size(record.received, language)
                record.progress?.let { localized.getString(R.string.state_running_progress, it, received, FileKinds.size(record.total, language)) }
                    ?: localized.getString(R.string.state_running_bytes, received)
            }
            DownloadState.COMPLETED -> if (record.id in missing) localized.getString(R.string.state_missing)
                else FileKinds.size(record.total.takeIf { it >= 0 } ?: record.received, language)
            DownloadState.FAILED -> localized.getString(R.string.state_failed) + ". " + localized.getString(DownloadService.failureMessage(record.failure))
            DownloadState.CANCELED -> localized.getString(R.string.state_canceled)
        }
        parts += formatDate(record.finished ?: record.started)
        if (record.source.isNotBlank()) parts += record.source
        return parts.filter { it.isNotBlank() }.joinToString(" · ")
    }

    private inner class Holder(val item: ItemDownloadBinding) : RecyclerView.ViewHolder(item.root)

    private inner class Adapter : ListAdapter<DownloadRecord, Holder>(object : DiffUtil.ItemCallback<DownloadRecord>() {
        override fun areItemsTheSame(old: DownloadRecord, new: DownloadRecord) = old.id == new.id
        override fun areContentsTheSame(old: DownloadRecord, new: DownloadRecord) = old == new
    }) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val record = getItem(position)
            val view = holder.item
            val color = getColor(when (record.kind) {
                FileKind.PDF -> R.color.kind_pdf
                FileKind.WORD -> R.color.kind_word
                FileKind.EXCEL -> R.color.kind_excel
                FileKind.POWERPOINT -> R.color.kind_powerpoint
                FileKind.IMAGE -> R.color.kind_image
                else -> R.color.kind_other
            })
            view.downloadBadge.text = FileKinds.badge(record.kind)
            view.downloadBadge.setTextColor(color)
            view.downloadBadge.backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, 0x24))
            view.downloadName.text = record.fileName.ifBlank { "…" }
            val meta = status(record)
            view.downloadMeta.text = meta
            val snippet = snippets[record.id]
            view.downloadSnippet.isVisible = snippet != null
            if (snippet != null) view.downloadSnippet.text = SpannableString(snippet.text).apply {
                setSpan(StyleSpan(Typeface.BOLD), snippet.start, snippet.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(BackgroundColorSpan(ColorUtils.setAlphaComponent(getColor(R.color.kind_word), 0x33)), snippet.start, snippet.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            view.downloadProgress.isVisible = record.state == DownloadState.RUNNING
            record.progress.let { progress ->
                view.downloadProgress.isIndeterminate = progress == null
                if (progress != null) view.downloadProgress.setProgressCompat(progress, false)
            }
            view.downloadCard.contentDescription = listOfNotNull(view.downloadName.text.toString(), meta, snippet?.text).joinToString(". ")
            view.downloadCard.setOnClickListener { open(record) }
            if (record.state == DownloadState.RUNNING) {
                view.downloadAction.setIconResource(R.drawable.ic_close)
                view.downloadAction.contentDescription = text(R.string.cancel_download)
                view.downloadAction.setOnClickListener { DownloadService.cancel(this@DownloadsActivity, record.id) }
            } else {
                view.downloadAction.setIconResource(R.drawable.ic_more)
                view.downloadAction.contentDescription = text(R.string.file_actions)
                view.downloadAction.setOnClickListener { showActions(it, record) }
            }
        }
    }

    companion object {
        private const val EXTRA_LANGUAGE = "language"
        fun intent(context: Context, language: AppLanguage): Intent =
            Intent(context, DownloadsActivity::class.java).putExtra(EXTRA_LANGUAGE, language.siteCode)
    }
}
