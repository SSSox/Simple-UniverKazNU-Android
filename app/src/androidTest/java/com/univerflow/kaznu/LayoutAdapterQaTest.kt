package com.univerflow.kaznu

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.ui.DownloadsActivity
import com.univerflow.kaznu.web.MobilePageAdapter
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The phone layout between page changes: touches, keys and the end of the page-enter motion start no adapter pass of
 * their own, the table view gives focus back to the table's own button, and a segment's thumb follows a text-size
 * change. Synthetic pages; every request is answered locally and nothing reaches the website.
 */
@RunWith(AndroidJUnit4::class)
class LayoutAdapterQaTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<DownloadsActivity>
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    @Before fun setup() {
        // A screen without a website session hosts the fixture WebView.
        scenario = ActivityScenario.launch(DownloadsActivity.intent(instrumentation.targetContext, AppLanguage.RUSSIAN))
        scenario.onActivity { activity ->
            web = WebView(activity)
            web.settings.javaScriptEnabled = true
            web.settings.useWideViewPort = true
            activity.setContentView(web)
            web.layoutParams = web.layoutParams.apply { width = (360 * activity.resources.displayMetrics.density).toInt() }
        }
    }

    @After fun close() {
        instrumentation.runOnMainSync { web.destroyFixture() }
        scenario.close()
    }

    private fun load(path: String, body: String) {
        val loaded = CountDownLatch(1)
        val address = "https://univer.kaznu.kz$path"
        instrumentation.runOnMainSync {
            web.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    if (request.isForMainFrame) null
                    else WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                // The fixture never leaves for the real website.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun onPageFinished(view: WebView, url: String?) { loaded.countDown() }
            }
            // The viewport the adapter sets is already there, so enabling the layout resizes nothing.
            web.loadDataWithBaseURL(address, """<!doctype html><html><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1"></head><body>$body</body></html>""",
                "text/html", "UTF-8", address)
        }
        assertTrue("Fixture loaded", loaded.await(15, TimeUnit.SECONDS))
    }

    private fun eval(script: String): String {
        val latch = CountDownLatch(1)
        var result = "null"
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { result = it; latch.countDown() } }
        assertTrue("JavaScript callback", latch.await(10, TimeUnit.SECONDS))
        return result
    }

    private fun asset(name: String) = instrumentation.targetContext.assets.open(name).bufferedReader().use { it.readText() }

    /**
     * Turns the phone layout on as the app does and counts the adapter's passes: each pass fits the wide tables once.
     * The stylesheet is inserted before the layout is on, so its insertion is not counted as a pass. [probe] runs right
     * after the layout is on and gives the result.
     */
    private fun adapt(bootstrap: Boolean = false, probe: String = "true"): String {
        val modules = MobilePageAdapter.SCRIPTS.joinToString("\n") { asset(it) }
        eval((if (bootstrap) asset("univer-page-start.js") + "\n" else "") + modules + "\nwindow.__univerStyleV1.apply(true);" +
            "window.passes=0;window.passTimes=[];(function(tables){var fit=tables.fit;tables.fit=function(){window.passes++;" +
            "window.passTimes.push(performance.now());return fit.apply(this,arguments);};})(window.__univerTablesV1);true")
        return eval("window.__univerMobileV1.setNativeChrome(true);window.__univerMobileV1.setEnabled(true,'ru');" +
            "window.__univerStyleV1.apply(true);window.enabledAt=performance.now();$probe")
    }

    @Test fun touchesAndRepeatedKeysStartNoAdapterPass() {
        load("/fixture/layout/input/", """<table class="wrappertable"><tr id="l4"><td class="center"><p>Новости факультета</p></td></tr></table>""")
        adapt()
        Thread.sleep(600)
        fun pointer() { eval("document.body.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true}));true"); Thread.sleep(250) }
        fun key(name: String) { eval("document.body.dispatchEvent(new KeyboardEvent('keydown',{key:${JSONObject.quote(name)},bubbles:true}));true"); Thread.sleep(250) }
        fun keyboard() = eval("document.documentElement.classList.contains('univer-app-keyboard')")

        eval("window.passes=0")
        repeat(3) { pointer() }
        assertEquals("touches without a keyboard start no adapter pass", "0", eval("window.passes"))
        key("Tab")
        assertEquals("a hardware key switches to keyboard mode", "true", keyboard())
        eval("window.passes=0")
        key("Tab"); key("ArrowDown"); key(" "); key("Enter")
        assertEquals("further keys start no adapter pass", "0", eval("window.passes"))
        pointer()
        assertEquals("a touch leaves keyboard mode", "false", keyboard())
        eval("window.passes=0")
        pointer()
        assertEquals("0", eval("window.passes"))
    }

    @Test fun theEndOfThePageEnterMotionStartsNoAdapterPass() {
        load("/fixture/layout/enter/", """<table class="wrappertable"><tr id="l4"><td class="center"><p>Новости факультета</p></td></tr></table>""")
        // The document-start bootstrap first, as the app installs it; its release starts the page-enter motion.
        val animations = adapt(bootstrap = true,
            probe = "JSON.stringify(document.querySelector('#l4>td').getAnimations().map(function(a){return a.animationName;}))")
        assumeTrue("The page-enter motion runs on this device (no reduced motion)", animations.contains("univer-page-in"))
        Thread.sleep(900)
        // The motion's marker goes after 260 ms; the page has not changed, so no pass follows the one that enabled it.
        assertEquals("passes after the layout was on", "0",
            eval("window.passTimes.filter(function(t){return t-window.enabledAt>150;}).length"))
    }

    private val tables = """<table class="mt"><tr class="top"><td class="ct">Транскрипт</td></tr><tr class="mid"><td class="ct">""" +
        listOf("first" to "Профессиональные практики", "second" to "Итоговая аттестация").joinToString("") { (id, heading) ->
            "<p>$heading</p><table id=\"$id\"><tr>" + (1..13).joinToString("") { "<th>Столбец $it</th>" } + "</tr>" +
                (1..6).joinToString("") { row -> "<tr>" + (1..13).joinToString("") { "<td>$row.$it</td>" } + "</tr>" } + "</table>"
        } + "</td></tr></table>"

    @Test fun closingTheTableViewGivesFocusBackToThatTablesExpandButton() {
        load("/student/transcript/", "<table><tr id=l4><td>$tables</td></tr></table>")
        eval("['first','second'].forEach(function(id){document.getElementById(id).style.width=Math.round(innerWidth*4)+'px';});true")
        adapt()
        Thread.sleep(500)
        assertEquals("both tables offer Expand", "2", eval("document.querySelectorAll('.univer-app-scroll-hint:not([hidden]) .univer-app-table-open').length"))
        // The second table's own button, kept before its table moves into the view.
        eval("window.own=document.getElementById('second').parentElement.previousElementSibling.querySelector('.univer-app-table-open');" +
            "window.own.focus();window.own.click();true")
        assertEquals("true", eval("!!document.querySelector('.univer-app-table-viewer')&&document.activeElement.classList.contains('univer-app-table-viewer-close')"))
        // While the table is in the view, the adapter's pass hides the hint of its empty box.
        Thread.sleep(500)
        assertEquals("true", eval("window.own.closest('.univer-app-scroll-hint').hidden"))
        // Back (the native callback runs this) closes the view.
        assertEquals("true", eval("window.__univerTablesV1.close()"))
        assertEquals("focus returns to the button that opened the view", "true", eval("document.activeElement===window.own"))

        // Opened with nothing focused: focus goes to this table's own button, not to the first one on the page.
        Thread.sleep(500)
        eval("document.activeElement.blur();window.__univerTablesV1.open(document.getElementById('second'))")
        Thread.sleep(500)
        assertEquals("true", eval("window.__univerTablesV1.close()"))
        assertEquals("focus goes to this table's own Expand", "true", eval("document.activeElement===window.own"))
    }

    @Test fun aSegmentsThumbFollowsTheChoiceWhenTheTextSizeChanges() {
        load("/fixture/layout/segments/", """<table class="wrappertable"><tr id="l4"><td class="center">
            <div class="univer-app-schedule-modes" role="group"><button type="button" aria-pressed="false">А</button>
            <button type="button" aria-pressed="true" id="chosen">Сетка недели по дням</button></div></td></tr></table>""")
        adapt()
        // The distance between the thumb and the chosen button (left, top and width), and that button's width.
        fun measure() = JSONObject(eval("(function(){var c=document.getElementById('chosen').getBoundingClientRect()," +
            "t=document.querySelector('.univer-kit-thumb').getBoundingClientRect();" +
            "return {gap:Math.abs(t.left-c.left)+Math.abs(t.top-c.top)+Math.abs(t.width-c.width),width:c.width};})()"))
        Thread.sleep(800)
        val before = measure()
        assertTrue("thumb under the chosen button: $before", before.getDouble("gap") <= 1.0)
        // What the app's text-size setting does: only WebView's text zoom changes, with no resize and no DOM change.
        instrumentation.runOnMainSync { web.settings.textZoom = 160 }
        Thread.sleep(1200)
        val after = measure()
        assertTrue("larger text resizes the chosen button: $before -> $after", Math.abs(after.getDouble("width") - before.getDouble("width")) > 4)
        assertTrue("thumb follows the chosen button: $after", after.getDouble("gap") <= 1.0)
    }
}
