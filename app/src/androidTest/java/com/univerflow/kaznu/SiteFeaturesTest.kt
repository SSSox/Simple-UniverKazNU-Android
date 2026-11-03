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
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Website features shared with the Windows app (news filters, attendance filters, syllabus links,
 * today in the schedule). Every request is answered by local fixtures: nothing reaches Univer.
 */
@RunWith(AndroidJUnit4::class)
class SiteFeaturesTest {
    private lateinit var scenario: ActivityScenario<DownloadsActivity>
    private lateinit var web: WebView
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val pages = mutableMapOf<String, String>()
    private val unexpected = java.util.concurrent.CopyOnWriteArrayList<String>()

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

    private fun load(body: String, origin: String) {
        val loaded = CountDownLatch(1)
        instrumentation.runOnMainSync {
            web.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (request.isForMainFrame) return null
                    val path = request.url.path.orEmpty()
                    val html = pages[path.trimEnd('/')]
                    if (html == null && path != "/favicon.ico") unexpected += path
                    return WebResourceResponse("text/html", "utf-8", if (html == null) 404 else 200, if (html == null) "Not Found" else "OK",
                        emptyMap(), ByteArrayInputStream((html ?: "").toByteArray()))
                }
                override fun onPageFinished(view: WebView, url: String?) { loaded.countDown() }
            }
            web.loadDataWithBaseURL(origin, "<html><head><meta charset=\"utf-8\"></head><body>$body</body></html>", "text/html", "UTF-8", origin)
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

