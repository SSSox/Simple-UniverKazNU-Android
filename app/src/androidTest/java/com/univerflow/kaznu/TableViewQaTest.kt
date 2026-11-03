package com.univerflow.kaznu

import android.graphics.Bitmap
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
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * "Open table" in a Word document, a web page and a workbook: a real tap opens it zoomed out, pinch and menu zoom it,
 * Back returns to the same place, rotation keeps it open. Synthetic files only, deleted afterwards.
 */
@RunWith(AndroidJUnit4::class)
class TableViewQaTest {
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

    private fun asset(name: String) = instrumentation.context.assets.open("documents/$name").use { it.readBytes() }

    private fun evaluate(web: WebView, script: String): String {
        val latch = CountDownLatch(1)
        var result = "null"
        instrumentation.runOnMainSync {
            // The viewer runs without JavaScript; the test enables it only to inspect the page.
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

    private fun touch(action: Int, down: Long, time: Long, count: Int, x: Float, y: Float, spread: Float = 0f) {
        val properties = Array(count) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(count) { index -> MotionEvent.PointerCoords().apply { this.x = if (index == 0) x - spread else x + spread; this.y = y; pressure = 1f; size = 1f } }
        val event = MotionEvent.obtain(down, time, action, count, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        instrumentation.uiAutomation.injectInputEvent(event, true)
        event.recycle()
    }

    private fun tap(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, down, down, 1, x, y)
        touch(MotionEvent.ACTION_UP, down, down + 60, 1, x, y)
    }

    /** Two fingers moving apart around the middle of [view]. */
    private fun pinchOut(view: View) {
        val location = IntArray(2)
        instrumentation.runOnMainSync { view.getLocationOnScreen(location) }
        val x = location[0] + view.width / 2f
        val y = location[1] + view.height / 2f
        val down = SystemClock.uptimeMillis()
        var time = down
        touch(MotionEvent.ACTION_DOWN, down, time, 1, x, y, 80f)
        time += 10
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), down, time, 2, x, y, 80f)
        for (step in 1..20) { time += 16; touch(MotionEvent.ACTION_MOVE, down, time, 2, x, y, 80f + 300f * step / 20) }
        time += 10
        touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), down, time, 2, x, y, 380f)
        touch(MotionEvent.ACTION_UP, down, time + 10, 1, x, y, 380f)
    }

    private fun waitFor(what: String, timeout: Long = 15_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (!condition()) {
            assertTrue(what, System.currentTimeMillis() < end)
            Thread.sleep(150)
        }
    }

    private fun check(record: DownloadRecord, shot: String) =
        ActivityScenario.launch<FileViewerActivity>(FileViewerActivity.intent(context, record.id, AppLanguage.RUSSIAN)).use { scenario ->
            lateinit var web: WebView
            lateinit var toolbar: MaterialToolbar
            scenario.onActivity { web = it.findViewById(R.id.viewer_web); toolbar = it.findViewById(R.id.viewer_toolbar) }
            waitFor("${record.fileName} rendered") { var on = false; scenario.onActivity { on = web.visibility == View.VISIBLE }; on }
            Thread.sleep(500)
            fun table(): WebView? { var found: WebView? = null; scenario.onActivity { found = it.findViewById(R.id.viewer_table_web) }; return found }
            fun visible(id: Int): Boolean { var on = false; scenario.onActivity { on = toolbar.menu.findItem(id).isVisible }; return on }
            fun enabled(id: Int): Boolean { var on = false; scenario.onActivity { on = toolbar.menu.findItem(id).isEnabled }; return on }
            fun subtitle(): String { var text = ""; scenario.onActivity { text = toolbar.subtitle?.toString().orEmpty() }; return text }
            fun scale(view: WebView) = evaluate(view, "visualViewport.scale").toDouble()
            // The link to the table is there; it is tapped like a finger would.
            assertEquals("\"Открыть таблицу\"", evaluate(web, "document.querySelector('.open-table a').textContent"))
            evaluate(web, "document.querySelector('.open-table a').scrollIntoView({block:'center'})")
            Thread.sleep(400)
            val documentScroll = evaluate(web, "scrollY")
            screenshot("$shot-document")
            fun openTable() {
                val point = evaluate(web, "(r=>[r.left+r.width/2,r.top+r.height/2])(document.querySelector('.open-table a').getBoundingClientRect())")
                    .trim('[', ']').split(',').map { it.toFloat() }
                val location = IntArray(2)
                var factor = 1f
                @Suppress("DEPRECATION") instrumentation.runOnMainSync { web.getLocationOnScreen(location); factor = web.scale }
                tap(location[0] + point[0] * factor, location[1] + point[1] * factor)
            }
            openTable()
            waitFor("table opened") { table() != null }
            val solo = table()!!
            waitFor("table page loaded") { evaluate(solo, "document.querySelector('.solo')!==null") == "true" }
            Thread.sleep(800)
            assertEquals("Таблица 1 из ${evaluate(web, "document.querySelectorAll('.open-table').length")}", subtitle())
            // Search, printing and the page width belong to the document; zoom works on the table.
            assertFalse(visible(R.id.viewer_find_toggle))
            assertFalse(visible(R.id.viewer_print))
            assertFalse(visible(R.id.viewer_page_a4))
            assertTrue(enabled(R.id.viewer_zoom_in))
            // It opens showing its whole width: zoomed out when it is wider than the screen.
            val overview = scale(solo)
            val width = evaluate(solo, "document.documentElement.scrollWidth").toDouble()
            val shown = evaluate(solo, "visualViewport.width").toDouble()
            android.util.Log.i("UniverQA", "$shot: table ${width}px, overview scale $overview, ${shown}px on screen")
            assertTrue("Whole width shown: $shown of $width", shown >= width - 2 || overview <= 0.26)
            val screen = solo.width / context.resources.displayMetrics.density
            if (width > screen + 2) assertTrue("Zoomed out: $overview", overview < 0.99) else assertEquals(1.0, overview, 0.02)
            assertFalse(enabled(R.id.viewer_zoom_out))
            screenshot("$shot-overview")
            // Two fingers zoom the table in, the menu zooms it back out to the overview.
            pinchOut(solo)
            Thread.sleep(800)
            val zoomed = scale(solo)
            assertTrue("Pinched: $overview -> $zoomed", zoomed > overview * 1.5)
            assertTrue(enabled(R.id.viewer_zoom_out))
            screenshot("$shot-zoomed")
            var steps = 0
            while (enabled(R.id.viewer_zoom_out) && steps++ < 40) { scenario.onActivity { toolbar.menu.performIdentifierAction(R.id.viewer_zoom_out, 0) }; Thread.sleep(250) }
            assertEquals(overview, scale(solo), 0.01)
            // Back returns to the document where it was read.
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            Thread.sleep(400)
            assertNull(table())
            assertTrue(visible(R.id.viewer_print))
            assertFalse(subtitle().startsWith("Таблица"))
            assertEquals(documentScroll, evaluate(web, "scrollY"))
            // A rotation keeps an open table open.
            openTable()
            waitFor("table opened again") { table() != null }
            scenario.recreate()
            waitFor("table open after recreation") { table()?.let { evaluate(it, "document.querySelector('.solo')!==null") == "true" } == true }
            scenario.onActivity { web = it.findViewById(R.id.viewer_web); toolbar = it.findViewById(R.id.viewer_toolbar) }
            assertTrue(subtitle().startsWith("Таблица 1 из"))
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            Thread.sleep(400)
            assertNull(table())
            // Back again leaves the viewer.
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            Thread.sleep(400)
            var finishing = false
            scenario.onActivity { finishing = it.isFinishing }
            assertTrue(finishing || scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED)
        }

    @Test fun tablesOpenOnTheirOwnScreenAndZoomApartFromTheDocument() {
        val rows = (1..30).joinToString("") { r -> "<tr>" + (1..10).joinToString("") { c -> "<td>Студент $r, оценка $c: зачтено</td>" } + "</tr>" }
        val page = "<!doctype html><html><body><h1>Ведомость</h1><p>Текст до таблицы.</p><table><tr>" +
            (1..10).joinToString("") { "<th>Неделя $it</th>" } + "</tr>$rows</table><p>Текст после.</p></body></html>"
        check(save("wide.html", "text/html", page.toByteArray()), "table-html")
        check(save("table.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", asset("table.xlsx")), "table-xlsx")
        check(save("word.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", asset("word.docx")), "table-docx")
    }
}
