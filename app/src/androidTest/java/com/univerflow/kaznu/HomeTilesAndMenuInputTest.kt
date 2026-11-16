package com.univerflow.kaznu

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
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
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The home tiles, the "Новый раздел" sheet and the "All sections" search, driven by real touches and keys on a local
 * fixture of Главная. Subresources get a local 404 and navigation away is refused: nothing reaches Univer.
 */
@RunWith(AndroidJUnit4::class)
class HomeTilesAndMenuInputTest {
    private lateinit var scenario: ActivityScenario<DownloadsActivity>
    private lateinit var web: WebView
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private val menu = """<div id="menu-home"><table id="tools"><tr><td class="center"><table class="tool"><tr><td><ul>""" +
        listOf("/student/myschedule/" to "Расписание", "/student/attendance/full/" to "Журнал посещений и успеваемости",
            "/student/transcript/" to "Транскрипт", "/student/umkd/" to "УМКД", "/student/messages/" to "Сообщения", "/my/docs/" to "Документы")
            .joinToString("") { "<li><a href=\"${it.first}\">${it.second}</a></li>" } +
        """</ul></td></tr><tr><td class="bottom-center">Учёба</td></tr></table></td></tr></table></div>"""

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
        val loaded = CountDownLatch(1)
        instrumentation.runOnMainSync {
            web.webViewClient = object : WebViewClient() {
                // The fixture document itself is the main-frame load; everything it asks for gets a local 404.
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    if (request.isForMainFrame) null
                    else WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                // A tapped tile or link stays on the fixture: nothing leaves for the real website.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun onPageFinished(view: WebView, url: String?) { loaded.countDown() }
            }
            web.loadDataWithBaseURL("https://univer.kaznu.kz/news/student", "<html><head><meta charset=\"utf-8\"></head><body>" +
                """<table class="wrappertable"><tr id="l4"><td class="center">$menu</td></tr></table></body></html>""", "text/html", "UTF-8", null)
        }
        assertTrue("Fixture loaded", loaded.await(15, TimeUnit.SECONDS))
        val script = MobilePageAdapter.SCRIPTS.joinToString("\n") { name ->
            instrumentation.targetContext.assets.open(name).bufferedReader().use { it.readText() } }
        // Every adapter pass calls the news module once: wrapping it counts the passes.
        // What the modules would send to the app is kept in window.__pinsSent.
        eval(MobilePageAdapter.withSenders(script, "{app:function(v){(window.__pinsSent=window.__pinsSent||[]).push(v);}}") + "\nwindow.__univerMobileV1.setEnabled(true,'ru');window.__univerStyleV1.apply(true);" +
            "window.passes=0;(function(m){var f=m.apply;m.apply=function(){window.passes++;return f.apply(this,arguments);};})(window.__univerNewsV1);")
        waitFor("Home tiles", "document.querySelectorAll('.univer-app-quick>.univer-app-tile').length===6")
    }

    @After fun close() {
        instrumentation.runOnMainSync { web.destroyFixture() }
        scenario.close()
    }

    private fun eval(script: String): String {
        val latch = CountDownLatch(1)
        var result = "null"
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { result = it; latch.countDown() } }
        assertTrue("JavaScript callback", latch.await(10, TimeUnit.SECONDS))
        return result
    }

    private fun waitFor(message: String, condition: String) {
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) {
            if (eval("(function(){try{return !!($condition);}catch(e){return false;}})()") == "true") return
            Thread.sleep(150)
        }
        fail(message)
    }

    /** The adapter pass count once no pass has run for a while. */
    private fun settledPasses(): Int {
        var last = eval("window.passes").toInt()
        repeat(12) {
            Thread.sleep(400)
            val now = eval("window.passes").toInt()
            if (now == last) return now
            last = now
        }
        return last
    }

    /** The point, in the WebView's own pixels, at the centre of the first element matching [selector]. */
    private fun point(selector: String): FloatArray {
        val rect = (JSONTokener(eval("(function(){var r=document.querySelector(${JSONObject.quote(selector)}).getBoundingClientRect();" +
            "return [r.left+r.width/2,r.top+r.height/2,innerWidth].join(',');})()")).nextValue() as String).split(',').map { it.toFloat() }
        var scale = 1f
        instrumentation.runOnMainSync { scale = web.width / rect[2] }
        return floatArrayOf(rect[0] * scale, rect[1] * scale)
    }

    /** A real finger event: the page receives it as trusted input. */
    private fun touch(down: Long, action: Int, at: FloatArray) = instrumentation.runOnMainSync {
        MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, at[0], at[1], 0).also {
            it.source = InputDevice.SOURCE_TOUCHSCREEN; web.dispatchTouchEvent(it); it.recycle()
        }
    }

    private fun tap(selector: String) {
        eval("document.querySelector(${JSONObject.quote(selector)}).scrollIntoView({block:'center'})")
        val at = point(selector)
        val down = SystemClock.uptimeMillis()
        touch(down, MotionEvent.ACTION_DOWN, at)
        Thread.sleep(60)
        touch(down, MotionEvent.ACTION_UP, at)
        Thread.sleep(400)
    }

    // Review finding: every finger movement of a lifted tile rewrote its inline style, which the adapter observes, so a
    // whole adapter pass (every table, menu link and page module) ran once per frame for the entire drag.
    @Test fun draggingATileRunsNoAdapterPassPerMovement() {
        val first = eval("document.querySelector('.univer-app-quick>.univer-app-tile .univer-app-tile-label').textContent")
        val from = point(".univer-app-quick>.univer-app-tile:nth-child(1)>a")
        val to = point(".univer-app-quick>.univer-app-tile:nth-child(3)>a")
        val before = settledPasses()
        val down = SystemClock.uptimeMillis()
        touch(down, MotionEvent.ACTION_DOWN, from)
        Thread.sleep(650) // A long press lifts the tile.
        val steps = 24
        for (step in 1..steps) {
            touch(down, MotionEvent.ACTION_MOVE, floatArrayOf(from[0] + (to[0] - from[0]) * step / steps, from[1] + (to[1] - from[1]) * step / steps))
            Thread.sleep(30)
        }
        touch(down, MotionEvent.ACTION_UP, to)
        val passes = settledPasses() - before
        assertTrue("Lifting and dropping cost a few passes, not one per movement ($passes for $steps movements)", passes < 8)
        assertEquals("The tile still lands where it was dropped", first,
            eval("document.querySelectorAll('.univer-app-quick>.univer-app-tile')[2].querySelector('.univer-app-tile-label').textContent"))
        assertEquals("Nothing stays lifted or shifted", "true", eval("!document.querySelector('.univer-app-lifted,.univer-app-drop-target')&&" +
            "Array.from(document.querySelectorAll('.univer-app-quick>.univer-app-tile')).every(t=>!t.style.transform)"))
    }

    // Review finding: each keystroke in "Найти раздел…" showed and hid menu items through their hidden attribute,
    // which the adapter observes, so every keystroke ran a whole adapter pass.
    @Test fun typingInTheSectionsSearchRunsNoAdapterPass() {
        eval("document.querySelector('.univer-app-menu').open=true")
        fun search(query: String) = eval("var s=document.querySelector('.univer-app-menu-search');s.value=${JSONObject.quote(query)};" +
            "s.dispatchEvent(new Event('input',{bubbles:true}))")
        val before = settledPasses()
        listOf("Р", "Ра", "Рас", "Расп", "Распи").forEach { search(it); Thread.sleep(150) }
        val passes = settledPasses() - before
        assertTrue("No adapter pass per keystroke ($passes for 5 keystrokes)", passes < 2)
        assertEquals("The search still filters the sections", "true",
            eval("Array.from(document.querySelectorAll('.univer-app-menu #tools li')).filter(li=>!li.hidden).map(li=>li.textContent.trim()).join()==='Расписание'"))
        search("нет такого раздела")
        assertEquals("and says when nothing matches", "false", eval("document.querySelector('.univer-app-menu .univer-app-empty').hidden"))
        search("")
        assertEquals("Clearing it shows every section again", "true",
            eval("Array.from(document.querySelectorAll('.univer-app-menu #tools li')).every(li=>!li.hidden)&&document.querySelector('.univer-app-menu .univer-app-empty').hidden"))
    }

    // Review finding: the "Новый раздел" sheet is a modal dialog, yet Escape did nothing and Tab left it for the page
    // hidden behind it.
    @Test fun theAddSectionSheetKeepsTheKeyboardInsideAndClosesOnEscape() {
        eval("document.querySelector('.univer-app-menu').open=true")
        tap(".univer-app-add-tile")
        waitFor("The sheet opens with focus on it", "document.activeElement===document.querySelector('.univer-app-add-panel')")
        fun key(name: String, shift: Boolean = false) =
            eval("document.activeElement.dispatchEvent(new KeyboardEvent('keydown',{key:'$name',shiftKey:$shift,bubbles:true,cancelable:true}))")
        key("Tab", shift = true)
        assertEquals("Shift+Tab from the sheet wraps to its last control", "true",
            eval("var r=document.querySelectorAll('.univer-app-add-row');r.length>0&&document.activeElement===r[r.length-1]"))
        key("Tab")
        assertEquals("Tab from the last control wraps to the first", "true", eval("document.activeElement===document.querySelector('.univer-app-add-close')"))
        eval("document.querySelector('.univer-app-add-search').focus()")
        key("Escape")
        waitFor("Escape closes the sheet", "!document.querySelector('.univer-app-add-layer')")
        assertEquals("Focus returns to the tile that opened the sheet", "true", eval("document.activeElement===document.querySelector('.univer-app-add-tile')"))
        assertEquals("Only the sheet closed, not the menu under it", "true", eval("document.querySelector('.univer-app-menu').open"))
    }

    /** A finger pulled from [from] by [dy] WebView pixels in [steps] moves, [pause] ms apart. */
    private fun pull(from: FloatArray, dy: Float, steps: Int, pause: Long, hold: Long = 0) {
        val down = SystemClock.uptimeMillis()
        touch(down, MotionEvent.ACTION_DOWN, from)
        for (step in 1..steps) { touch(down, MotionEvent.ACTION_MOVE, floatArrayOf(from[0], from[1] + dy * step / steps)); Thread.sleep(pause) }
        if (hold > 0) { repeat((hold / 30).toInt()) { touch(down, MotionEvent.ACTION_MOVE, floatArrayOf(from[0], from[1] + dy)); Thread.sleep(30) } }
        touch(down, MotionEvent.ACTION_UP, floatArrayOf(from[0], from[1] + dy))
        Thread.sleep(500)
    }

    // Owner's request of 15 Nov 2026: the "Новый раздел" sheet closes with a swipe of the finger.
    @Test fun theAddSectionSheetFollowsTheFingerAndClosesOnASwipeDown() {
        eval("window.__pinsSent=[]")
        tap(".univer-app-add-tile")
        waitFor("The sheet opens", "document.querySelector('.univer-app-add-panel')")
        assertEquals("It shows a grip", "true", eval("!!document.querySelector('.univer-app-add-panel>.univer-app-add-grip')"))
        val cssWidth = eval("innerWidth").toFloat()
        var scale = 1f
        instrumentation.runOnMainSync { scale = web.width / cssWidth }
        // A short slow pull from the title springs back.
        pull(point(".univer-app-add-head>h2"), 40 * scale, 6, 30, hold = 300)
        assertEquals("A short pull leaves the sheet open and in place", "true",
            eval("!!document.querySelector('.univer-app-add-layer')&&!document.querySelector('.univer-app-add-panel').style.transform"))
        // A pull that starts on a row of the list (scrolled to its top) closes the sheet, and the row does not pin itself.
        // Slow small steps (about 5 CSS px, under the 8 px slop) check the WebView does not take the pull for a scroll.
        pull(point(".univer-app-add-row"), 320 * scale, 64, 12)
        waitFor("A long pull closes the sheet", "!document.querySelector('.univer-app-add-layer')")
        assertEquals("No row was pinned by the pull", "0", eval("window.__pinsSent.filter(function(v){return String(v).indexOf('univer-pin:')===0;}).length"))
    }

    // Owner's request of 15 Nov 2026: on the phone «Наверх» moved at every stop over full-width rows; it now keeps its corner.
    @Test fun backToTopKeepsItsCornerOverFullWidthLinks() {
        eval("var box=document.createElement('div');for(var i=0;i<120;i++){var a=document.createElement('a');a.href='/student/x'+i;a.textContent='Ссылка '+i;" +
            "a.style.cssText='display:block;padding:14px 0;width:100%';box.appendChild(a);}document.querySelector('#l4>td').appendChild(box)")
        val spots = mutableSetOf<String>()
        for (y in listOf(1500, 1900, 2400, 2950, 3500)) {
            eval("scrollTo(0,$y);window.__univerTopV1.refresh()")
            spots += eval("(function(){var b=document.getElementById('univer-app-top').shadowRoot.querySelector('.top');return b.classList.contains('on')+' '+b.style.bottom+' '+b.style.right;})()")
        }
        assertEquals("Shown at every stop, always in the same corner", setOf("\"true 16px 16px\""), spots)
    }
}
