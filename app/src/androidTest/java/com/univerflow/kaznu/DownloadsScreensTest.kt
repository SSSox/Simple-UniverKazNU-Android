package com.univerflow.kaznu

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.view.View
import android.webkit.WebView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.data.DownloadHistoryCodec
import com.univerflow.kaznu.data.DownloadRepository
import com.univerflow.kaznu.data.DownloadTextIndex
import com.univerflow.kaznu.domain.DownloadFailure
import com.univerflow.kaznu.domain.DownloadKeys
import com.univerflow.kaznu.domain.DownloadRecord
import com.univerflow.kaznu.domain.DownloadState
import com.univerflow.kaznu.download.PublicDownloadStore
import com.univerflow.kaznu.preview.PreviewHtml
import com.univerflow.kaznu.preview.TextExtract
import com.univerflow.kaznu.ui.DownloadsActivity
import com.univerflow.kaznu.ui.FileViewerActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Synthetic files in the public Downloads folder and in-memory list entries. The installed
 * account's own download list is restored and every created file is deleted afterwards.
 */
@RunWith(AndroidJUnit4::class)
class DownloadsScreensTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val repository get() = DownloadRepository.get(context)
    private val files = mutableListOf<Uri>()
    private val records = mutableListOf<String>()

    @Before fun ready() {
        assumeTrue("MediaStore Downloads needs API 29", Build.VERSION.SDK_INT >= 29)
        runBlocking { repository.awaitLoaded() }
    }

    @After fun cleanUp() {
        records.forEach { repository.remove(it); DownloadTextIndex(context).forget(it) }
        files.forEach { context.contentResolver.delete(it, null, null) }
    }

    private fun save(name: String, mime: String, bytes: ByteArray, source: String = "Введение в QA"): DownloadRecord {
        val uri = PublicDownloadStore(context).create(name, mime).use { it.output.write(bytes); it.publish() }
        files += uri
        return add(DownloadRecord("qa-" + UUID.randomUUID(), name, uri.toString(), mime, DownloadState.COMPLETED,
            bytes.size.toLong(), bytes.size.toLong(), System.currentTimeMillis(), System.currentTimeMillis(), source))
    }

    private fun add(record: DownloadRecord): DownloadRecord {
        records += record.id
        repository.upsert(record, persistNow = false) // Test entries are never written to the installed history.
        return record
    }

    private fun pdf(): ByteArray {
        val document = PdfDocument()
        val paint = Paint().apply { textSize = 28f; color = Color.BLACK }
        repeat(2) { index ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
            page.canvas.drawText("Univer QA page ${index + 1}", 72f, 120f, paint)
            document.finishPage(page)
        }
        return ByteArrayOutputStream().also { document.writeTo(it); document.close() }.toByteArray()
    }

    private fun docx(complex: Boolean = false): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            if (complex) {
                fun cell(text: String, props: String = "") = "<w:tc><w:tcPr>$props</w:tcPr><w:p><w:r><w:t>$text</w:t></w:r></w:p></w:tc>"
                zip.write("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:m="http://schemas.openxmlformats.org/officeDocument/2006/math"><w:body>
                    <w:p><w:r><w:t>Силлабус: информационная безопасность</w:t></w:r></w:p><w:tbl><w:tblGrid><w:gridCol w:w="1440"/><w:gridCol w:w="2880"/><w:gridCol w:w="3600"/><w:gridCol w:w="4500"/></w:tblGrid>
                    <w:tr>${cell("Период", "<w:vMerge w:val=\"restart\"/>")}${cell("Учебная деятельность", "<w:gridSpan w:val=\"3\"/>")}</w:tr>
                    <w:tr>${cell("", "<w:vMerge/>")}${cell("Тема занятия")}${cell("Самостоятельная работа")}${cell("Форма контроля")}</w:tr>
                    <w:tr>${cell("1–2 недели")}${cell("Основные принципы защиты информации и безопасность университетских систем")}<w:tc><w:p><w:r><w:t>Вычислить значение: </w:t></w:r><m:oMath><m:f><m:num><m:sSup><m:e><m:r><m:t>x</m:t></m:r></m:e><m:sup><m:r><m:t>2</m:t></m:r></m:sup></m:sSup></m:num><m:den><m:r><m:t>y</m:t></m:r></m:den></m:f></m:oMath></w:p></w:tc>${cell("Практическое задание и обсуждение результатов")}</w:tr></w:tbl></w:body></w:document>""".toByteArray())
            } else {
            zip.write("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
                <w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Методичка QA</w:t></w:r></w:p>
                <w:tbl><w:tr><w:tc><w:p><w:r><w:t>Неделя</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Тема</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
                </w:body></w:document>""".toByteArray())
            }
            zip.closeEntry()
        }
    }.toByteArray()

    private fun <T : android.app.Activity> waitUntil(scenario: ActivityScenario<T>, message: String, condition: (T) -> Boolean) {
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end) {
            var done = false
            scenario.onActivity { done = condition(it) }
            if (done) return
            Thread.sleep(200)
        }
        fail(message)
    }

    private fun evaluate(web: WebView, script: String): String {
        val latch = CountDownLatch(1)
        var result = "null"
        instrumentation.runOnMainSync {
            // The viewer runs without JavaScript; the test enables it only to inspect the rendered page.
            @Suppress("SetJavaScriptEnabled")
            web.settings.javaScriptEnabled = true
            web.evaluateJavascript(script) { result = it; latch.countDown() }
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        return result
    }

    private fun screenshot(name: String) {
        Thread.sleep(500)
        val image = instrumentation.uiAutomation.takeScreenshot() ?: return
        val directory = File(context.getExternalFilesDir(null), "qa").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    private fun view(record: DownloadRecord, check: (FileViewerActivity) -> Unit) {
        ActivityScenario.launch<FileViewerActivity>(FileViewerActivity.intent(context, record.id, AppLanguage.RUSSIAN)).use { scenario ->
            waitUntil(scenario, "${record.fileName} rendered") {
                it.findViewById<View>(R.id.viewer_web).visibility == View.VISIBLE || it.findViewById<View>(R.id.viewer_message).visibility == View.VISIBLE }
            scenario.onActivity(check)
        }
    }

    @Test fun pdfWordTextAndUnsupportedFilesOpenInsideTheOfflineViewer() {
        val pdf = save("Univer-QA-${UUID.randomUUID()}.pdf", "application/pdf", pdf())
        ActivityScenario.launch<FileViewerActivity>(FileViewerActivity.intent(context, pdf.id, AppLanguage.RUSSIAN)).use { scenario ->
            waitUntil(scenario, "PDF visible") { it.findViewById<View>(R.id.viewer_web).visibility == View.VISIBLE }
            lateinit var web: WebView
            scenario.onActivity { activity ->
                web = activity.findViewById(R.id.viewer_web)
                val toolbar = activity.findViewById<MaterialToolbar>(R.id.viewer_toolbar)
                assertEquals(pdf.fileName, toolbar.title)
                assertTrue(toolbar.subtitle.toString().startsWith("PDF"))
                assertTrue(toolbar.menu.findItem(R.id.viewer_print).isVisible)
            }
            assertEquals("2", evaluate(web, "document.querySelectorAll('figure.page').length"))
            // Pages are background images, fetched when they come near the screen; resource timing shows the first one asked
            // for (it reports no sizes for the viewer's own answers), and the same picture decodes as a real page.
            val first = "performance.getEntriesByName('${PreviewHtml.pageUrl(0)}').some(e=>e.responseEnd>0)"
            val end = System.currentTimeMillis() + 15_000
            while (evaluate(web, first) != "true" && System.currentTimeMillis() < end) Thread.sleep(200)
            assertEquals("First page fetched", "true", evaluate(web, first))
            evaluate(web, "(()=>{const i=new Image();i.onload=()=>window.__w=i.naturalWidth;i.onerror=()=>window.__w=-1;i.src='${PreviewHtml.pageUrl(0)}';return 1})()")
            while (evaluate(web, "window.__w||0") == "0" && System.currentTimeMillis() < end) Thread.sleep(200)
            assertTrue("First page rendered", evaluate(web, "window.__w||0").toInt() > 500)
            assertEquals("\"Страница 1 из 2\"", evaluate(web, "document.querySelector('figcaption').textContent"))
            // Network and other origins stay closed: the page policy refuses the request before it leaves the viewer.
            evaluate(web, "fetch('https://example.com/').then(()=>window.__probe='open',()=>window.__probe='blocked');'started'")
            val probeEnd = System.currentTimeMillis() + 5_000
            while (evaluate(web, "window.__probe||''") == "\"\"" && System.currentTimeMillis() < probeEnd) Thread.sleep(100)
            assertEquals("\"blocked\"", evaluate(web, "window.__probe"))
            screenshot("viewer-pdf")
        }
        val word = save("Univer-QA-${UUID.randomUUID()}.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx())
        ActivityScenario.launch<FileViewerActivity>(FileViewerActivity.intent(context, word.id, AppLanguage.RUSSIAN)).use { scenario ->
            waitUntil(scenario, "Word document visible") { it.findViewById<View>(R.id.viewer_web).visibility == View.VISIBLE }
            lateinit var web: WebView
            scenario.onActivity { web = it.findViewById(R.id.viewer_web) }
            assertEquals("\"Методичка QA\"", evaluate(web, "document.querySelector('.paper b').textContent"))
            assertEquals("2", evaluate(web, "document.querySelectorAll('.paper td').length"))
            screenshot("viewer-docx")
        }
        val text = save("Univer-QA-${UUID.randomUUID()}.txt", "text/plain", "Заметки <script>alert(1)</script>\nВторая строка".toByteArray())
        ActivityScenario.launch<FileViewerActivity>(FileViewerActivity.intent(context, text.id, AppLanguage.ENGLISH)).use { scenario ->
            waitUntil(scenario, "Text visible") { it.findViewById<View>(R.id.viewer_web).visibility == View.VISIBLE }
            lateinit var web: WebView
            scenario.onActivity { web = it.findViewById(R.id.viewer_web) }
            assertEquals("true", evaluate(web, "document.querySelector('pre').textContent.includes('<script>alert(1)</script>')&&!document.querySelector('script')"))
        }
        val slides = save("Univer-QA-${UUID.randomUUID()}.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", byteArrayOf(1, 2, 3))
        view(slides) { activity ->
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.viewer_message).visibility)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.viewer_open_external).visibility)
            assertTrue(activity.findViewById<TextView>(R.id.viewer_message_text).text.contains("другом приложении"))
            assertFalse(activity.findViewById<MaterialToolbar>(R.id.viewer_toolbar).menu.findItem(R.id.viewer_print).isVisible)
        }
    }

    @Test fun downloadsListSearchesFiltersAndOffersCancelForRunningTransfers() {
        val now = System.currentTimeMillis()
        save("Методичка QA.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx())
        add(DownloadRecord("qa-" + UUID.randomUUID(), "Лекция 5 QA.pdf", null, "application/pdf", DownloadState.RUNNING, 449_536, 1_153_434, now + 1, source = "Введение в QA"))
        add(DownloadRecord("qa-" + UUID.randomUUID(), "Старый файл QA.doc", null, "application/msword", DownloadState.FAILED, 0, -1, now - 86_400_000,
            now - 86_400_000, "Методы QA", DownloadFailure.ACCESS_DENIED))
        ActivityScenario.launch<DownloadsActivity>(DownloadsActivity.intent(context, AppLanguage.RUSSIAN)).use { scenario ->
            fun shown(activity: DownloadsActivity) = activity.findViewById<RecyclerView>(R.id.downloads_list).adapter!!.itemCount
            waitUntil(scenario, "List loaded") { shown(it) >= 3 }
            scenario.onActivity { activity ->
                activity.findViewById<TextView>(R.id.downloads_search).text = "QA"
            }
            waitUntil(scenario, "Search keeps the three QA files") { shown(it) == 3 }
            // Item removal animations would leave a temporary gap in the screenshot.
            waitUntil(scenario, "List animations settled") { activity ->
                activity.findViewById<RecyclerView>(R.id.downloads_list).let { !it.hasPendingAdapterUpdates() && !it.isAnimating }
            }
            scenario.onActivity { activity ->
                val subtitle = activity.findViewById<MaterialToolbar>(R.id.downloads_toolbar).subtitle.toString()
                // Account history may already hold other files: the subtitle tells how many of them the search shows.
                assertTrue(subtitle, subtitle == "Файлов в списке: 3" || Regex("Показано: 3 из \\d+").matches(subtitle))
            }
            screenshot("downloads-list")
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.downloads_list)
                val running = (0 until list.childCount).map { list.getChildAt(it) }
                    .first { it.findViewById<TextView>(R.id.download_name).text.startsWith("Лекция") }
                assertEquals("Отменить загрузку", running.findViewById<View>(R.id.download_action).contentDescription)
                assertTrue(running.findViewById<TextView>(R.id.download_meta).text.startsWith("Скачивается 38%"))
                assertEquals(View.VISIBLE, running.findViewById<View>(R.id.download_progress).visibility)
                val chips = activity.findViewById<ChipGroup>(R.id.downloads_filters)
                (0 until chips.childCount).map { chips.getChildAt(it) as Chip }.first { it.text == "Word" }.isChecked = true
            }
            waitUntil(scenario, "Word filter") { shown(it) == 2 }
            scenario.onActivity { activity -> activity.findViewById<TextView>(R.id.downloads_search).text = "методы" }
            waitUntil(scenario, "Search by course") { shown(it) == 1 }
            scenario.onActivity { activity -> activity.findViewById<TextView>(R.id.downloads_search).text = "нет такого файла" }
            waitUntil(scenario, "Empty result") { it.findViewById<View>(R.id.downloads_empty).visibility == View.VISIBLE }
        }
    }

    @Test fun searchOpensFromTheIconFindsWordsInsideFilesAndFiltersByCourse() {
        val tag = UUID.randomUUID().toString().take(8)
        val word = save("Методичка QA-$tag.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx())
        val notes = save("Заметки QA-$tag.txt", "text/plain", "Первая строка.\nВнутри файла: криптография$tag и шифры.".toByteArray(), source = "Методы QA-$tag")
        ActivityScenario.launch<DownloadsActivity>(DownloadsActivity.intent(context, AppLanguage.RUSSIAN)).use { scenario ->
            fun shown(activity: DownloadsActivity) = activity.findViewById<RecyclerView>(R.id.downloads_list).adapter!!.itemCount
            fun chip(activity: DownloadsActivity, group: Int, label: String) = activity.findViewById<ChipGroup>(group).let { chips ->
                (0 until chips.childCount).map { chips.getChildAt(it) as Chip }.first { it.text == label } }
            waitUntil(scenario, "List loaded") { shown(it) >= 2 }
            scenario.onActivity { activity ->
                assertEquals("Search starts hidden", View.GONE, activity.findViewById<View>(R.id.downloads_search_layout).visibility)
                activity.findViewById<MaterialToolbar>(R.id.downloads_toolbar).menu.performIdentifierAction(R.id.downloads_search_toggle, 0)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.downloads_search_layout).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.downloads_text_search).visibility)
                activity.findViewById<TextView>(R.id.downloads_search).text = "КРИПТОГРАФИЯ$tag"
            }
            waitUntil(scenario, "Names alone do not match") { shown(it) == 0 }
            scenario.onActivity { (it.findViewById<Chip>(R.id.downloads_text_search)).isChecked = true }
            waitUntil(scenario, "Found inside the text file") { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.downloads_list)
                shown(activity) == 1 && list.findViewHolderForAdapterPosition(0)?.itemView?.findViewById<TextView>(R.id.download_snippet)?.let { snippet ->
                    snippet.visibility == View.VISIBLE && snippet.text.contains("криптография$tag и шифры")
                } == true
            }
            screenshot("downloads-text-search")
            // The course filter narrows any search: the text file belongs to the other course.
            scenario.onActivity { chip(it, R.id.downloads_subjects, "Введение в QA").isChecked = true }
            waitUntil(scenario, "Other course hides the match") { shown(it) == 0 }
            scenario.onActivity { it.findViewById<TextView>(R.id.downloads_search).text = tag }
            waitUntil(scenario, "Only the Word file of this course") { activity ->
                shown(activity) == 1 && activity.findViewById<RecyclerView>(R.id.downloads_list).findViewHolderForAdapterPosition(0)
                    ?.itemView?.findViewById<TextView>(R.id.download_name)?.text == word.fileName
            }
            scenario.recreate()
            waitUntil(scenario, "Search, text switch and course restored") { activity ->
                activity.findViewById<View>(R.id.downloads_search_layout).visibility == View.VISIBLE &&
                    activity.findViewById<Chip>(R.id.downloads_text_search).isChecked && chip(activity, R.id.downloads_subjects, "Введение в QA").isChecked && shown(activity) == 1
            }
            // Back closes the search and clears it before leaving the screen.
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitUntil(scenario, "Search closed") { activity ->
                activity.findViewById<View>(R.id.downloads_search_layout).visibility == View.GONE && activity.findViewById<TextView>(R.id.downloads_search).text.isEmpty() && !activity.isFinishing
            }
        }
        // Opened from a match, the viewer highlights the found word.
        ActivityScenario.launch<FileViewerActivity>(FileViewerActivity.intent(context, notes.id, AppLanguage.RUSSIAN, "криптография$tag")).use { scenario ->
            waitUntil(scenario, "Find bar shows the match") { activity ->
                activity.findViewById<View>(R.id.viewer_find_bar).visibility == View.VISIBLE && activity.findViewById<TextView>(R.id.viewer_find_count).text == "1 из 1"
            }
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<MaterialToolbar>(R.id.viewer_toolbar).menu.findItem(R.id.viewer_find_toggle).isVisible)
                activity.findViewById<TextView>(R.id.viewer_find).text = "нет такого слова"
            }
            waitUntil(scenario, "No match") { it.findViewById<TextView>(R.id.viewer_find_count).text == "Не найдено" }
        }
    }

    /** The same synthetic document saved by Word 16 as .doc and .rtf (see test/resources/documents). */
    @Test fun docAndRtfOpenLikeDocxAndTheirTextIsFoundBySearch() {
        fun asset(name: String) = instrumentation.context.assets.open("documents/$name").use { it.readBytes() }
        val tag = UUID.randomUUID().toString().take(8)
        val doc = save("Силлабус-QA-$tag.doc", "application/msword", asset("word.doc"))
        val rtf = save("Силлабус-QA-$tag.rtf", "application/rtf", asset("word.rtf"))
        listOf(doc, rtf).forEach { record ->
            ActivityScenario.launch<FileViewerActivity>(FileViewerActivity.intent(context, record.id, AppLanguage.RUSSIAN)).use { scenario ->
                waitUntil(scenario, "${record.fileName} visible") { it.findViewById<View>(R.id.viewer_web).visibility == View.VISIBLE }
                lateinit var web: WebView
                scenario.onActivity { activity ->
                    web = activity.findViewById(R.id.viewer_web)
                    assertTrue("Find in text", activity.findViewById<MaterialToolbar>(R.id.viewer_toolbar).menu.findItem(R.id.viewer_find_toggle).isVisible)
                }
                assertEquals("\"Силлабус: Информационная безопасность\"", evaluate(web, "document.querySelector('.paper h1').textContent"))
                assertEquals("Merged cells, the picture and the link, as in .docx", "true", evaluate(web,
                    "!!document.querySelector('.paper td[rowspan=\"2\"]')&&!!document.querySelector('.paper td[colspan=\"2\"]')&&" +
                        "document.querySelectorAll('.paper .pic').length===1&&document.querySelector('.paper a').getAttribute('href')==='https://www.kaznu.kz/'"))
                screenshot("viewer-${record.fileName.substringAfterLast('.')}")
            }
        }
        ActivityScenario.launch<DownloadsActivity>(DownloadsActivity.intent(context, AppLanguage.RUSSIAN)).use { scenario ->
            fun names(activity: DownloadsActivity) = activity.findViewById<RecyclerView>(R.id.downloads_list).let { list ->
                (0 until list.childCount).map { list.getChildAt(it).findViewById<TextView>(R.id.download_name).text.toString() } }
            waitUntil(scenario, "List loaded") { names(it).isNotEmpty() }
            scenario.onActivity { activity ->
                activity.findViewById<MaterialToolbar>(R.id.downloads_toolbar).menu.performIdentifierAction(R.id.downloads_search_toggle, 0)
                activity.findViewById<TextView>(R.id.downloads_search).text = "әліпби ұлттық"
                activity.findViewById<Chip>(R.id.downloads_text_search).isChecked = true
            }
            waitUntil(scenario, "Both old Word files found by their text") { activity -> names(activity).containsAll(listOf(doc.fileName, rtf.fileName)) }
        }
    }

    @Test fun theAppsOwnPdfReaderReadsAPdfDrawnByAndroid() {
        val document = PdfDocument()
        val paint = Paint().apply { textSize = 18f; color = Color.BLACK }
        val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        page.canvas.drawText("Univer QA: проверка текста", 72f, 120f, paint)
        page.canvas.drawText("Қазақ тілі, Final exam", 72f, 160f, paint)
        document.finishPage(page)
        val bytes = ByteArrayOutputStream().also { document.writeTo(it); document.close() }.toByteArray()
        // The reader used where PdfRenderer has no text (before Android 15), run here on any version.
        val text = TextExtract.text(bytes, "drawn.pdf")
        assertTrue(text, text.contains("Univer QA: проверка текста") && text.contains("Қазақ тілі, Final exam"))
    }

    @Test fun complexWordTablesScrollAndTheLayoutZoomSurvivesRecreation() {
        val word = save("Univer-QA-wide-${UUID.randomUUID()}.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx(complex = true))
        ActivityScenario.launch<FileViewerActivity>(FileViewerActivity.intent(context, word.id, AppLanguage.RUSSIAN)).use { scenario ->
            waitUntil(scenario, "Wide Word ready") { it.findViewById<View>(R.id.viewer_web).visibility == View.VISIBLE }
            lateinit var web: WebView
            scenario.onActivity { activity ->
                web = activity.findViewById(R.id.viewer_web)
                assertFalse(web.settings.javaScriptEnabled)
                assertTrue(web.settings.builtInZoomControls)
                assertTrue(activity.findViewById<MaterialToolbar>(R.id.viewer_toolbar).menu.findItem(R.id.viewer_page_a4).isVisible)
            }
            assertEquals("Table scrolls inside reading page", "true", evaluate(web, "var t=document.querySelector('.paper .table');t.scrollWidth>t.clientWidth+100&&document.documentElement.scrollWidth<=innerWidth+2"))
            assertEquals("Merged header and formula survive", "true", evaluate(web, "!!document.querySelector('td[rowspan=\"2\"]')&&!!document.querySelector('td[colspan=\"3\"]')&&document.querySelector('.word-equation').textContent==='x2y'"))
            screenshot("audit-1.16-docx-reading")
            // Zoomed out to 80%, the page is laid out wider and the table keeps its own sideways scroll.
            repeat(2) { scenario.onActivity { activity -> activity.findViewById<MaterialToolbar>(R.id.viewer_toolbar).menu.performIdentifierAction(R.id.viewer_zoom_out, 0) }; Thread.sleep(700) }
            assertEquals("\"0.8\"", evaluate(web, "getComputedStyle(document.documentElement).zoom"))
            screenshot("viewer-docx-zoom-80")
            scenario.recreate()
            waitUntil(scenario, "Zoom restored") { activity -> activity.findViewById<View>(R.id.viewer_web).visibility == View.VISIBLE }
            Thread.sleep(700)
            scenario.onActivity { activity -> web = activity.findViewById(R.id.viewer_web) }
            assertEquals("\"0.8\"", evaluate(web, "getComputedStyle(document.documentElement).zoom"))
        }
    }

    @Test fun downloadSortRespectsSearchAndSurvivesRecreation() {
        val now = System.currentTimeMillis()
        listOf(Triple("C UI-SORT-QA.pdf", 500L, now), Triple("A UI-SORT-QA.pdf", 100L, now - 1), Triple("B UI-SORT-QA.pdf", 900L, now - 2)).forEach { (name, size, date) ->
            add(DownloadRecord("qa-" + UUID.randomUUID(), name, null, "application/pdf", DownloadState.FAILED, 0, size, date))
        }
        ActivityScenario.launch<DownloadsActivity>(DownloadsActivity.intent(context, AppLanguage.RUSSIAN)).use { scenario ->
            scenario.onActivity { it.findViewById<TextView>(R.id.downloads_search).text = "UI-SORT-QA" }
            waitUntil(scenario, "Filtered list") { it.findViewById<RecyclerView>(R.id.downloads_list).adapter!!.itemCount == 3 }
            fun first(activity: DownloadsActivity) = activity.findViewById<RecyclerView>(R.id.downloads_list).findViewHolderForAdapterPosition(0)?.itemView?.findViewById<TextView>(R.id.download_name)?.text.toString()
            waitUntil(scenario, "Newest first") { first(it) == "C UI-SORT-QA.pdf" }
            scenario.onActivity { it.findViewById<MaterialToolbar>(R.id.downloads_toolbar).menu.performIdentifierAction(R.id.downloads_sort_name, 0) }
            waitUntil(scenario, "Alphabetical order") { first(it) == "A UI-SORT-QA.pdf" }
            scenario.onActivity { it.findViewById<MaterialToolbar>(R.id.downloads_toolbar).menu.performIdentifierAction(R.id.downloads_sort_size, 0) }
            waitUntil(scenario, "Largest first") { first(it) == "B UI-SORT-QA.pdf" }
            scenario.recreate()
            waitUntil(scenario, "Sort and search restored") { activity ->
                activity.findViewById<MaterialToolbar>(R.id.downloads_toolbar).menu.findItem(R.id.downloads_sort_size).isChecked &&
                    activity.findViewById<RecyclerView>(R.id.downloads_list).adapter!!.itemCount == 3 && first(activity) == "B UI-SORT-QA.pdf"
            }
        }
    }

    @Test fun historyCodecDropsDamagedEntriesAndForeignAddresses() {
        val good = DownloadRecord("a", "Лекция.pdf", "content://media/external/downloads/1", "application/pdf", DownloadState.COMPLETED, 5, 5, 1, 2, "Курс")
        val text = DownloadHistoryCodec.encode(listOf(good, good.copy(id = "b", uri = "file:///sdcard/Download/x.pdf"), good.copy(id = "f", failure = DownloadFailure.NETWORK)))
        // Unknown states, entries without an id and non-objects are skipped instead of failing the whole list.
        val damaged = text.dropLast(1) + ",{\"id\":\"c\",\"state\":\"BROKEN\",\"started\":1},{\"fileName\":\"x\",\"state\":\"COMPLETED\",\"started\":1},42]"
        val decoded = DownloadHistoryCodec.decode(damaged)
        assertEquals(listOf("a", "b", "f"), decoded.map { it.id })
        assertEquals(good, decoded[0])
        assertNull("Only content URIs are trusted", decoded[1].uri)
        assertEquals(DownloadFailure.NETWORK, decoded[2].failure)
        assertFalse(text.contains("https://"))
        // The repeat-download key survives a round trip; anything but a 32-digit hex digest is dropped.
        val key = DownloadKeys.of("https://univer.kaznu.kz/student/umkd/get/1/2/2026/1")
        val keyed = DownloadHistoryCodec.decode(DownloadHistoryCodec.encode(listOf(good.copy(key = key), good.copy(id = "x", key = "https://univer.kaznu.kz/"))))
        assertEquals(listOf(key, ""), keyed.map { it.key })
    }
}