    private fun waitFor(message: String, condition: String) {
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) {
            if (eval("(function(){try{return !!($condition);}catch(e){return false;}})()") == "true") return
            Thread.sleep(150)
        }
        fail(message)
    }

    private fun mobile(enabled: Boolean = true) {
        val script = MobilePageAdapter.SCRIPTS.joinToString("\n") { name ->
            instrumentation.targetContext.assets.open(name).bufferedReader().use { it.readText() } }
        eval(script + "\nwindow.__univerMobileV1.setEnabled($enabled,'ru');")
    }

    private fun shell(content: String) = """<table class="wrappertable"><tr id="l4"><td class="center">$content</td></tr></table>"""

    private fun news(page: Int, vararg rows: Pair<String, Boolean>) = shell("""<table class="inner"><tr><th>Дата</th><th>Новость</th></tr>""" +
        rows.mapIndexed { index, (text, important) ->
            """<tr class="news"><td><div class="date"><div class="day">${10 - index}</div>0$page.2026</div>${if (important) "<div>Важное</div>" else ""}</td><td><p>$text</p></td></tr>"""
        }.joinToString("") + "</table>" + (if (page < 2) """<table><tr><td class="tb">Страница $page <a href="/my/news/${page + 1}">&gt;</a></td></tr></table>"""
            else """<table><tr><td class="tb">Страница $page</td></tr></table>"""))

    @Test fun newsSearchCoversEveryPageFiltersImportantAndRestoresOriginalRows() {
        pages["/news/student/2"] = "<html><body>" + news(2, "Экзамен по физике перенесён" to false, "Спортивный праздник" to false) + "</body></html>"
        pages["/news/student/3"] = "<html><body>" + shell("<table><tr><td class=\"tb\">Страница 3</td></tr></table>") + "</body></html>"
        load(news(1, "Расписание экзаменов опубликовано" to true, "Конкурс эссе" to false), "https://univer.kaznu.kz/news/student")
        // A row the site itself keeps hidden is neither shown, found nor counted.
        eval("var r=document.querySelector('tr.news').cloneNode(true);r.hidden=true;r.cells[1].textContent='Скрытая сайтом';document.querySelector('tr.news').after(r)")
        mobile()
        waitFor("News tools", "document.querySelector('.univer-app-news-tools input.univer-app-news-query')")
        eval("""var q=document.querySelector('.univer-app-news-query');q.value='экзамен';q.dispatchEvent(new Event('input'))""")
        waitFor("Search reads the second page", "document.querySelectorAll('.univer-app-news-results tr.news').length===2")
        assertEquals("true", eval("Array.from(document.querySelectorAll('.univer-app-news-results tr.news')).every(r=>/экзамен/i.test(r.textContent))"))
        assertEquals("Results replace the source list", "true", eval("document.querySelector('table.univer-app-news:not([data-univer-results])').hasAttribute('data-univer-news-source')"))
        eval("document.querySelector('.univer-app-news-important').click()")
        waitFor("Important only", "document.querySelectorAll('.univer-app-news-results tr.news').length===1")
        assertEquals("true", eval("document.querySelector('.univer-app-news-results tr.news').textContent.includes('опубликовано')"))
        eval("document.querySelector('.univer-app-news-reset').click()")
        waitFor("Reset shows the page again", "document.querySelector('.univer-app-news-results').hidden")
        assertEquals("Pagers top and bottom", "2", eval("document.querySelectorAll('.univer-app-news-pager').length"))
        assertEquals("\"Показано 2\"", eval("document.querySelector('.univer-app-news-count').textContent"))
        mobile(false)
        assertEquals("0", eval("document.querySelectorAll('.univer-app-news-tools,.univer-app-news-pager,.univer-app-news-results,.univer-app-news-toggle').length"))
        assertEquals("true", eval("Array.from(document.querySelectorAll('tr.news')).every(r=>r.hidden===(r.cells[1].textContent==='Скрытая сайтом'))"))
    }

    private fun lesson(id: Int, type: String, table: String, sum: Int) =
        """<tr class="mid"><td class="ts"><a class="lt" href="/student/attendance/show/$id/2026/1">$type - Teacher $id</a><a href="#" class="rt syllabus-info">Интерактивный силлабус</a></td></tr>
           <tr><td><table class="inner" id="$table"><tr><th rowspan="2">РК1</th><th>01.09</th><th>Сумма</th></tr><tr><td>$sum</td><td>$sum</td></tr></table></td></tr>"""

    @Test fun attendanceFiltersCoursesAndLessonTypesAndLinksSyllabiOffline() {
        pages["/student/umkd/0/2026/1"] = """<html><body><div id="l4"><table class="inner"><tr><th>#</th><th>Дисциплина</th><th>Тип</th></tr>
            <tr class="link" id="77"><td>1</td><td>Социология</td><td>Обязательная</td></tr></table></div></body></html>"""
        pages["/student/umkd/77/2026/1"] = """<html><body><div id="l4"><a href="/student/interactive/syllabus/print/5/1">Силлабус (рус)</a>
            <a href="/student/umkd/get/1/2/2026/1">силлабус социология.pdf</a><a href="/student/umkd/get/3/4/2026/1">Лекция 1.pdf</a></div></body></html>"""
        load(shell("""<table class="mt">
            <tr class="top"><td class="ct">Социология ([РК1 + РК2 + Экз] (100)) 01.09.2026 - 12.12.2026</td></tr>
            ${lesson(1, "лекция", "lecture", 5)}${lesson(2, "семинар", "seminar", 7)}
            <tr class="top"><td class="ct">Психология ([РК1 + РК2 + Экз] (100)) 01.09.2026 - 12.12.2026</td></tr>
            ${lesson(3, "лекция", "psychology", 3)}
            <tr><td class="ct"><b>РК1 (100): 8</b></td></tr></table>"""), "https://univer.kaznu.kz/student/attendance/full/2026/1")
        eval("window.shape=function(){return JSON.stringify(Array.from(document.querySelectorAll('table.inner')).map(t=>Array.from(t.rows).map(r=>Array.from(r.cells).map(c=>[c.tagName,c.textContent,c.colSpan,c.rowSpan]))))};window.before=window.shape()")
        mobile()
        waitFor("Course selector", "document.querySelectorAll('.univer-app-attendance-courses option').length===3")
        assertEquals("Journal starts with summaries", "true", eval("document.querySelectorAll('.univer-app-attendance-details-toggle[aria-expanded=false]').length===2&&document.getElementById('lecture').closest('[hidden]')!==null"))
        // Each toggle redraws its course summary, so query the next collapsed one after every click.
        eval("for(var i=0;i<2;i++)document.querySelector('.univer-app-attendance-details-toggle[aria-expanded=false]').click()")
        assertEquals("\"Лекция\"", eval("document.querySelector('.univer-app-lesson-label strong').textContent"))
        fun visible(id: String) = eval("!document.getElementById('$id').closest('[hidden]')")
        eval("document.querySelector('.univer-app-attendance-filters button[data-kind=lecture]').click()")
        assertEquals("true", visible("lecture")); assertEquals("false", visible("seminar")); assertEquals("true", visible("psychology"))
        assertEquals("Matrices keep their cells", "true", eval("window.shape()===window.before"))
        eval("var s=document.querySelector('.univer-app-attendance-courses select');s.value=Array.from(s.options).find(o=>o.textContent==='Психология').value;s.dispatchEvent(new Event('change'))")
        assertEquals("false", visible("lecture")); assertEquals("true", visible("psychology"))
        eval("var s=document.querySelector('.univer-app-attendance-courses select');s.value='all';s.dispatchEvent(new Event('change'))")
        eval("document.querySelector('.univer-app-attendance-filters button[data-kind=all]').click()")
        assertEquals("true", visible("seminar"))
        waitFor("Course title links to its materials", "document.querySelector('a.univer-app-attendance-course[href=\"/student/umkd/77/2026/1\"]')")
        assertEquals("One syllabus button per course", "2", eval("document.querySelectorAll('.univer-app-course-syllabus').length"))
        eval("document.querySelector('.univer-app-course-heading').open=true;document.querySelector('.univer-app-course-syllabus').click()")
        waitFor("Syllabi listed", "document.querySelectorAll('.univer-app-syllabi li').length===2")
        assertEquals("Only syllabi, not other materials", "false", eval("document.querySelector('.univer-app-syllabi').textContent.includes('Лекция 1')"))
        mobile(false)
        assertEquals("0", eval("document.querySelectorAll('.univer-app-attendance-tools,.univer-app-course-heading,.univer-app-syllabi,.univer-app-lesson-label').length"))
        assertEquals("true", eval("Array.from(document.querySelectorAll('a.lt,a.syllabus-info')).every(a=>!a.hidden&&!a.hasAttribute('data-univer-lesson-label'))"))
        assertEquals("Only fixture pages were requested", emptyList<String>(), unexpected.toList())
    }

    private fun schedule(weekStart: Calendar): String {
        val format = SimpleDateFormat("dd.MM.yyyy", Locale.US)
        val start = format.format(weekStart.time)
        val end = format.format((weekStart.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 6) }.time)
        val days = listOf("ПН", "ВТ", "СР", "ЧТ", "ПТ", "СБ", "ВС")
        return shell("""<table class="mt"><tr class="top"><td class="lt"></td><td class="ct">Расписание</td><td class="rt"></td></tr>
            <tr class="mid"><td class="lt"></td><td class="tt"><select id="weekSelect"><option>Неделя 06. $start - $end</option></select>
            <table class="schedule"><tr><th></th>${days.joinToString("") { "<th>$it</th>" }}</tr>
            <tr><td>09:00-09:50</td>${days.indices.joinToString("") { "<td><div class=\"groups\"><div><p>Предмет $it</p></div></div></td>" }}</tr></table></td><td class="rt"></td></tr></table>""")
    }

    @Test fun scheduleMarksTodayOnlyInTheCurrentWeek() {
        val monday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12)
            add(Calendar.DAY_OF_MONTH, -((get(Calendar.DAY_OF_WEEK) + 5) % 7))
        }
        val today = (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7
        load(schedule(monday), "https://univer.kaznu.kz/student/myschedule/")
        mobile()
        waitFor("Day view", "document.querySelector('.univer-app-days')")
        assertEquals("1", eval("document.querySelectorAll('.univer-app-today-badge').length"))
        assertEquals("Today opens first", "true", eval("var s=Array.from(document.querySelectorAll('.univer-app-day'));s[$today].classList.contains('univer-app-today')&&!s[$today].hidden&&s.filter(x=>!x.hidden).length===1"))
        assertEquals("\"Сегодня\"", eval("document.querySelector('.univer-app-today-badge').textContent"))
        val earlier = (monday.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -14) }
        load(schedule(earlier), "https://univer.kaznu.kz/student/myschedule/")
        mobile()
        waitFor("Day view", "document.querySelector('.univer-app-days')")
        assertEquals("0", eval("document.querySelectorAll('.univer-app-today-badge').length"))
        assertEquals("Another week opens on Monday", "false", eval("document.querySelectorAll('.univer-app-day')[0].hidden"))
    }

    @Test fun semesterLinksBecomeStackedCardsAndTheRedundantUmkdButtonIsHidden() {
        load(shell("""<table class="mt"><tr class="top"><td class="lt"></td><td class="ct">УМКД</td><td class="rt"></td></tr>
            <tr class="mid"><td class="lt"></td><td class="ts" style="height:25px"><a class="lt" style="float:left" href="/student/umkd/0/2025/2">Перейти к 2025 году Весенний семестр</a>
            <a class="rt" style="float:right" href="/student/umkd/0/2026/2">Перейти к 2026 году Весенний семестр</a></td><td class="rt"></td></tr>
            <tr class="mid"><td class="lt"></td><td class="ts" id="discipline"><a id="discipline_btn" href="#">Просмотр УМКД по дисциплине</a></td><td class="rt"></td></tr></table>"""),
            "https://univer.kaznu.kz/student/umkd/")
        mobile()
        waitFor("Adapted", "document.documentElement.classList.contains('univer-mobile')")
        assertEquals("true", eval("""var a=document.querySelector('a.lt').getBoundingClientRect(),b=document.querySelector('a.rt').getBoundingClientRect();
            a.height>=44&&b.height>=44&&b.top>=a.bottom&&document.documentElement.scrollWidth<=innerWidth+1"""))
        assertEquals("\"none\"", eval("getComputedStyle(document.getElementById('discipline')).display"))
        mobile(false)
        assertEquals("Original floats and cell height return", "true",
            eval("getComputedStyle(document.querySelector('a.lt')).float==='left'&&document.querySelector('td.ts').style.height==='25px'&&!document.getElementById('univer-app-mobile-style')"))
    }
}
