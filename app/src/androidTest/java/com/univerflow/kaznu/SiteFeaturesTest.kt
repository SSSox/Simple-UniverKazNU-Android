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

    /** Loads the page scripts and switches the adapter; [before] runs once the scripts are installed, just before the switch. */
    private fun mobile(enabled: Boolean = true, before: String = "") {
        val script = MobilePageAdapter.SCRIPTS.joinToString("\n") { name ->
            instrumentation.targetContext.assets.open(name).bufferedReader().use { it.readText() } }
        eval(script + "\n" + before + "\nwindow.__univerMobileV1.setEnabled($enabled,'ru');")
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
           <tr><td><table class="inner" id="$table"><tr><th rowspan="2">РК1 (100)</th><th>01.09</th><th>Сумма</th></tr><tr><td>$sum</td><td>$sum</td></tr></table></td></tr>"""

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
        waitFor("Course chips", "document.querySelectorAll('.univer-app-attendance-courses button').length===3")
        assertEquals("One small table of numbers per course, each cell naming its period", "true", eval("var cells=Array.from(document.querySelectorAll('.univer-app-type-row .univer-app-score'));" +
            "cells.length>0&&cells.every(c=>/^РК1 \\(100\\): /.test(c.getAttribute('aria-label')))&&document.querySelectorAll('.univer-app-score-table').length===2"))
        assertEquals("Journal starts with summaries", "true", eval("document.querySelectorAll('.univer-app-attendance-details-toggle[aria-expanded=false]').length===2&&document.getElementById('lecture').closest('[hidden]')!==null"))
        // Each toggle redraws its course summary, so query the next collapsed one after every click.
        eval("for(var i=0;i<2;i++)document.querySelector('.univer-app-attendance-details-toggle[aria-expanded=false]').click()")
        assertEquals("\"Лекция\"", eval("document.querySelector('.univer-app-lesson-label strong').textContent"))
        fun visible(id: String) = eval("!document.getElementById('$id').closest('[hidden]')")
        eval("document.querySelector('.univer-app-attendance-filters button[data-kind=lecture]').click()")
        assertEquals("true", visible("lecture")); assertEquals("false", visible("seminar")); assertEquals("true", visible("psychology"))
        assertEquals("Matrices keep their cells", "true", eval("window.shape()===window.before"))
        eval("Array.from(document.querySelectorAll('.univer-app-attendance-courses button')).find(b=>b.textContent==='Психология').click()")
        assertEquals("false", visible("lecture")); assertEquals("true", visible("psychology"))
        eval("document.querySelector('.univer-app-attendance-courses button[data-course=all]').click()")
        eval("document.querySelector('.univer-app-attendance-filters button[data-kind=all]').click()")
        assertEquals("true", visible("seminar"))
        waitFor("Course title links to its materials", "document.querySelector('a.univer-app-attendance-course[href=\"/student/umkd/77/2026/1\"]')")
        assertEquals("One syllabus button per course", "2", eval("document.querySelectorAll('.univer-app-course-syllabus').length"))
        eval("document.querySelector('.univer-app-course-syllabus').click()")
        waitFor("Syllabi listed", "document.querySelectorAll('.univer-app-syllabi li').length===2")
        assertEquals("Only syllabi, not other materials", "false", eval("document.querySelector('.univer-app-syllabi').textContent.includes('Лекция 1')"))
        mobile(false)
        assertEquals("0", eval("document.querySelectorAll('.univer-app-attendance-tools,.univer-app-course-heading,.univer-app-syllabi,.univer-app-lesson-label').length"))
        assertEquals("true", eval("Array.from(document.querySelectorAll('a.lt,a.syllabus-info')).every(a=>!a.hidden&&!a.hasAttribute('data-univer-lesson-label'))"))
        assertEquals("Only fixture pages were requested", emptyList<String>(), unexpected.toList())
    }

    @Test fun aRedrawDoesNotSnapBackTheCourseRowTheStudentSwiped() {
        val courses = listOf("Социология", "Психология", "Культурология", "Политология", "История Казахстана", "Казахский (русский) язык")
        load(shell("""<table class="mt">""" + courses.mapIndexed { index, name ->
            """<tr class="top"><td class="ct">$name ([РК1 + РК2 + Экз] (100)) 01.09.2026 - 12.12.2026</td></tr>${lesson(index + 1, "лекция", "course$index", 5)}"""
        }.joinToString("") + "</table>"), "https://univer.kaznu.kz/student/attendance/full/2026/1")
        mobile()
        // The app's style makes the chips one row that scrolls sideways; without it they wrap and nothing scrolls.
        eval("window.__univerStyleV1.apply(true)")
        waitFor("Course chips", "document.querySelectorAll('.univer-app-attendance-courses button').length===7")
        waitFor("The row is wider than the phone", "(function(l){return l.scrollWidth>l.clientWidth+40;})(document.querySelector('.univer-app-attendance-course-list'))")
        eval("Array.from(document.querySelectorAll('.univer-app-attendance-courses button')).find(b=>b.textContent==='История Казахстана').click()")
        waitFor("A new choice is brought into sight", "document.querySelector('.univer-app-attendance-course-list').scrollLeft>0")
        eval("document.querySelector('.univer-app-attendance-course-list').scrollLeft=0")
        eval("document.querySelector('.univer-app-attendance-filters button[data-kind=lecture]').click()")
        waitFor("The type filter redrew the page", "document.querySelector('.univer-app-attendance-filters button[data-kind=lecture][aria-pressed=true]')")
        assertEquals("The swiped row stays where the student left it", "0", eval("document.querySelector('.univer-app-attendance-course-list').scrollLeft"))
        mobile(false)
    }

    // Review finding: a course chosen on another page (home overview, Текущая аттестация) was marked as shown while the
    // chips still wrapped, before the app style made them one scrolling row, so it stayed off-screen.
    @Test fun aCourseChosenOnAnotherPageIsBroughtIntoSightOnceTheRowScrolls() {
        val courses = listOf("Социология", "Психология", "Культурология", "Политология", "История Казахстана", "Казахский (русский) язык")
        load(shell("""<table class="mt">""" + courses.mapIndexed { index, name ->
            """<tr class="top"><td class="ct">$name ([РК1 + РК2 + Экз] (100)) 01.09.2026 - 12.12.2026</td></tr>${lesson(index + 1, "лекция", "course$index", 5)}"""
        }.joinToString("") + "</table>"), "https://univer.kaznu.kz/student/attendance/full/2026/1")
        eval("sessionStorage.setItem('univer.mobile.attendance-course.v1:/student/attendance/full/2026/1','name:история казахстана')")
        mobile()
        eval("window.__univerStyleV1.apply(true)")
        waitFor("The chosen course is pressed", "document.querySelector('.univer-app-attendance-courses button[aria-pressed=true]').textContent==='История Казахстана'")
        waitFor("and brought into sight once the row scrolls", "document.querySelector('.univer-app-attendance-course-list').scrollLeft>0")
        mobile(false)
    }

    // Review finding: a course name in "Сводка по предметам" is removed by the redraw it starts (the summary hides once a
    // course is chosen), so keyboard and TalkBack focus fell back to the page. It now lands on the chosen course's chip.
    @Test fun choosingACourseFromTheSummaryMovesFocusToItsChip() {
        load(shell("""<table class="mt">
            <tr class="top"><td class="ct">Социология ([РК1 + РК2 + Экз] (100)) 01.09.2026 - 12.12.2026</td></tr>${lesson(1, "лекция", "lecture", 5)}
            <tr class="top"><td class="ct">Психология ([РК1 + РК2 + Экз] (100)) 01.09.2026 - 12.12.2026</td></tr>${lesson(2, "лекция", "psychology", 3)}</table>"""),
            "https://univer.kaznu.kz/student/attendance/full/2026/1")
        eval("sessionStorage.clear()")
        mobile()
        waitFor("Course summary", "document.querySelectorAll('.univer-app-attendance-overview-name').length===2")
        eval("var n=Array.from(document.querySelectorAll('.univer-app-attendance-overview-name')).find(b=>b.textContent==='Психология');n.focus();n.click()")
        assertEquals("The summary gave way to the chosen course", "true", eval("document.querySelector('.univer-app-attendance-overview').hidden"))
        assertEquals("Focus is on the chosen course's chip, not lost to the page", "true",
            eval("var a=document.activeElement;a.matches('.univer-app-attendance-courses button[aria-pressed=true]')&&a.textContent==='Психология'"))
        mobile(false)
    }

    // Review finding: a paragraph holding only an audio player or a drawn image had no text, so it was hidden as blank.
    @Test fun aNewsPlayerWithoutTextIsNotTakenForABlankParagraph() {
        val article = "<p>" + "Длинный текст новости для проверки превью на телефоне. ".repeat(30) + "</p><p id=\"player\"><audio controls></audio></p><p id=\"blank\">&nbsp;</p>"
        load(shell("""<table class="inner"><tr><th>Дата</th><th>Новость</th></tr><tr class="news"><td><div class="date">01.01.2026</div></td><td><span class="header">Заголовок</span>$article</td></tr></table>"""),
            "https://univer.kaznu.kz/news/student")
        mobile()
        eval("window.__univerStyleV1.apply(true)")
        waitFor("Long article folded", "document.querySelector('.univer-app-news-body[data-collapsed=true]')")
        eval("document.querySelector('.univer-app-news-toggle').click()")
        assertEquals("The player stays in the expanded article", "true", eval("getComputedStyle(document.getElementById('player')).display!=='none'"))
        assertEquals("A paragraph of only a space is still blank", "true", eval("document.getElementById('blank').hasAttribute('data-univer-blank')"))
        mobile(false)
    }

    @Test fun aNewsPreviewEndsOnAWholeLine() {
        val article = "<p>" + "Длинный текст новости для проверки превью на телефоне. ".repeat(40) + "</p>"
        load(shell("""<table class="inner"><tr><th>Дата</th><th>Новость</th></tr><tr class="news"><td><div class="date">01.01.2026</div></td><td><span class="header">Заголовок</span>$article</td></tr></table>"""),
            "https://univer.kaznu.kz/news/student")
        mobile()
        eval("window.__univerStyleV1.apply(true)")
        waitFor("Long article folded", "document.querySelector('.univer-app-news-body[data-collapsed=true]')")
        waitFor("Fold set from the text", "document.querySelector('.univer-app-news-body').style.getPropertyValue('--univer-news-fold')")
        assertEquals("No line is cut by the fold", "true", eval("var b=document.querySelector('.univer-app-news-body'),edge=b.getBoundingClientRect().bottom,r=document.createRange();" +
            "r.selectNodeContents(b.querySelector('p').firstChild);var l=Array.from(r.getClientRects());l.length>3&&l.every(x=>x.bottom<=edge+1||x.top>=edge-1)"))
        mobile(false)
        assertEquals("Original layout gets its article back", "0", eval("document.querySelectorAll('.univer-app-news-body').length"))
    }

    // Observed on /news/student: articles pasted through TinyMCE keep its hidden #_mcePaste buffer, 1px wide at
    // left:-10000px. Its one-letter "lines" ended the preview halfway through a real line.
    @Test fun aHiddenPasteBufferDoesNotCutTheNewsPreview() {
        val buffer = """<div id="_mcePaste" style="position:absolute;left:-10000px;top:0;width:1px;height:1px;overflow:hidden">Уважаемые коллеги!</div>"""
        val article = buffer + "<div>" + "Длинный текст новости для проверки превью на телефоне. ".repeat(40) + "</div>"
        load(shell("""<table class="inner"><tr><th>Дата</th><th>Новость</th></tr><tr class="news"><td><div class="date">01.01.2026</div></td><td><span class="header">Заголовок</span>$article</td></tr></table>"""),
            "https://univer.kaznu.kz/news/student")
        mobile()
        eval("window.__univerStyleV1.apply(true)")
        waitFor("Long article folded", "document.querySelector('.univer-app-news-body[data-collapsed=true]')")
        waitFor("Fold set from the text", "document.querySelector('.univer-app-news-body').style.getPropertyValue('--univer-news-fold')")
        assertEquals("No visible line is cut by the fold", "true", eval("var b=document.querySelector('.univer-app-news-body'),edge=b.getBoundingClientRect().bottom,r=document.createRange();" +
            "r.selectNodeContents(b.querySelector('div:not(#_mcePaste)').firstChild);var l=Array.from(r.getClientRects());l.length>3&&l.every(x=>x.bottom<=edge+1||x.top>=edge-1)"))
        mobile(false)
    }

    @Test fun theQuestionnaireReadsAsFactsAtFullWidth() {
        load(shell("""<table class="mt"><tr id="Tr1" class="mid"><td class="tt"><div class="inner" style="display:inline-block;width:50%"><table class="inner">
            <tr><td><li>Страна:</li></td><td>Казахстан</td></tr><tr><td><li>Шифр специальности</li></td><td>6B06301</td></tr><tr><td> </td><td> </td></tr></table></div></td></tr></table>"""),
            "https://univer.kaznu.kz/student/profile/")
        mobile()
        eval("window.__univerStyleV1.apply(true)")
        waitFor("A list item marks a label, with or without a colon", "document.querySelector('table.univer-app-pairs')")
        assertEquals("The site's half-width column is gone", "true", eval("document.querySelector('table.univer-app-pairs').getBoundingClientRect().width>document.documentElement.clientWidth*.8"))
        assertEquals("A row with nothing in it takes no room", "none", eval("getComputedStyle(document.querySelector('table.univer-app-pairs').rows[2]).display").orEmpty().trim('"'))
        mobile(false)
        assertEquals("Original layout gets the plain table back", "0", eval("document.querySelectorAll('.univer-app-pairs,.univer-app-blank-field').length"))
    }

    @Test fun onlyTablesOfFactsLeaveTheLinkTableLayout() {
        load(shell("""<table class="xp" id="facts"><tr><td>Форма обучения:</td><td>очная</td><td>Ступень обучения:</td><td>бакалавриат</td></tr></table>
            <table class="xp" id="links"><tr><td><a href="/student/educplan/1">Семестр 1</a></td><td><a href="/student/educplan/2">Семестр 2</a></td></tr></table>"""),
            "https://univer.kaznu.kz/student/educplan/")
        mobile()
        assertEquals("true", eval("document.querySelector('#facts').classList.contains('univer-app-pairs')&&document.querySelector('#links').classList.contains('univer-app-nav-table')"))
    }

    @Test fun anEmptyTableHasNothingToExpand() {
        load(shell("""<table class="inner"><tr class="empty"><td colspan="5"><div style="width:900px">Не заполнено расписание Midterm</div></td></tr></table>"""),
            "https://univer.kaznu.kz/student/mymidterm/schedule/")
        mobile()
        eval("window.__univerStyleV1.apply(true)")
        waitFor("The note stands in its scroller", "document.querySelector('.univer-app-table-scroll table.univer-app-empty-note')")
        Thread.sleep(300)
        assertEquals("false", eval("!!document.querySelector('.univer-app-scroll-hint:not([hidden])')"))
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
        // aria-label is not allowed on a role-less div: the row of day buttons is a named group, like the view switch.
        assertEquals("The day buttons form a group named for screen readers", "true",
            eval("var d=document.querySelector('.univer-app-days');d.getAttribute('role')==='group'&&d.getAttribute('aria-label')==='День недели'"))
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

    // Review finding: every wrapped table, card lists and tables that fit included, was a focusable region announcing
    // "↔ swipe the table sideways". Only a table wider than its box is such a stop now, and its name has no arrow.
    @Test fun onlyATableWiderThanItsBoxIsAFocusableRegion() {
        load(shell("""<table id="narrow"><tr><th>Оценка</th><th>Балл</th></tr><tr><td>A</td><td>4,0</td></tr></table>
            <table id="wide"><tr>""" + (1..13).joinToString("") { "<th>Столбец $it</th>" } + "</tr><tr>" + (1..13).joinToString("") { "<td>$it</td>" } + "</tr></table>"),
            "https://univer.kaznu.kz/help/")
        eval("document.getElementById('wide').style.width=Math.round(innerWidth*4)+'px'")
        mobile()
        waitFor("Both tables stand in their boxes", "document.getElementById('narrow').parentElement.classList.contains('univer-app-table-scroll')&&" +
            "document.getElementById('wide').parentElement.classList.contains('univer-app-table-scroll')")
        assertEquals("The wide table is a named region", "true", eval("var b=document.getElementById('wide').parentElement;" +
            "b.tabIndex===0&&b.getAttribute('role')==='region'&&b.getAttribute('aria-label')==='Сдвиньте таблицу вбок, чтобы увидеть все столбцы'"))
        assertEquals("A table that fits is read in place", "true", eval("var b=document.getElementById('narrow').parentElement;" +
            "!b.hasAttribute('tabindex')&&!b.hasAttribute('role')&&!b.hasAttribute('aria-label')"))
        // The site redraws the table narrow: the adapter's next pass takes the stop away again.
        eval("var t=document.getElementById('wide');t.style.width='';t.innerHTML='<tbody><tr><td>Коротко</td></tr></tbody>'")
        waitFor("The narrowed table is no longer a stop", "!document.getElementById('wide').parentElement.hasAttribute('role')")
        assertEquals("false", eval("document.getElementById('wide').parentElement.hasAttribute('tabindex')"))
        mobile(false)
        assertEquals("0", eval("document.querySelectorAll('.univer-app-table-scroll').length"))
    }

    // Review finding: under the native app bar the page has no header "All sections" button, so Escape closed the menu
    // around the focused search field and focus fell back to the page. It now returns to the menu's title, as Back does.
    @Test fun escapeClosesTheSectionsMenuOntoItsTitleUnderTheNativeAppBar() {
        load("""<table class="wrappertable"><tr id="l1"><td class="header">Казахский национальный университет имени аль-Фараби</td></tr>
            <tr id="l4"><td class="center"><div id="menu-home"><table id="tools"><tr><td class="center"><table class="tool"><tr><td><ul>
            <li><a href="/student/myschedule/">Расписание</a></li><li><a href="/student/umkd/">УМКД</a></li></ul></td></tr>
            <tr><td class="bottom-center">Учёба</td></tr></table></td></tr></table></div></td></tr></table>""", "https://univer.kaznu.kz/student/umkd/")
        mobile(before = "window.__univerMobileV1.setNativeChrome(true);")
        waitFor("Sections menu", "document.querySelector('.univer-app-menu .univer-app-menu-search')")
        assertEquals("No header button under the native app bar", "0", eval("document.querySelectorAll('.univer-app-sections').length"))
        eval("var m=document.querySelector('.univer-app-menu');m.open=true;m.querySelector('.univer-app-menu-search').focus()")
        eval("document.activeElement.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true,cancelable:true}))")
        assertEquals("The menu closed onto its own title", "true",
            eval("var m=document.querySelector('.univer-app-menu');!m.open&&document.activeElement===m.querySelector(':scope>summary')"))
        mobile(false)
    }

    /** Chromium 80-85 as page scripts see it: selector APIs reject :is()/:has()/:where() (88/105) and replaceChildren (86) is missing. */
    private val olderEngine = """(function(){var newer=/:(is|has|where)\(/;
        [Document.prototype,Element.prototype,DocumentFragment.prototype].forEach(function(p){
            ['querySelector','querySelectorAll','matches','closest'].forEach(function(n){
                if(!Object.prototype.hasOwnProperty.call(p,n))return;var f=p[n];
                p[n]=function(s){if(newer.test(String(s)))throw new DOMException("'"+s+"' is not a valid selector",'SyntaxError');return f.apply(this,arguments);};
            });
            delete p.replaceChildren;
        });})();"""

    // Review finding: the documented floor is Chromium 80, but :is(), :has() and replaceChildren threw there and aborted
    // the whole adapter pass: no lesson captions or course summary, no folded FAQ, no Wi-Fi card on Е-услуги.
    @Test fun pagesStillAdaptOnAnEngineWithoutIsHasOrReplaceChildren() {
        load(shell("""<table class="mt">
            <tr class="top"><td class="ct">Социология ([РК1 + РК2 + Экз] (100)) 01.09.2026 - 12.12.2026</td></tr>${lesson(1, "лекция", "lecture", 5)}
            <tr class="top"><td class="ct">Психология ([РК1 + РК2 + Экз] (100)) 01.09.2026 - 12.12.2026</td></tr>${lesson(2, "семинар", "seminar", 3)}</table>"""),
            "https://univer.kaznu.kz/student/attendance/full/2026/1")
        eval("sessionStorage.clear()")
        mobile(before = olderEngine)
        waitFor("Course chips", "document.querySelectorAll('.univer-app-attendance-courses button').length===3")
        assertEquals("Lesson captions are written", "true",
            eval("Array.from(document.querySelectorAll('.univer-app-lesson-label strong')).map(s=>s.textContent).join()==='Лекция,Семинар'"))
        assertEquals("The course summary lists both courses", "2", eval("document.querySelectorAll('.univer-app-attendance-overview-name').length"))
        mobile(false)

        load(shell("""<table class="mt"><tr class="top"><td class="ct">Часто задаваемые вопросы</td></tr><tr id="spacer"><td></td></tr>
            <tr><td class="ct">Как получить справку?</td></tr><tr><td class="tt"><table class="inner"><tr><td>Ответ</td></tr><tr><td>Через Е-услуги.</td></tr></table></td></tr></table>"""),
            "https://univer.kaznu.kz/my/faq/")
        mobile(before = olderEngine)
        waitFor("The question folds its answer", "document.querySelector('details.univer-app-faq>summary').textContent==='Как получить справку?'")
        assertEquals("The panel's empty row takes no room", "true", eval("document.getElementById('spacer').classList.contains('univer-app-study-empty')"))
        mobile(false)

        load(shell("""<ul><li>Логин и пароль для системы print.kaznu.kz и WiFi-сети КазНУ.:<br>Логин: fixture_user<br>Пароль: fixture-pass1</li></ul>"""),
            "https://univer.kaznu.kz/ssc/")
        mobile(before = olderEngine)
        waitFor("The sign-in line becomes a card", "document.querySelectorAll('.univer-app-credentials .univer-app-credential').length===2")
        mobile(false)
    }

    // Review finding: one page module throwing aborted the rest of the adapter pass (frames, table fitting, swipe hints).
    @Test fun aFailingPageModuleDoesNotCostTheRestOfThePass() {
        load(shell("<table id=\"wide\"><tr>" + (1..13).joinToString("") { "<th>Столбец $it</th>" } + "</tr></table>"), "https://univer.kaznu.kz/help/")
        eval("document.getElementById('wide').style.width=Math.round(innerWidth*4)+'px'")
        mobile(before = "window.__univerDiscussionV1.apply=function(){throw new TypeError('fixture failure');};")
        waitFor("The swipe hint still comes after the failing module", "document.querySelector('.univer-app-scroll-hint:not([hidden])')")
        mobile(false)
    }
}
