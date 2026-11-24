package com.univerflow.kaznu.ui

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.MenuItem
import android.widget.PopupMenu
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.univerflow.kaznu.R
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.data.DownloadRepository
import com.univerflow.kaznu.data.DownloadTextIndex
import com.univerflow.kaznu.databinding.ActivityDownloadsBinding
import com.univerflow.kaznu.download.DownloadService
import com.univerflow.kaznu.domain.DownloadHistoryRules
import com.univerflow.kaznu.domain.DownloadList
import com.univerflow.kaznu.domain.DownloadRecord
import com.univerflow.kaznu.domain.DownloadState
import com.univerflow.kaznu.domain.FileKind
import com.univerflow.kaznu.domain.FileKinds
import com.univerflow.kaznu.domain.FilePresence
import com.univerflow.kaznu.domain.TextSearch
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Downloads": every file saved through the app, with search (by name and course, and optionally inside the files'
 * text), type and course filters, progress and in-app viewing.
 */
class DownloadsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDownloadsBinding
    private lateinit var repository: DownloadRepository
    private lateinit var language: AppLanguage
    private lateinit var adapter: DownloadsAdapter
    private var filter: FileKind? = null
    private var subject: String? = null
    private var sort = R.id.downloads_sort_date
    private var records: List<DownloadRecord> = emptyList()
    /** How many records the last render put in the list, for the count line. */
    private var shown = 0
    private val missing = mutableSetOf<String>()
    private val checked = mutableSetOf<String>()
    private var textSearch = false
    private val textIndex by lazy { DownloadTextIndex(this) }
    // The text of files read so far (bounded, least recently used first out) and the words found in each shown file.
    private val texts = DownloadList.TextCache()
    private var textScan = DownloadList.TextScan("")
    private val query get() = textScan.query
    private var snippets: Map<String, TextSearch.Snippet> = emptyMap()
    private var indexJob: Job? = null
    private var indexProgress: Pair<Int, Int>? = null
    private var shownCounts: Map<FileKind, Int>? = null
    /** The record whose file Android is asking the student about (a file saved by an earlier install). */
    private var consentFor: String? = null
    private val consent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val id = consentFor
        consentFor = null
        if (result.resultCode != RESULT_OK || id == null) return@registerForActivityResult
        lifecycleScope.launch {
            DownloadHistoryRules.findAfterLoad(id, repository::awaitLoaded, repository::find)?.let { deleteFile(it, approved = true) }
        }
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
        binding.downloadsToolbar.setOnMenuItemClickListener(::menuAction)
        binding.downloadsSubject.setOnClickListener(::showSubjects)
        binding.downloadsList.layoutManager = LinearLayoutManager(this)
        adapter = DownloadsAdapter(this, language, localized, Cards())
        binding.downloadsList.adapter = adapter
        binding.downloadsList.itemAnimator?.changeDuration = 0 // Progress updates must not flash the card.
        bindSearch()
        buildFilters()
        localize()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // After process death the list starts empty while the saved one loads: a filter or course restored with
                // the screen must not be pruned against that empty list, and "no downloads" must not flash.
                repository.awaitLoaded()
                repository.records.collect { list ->
                    if (isFinishing || AppLockSession.locked) { finish(); return@collect }
                    records = list; showKinds(); showSubject(); render(); verify(list); readTexts()
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
        textScan = DownloadList.TextScan(savedInstanceState?.getString("query").orEmpty())
        filter = savedInstanceState?.getString("filter")?.let { runCatching { FileKind.valueOf(it) }.getOrNull() }
        subject = savedInstanceState?.getString("subject")
        textSearch = savedInstanceState?.getBoolean("text_search") ?: false
        sort = savedInstanceState?.getInt("sort", R.id.downloads_sort_date) ?: R.id.downloads_sort_date
        consentFor = savedInstanceState?.getString("consent_for")
    }

    private fun menuAction(item: MenuItem): Boolean {
        if (item.itemId in listOf(R.id.downloads_sort_date, R.id.downloads_sort_name, R.id.downloads_sort_size)) {
            sort = item.itemId; item.isChecked = true; render(); return true
        }
        if (item.itemId != R.id.downloads_folder) return false
        try { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) } catch (_: ActivityNotFoundException) { message(R.string.open_failed) }
        return true
    }

    /** The search field and "also search inside files": each change filters the list and reads missing file texts. */
    private fun bindSearch() {
        binding.downloadsSearch.setText(query)
        binding.downloadsSearch.doAfterTextChanged {
            val terms = it?.toString().orEmpty()
            if (terms == query) return@doAfterTextChanged
            textScan = DownloadList.TextScan(terms)
            indexProgress = null
            showTextSearch(); render(); readTexts()
        }
        binding.downloadsTextSearch.isChecked = textSearch
        binding.downloadsTextSearch.setOnCheckedChangeListener { _, checked ->
            if (checked == textSearch) return@setOnCheckedChangeListener // Restored after rotation.
            textSearch = checked
            render(); readTexts()
        }
    }

    /** "Also search inside files" only means something once there is a query. */
    private fun showTextSearch() {
        val shown = query.isNotBlank() || textSearch
        if (binding.downloadsTextSearch.isVisible != shown) { Motion.layout(binding.downloadsRoot); binding.downloadsTextSearch.isVisible = shown }
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
        outState.putInt("sort", sort)
        outState.putString("consent_for", consentFor)
        super.onSaveInstanceState(outState)
    }

    /** The course button follows the list: "All courses", or the course chosen while it still has files. */
    private fun showSubject() {
        val names = FileKinds.subjects(records)
        if (subject != null && subject !in names) subject = null
        binding.downloadsSubject.isVisible = names.isNotEmpty()
        binding.downloadsSubject.text = subject ?: text(R.string.filter_all_subjects)
    }

    /** One menu of "All courses" and every course a file came from, each with its file count. */
    private fun showSubjects(anchor: android.view.View) {
        val names = listOf<String?>(null) + FileKinds.subjects(records)
        PopupMenu(this, anchor).apply {
            names.forEachIndexed { index, name ->
                val count = if (name == null) records.size else records.count { FileKinds.matchesSubject(it, name) }
                menu.add(0, index, index, "${name ?: text(R.string.filter_all_subjects)} · $count")
            }
            setOnMenuItemClickListener { item -> chooseSubject(names[item.itemId]); true }
        }.show()
    }

    /** The course the list shows; null is every course. */
    internal fun chooseSubject(name: String?) { subject = name; showSubject(); render() }

    /**
     * Reads unread file texts one at a time while the search looks inside files. The list is rebuilt only when a file
     * matches, and progress is throttled: otherwise a large library redraws a thousand cards per document.
     */
    private fun readTexts() {
        if (!textSearch || query.isBlank() || indexJob?.isActive == true) return
        fun searchable() = records.filter { it.id !in missing && textIndex.searchable(it) }
        if (textScan.pending(searchable(), texts).isEmpty()) return
        indexJob = lifecycleScope.launch {
            // Files finished while reading, and a query changed meanwhile, are picked up by the next round.
            while (textSearch && query.isNotBlank()) {
                val scan = textScan
                val round = scan.pending(searchable(), texts).takeIf { it.isNotEmpty() } ?: break
                for ((index, record) in round.withIndex()) {
                    indexProgress = index to round.size
                    val content = textIndex.text(record)
                    if (scan !== textScan || !textSearch) break
                    val entry = texts.put(record.id, DownloadList.FileText(content.orEmpty()))
                    val matched = scan.read(record.id, entry)
                    if (matched || FileKinds.matchesQuery(record, scan.query)) render()
                    else { texts.yieldTo(record.id); renderSubtitle() }
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

    /** Only the kinds the library holds get a filter; one that lost its last file falls back to "All". */
    private fun showKinds() {
        val present = records.mapTo(HashSet()) { FileKinds.filterKind(it.kind) }
        val chips = (0 until binding.downloadsFilters.childCount).map { binding.downloadsFilters.getChildAt(it) as Chip }
        if (filter != null && filter !in present) chips.first { it.tag == null }.isChecked = true
        chips.forEach { chip -> chip.isVisible = chip.tag == null || chip.tag in present }
        binding.downloadsFiltersScroll.isVisible = present.size > 1
        binding.downloadsSearchLayout.isVisible = records.isNotEmpty()
    }

    /** Each chip says how many files it would show, in a quieter colour than its name. */
    private fun showCounts() {
        val inside = textSearch && query.isNotBlank()
        val counts = DownloadList.kindCounts(records, query, subject, texts.takeIf { inside }?.snapshot(), textScan)
        // A running download renders the list every 250 ms: chips whose numbers stay are not re-laid out each time.
        if (counts == shownCounts) return
        shownCounts = counts
        val muted = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant)
        for (index in 0 until binding.downloadsFilters.childCount) {
            val chip = binding.downloadsFilters.getChildAt(index) as Chip
            val kind = chip.tag as FileKind?
            val name = kindName(kind)
            val count = if (kind == null) counts.values.sum() else counts[kind] ?: 0
            chip.text = SpannableString("$name  $count").apply {
                setSpan(ForegroundColorSpan(muted), name.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            chip.contentDescription = "$name: $count"
        }
    }

    private fun kindName(kind: FileKind?) = when (kind) {
        null -> text(R.string.filter_all)
        FileKind.PDF -> "PDF"
        FileKind.WORD -> "Word"
        FileKind.EXCEL -> "Excel"
        FileKind.POWERPOINT -> "PowerPoint"
        FileKind.IMAGE -> text(R.string.filter_images)
        else -> text(R.string.filter_other)
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
        binding.downloadsTextSearch.text = text(R.string.search_in_text)
        showTextSearch()
    }

    private fun render() {
        if (AppLockSession.locked) { finish(); return }
        val inside = textSearch && query.isNotBlank()
        val order = when (sort) { R.id.downloads_sort_name -> DownloadList.Order.NAME; R.id.downloads_sort_size -> DownloadList.Order.SIZE; else -> DownloadList.Order.DATE }
        val view = DownloadList.view(records, query, filter, subject, texts.takeIf { inside }?.snapshot(), order, textScan)
        val visible = view.records
        shown = visible.size
        snippets = view.snippets
        // The found words are part of each row, so the list diff redraws exactly the cards whose words changed.
        adapter.submitList(rows(visible, order))
        showCounts()
        renderSubtitle()
        Motion.show(binding.downloadsEmpty, visible.isEmpty())
        binding.downloadsEmptyText.text = text(if (records.isEmpty()) R.string.downloads_empty else R.string.downloads_no_match)
        binding.downloadsEmptyHint.text = text(R.string.downloads_empty_hint)
        binding.downloadsEmptyHint.isVisible = records.isEmpty()
    }

    /** Files by when they arrived, each group one sheet; a search or another order is one sheet of results. */
    private fun rows(files: List<DownloadRecord>, order: DownloadList.Order): List<DownloadRow> {
        val now = System.currentTimeMillis()
        fun period(record: DownloadRecord) = if (order == DownloadList.Order.DATE && query.isBlank()) DownloadList.period(record.displayTime, now) else null
        val list = ArrayList<DownloadRow>(files.size + 5)
        files.forEachIndexed { index, record ->
            val group = period(record)
            val first = index == 0 || period(files[index - 1]) != group
            if (first && group != null) list += DownloadRow.Heading(text(when (group) {
                DownloadList.Period.TODAY -> R.string.period_today
                DownloadList.Period.YESTERDAY -> R.string.period_yesterday
                DownloadList.Period.WEEK -> R.string.period_week
                DownloadList.Period.MONTH -> R.string.period_month
                DownloadList.Period.EARLIER -> R.string.period_earlier
            }))
            list += DownloadRow.File(record, first, index == files.lastIndex || period(files[index + 1]) != group, snippets[record.id])
        }
        return list
    }

    /** The count line above the list: how many files are shown, or how much of the library has been read. */
    private fun renderSubtitle() {
        val inside = textSearch && query.isNotBlank()
        binding.downloadsToolbar.subtitle = indexProgress?.takeIf { inside }?.let { (done, total) -> localized.getString(R.string.text_indexing, done, total) }
            ?: if (shown == records.size) localized.getString(R.string.downloads_count, records.size)
            else localized.getString(R.string.downloads_shown, shown, records.size)
    }

    /**
     * Files deleted, moved or renamed in other apps leave the list; each is checked again 1.5 s later, so a file another
     * app is replacing right now stays. A file the storage does not answer for stays too, marked unavailable.
     */
    private fun verify(list: List<DownloadRecord>) {
        val pending = list.filter { it.state == DownloadState.COMPLETED && it.id !in checked }
        if (pending.isEmpty()) return
        checked.addAll(pending.map { it.id })
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { pending.associateWith { FileActions.locate(this@DownloadsActivity, it) } }
            // Files saved before places were kept start from the place they have now.
            val places = withContext(Dispatchers.IO) {
                found.filter { (record, presence) -> presence == FilePresence.PRESENT && record.place == null }.keys
                    .mapNotNull { record -> record.uri?.let { FileActions.place(this@DownloadsActivity, it.toUri()) }?.let { record.id to it } }.toMap()
            }
            repository.keepPlaces(places)
            val unknown = found.filterValues { it == FilePresence.UNKNOWN }.keys.map { it.id }.toSet()
            val changed = pending.map { it.id }.filter { (it in unknown) != (it in missing) }
            pending.forEach { if (it.id in unknown) missing.add(it.id) else missing.remove(it.id) }
            if (changed.isNotEmpty()) adapter.notifyItemRangeChanged(0, adapter.itemCount)
            val first = found.filterValues { it == FilePresence.GONE }.keys
            if (first.isEmpty()) return@launch
            delay(RECHECK_MS)
            val gone = withContext(Dispatchers.IO) { first.filter { FileActions.locate(this@DownloadsActivity, it) == FilePresence.GONE } }
            repository.removeGone(gone)
            gone.forEach { textIndex.forget(it.id); texts.remove(it.id); textScan.forget(it.id) }
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
            if (available(record)) menu.add(0, 5, 4, localized.getString(R.string.delete_file))
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> open(record)
                    2 -> if (!FileActions.openExternal(this@DownloadsActivity, record)) message(R.string.open_failed)
                    3 -> if (!FileActions.share(this@DownloadsActivity, record, language)) message(R.string.open_failed)
                    4 -> { forget(record); message(R.string.removed_from_list) }
                    5 -> confirmDelete(record)
                }
                true
            }
        }.show()
    }

    private fun forget(record: DownloadRecord) {
        repository.remove(record.id); textIndex.forget(record.id); texts.remove(record.id); textScan.forget(record.id)
    }

    /** Deleting is final, so it is asked first, with the file's name. */
    private fun confirmDelete(record: DownloadRecord) {
        if (isFinishing || AppLockSession.locked) return
        MaterialAlertDialogBuilder(this)
            .setTitle(text(R.string.delete_file_title))
            .setMessage(localized.getString(R.string.delete_file_message, record.fileName))
            .setNegativeButton(text(R.string.cancel), null)
            .setPositiveButton(text(R.string.delete_file_action)) { _, _ -> deleteFile(record) }
            .show()
    }

    /** [approved]: Android's own question was answered yes; on Android 11+ it has deleted the file itself. */
    private fun deleteFile(record: DownloadRecord, approved: Boolean = false) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { FileActions.delete(this@DownloadsActivity, record) }
            if (isFinishing) return@launch
            when (result) {
                FileActions.Deletion.Deleted -> { forget(record); message(R.string.file_deleted) }
                FileActions.Deletion.Gone -> { forget(record); message(if (approved) R.string.file_deleted else R.string.file_already_gone) }
                is FileActions.Deletion.Consent -> if (approved) message(R.string.file_delete_failed) else {
                    consentFor = record.id
                    try { consent.launch(IntentSenderRequest.Builder(result.request).build()) }
                    catch (_: Exception) { consentFor = null; message(R.string.file_delete_failed) }
                }
                FileActions.Deletion.Changed -> message(R.string.file_delete_changed)
                FileActions.Deletion.Failed -> message(R.string.file_delete_failed)
            }
        }
    }

    private fun message(id: Int) = Snackbar.make(binding.root, text(id), Snackbar.LENGTH_LONG).show()

    /** What the list's cards ask of the screen. */
    private inner class Cards : DownloadsAdapter.Host {
        override fun missing(id: String) = id in missing
        override fun open(record: DownloadRecord) = this@DownloadsActivity.open(record)
        override fun actions(anchor: android.view.View, record: DownloadRecord) = showActions(anchor, record)
        override fun cancel(record: DownloadRecord) = DownloadService.cancel(this@DownloadsActivity, record.id)
    }

    companion object {
        private const val EXTRA_LANGUAGE = "language"
        private const val RECHECK_MS = 1_500L
        fun intent(context: Context, language: AppLanguage): Intent =
            Intent(context, DownloadsActivity::class.java).putExtra(EXTRA_LANGUAGE, language.siteCode)
    }
}
