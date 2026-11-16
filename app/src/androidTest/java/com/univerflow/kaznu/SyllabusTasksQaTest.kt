package com.univerflow.kaznu

import android.graphics.Bitmap
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
import com.univerflow.kaznu.web.PageScripts
import com.univerflow.kaznu.domain.StudyFolders
import org.json.JSONObject
import org.json.JSONTokener
import com.univerflow.kaznu.web.configureSecureSettings
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real WebView layout, with every network request answered locally. No student account is used. */
@RunWith(AndroidJUnit4::class)
class SyllabusTasksQaTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<DownloadsActivity>
    private lateinit var web: WebView
    private lateinit var adapter: MobilePageAdapter
    private val requested = CopyOnWriteArrayList<String>()
    private val navigated = CopyOnWriteArrayList<String>()

    @Before fun setup() {
        scenario = ActivityScenario.launch(DownloadsActivity.intent(instrumentation.targetContext, AppLanguage.RUSSIAN))
        scenario.onActivity { activity ->
            web = WebView(activity)
            web.configureSecureSettings()
            activity.setContentView(web)
            adapter = MobilePageAdapter(web, allowDocumentStart = false, nativeChrome = true)
        }
    }

    @After fun close() {
        instrumentation.runOnMainSync { adapter.close(); web.destroyFixture() }
        scenario.close()
    }

    private fun eval(source: String): String {
        val latch = CountDownLatch(1)
        var result = "null"
        instrumentation.runOnMainSync { web.evaluateJavascript(source) { result = it; latch.countDown() } }
        assertTrue("JavaScript callback", latch.await(10, TimeUnit.SECONDS))
        return result
    }

    private fun waitFor(message: String, expression: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            if (eval("(function(){try{return !!($expression);}catch(_){return false;}})()") == "true") return
            Thread.sleep(100)
        }
        capture("syllabus-failed")
        fail(message + ": " + eval("JSON.stringify({ready:document.readyState,adapted:document.documentElement.className,state:window.__univerSyllabusV1?.state(),cards:document.querySelectorAll('.univer-app-lesson').length,panel:document.querySelector('.univer-app-syl-panel')?.textContent.slice(0,800)})") + "; requested=" + requested)
    }

    private fun load() {
        val arrived = CountDownLatch(1)
        instrumentation.runOnMainSync {
            web.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val path = request.url.path.orEmpty().trimEnd('/')
                    requested.add(path)
                    val body = when (path) {
                        "/student/myschedule" -> "<html><head><meta charset='utf-8'></head><body>${shell(schedule)}</body></html>"
                        "/student/bachelor" -> shell("<table><tr><td>Специальность:</td><td>6B06301 Синтетическая программа</td></tr><tr><td>Отделение:</td><td>русское</td></tr></table>")
                        "/student/umkd" -> courses
                        "/student/umkd/7001/2026/1" -> shell("<a href='/student/interactive/syllabus/print/9001/2'>Силлабус</a><a href='/student/interactive/syllabus/print/9002/2'>Другой факультет</a>")
                        "/student/interactive/syllabus/print/9001/2" -> syllabus("6B06301", "Тема нашей программы", "СРО нашей программы")
                        "/student/interactive/syllabus/print/9002/2" -> syllabus("6B06101", "Чужая тема", "Чужое СРО")
                        "/student/myschedule/2026/1/21.09.2026/27.09.2026", "/student/myschedule/2026/1/28.09.2026/04.10.2026" -> shell(schedule)
                        else -> null
                    }
                    return WebResourceResponse("text/html", "UTF-8", if (body == null) 404 else 200,
                        if (body == null) "Not Found" else "OK", emptyMap(), ByteArrayInputStream(body.orEmpty().toByteArray()))
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    navigated.add(request.url.toString()); return true
                }
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { adapter.pageStarted(url) }
                override fun onPageFinished(view: WebView, url: String?) { adapter.apply(true, AppLanguage.RUSSIAN); arrived.countDown() }
            }
            web.loadUrl("https://univer.kaznu.kz/student/myschedule/")
        }
        assertTrue("Fixture navigation", arrived.await(15, TimeUnit.SECONDS))
        waitFor("Own-programme syllabus reached the class", "document.querySelector('.univer-app-syl-task')?.textContent.includes('Тема нашей программы')")
        waitFor("All syllabus work finished", "document.querySelector('.univer-app-syl-progress')?.hidden")
    }

    private fun capture(name: String) {
        val painted = CountDownLatch(1)
        instrumentation.runOnMainSync {
            web.postVisualStateCallback(System.nanoTime(), object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { painted.countDown() }
            })
        }
        assertTrue("Fixture painted before its screenshot", painted.await(10, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        Thread.sleep(150)
        val image = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull(image)
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "qa").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    @Test fun mobileSyllabusKeepsProgrammeWeekAndTouchTargetsAndRestoresTheSource() {
        load()
        assertEquals("true", eval("!document.querySelector('.univer-app-schedule').textContent.includes('Чужая')&&!document.querySelector('.univer-app-schedule').textContent.includes('Консультация')"))
        assertEquals("true", eval("document.querySelector('.univer-app-syl-sro').textContent.includes('СРО нашей программы')"))
        listOf(320, 360).forEach { width ->
            instrumentation.runOnMainSync {
                web.layoutParams = web.layoutParams.apply { this.width = (width * web.resources.displayMetrics.density).toInt() }
                web.settings.textZoom = 130
            }
            waitFor("No page-wide overflow at $width dp", "document.documentElement.scrollWidth<=innerWidth+2")
            assertEquals("All visible syllabus buttons remain touchable at $width dp: " + eval("JSON.stringify(Array.from(document.querySelectorAll('.univer-app-syl-panel button,.univer-app-syl-task button')).filter(b=>b.getClientRects().length).map(b=>[b.className,b.getBoundingClientRect().height]))"), "true", eval("""Array.from(document.querySelectorAll('.univer-app-syl-panel button,.univer-app-syl-task button'))
                .filter(b=>b.getClientRects().length&&!b.closest('[hidden]')).every(function(b){
                    // A task line is one text line tall; its touch target is the ::before that reaches past it (15 Nov 2026).
                    var hit=getComputedStyle(b,'::before'),extra=hit.position==='absolute'?-(parseFloat(hit.top)||0)-(parseFloat(hit.bottom)||0):0;
                    return b.getBoundingClientRect().height+extra>=47;})"""))
        }
        capture("syllabus-360-ru-large")
        eval("document.querySelector('.univer-app-syl-switch').click()")
        assertEquals("\"off\"", eval("document.querySelector('.univer-app-schedule').dataset.syllabus"))
        eval("document.querySelector('.univer-app-syl-switch').click();document.querySelectorAll('.univer-app-schedule-modes button')[1].click()")
        assertEquals("\"none\"", eval("getComputedStyle(document.querySelector('.univer-app-syl-panel')).display"))
        eval("document.querySelectorAll('.univer-app-schedule-modes button')[0].click();window.__univerDark=true;window.__univerPaletteV1.sync(document,true)")
        assertNotEquals("The syllabus panel follows the dark palette", "\"rgb(255, 255, 255)\"", eval("getComputedStyle(document.querySelector('.univer-app-syl-panel')).backgroundColor"))
        capture("syllabus-360-dark-large")
        instrumentation.runOnMainSync { adapter.apply(false, AppLanguage.RUSSIAN) }
        waitFor("Original removes every syllabus block", "!document.querySelector('.univer-app-syl-panel,.univer-app-syl-task')&&!document.getElementById('univer-syllabus-style')")
        assertEquals("The original table and its rowspan survive", "true", eval("document.querySelector('table.schedule td.field').getAttribute('rowspan')==='2'"))
        assertTrue("No external page or personal form was opened", navigated.isEmpty())
        assertTrue("Only the observed GET pages were read: $requested", requested.filter { it.startsWith("/student/") }.all {
            it.startsWith("/student/bachelor") || it.startsWith("/student/umkd") || it.startsWith("/student/interactive/syllabus/print/") || it == "/student/myschedule" || it.startsWith("/student/myschedule/")
        })
    }

    @Test fun materialFoldersReadOnlyTheClickedFilesCourseAndSectionAfterMobileAdaptation() {
        val page = "https://univer.kaznu.kz/student/umkd/7001/2026/1"
        val target = "https://univer.kaznu.kz/student/umkd/get/42/7001/2026/1"
        val loaded = CountDownLatch(1)
        instrumentation.runOnMainSync {
            web.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = WebResourceResponse("text/html", "UTF-8",
                    ByteArrayInputStream(shell("""<table class="mt"><tr class="top"><td class="ct"><a href="/student/umkd/">УМКД</a> / <a href="/student/umkd/7001/2026/1">Алгоритмы / структуры</a></td></tr>
                        <tr><td><div class="inner"><table class="inner"><tr><th colspan="7">Лекции: часть 1</th></tr><tr class="file"><td></td><td><a class="downLoad" href="$target">Лекция.pdf</a></td><td>Описание</td><td>RU</td><td>100</td><td>01.11.2026</td><td>Рейтинг</td></tr></table></div></td></tr></table>
                        <input type="password" value="fixture-not-part-of-the-folder">""").toByteArray()))
                override fun onPageFinished(view: WebView, url: String?) { adapter.apply(true, AppLanguage.RUSSIAN); loaded.countDown() }
            }
            web.loadUrl(page)
        }
        assertTrue(loaded.await(15, TimeUnit.SECONDS))
        waitFor("The course fixture is adapted", "document.documentElement.classList.contains('univer-mobile')")
        val json = JSONObject(JSONTokener(eval(PageScripts.materialPlace(page, target))).nextValue() as String)
        assertEquals(setOf("course", "section"), json.keys().asSequence().toSet())
        assertEquals("Univer УМКД/Алгоритмы, структуры/Лекции - часть 1", StudyFolders.relative(json.getString("course"), json.getString("section")))
        assertEquals("null", eval(PageScripts.materialPlace(page + "?other", target)))
        assertEquals("null", eval(PageScripts.materialPlace(page, target.replace("/42/", "/999/"))))
    }

    private fun shell(content: String) = "<table class='wrappertable'><tr id='l1'><td class='header'>КазНУ</td></tr><tr id='l4'><td class='center'>$content</td></tr></table>"
    private val courses = shell("""<table class="mt"><tr><td><table class="inner"><tr><th></th><th>Дисциплина</th><th>Тип</th></tr>
        <tr class="link" id="7001"><td></td><td>Синтетический курс</td><td>Обязательная</td></tr></table></td></tr></table>
        <script>jQuery('tr.link').dblclick(function(){window.location='/student/umkd/'+this.id+'/2026/1';});</script>""")
    private val schedule = """<select id="weekSelect"><option selected value="/student/myschedule/2026/1/21.09.2026/27.09.2026/">Неделя 04. 21.09.2026 - 27.09.2026</option>
        <option value="/student/myschedule/2026/1/28.09.2026/04.10.2026/">Неделя 05. 28.09.2026 - 04.10.2026</option></select>
        <div class="inner"><table class="schedule"><tr><th></th><th>ПН</th><th>ВТ</th><th>СР</th><th>ЧТ</th><th>ПТ</th><th>СБ</th></tr>
        <tr><td class="time">09:00 - 09:50</td><td class="field" rowspan="2"><div class="groups"><div><p class="teacher">Синтетический курс(лекция)</p><p class="teacher">Образцов О. О.</p><p class="params"><span>Ауд.: 309</span></p></div></div></td><td></td><td></td><td></td><td></td><td></td></tr>
        <tr><td class="time">10:00 - 10:50</td><td></td><td></td><td></td><td></td><td></td></tr></table></div>"""

    private fun syllabus(programme: String, topic: String, work: String): String {
        fun row(type: String, title: String, value: String, score: Int) = "<tr><td><div><div><div>$type:</div><div><div>$title</div></div></div><div><div><div><div>$value</div></div></div></div><div><div>$score</div></div></div></td></tr>"
        return """<table class="students"><tr><td class="label">Академический год</td><td>2026</td></tr>
            <tr><td class="label">Образовательная программа</td><td>Группа №: 1000001 (Образовательная программа: $programme Синтетическая программа, Языковое отделение: русское)</td></tr>
            <tr><td class="label">Лектор-(ы):</td><td>Образцов О. О.</td></tr></table>
            <table><tr><td class="it">4</td><td><table>${row("лекция", "Лекция 4", topic, 1)}${row("СРО/СРОП", "СРО 1", work, 10)}${row("СРО/СРОП", "СРОП 1", "Консультация", 0)}</table></td></tr></table>"""
    }
}
