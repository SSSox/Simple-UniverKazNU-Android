package com.univerflow.kaznu

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.univerflow.kaznu.domain.NoticeKind
import com.univerflow.kaznu.domain.NoticeScan
import com.univerflow.kaznu.domain.Notices
import com.univerflow.kaznu.web.ChannelGuard
import com.univerflow.kaznu.web.MobilePageAdapter
import com.univerflow.kaznu.web.NoticeBridge
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Notifications and wide-table views on synthetic pages built from markup seen on Univer. Every request is answered
 * locally; nothing reaches the site or an account.
 */
@RunWith(AndroidJUnit4::class)
class NoticesAndTablesQaTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var web: WebView

    private fun eval(js: String): String? {
        val latch = CountDownLatch(1); var value: String? = null
        instrumentation.runOnMainSync { web.evaluateJavascript(js) { value = it; latch.countDown() } }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        return value
    }
    private fun string(js: String) = JSONTokener(eval(js)).nextValue() as? String
    private fun number(js: String) = eval(js)!!.toDouble()

    private fun menu(rating: String) = """<table id="tools"><tr><td class="center"><table class="tool"><tr><td><ul>
        <li><a href="/student/rating/">Анкетирование студентов</a>$rating</li>
        <li><a style="color:Red; " href="/student/contract/list/">Договоры</a> <b style="color:Red;font-size:10px; ">&nbsp;</b></li>
        <li><a href="/student/fail/">Задолженности </a><b style="color:Red; font-size:10px;">&nbsp;</b></li>
        <li><a href="/my/calculatorgpa/" style="color:red"> Калькулятор GPA </a></li>
        <li><a href="/student/attendance/full/">Журнал посещений и успеваемости</a></li>
        <li><a href="https://example.com/outside">Чужая ссылка (3)</a></li>
        <li><a href="/Content/instructions/Guide.pdf">Руководство (4)</a></li>
        </ul></td></tr></table></td></tr></table>"""
    private val counted = """<b style="color:Red; font-size:10px;">&nbsp;(2)</b>"""
    private fun page(content: String, rating: String = counted) = """<!doctype html><html><head><meta charset="utf-8"></head><body>
        <table class="wrappertable"><tr id="l2"><td><div class="links"><a href="/user/pass/">Тестовый студент (fixture: 1)</a><a href="/user/logout">Выход</a></div></td></tr>
        <tr id="l3"><td class="center"><a href="/"><div class="center">Главная</div></a><a href="/student/bachelor/"><div class="center">Бакалавр</div></a><a href="/ssc/"><div class="center">E-услуги</div></a></td></tr>
        <tr id="l4"><td class="center">${menu(rating)}$content</td></tr></table></body></html>"""
    private val questionnaires = """<table class="mt"><tr class="top"><td class="ct">Список проводимых в данный момент опросов студентов</td></tr>
        <tr class="mid"><td class="tt"><div class="inner"><table class="inner"><tbody><tr><th>Название</th><th>Тип</th><th>Дата начала (дд/мм/гггг)</th><th>Дата окончания (дд/мм/гггг)</th></tr>
        <tr class="link" id="208"><td>Удовлетворенность обучающихся качеством преподавания</td><td>Анкетирование преподавателя <p style="color:red;">Обязательная</p></td><td>30.10.2026</td><td>30.11.2026</td></tr>
        <tr class="link" id="207"><td>Восприятие коррупции - 2026, осенний сем.</td><td>Анкетирование <p style="color:red;">Обязательная</p></td><td>29.10.2026</td><td>11.11.2026</td></tr>
        <tr class="empty"><td colspan="4">&nbsp;</td></tr></tbody></table></div></td></tr></table>"""
    // A questionnaire's own page (/student/rating/208): a line per teacher, "link done" with the [+] icon once answered,
    // as seen live. An unanswered line was not available live: here it is the same row without the mark.
    private fun answers(vararg done: Boolean) = """<table class="mt"><tr class="mid"><td class="tt"><div class="inner"><table class="inner"><tbody>
        <tr><th>Дисциплина</th><th>ФИО преподавателя</th><th>Ответил(а)</th><th>Дата ответов</th></tr>""" + done.mapIndexed { at, answered ->
        if (answered) """<tr class="link done" id="5$at/31$at"><td>Дисциплина $at</td><td>Преподаватель $at</td><td align="center"><img src="/content/icons/add.png" alt=" [+] "></td><td>31.10.2026</td></tr>"""
        else """<tr class="link" id="5$at/31$at"><td>Дисциплина $at</td><td>Преподаватель $at</td><td align="center"></td><td></td></tr>"""
    }.joinToString("") + "</tbody></table></div></td></tr></table>"
    // Synthetic wording: no blocked section was available on the live site.
    private val journal = """<table class="mt"><tr class="top"><td class="ct">Журнал посещений и успеваемости</td></tr>
        <tr class="mid"><td class="ct"><span style="color:red">Для просмотра успеваемости необходимо пройти <a href="/student/rating/">анкетирование</a>.</span></td></tr>
        <tr class="mid"><td class="ct"><p>Новости: опрос о столовой проводится по желанию.</p></td></tr></table>"""

    @SuppressLint("SetJavaScriptEnabled")
    @Test fun siteMarksBecomeNotificationsWithTheCountedPagesQuestionnaires() {
        val requested = Collections.synchronizedList(mutableListOf<String>())
        val pages = mapOf("/student/bachelor/" to page("<p>Бакалавр</p>"), "/ssc/" to page("<p>Е-услуги</p>", rating = ""),
            "/student/rating/" to page(questionnaires), "/student/rating/208" to page(answers(true, false)),
            "/student/rating/207" to page(answers(true)))
        val scans = LinkedBlockingQueue<NoticeScan>()
        val loaded = CountDownLatch(1)
        var bridge: NoticeBridge? = null
        var channel: ChannelGuard? = null
        instrumentation.runOnMainSync {
            web = WebView(instrumentation.targetContext)
            web.settings.javaScriptEnabled = true
            web.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (request.isForMainFrame) return null // The fixture document itself (loadDataWithBaseURL).
                    // WebView's own icon request is not the page's.
                    if (request.url.path != "/favicon.ico") requested.add(request.method + " " + request.url.path)
                    val body = pages[request.url.path] ?: return WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(body.toByteArray()))
                }
                override fun onPageFinished(view: WebView, url: String?) { loaded.countDown() }
            }
            channel = ChannelGuard(web)
            bridge = NoticeBridge(web, channel!!) { scan, _ -> scans.add(scan) }
            web.loadDataWithBaseURL("https://univer.kaznu.kz/student/attendance/full/", page(journal), "text/html", "UTF-8",
                "https://univer.kaznu.kz/student/attendance/full/")
        }
        try {
            assertTrue(loaded.await(15, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { bridge!!.scan(refresh = true) }
            val current = scans.poll(10, TimeUnit.SECONDS) ?: run {
                var url: String? = null; instrumentation.runOnMainSync { url = web.url }
                val script = instrumentation.targetContext.assets.open("univer-notices.js").bufferedReader().use { it.readText() }
                throw AssertionError("no page scan; url=$url result=" + eval("try{" + script + "(false,0)}catch(e){'ERR '+e.message+' '+e.stack}")?.take(600) +
                    " dom=" + eval("document.readyState+'|'+location.href+'|'+document.documentElement.outerHTML.length+'|'+document.body.innerHTML.slice(0,200)"))
            }
            assertEquals("/student/attendance/full/", current.page)
            val counts = current.links.associate { it.url.removePrefix("https://univer.kaznu.kz") to it.count }
            assertEquals(2, counts["/student/rating/"])
            // A red item without a number is a highlight; the account, foreign and file links never pass.
            assertEquals(0, counts["/student/contract/list/"]); assertEquals(0, counts["/my/calculatorgpa/"]); assertEquals(0, counts["/student/fail/"])
            assertFalse(counts.keys.any { it.startsWith("/user/") || it.contains("example.com") || it.contains("Content") })
            assertEquals(1, current.messages.size)
            assertTrue(current.messages.single().text.startsWith("Для просмотра успеваемости необходимо пройти анкетирование"))
            assertEquals("https://univer.kaznu.kz/student/rating/", current.messages.single().url)
            assertEquals("Журнал посещений и успеваемости", current.messages.single().title)

            // The questionnaire count is checked first, against each questionnaire's own page, and sent again.
            assertTrue(current.links.single { it.url.endsWith("/student/rating/") }.checking)
            val checked = scans.poll(15, TimeUnit.SECONDS)!!
            assertEquals("/student/attendance/full/", checked.page)
            val survey = checked.links.single { it.url.endsWith("/student/rating/") }
            assertFalse(survey.checking)
            assertEquals("207 is answered in full, 208 has a line left", 1, survey.open)
            assertEquals(listOf("Удовлетворенность обучающихся качеством преподавания" to "30.11.2026"), survey.details.map { it.title to it.until })

            // Then the sections, with GET (never the site root, which leads to the read notice); the count is not checked twice.
            val bachelor = scans.poll(15, TimeUnit.SECONDS)!!
            val services = scans.poll(15, TimeUnit.SECONDS)!!
            assertEquals("/student/bachelor/", bachelor.page)
            assertEquals("/ssc/", services.page)
            val rating = bachelor.links.single { it.url.endsWith("/student/rating/") }
            assertEquals(survey.details, rating.details)
            assertEquals(1, rating.open)
            assertEquals(0, services.links.single { it.url.endsWith("/student/rating/") }.count)
            assertEquals(listOf("GET /student/rating/", "GET /student/rating/208", "GET /student/rating/207", "GET /student/bachelor/", "GET /ssc/"),
                requested.toList())

            var list = Notices.merge(emptyList(), current, 1)
            assertEquals("not listed before its check", listOf(NoticeKind.BLOCKED), list.map { it.kind })
            list = Notices.merge(list, checked, 2)
            list = Notices.merge(list, bachelor, 3)
            assertEquals(setOf(NoticeKind.COUNTER, NoticeKind.BLOCKED), Notices.listed(list).map { it.kind }.toSet())
            assertEquals(1, list.single { it.kind == NoticeKind.COUNTER }.details.size)
            // E-услуги shows the same item without a count: the questionnaires are done.
            assertEquals(listOf(NoticeKind.BLOCKED), Notices.merge(list, services, 4).map { it.kind })

            // A second scan within the interval reads the page only.
            requested.clear()
            instrumentation.runOnMainSync { bridge!!.scan() }
            assertEquals("/student/attendance/full/", scans.poll(10, TimeUnit.SECONDS)!!.page)
            Thread.sleep(800)
            assertTrue(requested.isEmpty())
            assertTrue(scans.isEmpty())
        } finally {
            instrumentation.runOnMainSync { bridge?.close(); channel?.close(); web.destroyFixture() }
        }
    }

    // Wide tables.

    private fun point(selector: String): FloatArray {
        val rect = string("(function(){var r=document.querySelector(${org.json.JSONObject.quote(selector)}).getBoundingClientRect();" +
            "return [r.left+r.width/2,r.top+r.height/2,innerWidth].join(',');})()")!!.split(',').map { it.toFloat() }
        val location = IntArray(2); var scale = 1f
        instrumentation.runOnMainSync { web.getLocationOnScreen(location); scale = web.width / rect[2] }
        return floatArrayOf(location[0] + rect[0] * scale, location[1] + rect[1] * scale)
    }
    private fun tap(selector: String) {
        val (x, y) = point(selector).let { it[0] to it[1] }
        val time = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0))
        instrumentation.sendPointerSync(MotionEvent.obtain(time, time + 60, MotionEvent.ACTION_UP, x, y, 0))
        Thread.sleep(500)
    }
    /** Two real fingers on [selector], moving apart from [from] to [to] pixels each side of its centre. */
    private fun pinch(selector: String, from: Float, to: Float) {
        val (cx, cy) = point(selector).let { it[0] to it[1] }
        val properties = Array(2) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        fun coords(spread: Float) = Array(2) { index -> MotionEvent.PointerCoords().apply { x = if (index == 0) cx - spread else cx + spread; y = cy; pressure = 1f; size = 1f } }
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, count: Int, spread: Float) = instrumentation.sendPointerSync(MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count,
            properties.copyOf(count).requireNoNulls(), coords(spread).copyOf(count).requireNoNulls(), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0))
        send(MotionEvent.ACTION_DOWN, 1, from)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, from)
        for (step in 1..12) { send(MotionEvent.ACTION_MOVE, 2, from + (to - from) * step / 12); Thread.sleep(25) }
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, to)
        send(MotionEvent.ACTION_UP, 1, to)
        Thread.sleep(500)
    }
    private fun capture(name: String) {
        Thread.sleep(500)
        val image = instrumentation.uiAutomation.takeScreenshot()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "qa").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    private val transcript = """<table class="mt"><tr class="top"><td class="ct">Транскрипт</td></tr><tr class="mid"><td class="ct">
        <p>Шкала оценок</p><table id="fit"><tr><th>Оценка по буквенной системе</th><th>Цифровой эквивалент</th><th>%-ное содержание</th><th>Традиционная</th></tr>
        <tr><td>A</td><td>4,0</td><td>95-100</td><td>Отлично</td></tr><tr><td>B</td><td>3,0</td><td>80-84</td><td>Хорошо</td></tr></table>
        <p>Профессиональные практики</p><table id="wide"><tr>""" + (1..13).joinToString("") { "<th id=\"h$it\">Столбец $it</th>" } + "</tr>" +
        (1..6).joinToString("") { row -> "<tr>" + (1..13).joinToString("") { "<td>$row.$it</td>" } + "</tr>" } +
        """</table></td></tr></table>"""

    @SuppressLint("SetJavaScriptEnabled")
    @Test fun wideTablesFitTheScreenOrOpenFullScreenWithZoomAndComeBack() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val loaded = CountDownLatch(1)
        try {
            scenario.onActivity { activity ->
                web = WebView(activity); web.settings.javaScriptEnabled = true; web.settings.useWideViewPort = true; web.settings.domStorageEnabled = true
                val root = FrameLayout(activity); root.addView(web); activity.setContentView(root)
                ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
                    val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
                }
                ViewCompat.requestApplyInsets(root)
                web.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = if (request.isForMainFrame) null
                        else WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    // The fixture never leaves for the real website.
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                    override fun onPageFinished(view: WebView, url: String?) { loaded.countDown() }
                }
                web.loadDataWithBaseURL("https://univer.kaznu.kz/student/transcript/",
                    "<!doctype html><html><head><meta charset=utf-8></head><body><table><tr id=l4><td>$transcript</td></tr></table></body></html>", "text/html", "UTF-8", null)
            }
            assertTrue(loaded.await(15, TimeUnit.SECONDS))
            // The second table four screens wide; a header keeps a site handler and a marker.
            eval("document.getElementById('wide').style.width=Math.round(innerWidth*4)+'px';" +
                "window.sorted=0;document.getElementById('h1').addEventListener('click',function(){window.sorted++;});document.getElementById('wide').__marker=42;")
            val script = MobilePageAdapter.SCRIPTS.joinToString("\n") { name -> instrumentation.targetContext.assets.open(name).bufferedReader().use { it.readText() } }
            eval("$script\nwindow.__univerMobileV1.setNativeChrome(true);window.__univerMobileV1.setEnabled(true,'ru');window.__univerStyleV1.apply(true);")
            Thread.sleep(900)
            // The first table 10% wider than its box: the site's change reaches the adapter through its observer.
            eval("var t=document.getElementById('fit');t.style.width=Math.round(t.parentElement.clientWidth*1.1)+'px';")
            Thread.sleep(900)

            // A little too wide: drawn smaller to fit, no sideways scroll and no hint.
            val probe = eval("document.readyState+'|'+location.href+'|'+document.documentElement.outerHTML.length+'|'+document.body.innerHTML.slice(0,300)") + string("(function(){var t=document.getElementById('fit'),b=t.parentElement;return [typeof window.__univerTablesV1,document.documentElement.className,t.className,b.className,b.clientWidth,b.scrollWidth,t.style.width,t.style.zoom].join(' | ');})()")
            val fitZoom = eval("parseFloat(document.getElementById('fit').style.zoom)")!!.toDoubleOrNull() ?: throw AssertionError("not fitted: $probe")
            assertTrue("fitted zoom $fitZoom", fitZoom in 0.8..0.999)
            assertEquals("true", eval("(function(){var b=document.getElementById('fit').parentElement;return b.scrollWidth<=b.clientWidth+1;})()"))
            assertEquals("false", eval("!!document.getElementById('fit').parentElement.previousElementSibling?.matches('.univer-app-scroll-hint:not([hidden])')"))
            // Too wide: the swipe hint with "Развернуть".
            assertEquals("Развернуть", string("document.querySelector('.univer-app-scroll-hint:not([hidden]) .univer-app-table-open').textContent"))
            capture("tables-inline")

            tap(".univer-app-table-open")
            assertEquals("the same node, its handlers kept", "42", eval("document.querySelector('.univer-app-table-viewer-stage>table').__marker"))
            assertEquals("Профессиональные практики", string("document.querySelector('.univer-app-table-viewer-title').textContent"))
            val opened = number("parseFloat(document.querySelector('.univer-app-table-viewer-stage').style.zoom)")
            assertTrue("opens fitted to the width: $opened", opened < 0.5)
            assertEquals("true", eval("(function(){var s=document.querySelector('.univer-app-table-viewer-scroll');return s.scrollWidth<=s.clientWidth+1;})()"))
            assertEquals("По ширине", string("document.querySelector('.univer-app-table-viewer-fit').textContent"))
            capture("tables-viewer-fit")

            pinch(".univer-app-table-viewer-scroll", 60f, 300f)
            val zoomed = number("parseFloat(document.querySelector('.univer-app-table-viewer-stage').style.zoom)")
            assertTrue("two fingers zoom the table: $opened -> $zoomed", zoomed > opened * 2)
            assertEquals("the page itself is not zoomed", "1", eval("visualViewport.scale"))
            assertEquals("${Math.round(zoomed * 100)}%", string("document.querySelector('.univer-app-table-viewer-level').textContent"))
            capture("tables-viewer-zoomed")
            tap(".univer-app-table-viewer-fit")
            assertEquals(opened, number("parseFloat(document.querySelector('.univer-app-table-viewer-stage').style.zoom)"), 0.002)
            eval("document.getElementById('h1').click()")
            assertEquals("1", eval("window.sorted"))

            // Back (the native callback runs this) puts the table back in its box on the page.
            assertEquals("true", eval("window.__univerTablesV1.close()"))
            assertEquals("true", eval("document.getElementById('wide').parentElement.classList.contains('univer-app-table-scroll')&&!document.querySelector('.univer-app-table-viewer')&&!document.documentElement.classList.contains('univer-app-table-viewing')"))
            assertEquals("false", eval("window.__univerTablesV1.close()"))

            // Original with the view open: everything returns as the site drew it.
            // The hint shows again on the adapter's next pass after the table is back.
            Thread.sleep(500)
            val before = string("(function(){var b=document.querySelector('.univer-app-table-open'),r=b.getBoundingClientRect();return [b.closest('[hidden]')?'hidden':'shown',Math.round(r.top),Math.round(r.left),innerHeight,scrollY].join(' ');})()")
            tap(".univer-app-table-open")
            assertEquals("opens again ()", "true", eval("!!document.querySelector('.univer-app-table-viewer')"))
            eval("window.__univerMobileV1.setEnabled(false,'ru');window.__univerStyleV1.apply(false);")
            assertEquals("true", eval("!document.querySelector('.univer-app-table-viewer,.univer-app-table-scroll,.univer-app-scroll-hint,.univer-app-table-fitted')"))
            assertEquals("", string("document.getElementById('fit').style.zoom"))
            assertEquals("true", eval("document.getElementById('wide').parentElement.tagName==='TD'&&document.getElementById('wide').__marker===42"))
        } finally {
            instrumentation.runOnMainSync { if (::web.isInitialized) web.destroyFixture() }
            scenario.close()
        }
    }
}
