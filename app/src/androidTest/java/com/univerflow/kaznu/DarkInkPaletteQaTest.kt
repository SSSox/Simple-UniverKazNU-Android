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
 * In the dark theme the palette turned white foreground paints into the dark surface colour: a link's glyph on its
 * coloured plaque and the schedule switch's knob became nearly black. Synthetic page; nothing from the network.
 */
@RunWith(AndroidJUnit4::class)
class DarkInkPaletteQaTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var web: WebView

    private fun evaluate(script: String): String {
        val latch = CountDownLatch(1)
        var result = "null"
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { result = it; latch.countDown() } }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        return result
    }

    @After fun close() { instrumentation.runOnMainSync { web.destroyFixture() } }

    @SuppressLint("SetJavaScriptEnabled")
    @Test fun whiteGlyphsAndTheSwitchKnobStayWhiteWhileSurfacesTurnDark() {
        val assets = instrumentation.targetContext.assets
        val modules = listOf("univer-palette.js", "univer-shortcuts-css.js", "univer-mobile-css.js", "univer-motion.js", "univer-style.js")
            .joinToString("\n") { name -> assets.open(name).bufferedReader().use { it.readText() } }
        val html = """<!doctype html><html><head><meta charset="utf-8"></head><body><table><tr id="l4"><td>
            <span class="univer-app-link-icon" id="icon" style="--tint:#6d28d9"></span>
            <label class="univer-app-schedule-mode">Подробно<input type="checkbox" id="knob" checked></label>
            <span class="univer-kit-thumb" id="surface"></span>
            </td></tr></table></body></html>"""
        val loaded = CountDownLatch(1)
        instrumentation.runOnMainSync {
            web = WebView(instrumentation.targetContext)
            web.settings.javaScriptEnabled = true
            web.webViewClient = object : WebViewClient() {
                // The fixture document itself is the main-frame load; everything it asks for gets a local 404.
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    if (request.isForMainFrame) null
                    else WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun onPageFinished(view: WebView, url: String?) { loaded.countDown() }
            }
            web.loadDataWithBaseURL("https://univer.kaznu.kz/student/myschedule/", html, "text/html", "UTF-8", null)
        }
        assertTrue(loaded.await(15, TimeUnit.SECONDS))
        // The dark theme with the home tiles' stylesheet and the presentation layer, each drawn by the palette.
        evaluate(modules + "\nwindow.__univerDark=true;document.documentElement.classList.add('univer-mobile');" +
            "var s=document.createElement('style');s.id='univer-shortcuts-style';window.__univerPaletteV1.fill(s,window.__univerShortcutsCssV1);" +
            "document.head.appendChild(s);window.__univerStyleV1.apply(true);true")
        fun background(id: String, pseudo: String? = null) =
            evaluate("getComputedStyle(document.getElementById('$id')" + (pseudo?.let { ",'$it'" } ?: "") + ").backgroundColor")

        assertTrue(evaluate("document.documentElement.className").contains("univer-app-dark"))
        assertEquals("a white surface of the same stylesheet turns dark", "\"rgb(23, 32, 42)\"", background("surface"))
        assertEquals("a white glyph on its coloured plaque stays white", "\"rgb(255, 255, 255)\"", background("icon", "::before"))
        assertEquals("the switch knob stays white on its track", "\"rgb(255, 255, 255)\"", background("knob", "::before"))
    }
}
