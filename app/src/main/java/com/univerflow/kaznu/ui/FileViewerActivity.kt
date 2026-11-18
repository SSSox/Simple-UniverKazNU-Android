package com.univerflow.kaznu.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintManager
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.univerflow.kaznu.R
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.data.AppPreferences
import com.univerflow.kaznu.data.DownloadRepository
import com.univerflow.kaznu.databinding.ActivityFileViewerBinding
import com.univerflow.kaznu.domain.DownloadRecord
import com.univerflow.kaznu.domain.DownloadState
import com.univerflow.kaznu.domain.FileKind
import com.univerflow.kaznu.domain.FileKinds
import com.univerflow.kaznu.domain.TextZoom
import com.univerflow.kaznu.preview.Bounded
import com.univerflow.kaznu.preview.ChannelSource
import com.univerflow.kaznu.preview.DeferredStream
import com.univerflow.kaznu.preview.DocumentTables
import com.univerflow.kaznu.preview.FileAccess
import com.univerflow.kaznu.preview.FileMedia
import com.univerflow.kaznu.preview.HtmlText
import com.univerflow.kaznu.preview.HtmlView
import com.univerflow.kaznu.preview.PdfPages
import com.univerflow.kaznu.preview.PreviewHtml
import com.univerflow.kaznu.preview.PreviewLabels
import com.univerflow.kaznu.preview.PreviewLimitException
import com.univerflow.kaznu.preview.SheetView
import com.univerflow.kaznu.preview.SlideView
import com.univerflow.kaznu.preview.TextPreview
import com.univerflow.kaznu.preview.UnsupportedDocumentException
import com.univerflow.kaznu.preview.WordView
import com.univerflow.kaznu.web.configureOfflineViewer
import com.univerflow.kaznu.web.RetiredWebViewClient
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * In-app viewer for downloaded files, served from a private origin with JavaScript, file access and network off.
 * Below 100% zoom the page is laid out wider instead of shrunk; tables can open on their own screen.
 */
class FileViewerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFileViewerBinding
    private var language = AppLanguage.RUSSIAN
    @Volatile private var record: DownloadRecord? = null
    /** The document page for WebView, drawn when WebView reads it; a PDF is written while it is read. */
    @Volatile private var page: (() -> InputStream)? = null
    /** The table on its own screen: its page (drawn when read) and the WebView showing it; [tableIndex] is -1 while none is open. */
    @Volatile private var tablePage: (() -> InputStream)? = null
    /** The copy of the document being printed and the WebView loading it ([print]). */
    @Volatile private var printPage: (() -> InputStream)? = null
    private var printWeb: WebView? = null
    private var tableWeb: WebView? = null
    @Volatile private var tableIndex = -1
    /** The table that was open before a rotation, opened again once the document is ready. */
    private var restoreTable = -1
    private val closeTableOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeTable()
    }
    @Volatile private var pdf: PdfPages? = null
    @Volatile private var pageWidth = 1080
    @Volatile private var media: FileMedia? = null
    private var messageId: Int? = null
    /** The open document, drawn again for a new layout zoom or page width. */
    private var current: Page? = null
    private var layoutZoom = 100
    private var paperA4 = true
    private var textZoom = TextZoom.DEFAULT
    private var baseTextZoom = 100
    private var previewStarted = false
    private var webGone = false
    private lateinit var findBar: FindBar
    /** Words to find once the page has its text: from the search in Downloads, the open search before a rotation or a redraw. */
    private var findQuery: String? = null
    /** "Zoom out" was tapped: the step that reaches the page's limit settles exactly on it. */
    private var zoomingOut = false
    /** Where to return after a redraw, as a share of the page's height. */
    private var restoreFraction: Float? = null
    /** Two-finger zoom of the page: WebView's own above 100%, the viewer's layout zoom at or below it. */
    private lateinit var pinch: LayoutPinch
    private val hideZoom = Runnable { Motion.show(binding.viewerZoomLevel, false) }
    private val languages = LanguageResources(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        layoutZoom = savedInstanceState?.getInt("layout_zoom", 100) ?: 100
        restoreTable = savedInstanceState?.getInt("table", -1) ?: -1
        // A search in Downloads opens the file with its words found; a rotation keeps the open search.
        findQuery = savedInstanceState?.getString("find") ?: intent.getStringExtra(EXTRA_QUERY)?.takeIf { it.isNotBlank() }
        binding = ActivityFileViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        AppLockSession.secondaryScreen(this, binding.root)
        findBar = FindBar(window, binding, ::text) { active, count -> labels().getString(R.string.find_count, active, count) }
        pageWidth = (resources.displayMetrics.widthPixels * 1.5f).toInt()
        binding.viewerToolbar.setNavigationOnClickListener { if (tableWeb != null) closeTable() else finish() }
        onBackPressedDispatcher.addCallback(this, closeTableOnBack)
        binding.viewerToolbar.setOnMenuItemClickListener { item ->
            val current = record ?: return@setOnMenuItemClickListener false
            when (item.itemId) {
                R.id.viewer_find_toggle -> { findBar.open(findBar.query); true }
                R.id.viewer_external -> { if (!FileActions.openExternal(this, current)) message(R.string.open_failed); true }
                R.id.viewer_share -> { if (!FileActions.share(this, current, language)) message(R.string.open_failed); true }
                R.id.viewer_print -> { print(current); true }
                R.id.viewer_zoom_in -> { zoomIn(); true }
                R.id.viewer_zoom_out -> { zoomOut(); true }
                R.id.viewer_page_a4 -> { setPaperA4(!paperA4); true }
                else -> false
            }
        }
        // The page background before the first paint, so the dark theme never flashes white.
        binding.viewerWeb.setBackgroundColor(MaterialColors.getColor(binding.viewerWeb, android.R.attr.colorBackground))
        binding.viewerOpenExternal.setOnClickListener { record?.let { if (!FileActions.openExternal(this, it)) message(R.string.open_failed) } }
        configureWeb(binding.viewerWeb)
        pinch = LayoutPinch(binding.viewerWeb, layoutZoom = { layoutZoom }, preview = ::showZoom, settle = { step ->
            if (step != layoutZoom) { layoutZoom = step; redraw() }
            showZoom(step)
        }).also { it.attach() }
        localize()
        if (AppLockSession.locked) finish()
        // Pictures of viewers that never closed (the process was stopped) are removed after a while.
        val folder = File(cacheDir, MEDIA_FOLDER)
        Thread { folder.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > STALE_MEDIA }?.forEach { it.deleteRecursively() } }.start()
    }

    override fun onStart() {
        super.onStart()
        // onStart applies the background timeout before any file bytes are read.
        if (isFinishing || AppLockSession.locked) {
            if (!isFinishing) finish()
            return
        }
        if (previewStarted) return
        previewStarted = true
        val requested = intent.getStringExtra(EXTRA_LANGUAGE)
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching { AppPreferences(applicationContext).let { Triple(it.language, it.previewPageA4, it.textZoom) } }.getOrNull()
            }
            language = if (requested != null) AppLanguage.fromCode(requested) else saved?.first ?: AppLanguage.RUSSIAN
            paperA4 = saved?.second ?: true
            // The app's text size reaches downloaded files too, as it does the website.
            textZoom = saved?.third ?: TextZoom.DEFAULT
            if (!webGone) binding.viewerWeb.settings.textZoom = baseTextZoom * textZoom / 100
            localize()
            prepare(intent.getStringExtra(EXTRA_RECORD))
        }
    }

    private fun labels() = languages.of(language)

    private fun text(id: Int) = labels().getString(id)

    private val dark get() = ThemeMode.isDark(this)

    private fun style() = PreviewHtml.Style(layoutZoom, paperA4, dark)

    private fun localize() {
        binding.viewerToolbar.navigationContentDescription = text(R.string.back)
        binding.viewerToolbar.menu.findItem(R.id.viewer_external).title = text(R.string.open_external)
        binding.viewerToolbar.menu.findItem(R.id.viewer_share).title = text(R.string.share)
        binding.viewerToolbar.menu.findItem(R.id.viewer_print).title = text(R.string.print)
        binding.viewerToolbar.menu.findItem(R.id.viewer_zoom_in).title = text(R.string.preview_zoom_in)
        binding.viewerToolbar.menu.findItem(R.id.viewer_zoom_out).title = text(R.string.preview_zoom_out)
        binding.viewerToolbar.menu.findItem(R.id.viewer_page_a4).title = text(R.string.preview_page_a4)
        binding.viewerOpenExternal.text = text(R.string.open_external)
        binding.viewerProgressText.text = text(R.string.preview_preparing)
        binding.viewerToolbar.menu.findItem(R.id.viewer_find_toggle).title = text(R.string.find_in_file)
        findBar.localize()
        val current = record
        binding.viewerToolbar.title = current?.fileName?.let(FileKinds::title)?.ifBlank { null } ?: text(R.string.downloads)
        val tables = this.current?.tables
        binding.viewerToolbar.subtitle = if (tableIndex >= 0 && tables != null)
            text(R.string.preview_table_title).format(Locale.ROOT, tableIndex + 1, tables.count) else current?.let { item ->
            val format = item.fileName.substringAfterLast('.', "").takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) }
                ?.uppercase(Locale.ROOT) ?: FileKinds.badge(item.kind)
            listOf(format, FileKinds.size(item.total.takeIf { it >= 0 } ?: item.received, language)).filter { it.isNotBlank() }.joinToString(" · ")
        }
        messageId?.let { binding.viewerMessageText.text = text(it) }
        updateMenu()
    }

    private fun updateMenu() {
        val ready = record != null && messageId == null && binding.viewerWeb.isVisible
        val table = tableWeb
        // Searching, printing and the page width belong to the document, not to one table of it.
        binding.viewerToolbar.menu.findItem(R.id.viewer_find_toggle).isVisible = ready && findBar.available && table == null
        binding.viewerToolbar.menu.findItem(R.id.viewer_print).isVisible = ready && table == null
        binding.viewerToolbar.menu.findItem(R.id.viewer_external).isVisible = record != null
        binding.viewerToolbar.menu.findItem(R.id.viewer_share).isVisible = record != null
        // Neither zoom item stays active past its limits (600% magnified, 25% laid out), so a tap never silently does nothing.
        // canZoomIn/canZoomOut are deprecated for racing a scale change; they are read after onScaleChanged, when it is applied.
        @Suppress("DEPRECATION") val magnify = ready && !webGone && (table ?: binding.viewerWeb).canZoomIn()
        @Suppress("DEPRECATION") val magnified = ready && !webGone && (table ?: binding.viewerWeb).canZoomOut()
        val laidOut = table == null
        binding.viewerToolbar.menu.findItem(R.id.viewer_zoom_in).apply { isVisible = ready; isEnabled = magnify || ready && laidOut && layoutZoom < 100 }
        binding.viewerToolbar.menu.findItem(R.id.viewer_zoom_out).apply { isVisible = ready; isEnabled = magnified || ready && laidOut && layoutZoom > PreviewHtml.MIN_ZOOM }
        binding.viewerToolbar.menu.findItem(R.id.viewer_page_a4).apply { isVisible = ready && laidOut && current?.paper == true; isChecked = paperA4 }
    }

    private suspend fun prepare(id: String?) {
        val repository = DownloadRepository.get(this)
        repository.awaitLoaded()
        val item = repository.find(id)?.takeIf { it.state == DownloadState.COMPLETED && it.uri != null }
        if (item == null) { showMessage(R.string.preview_missing, external = false); return }
        record = item
        localize()
        val store = FileMedia(File(File(cacheDir, MEDIA_FOLDER), UUID.randomUUID().toString()))
        media = store
        try {
            val built = withContext(Dispatchers.IO) { build(item, store) }
            if (built == null) { showMessage(R.string.preview_unsupported, external = true); return }
            findBar.available = built.findable
            current = built
            updateMenu()
            page = built.source(style())
            binding.viewerWeb.loadUrl(PreviewHtml.INDEX)
            if (restoreTable >= 0) { openTable(restoreTable); restoreTable = -1 }
        } catch (cancelled: CancellationException) { pdf?.close(); throw cancelled }
        catch (_: PreviewLimitException) { showMessage(R.string.preview_limit, external = true) }
        catch (_: UnsupportedDocumentException) { showMessage(R.string.preview_unsupported, external = true) }
        catch (_: FileNotFoundException) { showMessage(R.string.state_missing, external = false) }
        catch (_: SecurityException) { showMessage(R.string.state_missing, external = false) }
        catch (_: OutOfMemoryError) { showMessage(R.string.preview_limit, external = true) }
        catch (_: Exception) { showMessage(R.string.preview_failed, external = true) }
    }

    /**
     * A page to show, drawn for a [PreviewHtml.Style]; [findable] when it has text, [paper] for the A4 page width setting,
     * [tables] that open on their own screen. A PDF is a [stream] instead, written while WebView reads it.
     */
    private class Page(val render: (PreviewHtml.Style) -> String, val findable: Boolean = false, val paper: Boolean = false,
        val tables: DocumentTables? = null, val stream: ((PreviewHtml.Style) -> InputStream)? = null) {
        /** The page is drawn when WebView reads it, on WebView's own thread, so a large document never stalls the screen. */
        fun source(style: PreviewHtml.Style): () -> InputStream = stream?.let { open -> { open(style) } }
            ?: { ByteArrayInputStream(render(style).toByteArray(Charsets.UTF_8)) }
    }

    /** Returns the page to show, or null for a type that only another app can open. Runs on IO. */
    private suspend fun build(item: DownloadRecord, store: FileMedia): Page? {
        val uri = item.uri!!.toUri()
        val labels = labels()
        val coroutine = currentCoroutineContext()
        val checkActive = { coroutine.ensureActive() }
        val extension = item.fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val name = item.fileName
        val site = language.siteCode
        val previewLabels = PreviewLabels(Locale.forLanguageTag(language.localeCode), labels.getString(R.string.preview_sheets),
            labels.getString(R.string.preview_sheet_empty), labels.getString(R.string.preview_slides_empty), labels.getString(R.string.preview_notes),
            slide = { number, total -> labels.getString(R.string.preview_slide, number, total) },
            sheetTruncated = { rows -> labels.resources.getQuantityString(R.plurals.preview_sheet_truncated, rows, rows) })
        fun open(): InputStream = contentResolver.openInputStream(uri) ?: throw FileNotFoundException()
        fun bytes(limit: Int) = open().use { input -> Bounded.read(input, limit, checkActive) }
        // Compound files (.doc, .xls, .ppt) are read in place from the open file; zip files are streamed.
        val opened = ArrayList<Closeable>()
        fun access(): FileAccess {
            val head = open().use { input ->
                val buffer = ByteArray(FileAccess.HEAD)
                var count = 0
                while (count < buffer.size) { val read = input.read(buffer, count, buffer.size - count); if (read < 0) break; count += read }
                buffer.copyOf(count)
            }
            return FileAccess(head, ::open) {
                val descriptor = contentResolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException()
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).also { opened += it }.channel.let(::ChannelSource)
            }
        }
        val openTable = labels.getString(R.string.preview_open_table)
        fun reading(body: String, notice: String?): Page {
            val tables = DocumentTables.find(body, openTable)
            return Page({ style -> PreviewHtml.docx(name, site, notice, tables.body, style) }, findable = true, paper = true, tables = tables)
        }
        fun web(bytes: ByteArray) = reading(HtmlView.html(bytes, checkActive, store), null)
        try {
            return when {
                item.kind == FileKind.PDF -> {
                    val descriptor = contentResolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException()
                    val pages = try { PdfPages(descriptor) } catch (failure: Exception) { descriptor.close(); throw failure }
                    pdf = pages
                    // A longer file shows its first pages; the notice says how many.
                    val notice = if (pages.count > PdfPages.PAGE_LIMIT) labels.resources.getQuantityString(R.plurals.preview_pdf_truncated, PdfPages.PAGE_LIMIT, PdfPages.PAGE_LIMIT) else null
                    // Page sizes are read while the page is written: the first pages show without waiting for the rest.
                    Page({ "" }, stream = { style ->
                        PreviewHtml.pdfStream(name, site, pages.shown, pages::size, style, notice) { number -> labels.getString(R.string.preview_page, number, pages.count) }
                    })
                }
                item.kind == FileKind.IMAGE -> {
                    open().close()
                    Page({ style -> PreviewHtml.image(name, site, style) })
                }
                item.kind == FileKind.TEXT || extension == "csv" -> {
                    val content = open().use { input -> TextPreview.read(input, checkActive = checkActive) }
                    val notice = if (content.truncated) labels.getString(R.string.preview_text_truncated) else null
                    Page({ style -> PreviewHtml.text(name, site, notice, content.text, style) }, findable = true)
                }
                // .doc, .docx, .rtf and their templates share one reading view, whatever the file really is inside.
                item.kind == FileKind.WORD && extension in WORD_VIEW -> reading(WordView.html(access(), checkActive, store), null)
                // .xls and .xlsx share one table view; a site's "Excel" export that is really a web page opens as one.
                item.kind == FileKind.EXCEL && extension in SHEET_VIEW -> {
                    val file = access()
                    if (HtmlText.isHtml(file.head)) web(bytes(HtmlView.FILE_LIMIT))
                    else DocumentTables.find(SheetView.html(file, previewLabels, checkActive), openTable).let { tables ->
                        Page({ style -> PreviewHtml.sheet(name, site, tables.body, style) }, findable = true, tables = tables)
                    }
                }
                // .ppt and .pptx share one slide view.
                item.kind == FileKind.POWERPOINT && extension in SLIDE_VIEW -> SlideView.html(access(), previewLabels, checkActive, store)
                    .let { body -> Page({ style -> PreviewHtml.slides(name, site, body, style) }, findable = true) }
                extension in WEB_VIEW -> web(bytes(HtmlView.FILE_LIMIT))
                else -> null
            }
        } finally {
            opened.forEach { runCatching { it.close() } }
        }
    }

    /** Draws the open document again (a new layout zoom or page width), keeping the reading position and the search. */
    private fun redraw() {
        val document = current ?: return
        if (webGone) return
        val web = binding.viewerWeb
        @Suppress("DEPRECATION") val total = web.contentHeight * web.scale - web.height
        restoreFraction = if (total > 0) (web.scrollY / total).coerceIn(0f, 1f) else 0f
        if (findBar.isOpen) findQuery = findBar.query
        page = document.source(style())
        web.loadUrl(PreviewHtml.INDEX)
        updateMenu()
    }

    /** Shows table [index] of the document on its own screen, over the document, which stays where it was read. */
    private fun openTable(index: Int) {
        val markup = current?.tables?.markup(index) ?: return
        if (webGone || isFinishing) return
        if (findBar.isOpen) findBar.close()
        val title = record?.fileName.orEmpty()
        val site = language.siteCode
        val style = style()
        tablePage = { ByteArrayInputStream(PreviewHtml.table(title, site, markup, style).toByteArray(Charsets.UTF_8)) }
        val web = tableWeb ?: WebView(this).also { web ->
            web.id = R.id.viewer_table_web
            web.configureOfflineViewer()
            // The table opens zoomed out to its full width (the page sets no initial scale); WebView zooms it from there.
            web.settings.loadWithOverviewMode = true
            web.settings.textZoom = binding.viewerWeb.settings.textZoom
            web.setBackgroundColor(MaterialColors.getColor(binding.viewerWeb, android.R.attr.colorBackground))
            web.webViewClient = tableClient()
            // Above the document, below the progress, zoom and message views.
            (binding.viewerWeb.parent as ViewGroup).addView(web, 1, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            tableWeb = web
        }
        tableIndex = index
        web.loadUrl(PreviewHtml.tableUrl(index))
        binding.viewerWeb.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        closeTableOnBack.isEnabled = true
        localize()
    }

    private fun closeTable() {
        val web = tableWeb ?: return
        tableWeb = null
        tableIndex = -1
        tablePage = null
        closeTableOnBack.isEnabled = false
        (web.parent as? ViewGroup)?.removeView(web)
        web.stopLoading(); web.webViewClient = RetiredWebViewClient(); web.destroy()
        if (!webGone) binding.viewerWeb.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        localize()
    }

    private fun tableClient() = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse = respond(request)
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = navigate(request)
        override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) = scaled(view, oldScale, newScale)
        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            // The document's WebView shares the renderer and reports the failure itself.
            if (view === tableWeb) closeTable() else { (view.parent as? ViewGroup)?.removeView(view); view.destroy() }
            return true
        }
    }

    private fun zoomIn() {
        @Suppress("DEPRECATION")
        tableWeb?.let { table -> if (table.canZoomIn()) { zoomingOut = false; table.zoomBy(1.25f) }; return }
        val step = PreviewHtml.ZOOM_STEPS.firstOrNull { it > layoutZoom }
        @Suppress("DEPRECATION")
        when {
            layoutZoom < 100 && step != null -> { layoutZoom = step; redraw(); showZoom() }
            binding.viewerWeb.canZoomIn() -> { zoomingOut = false; binding.viewerWeb.zoomBy(1.25f) }
        }
    }

    private fun zoomOut() {
        @Suppress("DEPRECATION")
        tableWeb?.let { table -> if (table.canZoomOut()) { zoomingOut = true; table.zoomBy(0.8f) }; return }
        @Suppress("DEPRECATION")
        if (binding.viewerWeb.canZoomOut()) { zoomingOut = true; binding.viewerWeb.zoomBy(0.8f); return }
        PreviewHtml.ZOOM_STEPS.lastOrNull { it < layoutZoom }?.let { layoutZoom = it; redraw(); showZoom() }
    }

    private fun setPaperA4(on: Boolean) {
        paperA4 = on
        redraw()
        val context = applicationContext
        lifecycleScope.launch(Dispatchers.IO) { runCatching { AppPreferences(context).previewPageA4 = on } }
    }

    /** The current zoom for a moment at the bottom: the layout zoom below 100%, the magnification above. */
    private fun showZoom(percent: Int = currentZoom()) {
        binding.viewerZoomLevel.text = text(R.string.preview_zoom_level).format(Locale.ROOT, percent)
        Motion.show(binding.viewerZoomLevel, true)
        binding.viewerZoomLevel.removeCallbacks(hideZoom)
        binding.viewerZoomLevel.postDelayed(hideZoom, 1200)
    }

    @Suppress("DEPRECATION")
    private fun currentZoom() = tableWeb?.let { (it.scale / resources.displayMetrics.density * 100).roundToInt() }
        ?: if (layoutZoom < 100 || webGone) layoutZoom else (binding.viewerWeb.scale / resources.displayMetrics.density * 100).roundToInt()

    private fun showMessage(id: Int, external: Boolean) {
        messageId = id
        binding.viewerProgress.isVisible = false
        binding.viewerWeb.isVisible = false
        binding.viewerFindBar.isVisible = false
        Motion.show(binding.viewerMessage, true)
        binding.viewerMessageText.text = text(id)
        binding.viewerOpenExternal.isVisible = external && record != null
        updateMenu()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("layout_zoom", layoutZoom)
        outState.putInt("table", tableIndex)
        if (findBar.isOpen) outState.putString("find", findBar.query)
        super.onSaveInstanceState(outState)
    }

    private fun print(item: DownloadRecord) {
        val manager = getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        val name = item.fileName.ifBlank { "Univer" }
        try {
            if (item.kind == FileKind.PDF) { manager.print(name, FileActions.PdfPrintAdapter(this, item.uri!!.toUri(), name), null); return }
            val document = current ?: return
            if (printWeb != null || webGone) return
            // While reading, pictures are fetched only near the screen. The printed copy is a page of its own that fetches
            // all of them, at 100%, and goes to the printer once it has loaded (an unattached WebView prints as well).
            // Built when the print WebView reads it, off the main thread, like the reading page.
            val style = PreviewHtml.Style(100, paperA4, dark, print = true)
            printPage = { ByteArrayInputStream(document.render(style).toByteArray(Charsets.UTF_8)) }
            val web = WebView(this)
            printWeb = web
            web.configureOfflineViewer()
            web.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse = respond(request)
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun onPageFinished(view: WebView, url: String?) {
                    if (url != PreviewHtml.PRINT || view !== printWeb || isFinishing) return
                    try { manager.print(name, PrintedCopy(view.createPrintDocumentAdapter(name), ::closePrint), null) }
                    catch (_: Exception) { closePrint(); message(R.string.print_failed) }
                }
                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                    if (view === printWeb) closePrint() else view.destroy()
                    return true
                }
            }
            web.loadUrl(PreviewHtml.PRINT)
        } catch (_: Exception) { closePrint(); message(R.string.print_failed) }
    }

    private fun closePrint() {
        val web = printWeb ?: return
        printWeb = null
        printPage = null
        web.stopLoading(); web.webViewClient = RetiredWebViewClient(); web.destroy()
    }

    /** Prints the copy and lets it go when the print dialog is done with it. */
    private class PrintedCopy(private val copy: PrintDocumentAdapter, private val finished: () -> Unit) : PrintDocumentAdapter() {
        override fun onStart() = copy.onStart()
        override fun onLayout(oldAttributes: PrintAttributes?, newAttributes: PrintAttributes, cancellationSignal: CancellationSignal,
            callback: LayoutResultCallback, extras: Bundle?) = copy.onLayout(oldAttributes, newAttributes, cancellationSignal, callback, extras)
        override fun onWrite(pages: Array<out PageRange>, destination: ParcelFileDescriptor, cancellationSignal: CancellationSignal,
            callback: WriteResultCallback) = copy.onWrite(pages, destination, cancellationSignal, callback)
        override fun onFinish() { copy.onFinish(); finished() }
    }

    private fun message(id: Int) = Snackbar.make(binding.root, text(id), Snackbar.LENGTH_LONG).show()

    private fun configureWeb(web: WebView) {
        web.configureOfflineViewer()
        baseTextZoom = web.settings.textZoom
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse = respond(request)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = navigate(request)
            override fun onPageCommitVisible(view: WebView, url: String?) {
                if (url == PreviewHtml.INDEX && messageId == null) {
                    Motion.show(binding.viewerProgress, false)
                    binding.viewerWeb.isVisible = true
                    updateMenu()
                    // Matches are counted once the page has its text; a search from Downloads starts here.
                    findQuery?.let { query -> findQuery = null; view.postDelayed({ if (!isFinishing) findBar.open(query) }, 150) }
                }
            }
            // The zoom limits are known once the page is laid out; a redrawn page returns to where it was read.
            override fun onPageFinished(view: WebView, url: String?) {
                if (url != PreviewHtml.INDEX || isFinishing) return
                updateMenu()
                view.postDelayed({
                    if (isFinishing || webGone) return@postDelayed
                    restoreFraction?.let { fraction ->
                        restoreFraction = null
                        @Suppress("DEPRECATION") val total = view.contentHeight * view.scale - view.height
                        if (total > 0) view.scrollTo(0, (fraction * total).roundToInt())
                    }
                    updateMenu()
                }, 250)
            }
            override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) = scaled(view, oldScale, newScale)
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                webGone = true
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.destroy()
                if (!isFinishing && !isDestroyed) showMessage(R.string.preview_failed, external = true)
                return true
            }
        }
    }

    /** Pinch, double tap and the menu all end here, in the document and in a table. */
    private fun scaled(view: WebView, oldScale: Float, newScale: Float) {
        if (isFinishing) return
        // WebView stops offering "zoom out" within 0.7% of its minimum; the last step lands on it (zoomBy clamps),
        // so the page never keeps a few pixels of sideways scroll.
        @Suppress("DEPRECATION")
        if (zoomingOut && newScale < oldScale && !view.canZoomOut()) { zoomingOut = false; view.zoomBy(0.5f) }
        if (newScale > oldScale) zoomingOut = false
        if (view.isVisible && abs(newScale - oldScale) > 0.001f) showZoom()
        updateMenu()
    }

    /** The viewer itself never navigates: a tapped link opens outside the app, or a table on its own screen. */
    private fun navigate(request: WebResourceRequest): Boolean {
        val url = request.url
        if (request.isForMainFrame && request.hasGesture()) {
            if (url.scheme == "https" && url.host == FILES_HOST)
                TABLE_PATH.matchEntire(url.path.orEmpty())?.let { match -> runOnUiThread { openTable(match.groupValues[1].toInt()) } }
            else if (url.scheme in setOf("http", "https", "mailto")) runOnUiThread { openLink(url) }
        }
        return true
    }

    private fun openLink(uri: android.net.Uri) {
        try { startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)) }
        catch (_: ActivityNotFoundException) { message(R.string.open_failed) }
    }

    /** Runs on a WebView thread. Unknown paths and every other origin receive an empty 404. */
    private fun respond(request: WebResourceRequest): WebResourceResponse {
        val url = request.url
        if (url.scheme == "https" && url.host == FILES_HOST && request.method == "GET") {
            val path = url.path.orEmpty()
            try {
                when {
                    path == "/index.html" -> page?.let { return WebResourceResponse("text/html", "utf-8", 200, "OK", NO_CACHE, it()) }
                    path == "/print.html" -> printPage?.let { return WebResourceResponse("text/html", "utf-8", 200, "OK", NO_CACHE, it()) }
                    path.startsWith("/table/") -> tablePage?.takeIf { path == "/table/$tableIndex" }?.let {
                        return WebResourceResponse("text/html", "utf-8", 200, "OK", NO_CACHE, it())
                    }
                    path == "/file" -> record?.takeIf { it.kind == FileKind.IMAGE }?.let { item ->
                        contentResolver.openInputStream(item.uri!!.toUri())?.let { stream ->
                            return WebResourceResponse(FileActions.mime(item), null, 200, "OK", NO_CACHE, stream)
                        }
                    }
                    path.startsWith("/media/") -> path.removePrefix("/media/").takeIf { it.matches(MEDIA_NAME) }?.let { name ->
                        media?.open(name)?.let { (stream, type) -> return WebResourceResponse(type, null, 200, "OK", NO_CACHE, stream) }
                    }
                    // A page is drawn when WebView reads the answer, not when it asks: it may ask ahead of time.
                    else -> PAGE_PATH.matchEntire(path)?.let { match ->
                        val index = match.groupValues[1].toInt()
                        pdf?.takeIf { index < it.count }?.let { pages ->
                            return WebResourceResponse("image/png", null, 200, "OK", NO_CACHE, DeferredStream { pages.render(index, pageWidth) })
                        }
                    }
                }
            } catch (_: OutOfMemoryError) {
                // A page too large to draw in this device's memory shows the limit message, as when the file is read.
                if (path == "/index.html" || path == "/print.html" || path.startsWith("/table/")) runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (path == "/index.html") showMessage(R.string.preview_limit, external = true)
                    else if (path == "/print.html") { if (printWeb != null) { closePrint(); message(R.string.preview_limit) } }
                    else if (tableWeb != null) { closeTable(); message(R.string.preview_limit) }
                }
            } catch (_: Exception) { /* A missing or unreadable part is shown as absent. */ }
        }
        return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", NO_CACHE, ByteArrayInputStream(ByteArray(0)))
    }

    override fun onDestroy() {
        pinch.release()
        closeTable()
        closePrint()
        // A WebView whose renderer is gone was destroyed already; it is not used again.
        if (!webGone) binding.viewerWeb.apply {
            stopLoading(); webViewClient = RetiredWebViewClient()
            (parent as? android.view.ViewGroup)?.removeView(this); destroy()
        }
        pdf?.close(); pdf = null; page = null
        media?.let { store -> media = null; Thread { store.clear() }.start() }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_RECORD = "record"
        private const val EXTRA_LANGUAGE = "language"
        private const val EXTRA_QUERY = "query"
        private const val FILES_HOST = "univer-files.local"
        private const val MEDIA_FOLDER = "viewer-media"
        private const val STALE_MEDIA = 6 * 60 * 60 * 1000L
        private val WORD_VIEW = setOf("doc", "dot", "docx", "docm", "dotx", "dotm", "rtf")
        private val SHEET_VIEW = setOf("xls", "xlt", "xlsx", "xlsm", "xltx", "xltm")
        private val SLIDE_VIEW = setOf("ppt", "pps", "pot", "pptx", "ppsx", "pptm", "potx", "ppsm", "potm")
        private val WEB_VIEW = setOf("htm", "html")
        private val NO_CACHE = mapOf("Cache-Control" to "no-store")
        /** A table link inside the document: /table/{index}. */
        private val TABLE_PATH = Regex("^/table/(\\d{1,5})$")
        /** Compiled once: each page image and media file of an open document asks through these. */
        private val PAGE_PATH = Regex("^/page/(\\d{1,5})\\.png$")
        private val MEDIA_NAME = Regex("[0-9a-f]{64}")
        /** [query]: words to find in the document as soon as it opens (from the search in Downloads). */
        fun intent(context: Context, recordId: String, language: AppLanguage, query: String? = null): Intent =
            Intent(context, FileViewerActivity::class.java).putExtra(EXTRA_RECORD, recordId).putExtra(EXTRA_LANGUAGE, language.siteCode)
                .apply { if (!query.isNullOrBlank()) putExtra(EXTRA_QUERY, query.take(200)) }
    }
}
