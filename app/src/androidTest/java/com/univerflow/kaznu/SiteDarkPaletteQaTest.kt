package com.univerflow.kaznu

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The dark schedule showed a light bar: the site's empty tool row (tr.mid > td.tb) painted with its light gradient
 * picture. Synthetic page with the site's table frame and mtable.css rules; nothing from the network or an account.
 */
@RunWith(AndroidJUnit4::class)
class SiteDarkPaletteQaTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var web: WebView

    private fun evaluate(script: String): String {
        val latch = CountDownLatch(1)
        var result = "null"
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { result = it; latch.countDown() } }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        return result
    }

    @After fun close() { instrumentation.runOnMainSync { web.destroy() } }

    @SuppressLint("SetJavaScriptEnabled")
    @Test fun theSchedulesEmptyToolRowStaysHiddenAndTheSiteTableFrameIsDarkInTheDarkTheme() {
        val assets = instrumentation.targetContext.assets
        val modules = listOf("univer-palette.js", "univer-mobile-css.js", "univer-motion.js", "univer-style.js")
            .joinToString("\n") { name -> assets.open(name).bufferedReader().use { it.readText() } }
        val html = """<!doctype html><html><head><style>
            table.mt tr.top td.ct{background:url(/content/images/mt/top-center.gif) repeat-x}
            table.mt tr.mid td.tb{background:url(/content/images/mt/tools-center.gif) repeat-x}
            table.mt table.inner th{background:url(/content/images/mt/tbl-th-bg.gif) repeat-x;color:#000}
            .groups div div.gr_head{background:url(/content/images/schedule/v2.png)}
            </style></head><body><table><tr id="l4"><td>
            <table class="mt"><tbody>
              <tr class="top"><td class="lt"></td><td class="ct">Расписание</td><td class="rt"></td></tr>
              <tr class="mid"><td class="lt"></td><td class="ct"><div class="univer-app-schedule">Понедельник 09:00 История</div>
                <table class="inner"><tr><th>Время</th></tr></table></td><td class="rt"></td></tr>
              <tr class="mid univer-app-study-empty" id="empty"><td class="tb" colspan="3"></td></tr>
            </tbody></table></td></tr></table></body></html>"""
        val loaded = CountDownLatch(1)
        instrumentation.runOnMainSync {
            web = WebView(instrumentation.targetContext)
            web.settings.javaScriptEnabled = true
            web.webViewClient = object : WebViewClient() {
                // Nothing leaves the device: the site's pictures are answered empty.
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                    WebResourceResponse("image/gif", null, ByteArrayInputStream(ByteArray(0)))
                override fun onPageFinished(view: WebView, url: String?) { loaded.countDown() }
            }
            web.loadDataWithBaseURL("https://univer.kaznu.kz/student/myschedule/", html, "text/html", "UTF-8", null)
        }
        assertTrue(loaded.await(15, TimeUnit.SECONDS))
        // What the adapter has done by then: the phone layout of the schedule page, with its stylesheet.
        evaluate(modules + "\ndocument.documentElement.classList.add('univer-mobile','univer-app-schedule-page');" +
            "var s=document.createElement('style');s.id='univer-mobile';window.__univerPaletteV1.fill(s,window.__univerMobileCssV1);document.head.appendChild(s);")
        fun theme(dark: Boolean) = evaluate("window.__univerDark=$dark;window.__univerPaletteV1.fill(document.getElementById('univer-mobile'),window.__univerMobileCssV1);" +
            "window.__univerStyleV1.apply(true);document.documentElement.className")
        fun style(selector: String, property: String) = evaluate("getComputedStyle(document.querySelector('$selector')).getPropertyValue('$property')")

        assertTrue(theme(dark = true).contains("univer-app-dark"))
        // The empty row is hidden in the schedule's layout, which shows the table's other rows as blocks.
        assertEquals("\"none\"", style("#empty", "display"))
        assertEquals("\"block\"", style("table.mt tr.mid:not(#empty)", "display"))
        // The frame's light pictures are not drawn in the dark theme; other pictures of the site stay.
        assertEquals("\"none\"", style("table.inner th", "background-image"))
        assertEquals("\"none\"", style("#empty td.tb", "background-image"))
        assertTrue(evaluate("document.getElementById('univer-app-site-dark').textContent").contains(".groups div div.gr_head").not())

        // The light theme keeps the site's own look, and the empty row hidden.
        assertFalse(theme(dark = false).contains("univer-app-dark"))
        assertTrue(style("table.inner th", "background-image").contains("/content/images/mt/tbl-th-bg.gif"))
        assertEquals("\"none\"", style("#empty", "display"))
    }
}
