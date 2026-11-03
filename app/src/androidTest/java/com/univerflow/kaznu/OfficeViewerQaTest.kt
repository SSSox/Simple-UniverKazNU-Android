package com.univerflow.kaznu

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.View
import android.webkit.WebView
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
 * Spreadsheets, presentations and web pages in the offline viewer, light and dark, from the unit tests' Office files.
 * Entries stay in memory; the installed account's list and theme are untouched and created files are deleted.
 */
@RunWith(AndroidJUnit4::class)
class OfficeViewerQaTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val repository get() = DownloadRepository.get(context)
    private val files = mutableListOf<Uri>()
    private val records = mutableListOf<String>()
    private var nightMode = AppCompatDelegate.MODE_NIGHT_UNSPECIFIED

    @Before fun ready() {
        assumeTrue("MediaStore Downloads needs API 29", Build.VERSION.SDK_INT >= 29)
        runBlocking { repository.awaitLoaded() }
        nightMode = AppCompatDelegate.getDefaultNightMode()
    }

    @After fun cleanUp() {
        records.forEach { repository.remove(it); DownloadTextIndex(context).forget(it) }
        files.forEach { context.contentResolver.delete(it, null, null) }
        instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(nightMode) }
    }

    private fun asset(name: String) = instrumentation.context.assets.open("documents/$name").use { it.readBytes() }

    private fun save(name: String, mime: String): DownloadRecord {
        val bytes = asset(name)
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
            // The viewer runs without JavaScript; the test enables it only to inspect the rendered page.
            @Suppress("SetJavaScriptEnabled")
            web.settings.javaScriptEnabled = true
            web.evaluateJavascript(script) { result = it; latch.countDown() }
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        return result
    }

    private fun screenshot(name: String) {
        Thread.sleep(700)
        val image = instrumentation.uiAutomation.takeScreenshot() ?: return
        val directory = File(context.getExternalFilesDir(null), "qa").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    /** Opens [record], checks the page and its theme, and returns the body markup as the WebView parsed it. */
    private fun open(record: DownloadRecord, dark: Boolean, shot: String, check: (WebView) -> Unit = {}): String =
        ActivityScenario.launch<FileViewerActivity>(FileViewerActivity.intent(context, record.id, AppLanguage.RUSSIAN)).use { scenario ->
            val end = System.currentTimeMillis() + 20_000
            var visible = false
            while (!visible && System.currentTimeMillis() < end) {
                scenario.onActivity { visible = it.findViewById<View>(R.id.viewer_web).visibility == View.VISIBLE }
                if (!visible) Thread.sleep(200)
            }
            assertTrue("${record.fileName} rendered", visible)
            lateinit var web: WebView
            scenario.onActivity { web = it.findViewById(R.id.viewer_web) }
            assertEquals(if (dark) "\"dark\"" else "\"\"", evaluate(web, "document.documentElement.className"))
            // Nothing in the page ever runs: no script element survives and the policy forbids every request.
            assertEquals("0", evaluate(web, "document.scripts.length"))
            assertTrue(evaluate(web, "document.querySelector('meta[http-equiv]').content").contains("default-src 'none'"))
            // The page itself never scrolls sideways; wide tables scroll inside their own frame.
            assertEquals("true", evaluate(web, "document.documentElement.scrollWidth<=window.innerWidth+1"))
            check(web)
            screenshot(shot)
            evaluate(web, "document.body.innerHTML")
        }

    @Test fun spreadsheetsSlidesAndWebPagesLookAlikeInTheirFamilyInBothThemes() {
        for (dark in listOf(false, true)) {
            instrumentation.runOnMainSync {
                AppCompatDelegate.setDefaultNightMode(if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
            }
            val theme = if (dark) "dark" else "light"
            val sheet = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            val xlsx = open(save("table.xlsx", sheet), dark, "viewer-xlsx-$theme") { web ->
                assertEquals("true", evaluate(web, "(function(g){return g.scrollWidth>g.clientWidth})(document.querySelector('.grid'))"))
                assertEquals("\"sticky\"", evaluate(web, "getComputedStyle(document.querySelector('.grid thead th')).position"))
            }
            assertEquals(xlsx, open(save("table.xls", "application/vnd.ms-excel"), dark, "viewer-xls-$theme"))
            assertTrue(xlsx, xlsx.contains("Ведомость: Информационная безопасность") && xlsx.contains("rowspan=\\\"2\\\""))
            val pptx = open(save("deck.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"), dark, "viewer-pptx-$theme")
            assertEquals(pptx, open(save("deck.ppt", "application/vnd.ms-powerpoint"), dark, "viewer-ppt-$theme"))
            assertTrue(pptx, pptx.contains("Основные понятия") && pptx.contains("background-image:url(https://univer-files.local/media/"))
            val page = open(save("page.html", "text/html"), dark, "viewer-html-$theme") { web ->
                assertEquals("0", evaluate(web, "document.querySelectorAll('iframe,form,input,object,embed,svg,[onclick],[onload]').length"))
                // The only url() left in a style is the viewer's own picture box, pointing at its own pictures.
                assertEquals("true", evaluate(web, "Array.from(document.querySelectorAll('[style*=url]')).every(e=>e.closest('.pic')&&" +
                    "e.style.backgroundImage.startsWith('url(\"https://univer-files.local/media/'))"))
                assertEquals("\"https://www.kaznu.kz/\"", evaluate(web, "document.querySelector('a[href]').href"))
            }
            assertTrue(page, page.contains("Расписание экзаменов") && !page.contains("example.com"))
        }
    }
}
