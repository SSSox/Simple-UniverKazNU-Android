package com.univerflow.kaznu

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.view.View
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.univerflow.kaznu.data.DownloadRepository
import com.univerflow.kaznu.data.DownloadTextIndex
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.domain.DownloadRecord
import com.univerflow.kaznu.domain.DownloadState
import com.univerflow.kaznu.download.PublicDownloadStore
import com.univerflow.kaznu.preview.PreviewHtml
import com.univerflow.kaznu.ui.FileViewerActivity
import com.univerflow.kaznu.web.configureOfflineViewer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Presentations full of photos open in the viewer: deck-photos.pptx/.ppt (six 1.3 MB photos, past one 8 MB page) and a
 * 65 MB .pptx made on the device. Entries stay in memory and the files are deleted afterwards.
 */
@RunWith(AndroidJUnit4::class)
class LargeDocumentsQaTest {
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

    private fun save(name: String, mime: String, write: (java.io.OutputStream) -> Unit): DownloadRecord {
        val fileName = "Univer-QA-${UUID.randomUUID().toString().take(8)}-$name"
        var size = 0L
        val uri = PublicDownloadStore(context).create(fileName, mime).use { target ->
            val counting = object : java.io.FilterOutputStream(target.output) {
                override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); size += len }
                override fun write(b: Int) { out.write(b); size++ }
            }
            write(counting)
            counting.flush()
            target.publish()
        }
        files += uri
        val record = DownloadRecord("qa-" + UUID.randomUUID(), fileName, uri.toString(), mime, DownloadState.COMPLETED,
            size, size, System.currentTimeMillis(), System.currentTimeMillis(), "QA")
        records += record.id
        repository.upsert(record, persistNow = false) // Test entries are never written to the installed history.
        return record
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
        Thread.sleep(600)
        val image = instrumentation.uiAutomation.takeScreenshot() ?: return
        val directory = File(context.getExternalFilesDir(null), "qa").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    /**
     * Opens [record] and returns the body markup once the first picture has loaded, with the number of pictures. With
     * [print], the system print dialog opens on the printed copy and is closed unprinted.
     */
    private fun open(record: DownloadRecord, shot: String, print: Boolean = false): Pair<String, Int> =
        ActivityScenario.launch<FileViewerActivity>(FileViewerActivity.intent(context, record.id, AppLanguage.RUSSIAN)).use { scenario ->
            var visible = false
            var message: String? = null
            val end = System.currentTimeMillis() + 90_000
            while (!visible && message == null && System.currentTimeMillis() < end) {
                scenario.onActivity {
                    visible = it.findViewById<View>(R.id.viewer_web).visibility == View.VISIBLE
                    if (it.findViewById<View>(R.id.viewer_message).visibility == View.VISIBLE) message = it.findViewById<android.widget.TextView>(R.id.viewer_message_text).text.toString()
                }
                if (!visible) Thread.sleep(300)
            }
            assertNull("${record.fileName}: $message", message)
            assertTrue("${record.fileName} rendered", visible)
            lateinit var web: WebView
            scenario.onActivity { web = it.findViewById(R.id.viewer_web) }
            // Pictures are the viewer's own files, not part of the page, drawn as boxes whose background is fetched near the screen.
            val fetched = "performance.getEntriesByType('resource').filter(e=>e.name.indexOf('/media/')>0&&e.responseEnd>0)"
            val first = "(u=>$fetched.some(e=>e.name===u))(getComputedStyle(document.querySelector('.pic>span>span')).backgroundImage.slice(5,-2))"
            val loaded = System.currentTimeMillis() + 15_000
            while (evaluate(web, first) != "true" && System.currentTimeMillis() < loaded) Thread.sleep(300)
            assertEquals("First picture shown", "true", evaluate(web, first))
            assertEquals("0", evaluate(web, "document.images.length"))
            assertTrue(evaluate(web, "document.documentElement.outerHTML.length").toInt() < 200_000)
            Thread.sleep(1000)
            val pictures = evaluate(web, "document.querySelectorAll('.pic').length").toInt()
            val distinct = evaluate(web, "new Set(Array.from(document.querySelectorAll('.pic>span>span')).map(s=>s.style.backgroundImage)).size").toInt()
            val near = evaluate(web, "new Set($fetched.map(e=>e.name)).size").toInt()
            android.util.Log.i("UniverQA", "$shot: $pictures pictures ($distinct different), $near fetched at the top")
            if (distinct > 3) assertTrue("Only pictures near the screen: $near of $distinct", near < distinct)
            screenshot(shot)
            if (print) {
                lateinit var toolbar: com.google.android.material.appbar.MaterialToolbar
                scenario.onActivity { toolbar = it.findViewById(R.id.viewer_toolbar) }
                instrumentation.runOnMainSync { toolbar.menu.performIdentifierAction(R.id.viewer_print, 0) }
                val end = System.currentTimeMillis() + 20_000
                while (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("printspooler") != true &&
                    System.currentTimeMillis() < end) Thread.sleep(300)
                assertEquals("Print dialog open", true, instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("printspooler"))
                Thread.sleep(4000) // The preview is drawn from the printed copy.
                screenshot("$shot-print")
                instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                Thread.sleep(1500)
            }
            evaluate(web, "document.body.innerHTML") to pictures
        }

    /**
     * The printed copy of a document: an unattached WebView, as the viewer prints it, loads the copy and has every picture
     * read in full when its page finishes loading, which is when the viewer hands it to the printer.
     */
    @Test fun thePrintedCopyHasEveryPictureWhenItFinishesLoading() {
        val png = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).let { bitmap ->
            bitmap.eraseColor(Color.rgb(27, 95, 166))
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle() }.toByteArray()
        }
        val count = 30
        fun name(index: Int) = "%064x".format(index + 1)
        val body = (0 until count).joinToString("") { index ->
            "<p>${"Длинный текст между картинками. ".repeat(300)}</p><p>" + PreviewHtml.picture("${PreviewHtml.mediaUrl(name(index))}#400x300") + "</p>"
        }
        val page = PreviewHtml.docx("print.docx", "ru", null, body, PreviewHtml.Style(print = true)).toByteArray()
        val read = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val other = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val finished = CountDownLatch(1)
        var complete = -1
        lateinit var web: WebView
        instrumentation.runOnMainSync {
            web = WebView(context)
            web.configureOfflineViewer()
            web.webViewClient = object : android.webkit.WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                    val path = request.url.path.orEmpty()
                    if (path == "/print.html") return android.webkit.WebResourceResponse("text/html", "utf-8", java.io.ByteArrayInputStream(page))
                    // WebView also asks for things that are not pictures of the document, such as an icon for the page.
                    if (!path.startsWith("/media/")) { other += path; return null }
                    val media = path.removePrefix("/media/")
                    // A picture counts once WebView has read its last byte.
                    return android.webkit.WebResourceResponse("image/png", null, object : java.io.FilterInputStream(java.io.ByteArrayInputStream(png)) {
                        override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, len).also { if (it < 0) read += media }
                    })
                }
                override fun onPageFinished(view: WebView, url: String?) { complete = read.size; finished.countDown() }
            }
            web.loadUrl(PreviewHtml.PRINT)
        }
        assertTrue(finished.await(30, TimeUnit.SECONDS))
        android.util.Log.i("UniverQA", "printed copy: $complete of $count pictures read when the page finished loading, other requests: $other")
        instrumentation.runOnMainSync { web.destroy() }
        assertEquals(count, complete)
    }

    private fun asset(name: String) = instrumentation.context.assets.open("documents/$name").use { it.readBytes() }

    @Test fun presentationsFullOfPhotosOpenAndMatchInBothFormats() {
        // The two decks (16 MB) are kept out of version control: without a local copy there is nothing to open.
        val documents = instrumentation.context.assets.list("documents").orEmpty()
        assumeTrue("deck-photos.pptx/.ppt are not in androidTest/assets/documents", "deck-photos.pptx" in documents && "deck-photos.ppt" in documents)
        val pptx = open(save("deck-photos.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation") { it.write(asset("deck-photos.pptx")) }, "large-pptx", print = true)
        val ppt = open(save("deck-photos.ppt", "application/vnd.ms-powerpoint") { it.write(asset("deck-photos.ppt")) }, "large-ppt")
        assertEquals(6, pptx.second)
        assertEquals(pptx, ppt)
    }

    /** A 1500-page PDF shows its first page without waiting for the others; the rest of the pages follow. */
    @Test fun aLongPdfShowsItsFirstPagesAtOnce() {
        val pages = 1500
        val record = save("long.pdf", "application/pdf") { output ->
            val document = android.graphics.pdf.PdfDocument()
            val paint = android.graphics.Paint().apply { textSize = 11f; color = Color.BLACK }
            for (index in 1..pages) {
                val page = document.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, index).create())
                page.canvas.drawText("Univer QA — страница $index", 72f, 72f, paint.apply { textSize = 20f })
                paint.textSize = 11f
                for (line in 0 until 50) page.canvas.drawText("Строка $line: Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor.", 72f, 110f + line * 14, paint)
                document.finishPage(page)
            }
            document.writeTo(output)
            document.close()
        }
        val start = System.currentTimeMillis()
        ActivityScenario.launch<FileViewerActivity>(FileViewerActivity.intent(context, record.id, AppLanguage.RUSSIAN)).use { scenario ->
            var visible = false
            while (!visible && System.currentTimeMillis() - start < 90_000) {
                scenario.onActivity { visible = it.findViewById<View>(R.id.viewer_web).visibility == View.VISIBLE }
                if (!visible) Thread.sleep(50)
            }
            val shown = System.currentTimeMillis() - start
            assertTrue("PDF rendered", visible)
            lateinit var web: WebView
            scenario.onActivity { web = it.findViewById(R.id.viewer_web) }
            // Pages are background images; resource timing tells which ones WebView has fetched.
            val fetched = "performance.getEntriesByType('resource').filter(e=>e.name.indexOf('/page/')>0&&e.responseEnd>0)"
            while (evaluate(web, "$fetched.some(e=>e.name.endsWith('/page/0.png'))") != "true" && System.currentTimeMillis() - start < 60_000) Thread.sleep(50)
            val first = System.currentTimeMillis() - start
            screenshot("long-pdf-first")
            while (evaluate(web, "document.readyState") != "\"complete\"" && System.currentTimeMillis() - start < 120_000) Thread.sleep(100)
            val all = System.currentTimeMillis() - start
            Thread.sleep(1500)
            val near = evaluate(web, "$fetched.length").toInt()
            android.util.Log.i("UniverQA", "long pdf: visible $shown ms, first page $first ms, all $pages pages listed $all ms, pages fetched at the top $near")
            assertEquals(pages.toString(), evaluate(web, "document.querySelectorAll('figure.page').length"))
            assertEquals("\"Страница 1 из $pages\"", evaluate(web, "document.querySelector('figcaption').textContent"))
            assertTrue("First page after $first ms", first < 5_000)
            // Only the pages near the screen are drawn, not all of them.
            assertTrue("Pages fetched at the top: $near", near in 1..12)
            // Far down the file, the pages there are drawn when they come near.
            evaluate(web, "document.querySelectorAll('figure.page')[1200].scrollIntoView()")
            val far = System.currentTimeMillis()
            while (evaluate(web, "$fetched.some(e=>e.name.endsWith('/page/1200.png'))") != "true" && System.currentTimeMillis() - far < 15_000) Thread.sleep(50)
            android.util.Log.i("UniverQA", "long pdf: page 1201 drawn ${System.currentTimeMillis() - far} ms after scrolling, fetched ${evaluate(web, "$fetched.length")}")
            assertEquals("true", evaluate(web, "$fetched.some(e=>e.name.endsWith('/page/1200.png'))"))
            assertTrue(evaluate(web, "$fetched.length").toInt() < 40)
            screenshot("long-pdf-far")
        }
    }

    @Test fun aSixtyMegabytePresentationOpens() {
        val random = Random(3)
        val pixels = IntArray(1200 * 900)
        val record = save("big.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation") { output ->
            val p = "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
            ZipOutputStream(output).use { zip ->
                fun put(name: String, data: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() }
                put("ppt/presentation.xml", "<p:presentation $p><p:sldIdLst>${(1..20).joinToString("") { "<p:sldId id=\"${255 + it}\" r:id=\"s$it\"/>" }}</p:sldIdLst></p:presentation>".toByteArray())
                put("ppt/_rels/presentation.xml.rels", ("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    (1..20).joinToString("") { "<Relationship Id=\"s$it\" Type=\"x/slide\" Target=\"slides/slide$it.xml\"/>" } + "</Relationships>").toByteArray())
                for (index in 1..20) {
                    put("ppt/slides/slide$index.xml", ("<p:sld $p><p:cSld><p:spTree><p:sp><p:nvSpPr><p:nvPr><p:ph type=\"title\"/></p:nvPr></p:nvSpPr>" +
                        "<p:txBody><a:p><a:r><a:t>Фото $index</a:t></a:r></a:p></p:txBody></p:sp><p:pic><p:blipFill><a:blip r:embed=\"i\"/></p:blipFill></p:pic>" +
                        "</p:spTree></p:cSld></p:sld>").toByteArray())
                    put("ppt/slides/_rels/slide$index.xml.rels", ("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                        "<Relationship Id=\"i\" Type=\"x/image\" Target=\"../media/image$index.png\"/></Relationships>").toByteArray())
                    for (i in pixels.indices) pixels[i] = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
                    val bitmap = Bitmap.createBitmap(pixels, 1200, 900, Bitmap.Config.ARGB_8888)
                    put("ppt/media/image$index.png", ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle() }.toByteArray())
                }
            }
        }
        assertTrue("File past the old 64 MB limit: ${record.total}", record.total > 64L * 1024 * 1024)
        assertEquals(20, open(record, "large-pptx-60mb").second)
    }
}
