package com.univerflow.kaznu

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.appbar.MaterialToolbar
import com.univerflow.kaznu.data.DownloadRepository
import com.univerflow.kaznu.data.DownloadTextIndex
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.domain.DownloadRecord
import com.univerflow.kaznu.domain.DownloadState
import com.univerflow.kaznu.download.PublicDownloadStore
import com.univerflow.kaznu.ui.FileViewerActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Zoom in every format the viewer opens: the menu's "Zoom in"/"Zoom out" and a two-finger pinch both change the page
 * scale, and the page can be made smaller again after zooming in. Synthetic files only; entries stay in memory.
 */
@RunWith(AndroidJUnit4::class)
class ViewerZoomQaTest {
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

    private fun asset(name: String) = instrumentation.context.assets.open("documents/$name").use { it.readBytes() }

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

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply { drawColor(Color.rgb(233, 241, 251)); drawCircle(400f, 300f, 200f, Paint().apply { color = Color.rgb(27, 95, 166) }) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle() }.toByteArray()
    }

    private fun save(name: String, mime: String, bytes: ByteArray): DownloadRecord {
        val fileName = "Univer-QA-${UUID.randomUUID().toString().take(8)}-$name"
        val uri = PublicDownloadStore(context).create(fileName, mime).use { it.output.write(bytes); it.publish() }
        files += uri
        val record = DownloadRecord("qa-" + UUID.randomUUID(), fileName, uri.toString(), mime, DownloadState.COMPLETED,
            bytes.size.toLong(), bytes.size.toLong(), System.currentTimeMillis(), System.currentTimeMillis(), "QA")
        records += record.id
        repository.upsert(record, persistNow = false) // Test entries are never written to the installed history.
        return record
    }

    private fun evaluate(web: WebView, script: String): String {
        val latch = CountDownLatch(1)
        var result = "null"
        instrumentation.runOnMainSync {
            // The viewer runs without JavaScript; the test enables it only to read the page scale.
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
        val directory = java.io.File(context.getExternalFilesDir(null), "qa").apply { mkdirs() }
        java.io.File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    /** Two fingers moving apart ([outward]) or together around the middle of [view]. */
    private fun pinch(view: View, outward: Boolean) {
        val location = IntArray(2)
        instrumentation.runOnMainSync { view.getLocationOnScreen(location) }
        pinchAt(location[0] + view.width / 2f, location[1] + view.height / 2f, outward)
    }

    /** Two fingers around the screen point ([x], [y]). */
    private fun pinchAt(x: Float, y: Float, outward: Boolean = true) {
        val (from, to) = if (outward) 80f to 380f else 380f to 80f
        val properties = Array(2) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        fun coords(spread: Float) = arrayOf(
            MotionEvent.PointerCoords().apply { this.x = x - spread; this.y = y; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { this.x = x + spread; this.y = y; pressure = 1f; size = 1f })
        val down = SystemClock.uptimeMillis()
        var time = down
        fun send(action: Int, count: Int, spread: Float) {
            val event = MotionEvent.obtain(down, time, action, count, properties, coords(spread), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            instrumentation.uiAutomation.injectInputEvent(event, true)
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, 1, from)
        time += 10
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, from)
        for (step in 1..20) { time += 16; send(MotionEvent.ACTION_MOVE, 2, from + (to - from) * step / 20) }
        time += 10
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, to)
        time += 10
        send(MotionEvent.ACTION_UP, 1, to)
    }

    @Test fun theTextSizeSettingReachesDownloadedFiles() {
        val preferences = com.univerflow.kaznu.data.AppPreferences(context)
        val saved = preferences.textZoom
        try {
            // The smallest size the settings offer now; the user's own size is put back afterwards.
            preferences.textZoom = 60
            val record = save("notes.txt", "text/plain", "Размер текста в просмотре файлов.".toByteArray())
            ActivityScenario.launch<FileViewerActivity>(FileViewerActivity.intent(context, record.id, AppLanguage.RUSSIAN)).use { scenario ->
                val end = System.currentTimeMillis() + 20_000
                var visible = false
                while (!visible && System.currentTimeMillis() < end) {
                    scenario.onActivity { visible = it.findViewById<View>(R.id.viewer_web).visibility == View.VISIBLE }
                    if (!visible) Thread.sleep(200)
                }
                var zoom = 0
                var base = 0
                // WebView's own default follows the system font size; the setting scales it, as on the website.
                scenario.onActivity { zoom = it.findViewById<WebView>(R.id.viewer_web).settings.textZoom; base = WebView(it).settings.textZoom }
                assertEquals(base * 60 / 100, zoom)
            }
        } finally {
            preferences.textZoom = saved
        }
        assertEquals(60, com.univerflow.kaznu.domain.TextZoom.MIN)
    }

    @Test fun everyFormatZoomsFromTheMenuAndWithTwoFingers() {
        val formats = listOf(
            Triple("page.pdf", "application/pdf", pdf()),
            Triple("picture.png", "image/png", png()),
            Triple("notes.txt", "text/plain", ("Заметки по курсу. ".repeat(40) + "\nВторая строка").toByteArray()),
            Triple("grades.csv", "text/csv", "Студент;Балл\nИванов;95\nӘлия;87,5".toByteArray()),
            Triple("word.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", asset("word.docx")),
            Triple("word.doc", "application/msword", asset("word.doc")),
            Triple("word.rtf", "application/rtf", asset("word.rtf")),
            Triple("table.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", asset("table.xlsx")),
            Triple("table.xls", "application/vnd.ms-excel", asset("table.xls")),
            Triple("deck.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", asset("deck.pptx")),
            Triple("deck.ppt", "application/vnd.ms-powerpoint", asset("deck.ppt")),
            Triple("page.html", "text/html", asset("page.html")),
        )
        val report = mutableListOf<String>()
        var failed = false
        for ((name, mime, bytes) in formats) {
            val record = save(name, mime, bytes)
            ActivityScenario.launch<FileViewerActivity>(FileViewerActivity.intent(context, record.id, AppLanguage.RUSSIAN)).use { scenario ->
                val end = System.currentTimeMillis() + 20_000
                var visible = false
                while (!visible && System.currentTimeMillis() < end) {
                    scenario.onActivity { visible = it.findViewById<View>(R.id.viewer_web).visibility == View.VISIBLE }
                    if (!visible) Thread.sleep(200)
                }
                assertTrue("$name rendered", visible)
                lateinit var web: WebView
                lateinit var toolbar: MaterialToolbar
                var menu = false
                scenario.onActivity {
                    web = it.findViewById(R.id.viewer_web)
                    toolbar = it.findViewById(R.id.viewer_toolbar)
                    menu = toolbar.menu.findItem(R.id.viewer_zoom_in).isVisible && toolbar.menu.findItem(R.id.viewer_zoom_out).isVisible
                }
                fun action(id: Int) { scenario.onActivity { toolbar.menu.performIdentifierAction(id, 0) } }
                fun enabled(id: Int): Boolean { var on = false; scenario.onActivity { on = toolbar.menu.findItem(id).isEnabled }; return on }
                /** The page's zoom: its layout zoom (25–100%) times WebView's magnification. */
                fun zoom(): Double {
                    Thread.sleep(900)
                    return evaluate(web, "(parseFloat(getComputedStyle(document.documentElement).zoom)||1)*visualViewport.scale").toDoubleOrNull() ?: -1.0
                }
                /** Taps [id] while it is enabled (at most 20 times) and returns the zoom it ends at. */
                fun limit(id: Int): Double {
                    repeat(20) { if (enabled(id)) { action(id); Thread.sleep(450) } }
                    return zoom()
                }
                val start = zoom()
                // From 100% down the page is laid out again, smaller and wider, to 25%; the item is then inactive.
                val outAtStart = enabled(R.id.viewer_zoom_out)
                action(R.id.viewer_zoom_out)
                val stepDown = zoom()
                val minimum = limit(R.id.viewer_zoom_out)
                val outAtMinimum = enabled(R.id.viewer_zoom_out)
                // Up again through the layout steps, then magnified to 600%, and all the way back.
                val maximum = limit(R.id.viewer_zoom_in)
                val inAtMaximum = enabled(R.id.viewer_zoom_in)
                val bottom = limit(R.id.viewer_zoom_out)
                repeat(7) { action(R.id.viewer_zoom_in); Thread.sleep(450) }
                val reset = zoom()
                // Two fingers together at 100% lay the page out smaller; apart again bring it back; apart once more magnify it.
                pinch(web, outward = false)
                val pinchedIn = zoom()
                pinch(web, outward = true)
                val pinchedBack = zoom()
                pinch(web, outward = true)
                val magnified = zoom()
                pinch(web, outward = false)
                var ok = menu && outAtStart && kotlin.math.abs(stepDown - 0.9) < 0.01 && kotlin.math.abs(minimum - 0.25) < 0.01 && !outAtMinimum &&
                    maximum >= 5.9 && !inAtMaximum && kotlin.math.abs(bottom - 0.25) < 0.01 && kotlin.math.abs(reset - 1.0) < 0.01 &&
                    pinchedIn <= 0.34 && kotlin.math.abs(pinchedBack - 1.0) < 0.01 && magnified > 1.2
                var extra = ""
                if (name.startsWith("table.")) {
                    // Back to 100% first: down to 25% (magnification ends there too), then the seven steps up.
                    limit(R.id.viewer_zoom_out); repeat(7) { action(R.id.viewer_zoom_in); Thread.sleep(450) }
                    // Two fingers on the table itself zoom the page, even though the table scrolls sideways on its own.
                    val rect = evaluate(web, "(function(r){return [r.left+r.width/2,r.top+Math.min(r.height/2,150)].join(',')})(document.querySelector('.grid').getBoundingClientRect())")
                        .trim('"').split(',').map { it.toFloat() }
                    val location = IntArray(2)
                    var density = 1f
                    scenario.onActivity { web.getLocationOnScreen(location); density = it.resources.displayMetrics.density }
                    pinchAt(location[0] + rect[0] * density, location[1] + rect[1] * density)
                    val onTable = zoom()
                    ok = ok && onTable > 1.2
                    extra = " pinchOnTable=%.2f".format(onTable)
                }
                if (name.endsWith(".docx") || name.endsWith(".html")) {
                    // At 25% the reading page stops at an A4 sheet; with "A4 page width" off it fills the wider layout.
                    limit(R.id.viewer_zoom_out)
                    fun share() = evaluate(web, "document.querySelector('.paper').offsetWidth/document.body.clientWidth").toDoubleOrNull() ?: -1.0
                    var checked = false
                    scenario.onActivity { checked = toolbar.menu.findItem(R.id.viewer_page_a4).let { it.isVisible && it.isChecked } }
                    val a4 = share()
                    screenshot("zoom-25-a4-${name.substringAfterLast('.')}")
                    action(R.id.viewer_page_a4)
                    val full = zoom().let { share() }
                    screenshot("zoom-25-full-${name.substringAfterLast('.')}")
                    action(R.id.viewer_page_a4) // The setting is the user's: it is put back.
                    Thread.sleep(900)
                    ok = ok && checked && a4 < 0.6 && full > 0.95
                    extra += " a4Share=%.2f fullShare=%.2f".format(a4, full)
                }
                failed = failed || !ok
                report += "%s start=%.2f outAtStart=%s step=%.2f min=%.2f max=%.2f bottom=%.2f reset=%.2f pinchIn=%.2f pinchBack=%.2f magnified=%.2f%s %s".format(
                    name.substringAfterLast('.'), start, outAtStart, stepDown, minimum, maximum, bottom, reset, pinchedIn, pinchedBack, magnified, extra, if (ok) "OK" else "FAIL")
            }
        }
        android.util.Log.i("ViewerZoomQa", report.joinToString("\n"))
        assertFalse(report.joinToString("\n"), failed)
    }
}
