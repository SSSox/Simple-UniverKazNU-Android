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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Текущая аттестация and Дистанционные курсы in the phone view. Markup follows the pages observed on Univer
 * (October 2026); every request is answered by local fixtures and nothing reaches the website.
 */
@RunWith(AndroidJUnit4::class)
class StudyPagesTest {
    private lateinit var scenario: ActivityScenario<DownloadsActivity>
    private lateinit var web: WebView
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val jquery get() = instrumentation.context.assets.open("site-jquery.js").bufferedReader().use { it.readText() }
    private val pages = mutableMapOf<String, String>()
    private val requested = CopyOnWriteArrayList<String>()
    private val unexpected = CopyOnWriteArrayList<String>()
    private val navigations = CopyOnWriteArrayList<String>()

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

    private fun load(body: String, origin: String, scripts: String = "") {
        val loaded = CountDownLatch(1)
        instrumentation.runOnMainSync {
            web.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (request.isForMainFrame) return null
                    val path = request.url.path.orEmpty()
                    val html = pages[path.trimEnd('/')]
                    if (path.startsWith("/student/")) { requested += path; if (html == null) unexpected += path }
                    return WebResourceResponse("text/html", "utf-8", if (html == null) 404 else 200, if (html == null) "Not Found" else "OK",
                        emptyMap(), ByteArrayInputStream((html ?: "").toByteArray()))
                }
                // Page navigations are recorded and stopped: the fixture never leaves for the real website.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    navigations += request.url.toString(); return true
                }
                override fun onPageFinished(view: WebView, url: String?) { loaded.countDown() }
            }
            web.loadDataWithBaseURL(origin, "<html><head><meta charset=\"utf-8\"><script>$scripts</script></head><body>$body</body></html>", "text/html", "UTF-8", origin)
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

    private fun navigatedTo(path: String) {
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) { if (navigations.any { it == "https://univer.kaznu.kz$path" }) return; Thread.sleep(100) }
        fail("Navigation to $path, saw $navigations")
    }

    private fun mobile(enabled: Boolean = true) {
        val script = MobilePageAdapter.SCRIPTS.joinToString("\n") { name ->
            instrumentation.targetContext.assets.open(name).bufferedReader().use { it.readText() } }
        eval(script + "\nwindow.__univerMobileV1.setNativeChrome(true);window.__univerMobileV1.setEnabled($enabled,'ru');window.__univerStyleV1.apply($enabled);")
    }

    private fun shell(content: String) = """<table class="wrappertable"><tr id="l4"><td class="center">$content</td></tr></table>"""
    private fun day(offset: Int) = SimpleDateFormat("dd.MM.yyyy", Locale.ROOT).format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offset) }.time)

    @Test fun attestationShowsCourseCardsSummaryAndSemesterSwitchAndRestoresOriginal() {
        load(shell("""<table class="mt" id="panel">
            <tr class="top"><td class="lt"></td><td class="ct" id="heading">Аттестация студента.  Осенний Семестр, 2026-2027 уч. год (1 Курс, 1 Семестр)</td><td class="rt"></td></tr>
            <tr class="mid"><td class="lt"></td><td class="ts"><a href="/student/attestation/2025/2" class="lt" id="previous"><img src="/content/icons/tb_left.png" alt=" [<<] "> Перейти к 2025-2026 году Весенний семестр</a>
                <a href="/student/attestation/2026/2" class="rt" id="next"> Перейти к 2026-2027 году Весенний семестр<img src="/content/icons/tb_right.png" alt=" [>>] "></a></td><td class="rt"></td></tr>
            <tr class="mid"><td class="lt"></td><td class="tt"><table class="inner" id="grades">
                <tr><th>Дисциплина</th><th>Кред.</th><th>РК1</th><th>РК2</th><th style="color:red">ПА*</th><th>Сумма</th><th>GPA</th><th>Букв.</th><th>Значение</th></tr>
                <tr><td>Иностранный язык</td><td>5</td><td>99</td><td>0</td><td>0</td><td>30</td><td>0</td><td>F</td><td>неуд.     </td></tr>
                <tr><td>Социология</td><td>2</td><td>87</td><td>90</td><td>95</td><td>91</td><td>4</td><td>A</td><td>отл.</td></tr>
                <tr><th><b>GPA(2026-2027 уч. год)-&nbsp;0</b></th><th colspan="8">Освоено кредитов (2026-2027 уч. год) -&nbsp;0</th></tr>
            </table></td><td class="rt"></td></tr>
            <tr class="mid"><td class="lt"></td><td class="ct" style="color:red" id="note"><b>Примечание:</b><br /><br /><b>* - оценка итогового контроля (экзамена) может быть аннулирована</b></td><td class="rt"></td></tr>
            </table>"""), "https://univer.kaznu.kz/student/attestation/")
        val headingBefore = eval("document.getElementById('heading').innerHTML")
        mobile()
        waitFor("Course cards", "document.querySelectorAll('.univer-app-attestation-course').length===2")
        assertEquals("Name, letter grade and the line of facts", "true", eval("""(function(){var c=document.querySelectorAll('.univer-app-attestation-course');
            return c[0].querySelector('h3').textContent==='Иностранный язык'&&c[0].querySelector('.univer-app-attestation-letter').dataset.grade==='f'&&
            c[1].querySelector('.univer-app-attestation-letter').dataset.grade==='a'&&c[0].querySelector('.univer-app-attestation-meta').textContent==='5 кредитов · неуд. · GPA 0'&&
            c[1].querySelector('.univer-app-attestation-meta').textContent==='2 кредита · отл. · GPA 4';})()"""))
        assertEquals("Periods and the weighted total", "\"РК1 99|РК2 0|ПА* 0|Итог 30\"", eval("""Array.from(document.querySelectorAll('.univer-app-attestation-course')[0].querySelectorAll('.univer-app-score'))
            .map(t=>t.querySelector('.univer-app-score-label').textContent+' '+t.querySelector('.univer-app-score-value').textContent).join('|')"""))
        assertEquals("Year facts from the footer and the semester's credits", "\"0 GPA за учебный год|0 Освоено кредитов за учебный год|7 Кредитов в семестре\"",
            eval("Array.from(document.querySelectorAll('.univer-app-attestation-stat')).map(s=>s.querySelector('.univer-app-attestation-stat-value').textContent+' '+s.querySelector('.univer-app-attestation-stat-label').textContent).join('|')"))
        assertEquals("\"Осенний Семестр, 2026-2027 уч. год|1 Курс, 1 Семестр\"", eval("document.querySelector('.univer-app-attestation-term').textContent+'|'+document.querySelector('.univer-app-attestation-level').textContent"))
        assertEquals("Semester switch", "\"Предыдущий 2025–2026 · Весенний семестр /student/attestation/2025/2|Следующий 2026–2027 · Весенний семестр /student/attestation/2026/2\"",
            eval("Array.from(document.querySelectorAll('.univer-app-term-nav a')).map(a=>a.querySelector('.univer-app-term-caption').textContent+' '+a.querySelector('.univer-app-term-value').textContent+' '+new URL(a.href).pathname).join('|')"))
        assertEquals("The table and long links are hidden, the cards fit the phone", "true", eval("""document.getElementById('grades').getClientRects().length===0&&
            document.getElementById('previous').getClientRects().length===0&&document.querySelector('.univer-app-attestation-course').getBoundingClientRect().width>200&&
            document.documentElement.scrollWidth<=innerWidth+1"""))
        // The class log opens on this course: the attendance module resolves the name.
        eval("document.addEventListener('click',function(e){e.preventDefault();});document.querySelectorAll('.univer-app-attestation-journal')[1].click()")
        assertEquals("\"name:социология\"", eval("sessionStorage.getItem('univer.mobile.attendance-course.v1:/student/attendance/full/')"))
        mobile(false)
        assertEquals("Original restores the server markup", "true", eval("""document.querySelectorAll('[class*=univer-app-attestation],.univer-app-term-nav,.univer-app-term-source').length===0&&
            document.getElementById('grades').getClientRects().length>0&&document.getElementById('previous').getClientRects().length>0"""))
        assertEquals(headingBefore, eval("document.getElementById('heading').innerHTML"))
    }

    // Headings and footnote as Univer shows them in English (observed: "RK" without 1, GPA column with a colon).
    @Test fun attestationReadsEnglishHeadingsAndFootnote() {
        load(shell("""<table class="mt"><tr class="top"><td class="lt"></td><td class="ct">Student attestation. Autumn semester, 2026-2027 уч. год (1 Year of study, 1 semester)</td><td class="rt"></td></tr>
            <tr class="mid"><td class="lt"></td><td class="tt"><table class="inner" id="grades">
                <tr><th>Discipline</th><th>Cred.</th><th>RK</th><th>RK2</th><th>PA*</th><th>sum</th><th>Grade Point Average (GPA):</th><th>Letter</th><th>Value</th></tr>
                <tr><td>Foreign Language</td><td>5</td><td>99</td><td>0</td><td>0</td><td>30</td><td>0</td><td>F</td><td>unsatisf-y</td></tr>
                <tr><th><b>Grade Point Average (GPA):(2026-2027 уч. год)-&nbsp;0</b></th><th colspan="8">Credits completed (2026-2027 уч. год) -&nbsp;0</th></tr>
            </table></td><td class="rt"></td></tr>
            <tr class="mid"><td class="lt"></td><td class="ct" style="color:red"><b>Additional Information:</b><br><br><b>* - the exam grade may be cancelled</b></td><td class="rt"></td></tr></table>"""),
            "https://univer.kaznu.kz/student/attestation/")
        mobile()
        waitFor("Course card", "document.querySelectorAll('.univer-app-attestation-course').length===1")
        assertEquals("\"RK 99|RK2 0|PA* 0|Итог 30|5 кредитов · unsatisf-y · GPA 0\"", eval("""Array.from(document.querySelectorAll('.univer-app-score')).map(t=>t.querySelector('.univer-app-score-label').textContent+' '+t.querySelector('.univer-app-score-value').textContent).join('|')+'|'+
            document.querySelector('.univer-app-attestation-meta').textContent"""))
        assertEquals("\"Grade Point Average (GPA) за учебный год|Credits completed за учебный год|Кредитов в семестре\"",
            eval("Array.from(document.querySelectorAll('.univer-app-attestation-stat-label')).map(s=>s.textContent).join('|')"))
        assertEquals("The footnote is recognised", "1", eval("document.querySelectorAll('.univer-app-attestation-note').length"))
    }

    // Kazakh headings as observed: "Апта", "Оқу мерзімі" (period), "Тапсырмалардың сан" (tasks).
    @Test fun distanceWeeksReadKazakhHeadingsAndMarkTheCurrentWeek() {
        load(shell("""<table class="mt"><tr class="mid"><td class="lt"></td><td class="tt">
            <table class="inner"><tr><th><img></th><th>Апта</th><th>Оқу мерзімі</th><th>Ресурстар сан</th><th>Тапсырмалардың сан</th><th>&nbsp;</th></tr></table>
            <div class="inner scrool"><table class="inner" id="weeks"><tr class="link" id="1"><td><img></td><td>1</td><td>${day(-9)} 00:00-${day(-3)} 00:00</td><td>0</td><td>0</td></tr>
            <tr class="link" id="2"><td><img></td><td>2</td><td>${day(-2)} 00:00-${day(4)} 00:00</td><td>2</td><td>1</td></tr></table></div></td><td class="rt"></td></tr></table>"""),
            "https://univer.kaznu.kz/student/discussion/acpos/week/list/536582/101966/2026/1/", jquery + """
            window.opened=[]; $(document).ready(function(){ $("table.mt table.inner tr.link").each(function(){ $(this).dblclick(function(){ window.opened.push(this.id); }); }); });""")
        mobile()
        waitFor("Week cards", "document.querySelectorAll('.univer-app-dist-card').length===2")
        assertEquals("\"2|Ресурсы: 2 · Задания: 1|1\"", eval("""(function(){var c=document.querySelector('.univer-app-dist-card[data-current]');
            return c.querySelector('.univer-app-dist-lead').textContent+'|'+c.querySelector('.univer-app-dist-meta').textContent+'|'+c.querySelector('.univer-app-dist-count').textContent;})()"""))
        eval("document.querySelector('.univer-app-dist-card[data-current] .univer-app-dist-main').click()")
        assertEquals("\"2\"", eval("window.opened.join(',')"))
    }

    private fun discussionRows(rows: String, headers: String) = """<table class="mt"><tr class="top"><td class="lt"></td><td class="ct">Список</td><td class="rt"></td></tr>
        <tr class="mid"><td class="lt"></td><td class="tt"><div class="inner"><table class="inner"><tr>$headers</tr>$rows</table></div></td><td class="rt"></td></tr></table>"""

    @Test fun distanceCoursesOpenRowsWithSiteHandlersAndCountTasksPerDiscipline() {
        val start = day(-2); val end = day(3)
        pages["/student/discussion/acpos/list/102/2026/1"] = "<html><body>" + shell(discussionRows("""<tr class="link" id="500"><td><img></td><td>6B06301-Системы (0)</td><td>${day(-60)}</td><td>${day(40)}</td></tr>""",
            "<th>&nbsp;</th><th>Специальность</th><th>Дата начала (дд/мм/гггг)</th><th>Дата окончания (дд/мм/гггг)</th>")) + "</body></html>"
        pages["/student/discussion/acpos/list/101/2026/1"] = "<html><body>" + shell(discussionRows("""<tr class="link" id="501"><td><img></td><td>6B06301-Системы (0)</td><td>${day(-60)}</td><td>${day(40)}</td></tr>""",
            "<th>&nbsp;</th><th>Специальность</th><th>Дата начала (дд/мм/гггг)</th><th>Дата окончания (дд/мм/гггг)</th>")) + "</body></html>"
        val weeks = { tasks: Int -> "<html><body>" + shell("""<table class="mt"><tr class="mid"><td class="lt"></td><td class="tt">
            <table class="inner"><tr><th><img></th><th>Неделя</th><th>Период</th><th>Количество ресурсов</th><th>Количество заданий</th><th>&nbsp;</th></tr></table>
            <div class="inner scrool"><table class="inner"><tr class="link" id="1"><td><img></td><td>1</td><td>${day(-9)} 00:00-${day(-3)} 00:00</td><td>0</td><td>0</td></tr>
            <tr class="link" id="2"><td><img></td><td>2</td><td>${day(-2)} 00:00-${day(4)} 00:00</td><td>0</td><td>$tasks</td></tr></table></div></td><td class="rt"></td></tr></table>""") + "</body></html>" }
        // An ended session answers with the sign-in form: that discipline is "not checked", never "no tasks".
        pages["/student/discussion/acpos/list/103/2026/1"] = """<html><body><form id="login_frm"><input type="text"></form></body></html>"""
        pages["/student/discussion/acpos/week/list/500/102/2026/1"] = weeks(1)
        pages["/student/discussion/acpos/week/list/501/101/2026/1"] = weeks(0)
        pages["/student/discussion/task/list/500/102/2026/1/2"] = "<html><body>" + shell(discussionRows("""<tr class="link" id="9001" title="План"><td><img src="/content/icons/file.png"></td><td> Эссе по истории (0). Автор: Test Teacher</td><td>$start</td><td>$end</td><td>$start</td><td>42</td></tr>""",
            "<th>&nbsp;</th><th>Название</th><th>Дата начала (дд/мм/гггг)</th><th>Дата окончания (дд/мм/гггг)</th><th>Дата созд.</th><th>Участников</th>")) + "</body></html>"
        load(shell("""<table class="mt" id="help"><tr class="top"><td class="lt"></td><td class="ct">Инструкция</td><td class="rt"></td></tr>
            <tr class="mid"><td class="lt"></td><td class="ct"><a href="/Content/instructions/guide.pdf">Инструкция для студентов</a></td><td class="rt"></td></tr></table>
            <table class="mt"><tr class="top"><td class="lt"></td><td class="ct"><span style="float:left"><a>Назад</a> / <a href="">Список дисциплин 2026-2027 год Осенний Семестр</a></span></td><td class="rt"></td></tr>
            <tr class="mid"><td class="lt"></td><td class="ts"><a href="#" id="subjectShow_btn" class="lt"><img src="/content/icons/door-in-icon.png">Открыть дисциплину</a></td><td class="rt"></td></tr>
            <tr class="brk"><td class="lt"></td><td class="ct">2026-2027 год Осенний Семестр</td><td class="rt"></td></tr>
            <tr class="mid"><td class="lt"></td><td class="tt"><div class="inner"><table class="inner" id="disciplines"><tr><th>&nbsp;</th><th>Дисциплина</th></tr>
            <tr class="link" id="101"><td><img src="/content/icons/folder.png"></td><td>Социология  (0)</td></tr>
            <tr class="link" id="102"><td><img src="/content/icons/folder.png"></td><td>История Казахстана  (0)</td></tr>
            <tr class="link" id="103"><td><img src="/content/icons/folder.png"></td><td>Политология  (0)</td></tr>
            <tr class="link" id="other"><td><img src="/content/icons/folder.png"></td><td>Обсуждения других пользователей</td></tr></table></div></td><td class="rt"></td></tr></table>"""),
            "https://univer.kaznu.kz/student/discussion/index/", jquery + """
            window.opened=[]; var checked=false;
            $(document).ready(function(){ $("table.mt table.inner tr.link").each(function(){
                $(this).click(function(){ checked=$(this); });
                $(this).dblclick(function(){ window.opened.push(checked.attr('id')+'>'+"/student/discussion/acpos/list/" + this.id + "/2026/1/"); });
            }); });""")
        val helpHome = eval("document.getElementById('help').parentElement.className")
        sessionStorageClear()
        mobile()
        waitFor("Discipline cards", "document.querySelectorAll('.univer-app-dist-card').length===4")
        assertEquals("Names without the site's zero counter; the other users' list keeps its own icon", "\"Социология|История Казахстана|Политология|Обсуждения других пользователей:people\"",
            eval("Array.from(document.querySelectorAll('.univer-app-dist-title')).map(t=>t.textContent).join('|')+':'+document.querySelectorAll('.univer-app-dist-card')[3].dataset.kind"))
        waitFor("Task counts", "!/Проверяем/.test(document.querySelector('.univer-app-dist-due-status').textContent)")
        assertEquals("\"Заданий нет|1 задание · срок ${end.substring(0, 5)}|(not checked)\"",
            eval("Array.from(document.querySelectorAll('.univer-app-dist-tasks')).map(n=>n.hidden?'(not checked)':n.textContent).join('|')"))
        assertEquals("\"Не удалось проверить часть дисциплин\"", eval("document.querySelector('.univer-app-dist-due-status').textContent"))
        assertEquals("Open tasks lead the page and open the task itself", "\"Открытые задания · 1|Эссе по истории|/student/discussion/task/preview/9001/500/102/2026/1/2/0/0/0/\"",
            eval("""(function(){var s=document.querySelector('.univer-app-dist-due'),a=s.querySelector('a');
                return s.querySelector('h2').textContent+'|'+a.querySelector('.univer-app-dist-due-name').textContent+'|'+new URL(a.href).pathname;})()"""))
        assertTrue("Only the pages the cards open were read: $requested", unexpected.isEmpty() && requested.none { it.contains("/task/preview/") })
        // A discipline with one study plan opens its weeks directly, skipping the plan list.
        eval("document.querySelectorAll('.univer-app-dist-main')[1].click()")
        navigatedTo("/student/discussion/acpos/week/list/500/102/2026/1/")
        assertEquals("No row handler ran for it", "\"\"", eval("window.opened.join(',')"))
        // Otherwise one tap replays the site's click (selection) and dblclick (open the selected row).
        eval("document.querySelectorAll('.univer-app-dist-main')[2].click()")
        assertEquals("\"103>/student/discussion/acpos/list/103/2026/1/\"", eval("window.opened.join(',')"))
        assertEquals("Instructions fold, the opener and the repeated semester row are hidden", "true", eval("""document.querySelector('details.univer-app-dist-help>summary').textContent==='Инструкции (1)'&&
            document.getElementById('subjectShow_btn').getClientRects().length===0&&document.querySelector('tr.brk').getClientRects().length===0&&
            document.getElementById('disciplines').getClientRects().length===0&&document.documentElement.scrollWidth<=innerWidth+1"""))
        mobile(false)
        assertEquals("Original restores tables, buttons and the instructions' place", "true", eval("""document.querySelectorAll('.univer-app-dist-list,.univer-app-dist-due,.univer-app-dist-help,.univer-app-dist-source,.univer-app-dist-head').length===0&&
            document.getElementById('disciplines').getClientRects().length>0&&document.getElementById('subjectShow_btn').getClientRects().length>0&&
            !document.documentElement.classList.contains('univer-app-discussion-page')"""))
        assertEquals(helpHome, eval("document.getElementById('help').parentElement.className"))
        eval("$('#101').click();$('#101').trigger('dblclick')")
        assertEquals("The site's own handlers are untouched", "\"103>/student/discussion/acpos/list/103/2026/1/,101>/student/discussion/acpos/list/101/2026/1/\"", eval("window.opened.join(',')"))
    }

    private fun plans(vararg ids: Int) = shell(discussionRows(ids.joinToString("") { """<tr class="link" id="$it"><td><img></td><td>6B06301-Системы (0)</td><td>${day(-60)}</td><td>${day(40)}</td></tr>""" },
        "<th>&nbsp;</th><th>Специальность</th><th>Дата начала (дд/мм/гггг)</th><th>Дата окончания (дд/мм/гггг)</th>"))

    @Test fun planPageWithASinglePlanOpensItsWeeksAndSeveralPlansStay() {
        load(plans(500), "https://univer.kaznu.kz/student/discussion/acpos/list/102/2026/1/")
        mobile()
        navigatedTo("/student/discussion/acpos/week/list/500/102/2026/1/")
        load(plans(500, 501), "https://univer.kaznu.kz/student/discussion/acpos/list/102/2026/1/")
        navigations.clear()
        mobile()
        waitFor("Plan cards", "document.querySelectorAll('.univer-app-dist-card').length===2")
        Thread.sleep(500)
        assertTrue("A choice of plans is left to the student: $navigations", navigations.isEmpty())
    }

    @Test fun distanceWeeksListTheCourseTasksFirstAndFoldEmptyWeeks() {
        val week = { n: Int, from: Int, resources: Int, tasks: Int ->
            """<tr class="link" id="$n"><td><img></td><td>$n</td><td>${day(from)} 00:00-${day(from + 6)} 00:00</td><td>$resources</td><td>$tasks</td></tr>""" }
        pages["/student/discussion/task/list/500/102/2026/1/3"] = "<html><body>" + shell(discussionRows("""<tr class="link" id="9001"><td><img src="/content/icons/file.png"></td><td> Эссе по истории (0). Автор: Test Teacher</td><td>${day(-2)}</td><td>${day(3)}</td><td>${day(-2)}</td><td>42</td></tr>
            <tr class="link" id="9000"><td><img src="/content/icons/file.png"></td><td> Конспект (0). Автор: Test Teacher</td><td>${day(-20)}</td><td>${day(-10)}</td><td>${day(-20)}</td><td>40</td></tr>""",
            "<th>&nbsp;</th><th>Название</th><th>Дата начала (дд/мм/гггг)</th><th>Дата окончания (дд/мм/гггг)</th><th>Дата созд.</th><th>Участников</th>")) + "</body></html>"
        load(shell("""<table class="mt"><tr class="mid"><td class="lt"></td><td class="tt">
            <table class="inner"><tr><th><img></th><th>Неделя</th><th>Период</th><th>Количество ресурсов</th><th>Количество заданий</th><th>&nbsp;</th></tr></table>
            <div class="inner scrool"><table class="inner" id="weeks">${week(1, -23, 0, 0)}${week(2, -16, 0, 0)}${week(3, -2, 0, 2)}${week(4, 5, 0, 0)}</table></div></td><td class="rt"></td></tr></table>"""),
            "https://univer.kaznu.kz/student/discussion/acpos/week/list/500/102/2026/1/")
        mobile()
        waitFor("Course tasks", "document.querySelectorAll('.univer-app-dist-week-tasks .univer-app-dist-due-item').length===2")
        assertEquals("Open tasks first, each opening its own page; the ended one is marked", "\"Задания предмета · 2|Эссе по истории>/student/discussion/task/preview/9001/500/102/2026/1/3/0/0/0/:open|Конспект>/student/discussion/task/preview/9000/500/102/2026/1/3/0/0/0/:closed\"",
            eval("""(function(){var s=document.querySelector('.univer-app-dist-week-tasks');return s.querySelector('h2').textContent+'|'+Array.from(s.querySelectorAll('a')).map(function(a){
                return a.querySelector('.univer-app-dist-due-name').textContent+'>'+new URL(a.href).pathname+':'+(a.dataset.closed?'closed':'open');}).join('|');})()"""))
        assertEquals("The section leads the week list", "true", eval("document.querySelector('.univer-app-dist-week-tasks').nextElementSibling.classList.contains('univer-app-dist-list')"))
        assertEquals("Empty weeks other than the current one wait behind a button", "\"3|Показать все недели (4)\"", eval("""Array.from(document.querySelectorAll('.univer-app-dist-card')).filter(c=>c.getClientRects().length).map(c=>c.querySelector('.univer-app-dist-lead').textContent).join(',')+'|'+
            document.querySelector('button.univer-app-dist-more').textContent"""))
        eval("document.querySelector('button.univer-app-dist-more').click()")
        waitFor("All weeks shown", "Array.from(document.querySelectorAll('.univer-app-dist-card')).filter(c=>c.getClientRects().length).length===4")
        assertEquals("\"Скрыть пустые недели|true\"", eval("document.querySelector('button.univer-app-dist-more').textContent+'|'+document.querySelector('button.univer-app-dist-more').getAttribute('aria-expanded')"))
        mobile(false)
        assertEquals("Original removes the section and the button", "true", eval("""document.querySelectorAll('.univer-app-dist-week-tasks,.univer-app-dist-more,.univer-app-dist-list').length===0&&
            document.getElementById('weeks').getClientRects().length>0"""))
    }

    // Markup of the task page as observed (October 2026); names and text are synthetic.
    @Test fun taskPageShowsOneCardAndAStyledAnswerFormWithoutSubmitting() {
        val description = "План семинарского занятия: " + (1..20).joinToString(" ") { "$it. Тема для обсуждения номер $it." }
        load(shell("""<table class="mt"><tr class="top"><td class="lt"></td><td class="ct"><a href="/student/discussion/acpos/week/list/500/102/2026/1/">Недели</a>/ <a href="/student/discussion/acpos/list/102/2026/1/">Список календарей</a> / <a href="/student/discussion/task/list/500/102/2026/1/3/">Задания</a> / Эссе по истории (История Казахстана).</td><td class="rt"></td></tr>
            <tr class="mid"><td class="lt"></td><td class="ct"><img src="/content/icons/info.png"><a id="help_btn" name="none" href="#">Информация: <strong>Интернет-консультант</strong></a><div id="help_div" style="display:none;">Справка</div></td><td class="rt"></td></tr>
            <tr class="mid"><td class="lt"></td><td class="ct"><table class="xp"><tr><td><li>Предмет:</li></td><td>История Казахстана</td><td><li>Текущая неделя:</li></td><td>3</td></tr></table></td><td class="rt"></td></tr>
            <tr class="mid" id="Tr1"><td class="lt"></td><td class="tt"><div class="inner"><table class="inner" id="task"><tr id="Tr2"><th>Информация</th><th>Сообщения</th></tr>
                <tr class="message"><td valign="top"><div class="inf"><span class="btn"><a class="btn ans" href="#mes" name="user_1"><img src="/content/icons/reply.png"></a></span>
                    <span class="title">Название задания:</span>Эссе по истории<br /> <span class="capt" title="(teacher)">Автор:</span> Test Teacher<br />
                    <span class="duration">Продолжительность:</span><br /> ${day(-1)} 00:00-${day(3)} 23:00 <span class="title">Максимальное кол-во ответов:</span><span style="color:Red;font-weight:bold;">2</span><br />
                    <span class="title">Максимальный балл:</span>10<br /></div></td>
                <td valign="top"><span class="header">Формат ответа:</span> Ответ в виде файла <br /><span class="header">Описание:</span> $description</td></tr></table></div></td><td class="rt"></td></tr>
            <tr class="mid" id="group_list"><td class="lt"></td><td class="tt"><div class="inner"><table class="inner"><tr id="false"><th>Информация</th><th>Сообщения</th></tr></table></div></td><td class="rt"></td></tr>
            <tr class="brk"><td class="lt"></td><td class="ct">Написать сообщение</td><td class="rt"></td></tr>
            <tr class="mid"><td class="lt"></td><td class="ct"><form id="send_message" action="/student/discussion/task/send/9001/500/102/2026/1/3/" method="post" enctype="multipart/form-data" onsubmit="window.submitted=true;return false;">
                <table style="width:100%;"><tr><td colspan="2"><label for="user_to">Кому:</label> <select id="user_to" name="user_to" style="width:430px;"><option value="1">Test Teacher(teacher)</option></select></td></tr>
                <tr><td style="vertical-align:top;width:50%;"><label for="file">Прикрепить файлы</label> &nbsp; <a href="#" id="fileS">Добавить</a><br /><div id="files_div"></div></td>
                <td style="vertical-align:bottom;width:50%;text-align:right;"><label id="time" name="0">0</label> <input id="sb_btn" type="button" value="Отправить сообщение" onclick="window.submitted=true" /></td></tr></table></form></td><td class="rt"></td></tr></table>"""),
            "https://univer.kaznu.kz/student/discussion/task/preview/9001/500/102/2026/1/3/0/0/0/")
        val formBefore = eval("document.getElementById('send_message').outerHTML")
        mobile()
        waitFor("Task card", "document.querySelector('section.univer-app-task')")
        // Three days and some hours remain, so the count reads 3 or 4 depending on the time of the run.
        assertEquals("Course, title, author, deadline and the facts as chips", "\"История Казахстана|Эссе по истории|Test Teacher|open|true|Ответ в виде файла,Попыток: 2,Макс. балл: 10\"",
            eval("""(function(){var b=document.querySelector('section.univer-app-task'),d=b.querySelector('.univer-app-task-deadline');return [b.querySelector('.univer-app-task-course').textContent,
                b.querySelector('h2').textContent,b.querySelector('.univer-app-task-author').textContent,d.dataset.state,/^Осталось [34] дня$/.test(d.querySelector('span').textContent),
                Array.from(b.querySelectorAll('.univer-app-task-chip')).map(c=>c.textContent).join(',')].join('|');})()"""))
        assertEquals("The long description starts folded and opens on tap", "\"true|false\"", eval("""(function(){var p=document.querySelector('.univer-app-task-description'),f=p.dataset.folded;
            document.querySelector('.univer-app-task-more').click();return f+'|'+p.dataset.folded;})()"""))
        assertEquals("The source table, help line and empty history are hidden; the form is restyled with a hint and fits the phone", "true", eval("""document.getElementById('task').getClientRects().length===0&&
            document.getElementById('help_btn').getClientRects().length===0&&document.getElementById('group_list').getClientRects().length===0&&
            document.getElementById('send_message').classList.contains('univer-app-task-form')&&document.getElementById('files_div').nextElementSibling.classList.contains('univer-app-task-hint')&&
            document.getElementById('sb_btn').getClientRects().length>0&&document.documentElement.scrollWidth<=innerWidth+1"""))
        mobile(false)
        assertEquals("Original restores the page and nothing was sent", "true", eval("""document.querySelectorAll('section.univer-app-task,.univer-app-task-hint,.univer-app-task-form,.univer-app-task-head').length===0&&
            document.getElementById('task').getClientRects().length>0&&!window.submitted"""))
        assertEquals(formBefore, eval("document.getElementById('send_message').outerHTML"))
    }

    private fun sessionStorageClear() = eval("sessionStorage.clear()")

    private fun lesson(id: Int, type: String, sum: Int) =
        """<tr class="mid"><td class="ts"><a class="lt" href="/student/attendance/show/$id/2026/1">$type - Teacher $id</a></td></tr>
           <tr><td><table class="inner"><tr><th rowspan="2">РК1</th><th>01.09</th><th>Сумма</th></tr><tr><td>$sum</td><td>$sum</td></tr></table></td></tr>"""

    @Test fun classLogOpensOnTheCourseNamedByTheAttestationPage() {
        load(shell("""<table class="mt">
            <tr class="top"><td class="ct">Социология ([РК1 + РК2 + Экз] (100)) 01.09.2026 - 12.12.2026</td></tr>${lesson(1, "лекция", 5)}
            <tr class="top"><td class="ct">Психология ([РК1 + РК2 + Экз] (100)) 01.09.2026 - 12.12.2026</td></tr>${lesson(3, "лекция", 3)}</table>"""),
            "https://univer.kaznu.kz/student/attendance/full/")
        eval("sessionStorage.setItem('univer.mobile.attendance-course.v1:/student/attendance/full/','name:психология')")
        mobile()
        waitFor("Course chips", "document.querySelector('.univer-app-attendance-courses button[aria-pressed=true]')")
        assertEquals("\"Психология\"", eval("document.querySelector('.univer-app-attendance-courses button[aria-pressed=true]').textContent"))
        assertEquals("The name becomes the journal's own course token", "true",
            eval("sessionStorage.getItem('univer.mobile.attendance-course.v1:/student/attendance/full/').startsWith('психология (')"))
        // An unknown name falls back to every course instead of an empty page.
        eval("sessionStorage.setItem('univer.mobile.attendance-course.v1:/student/attendance/full/','name:химия')")
        mobile(false); eval("delete window.__univerAttendanceV1"); mobile()
        waitFor("Course chips again", "document.querySelector('.univer-app-attendance-courses button[aria-pressed=true]')")
        assertEquals("\"all\"", eval("document.querySelector('.univer-app-attendance-courses button[aria-pressed=true]').dataset.course"))
    }
}
