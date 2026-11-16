package com.univerflow.kaznu

import android.annotation.SuppressLint
import android.graphics.Bitmap
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
import com.univerflow.kaznu.domain.HomeShortcuts
import com.univerflow.kaznu.domain.PinnedLink
import com.univerflow.kaznu.domain.ShortcutRequest
import com.univerflow.kaznu.web.MobilePageAdapter
import com.univerflow.kaznu.web.ChannelGuard
import com.univerflow.kaznu.web.DownloadBatch
import com.univerflow.kaznu.web.ShortcutBridge
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The Windows features ported to Android, on synthetic pages built from structures observed on Univer.
 * Every request, including the summaries' GET reads, is answered locally; nothing reaches the site.
 */
@RunWith(AndroidJUnit4::class)
class WindowsParityQaTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var web: WebView
    private var channel: ChannelGuard? = null
    private val pins = HomeShortcuts(listOf(PinnedLink("Сообщения", "https://univer.kaznu.kz/student/messages/"),
        PinnedLink("Youtube", "https://www.youtube.com/@kaznu")))

    private fun eval(js: String): String? {
        val latch = CountDownLatch(1); var value: String? = null
        instrumentation.runOnMainSync { web.evaluateJavascript(js) { value = it; latch.countDown() } }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        return value
    }
    private fun string(js: String) = JSONTokener(eval(js)).nextValue() as? String
    private fun capture(name: String) {
        Thread.sleep(700)
        val image = instrumentation.uiAutomation.takeScreenshot()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "qa").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    private val menu = """<div id="menu-home"><table id="tools"><tr><td class="center">""" +
        listOf("Учёба" to listOf("/student/myschedule/" to "Расписание", "/student/attendance/full/" to "Журнал посещений и успеваемости",
            "/student/transcript/" to "Транскрипт", "/student/umkd/" to "УМКД", "/student/messages/" to "Сообщения"),
            "Сервисы" to listOf("/my/docs/" to "Документы", "/my/faq/" to "Часто задаваемые вопросы", "/my/calculatorgpa/" to "Калькулятор GPA"))
            .joinToString("") { (title, links) ->
                """<table class="tool"><tr><td><ul>""" + links.joinToString("") { "<li><a href=\"${it.first}\">${it.second}</a></li>" } +
                    """</ul></td></tr><tr><td class="bottom-center">$title</td></tr></table>"""
            } + "</td></tr></table></div>"
    private val useful = """<div class="links"><a href="/user/loginMoodle">СДО Moodle</a><a href="https://www.youtube.com/@kaznu">Youtube</a>
        <a href="https://www.facebook.com/kaznu">Facebook</a><a href="https://apps.apple.com/kz/app/univer">App Store</a>
        <a href="https://play.google.com/store/apps/details?id=kz.univer">Google Play</a><a href="https://lib.kaznu.kz/">Библиотека</a>
        <a href="https://open.kaznu.kz/">Open KazNU</a><a href="https://kaizen.kaznu.kz/">Кайдзен</a></div>"""

    private fun page(content: String, active: String) = """<html><head><meta name="viewport" content="width=1000"></head><body>
        <table class="wrappertable"><tr id="l1"><td class="header">Казахский национальный университет имени аль-Фараби
        <a href="/lang/change/kz/"><img src="/content/icons/kz.png"></a><img src="/content/icons/ru.png"><a href="/lang/change/en/"><img src="/content/icons/en.png"></a></td></tr>
        <tr id="l2"><td><table class="inner"><tr><td class="center"><div id="logo">Univer 2.0</div><div class="links"><a href="/user/pass/">Тестовый студент</a><br><a href="/user/logout">Выход</a></div></td></tr></table></td></tr>
        <tr id="l3"><td><table class="inner"><tr><td class="left"></td><td class="center">
        <a ${if (active == "home") "class=\"active\"" else ""} href="/news/student"><div class="left"></div><div class="center">Главная</div><div class="right"></div></a>
        <a ${if (active == "bachelor") "class=\"active\"" else ""} href="/student/bachelor/"><div class="left"></div><div class="center">Бакалавр</div><div class="right"></div></a>
        <a ${if (active == "ssc") "class=\"active\"" else ""} href="/ssc/"><div class="left"></div><div class="center">E-услуги</div><div class="right"></div></a></td><td class="right"></td></tr></table></td></tr>
        <tr id="l4"><td class="center">$menu$content</td></tr>
        <tr id="l5"><td class="center">$useful<table id="copyright"><tr><td>© КазНУ имени аль-Фараби</td></tr></table></td></tr></table></body></html>"""

    private val schedule = """<table class="mt"><tr class="top"><td class="ct"><a href="/student/myschedule/2026/1">Расписание</a></td></tr><tr><td class="ts">
        <select id="weekSelect"><option selected>Неделя 07. 12.10.2026 - 18.10.2026</option><option value="/student/myschedule/2026/1/8">Неделя 08. 19.10.2026 - 25.10.2026</option></select></td></tr>
        <tr><td><div class="scrool"><table class="schedule"><tr><th></th><th>Пн</th><th>Вт</th><th>Ср</th><th>Чт</th><th>Пт</th><th>Сб</th></tr>
        <tr><td class="time">09:00 09:50</td>""" + "<td rowspan=\"2\"><div class=\"groups\"><div><p>Алгоритмы и структуры данных</p><p class=\"params\"><span>ауд. 305</span><span>лекция</span></p><p>Тестовый преподаватель</p></div></div></td>".repeat(6) +
        """</tr><tr><td class="time">10:00 10:50</td></tr><tr><td class="time">11:00 11:50</td>""" + "<td><div class=\"groups\"><div><p>Базы данных</p><p class=\"params\"><span>ауд. 112</span><span>семинар</span></p></div></div></td>".repeat(6) +
        """</tr><tr><td class="time">18:00 18:50</td>""" + "<td><div class=\"groups\"><div><p>Английский язык</p><p class=\"params\"><span>ауд. 410</span><span>практика</span></p></div></div></td>".repeat(6) + "</tr></table></div></td></tr></table>"
    private val grades = """<table class="mt"><tr class="top"><td class="ct">Журнал посещений и успеваемости</td></tr>
        <tr><td class="ct">Алгоритмы и структуры данных ([1]) 01.09.2026 - 20.12.2026</td></tr><tr><td class="ct"><b>РК1 (100): 92 РК2 (100): 85 Экзамен (100): </b></td></tr>
        <tr><td class="ct">Базы данных 01.09.2026 - 20.12.2026</td></tr><tr><td class="ct"><b>РК1 (100): 78 РК2 (100): 64 Экзамен (100): </b></td></tr>
        <tr><td class="ct">Английский язык 01.09.2026 - 20.12.2026</td></tr><tr><td class="ct"><b>РК1 (100): 95 РК2 (100): Экзамен (100): </b></td></tr></table>"""
    private val requests = """<table class="mt"><tr><td><table><tr><th>#</th><th>Факультет</th><th>Услуга</th><th>Дата</th><th>Статус</th></tr>
        <tr id="501"><td>1</td><td>ФИТ</td><td>Справка об обучении</td><td>08.10.2026 17:59:10</td><td>Завершена</td></tr>
        <tr id="502"><td>2</td><td>ФИТ</td><td>Справка для военкомата</td><td>12.10.2026 10:37:41</td><td>В обработке</td></tr>
        <tr id="503"><td>3</td><td>ФИТ</td><td>Транскрипт на английском</td><td>13.10.2026 09:02:11</td><td>Отклонена</td></tr></table></td></tr></table>"""
    private val desk = """<div class="ts"><a href="/ssc/UserServiceList">Услуги</a><a href="/ssc/StatementList">Заявки е-услуг</a></div>
        <ul><li>Логин и пароль для системы print.kaznu.kz и WiFi-сети КазНУ.:<br>Логин: fixture_user<br>Пароль: fixture-pass1</li></ul>"""
    /** The requests list as the site serves it: a panel with a toolbar and a sortable table whose last column is often blank. */
    private val liveRequests = """<table class="mt"><tr class="top"><td class="lt"></td><td class="ct">Список заявок 1- 40 из 3</td><td class="rt"></td></tr>
        <tr class="mid"><td class="lt"></td><td class="ts"><a class="lt" href="#" id="openButton"><img alt=" [+] " src="/content/icons/door-in-icon.png">Открыть</a></td><td class="rt"></td></tr>
        <tr class="mid"><td class="lt"></td><td class="ct"><table class="inner table_sort"><tr><th></th><th>#</th><th>Услуга</th><th>Дата</th><th>Статус</th><th>Уведомления</th></tr>
        <tr class="link" id="501"><td><input type="checkbox"></td><td>1</td><td>Справка об обучении</td><td>08.10.2026 17:59:10</td><td>Завершена</td><td>
            </td></tr>
        <tr class="link" id="502"><td><input type="checkbox"></td><td>2</td><td>Справка для военкомата</td><td>12.10.2026 10:37:41</td><td>В обработке</td><td> </td></tr>
        <tr class="link" id="503"><td><input type="checkbox"></td><td>3</td><td>Транскрипт на английском</td><td>13.10.2026 09:02:11</td><td>Отклонена</td><td> </td></tr></table></td><td class="rt"></td></tr></table>"""
    /** The desk as the site serves it: no buttons of its own, one panel with the credentials. */
    private val liveDesk = """<table class="mt"><tr class="top"><td class="lt"></td><td class="ct">Услуги</td><td class="rt"></td></tr><tr class="mid"><td class="lt"></td><td class="ct">Здравствуйте, Тестовый студент. Вы находитесь на главной странице вкладки "Е-услуги"
        <ul><li>Логин и пароль для системы print.kaznu.kz и WiFi-сети КазНУ.:<br>Логин: fixture_user<br>Пароль: fixture-pass1</li></ul></td><td class="rt"></td></tr></table>"""
    private val bachelor = """<table class="mt"><tr class="top"><td class="lt"></td><td class="ct message"><ul><li>Переводной GPA = 2.85</li><li>Общий GPA = 3.12</li></ul></td><td class="rt"></td></tr></table>
        <table class="mt"><tr class="top"><td class="lt"></td><td class="ct">Личные данные</td><td class="rt"></td></tr><tr><td class="lt"></td><td class="ct">Здравствуйте <b>Тестовый студент</b>. Вы находитесь на главной странице вкладки "Бакалавр"<br>Факультет информационных технологий, 2 курс</td><td class="rt"></td></tr>
        <tr><td class="lt"></td><td class="ct">Информационный бюллетень</td><td class="rt"></td></tr><tr><td class="lt"></td><td class="ct"><ol><li>Регистрация на летний семестр открыта до 20 октября.</li></ol></td><td class="rt"></td></tr></table>"""
    private val news = """<table class="mt"><tr class="top"><td class="ct">Новости</td></tr><tr><td class="tt"><table class="inner"><tr><th>Дата</th><th>Новость</th></tr>
        <tr class="news"><td><div class="date">14.10.2026</div><div>Важно</div></td><td><p><b>Опубликовано расписание экзаменов</b></p><p>Проверьте время и аудиторию в разделе расписания. Это синтетический пример новости для проверки интерфейса.</p></td></tr></table></td></tr></table>"""

    /** Fails when anything outside the intended horizontal scrollers is wider than the phone. */
    private fun overflow(tag: String) {
        val report = string("(function(){var w=document.documentElement.clientWidth,out=[];document.querySelectorAll('#l4 *,#l3 *').forEach(function(n){" +
            "var r=n.getBoundingClientRect();if(r.right>w+1&&r.width>0&&!n.closest('.univer-app-table-scroll,.univer-app-days,.univer-app-schedule-scroll,.univer-app-attendance-course-list,#l3 td.center,[hidden]'))" +
            "out.push(n.tagName+'.'+String(n.className).slice(0,50)+' '+Math.round(r.left)+'..'+Math.round(r.right));});return out.slice(0,8).join(' | ');})()").orEmpty()
        assertTrue("$tag overflows the phone width: $report", report.isEmpty())
    }

    /** A real (trusted) tap at the centre of the first element matching [selector]. */
    private fun point(selector: String): FloatArray {
        val rect = string("(function(){var r=document.querySelector(${org.json.JSONObject.quote(selector)}).getBoundingClientRect();" +
            "return [r.left+r.width/2,r.top+r.height/2,innerWidth].join(',');})()")!!.split(',').map { it.toFloat() }
        val location = IntArray(2); var scale = 1f
        instrumentation.runOnMainSync { web.getLocationOnScreen(location); scale = web.width / rect[2] }
        return floatArrayOf(location[0] + rect[0] * scale, location[1] + rect[1] * scale)
    }

    /** A real finger: hold on one element, optionally slide to another, then lift. */
    private fun hold(selector: String, to: String? = null) {
        val a = point(selector); val b = to?.let(::point)
        val down = android.os.SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(android.view.MotionEvent.obtain(down, down, android.view.MotionEvent.ACTION_DOWN, a[0], a[1], 0))
        Thread.sleep(650)
        if (b != null) for (step in 1..10) {
            val x = a[0] + (b[0] - a[0]) * step / 10; val y = a[1] + (b[1] - a[1]) * step / 10
            instrumentation.sendPointerSync(android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), android.view.MotionEvent.ACTION_MOVE, x, y, 0))
            Thread.sleep(30)
        }
        val end = b ?: a
        instrumentation.sendPointerSync(android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), android.view.MotionEvent.ACTION_UP, end[0], end[1], 0))
        Thread.sleep(500)
    }

    private fun tap(selector: String) {
        val rect = string("(function(){var r=document.querySelector(${org.json.JSONObject.quote(selector)}).getBoundingClientRect();" +
            "return [r.left+r.width/2,r.top+r.height/2,innerWidth].join(',');})()")!!.split(',').map { it.toFloat() }
        val location = IntArray(2); var scale = 1f
        instrumentation.runOnMainSync { web.getLocationOnScreen(location); scale = web.width / rect[2] }
        val x = location[0] + rect[0] * scale; val y = location[1] + rect[1] * scale
        val time = android.os.SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(android.view.MotionEvent.obtain(time, time, android.view.MotionEvent.ACTION_DOWN, x, y, 0))
        instrumentation.sendPointerSync(android.view.MotionEvent.obtain(time, time + 60, android.view.MotionEvent.ACTION_UP, x, y, 0))
        Thread.sleep(400)
    }

    private fun load(content: String, path: String, active: String) {
        val latch = CountDownLatch(1)
        val pages = mapOf("/student/myschedule/" to schedule, "/student/attendance/full/" to grades, "/ssc/StatementList" to requests, "/ssc/" to desk)
        instrumentation.runOnMainSync {
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) { latch.countDown() }
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val path = request.url.path.orEmpty()
                    pages[path]?.let { return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(page(it, "bachelor").toByteArray())) }
                    val shapes = when (request.url.lastPathSegment) {
                        "kz.png" -> "<rect width='24' height='16' fill='#00a9ce'/><circle cx='12' cy='8' r='4' fill='#ffd046'/>"
                        "ru.png" -> "<rect width='24' height='16' fill='white'/><path d='M0 8h24' stroke='#2456a4' stroke-width='5'/>"
                        else -> "<rect width='24' height='16' fill='white'/><rect width='10' height='8' fill='#2456a4'/>"
                    }
                    return WebResourceResponse("image/svg+xml", "UTF-8", ByteArrayInputStream("<svg xmlns='http://www.w3.org/2000/svg' width='24' height='16'>$shapes</svg>".toByteArray()))
                }
            }
            web.loadDataWithBaseURL("https://univer.kaznu.kz$path", page(content, active), "text/html", "UTF-8", null)
        }
        assertTrue(latch.await(15, TimeUnit.SECONDS))
        val script = MobilePageAdapter.SCRIPTS.joinToString("\n") { name -> instrumentation.targetContext.assets.open(name).bufferedReader().use { it.readText() } }
        eval(MobilePageAdapter.withSenders(script, channel?.senders() ?: "null") + "\nwindow.__univerMotion=true;window.__univerShortcutsV1.configure(${ShortcutBridge.script(pins)});" +
            "window.__univerMobileV1.setNativeChrome(true);window.__univerMobileV1.setEnabled(true,'ru');window.__univerStyleV1.apply(true);")
        Thread.sleep(900)
    }

    private fun width(dp: Int) {
        instrumentation.runOnMainSync {
            web.layoutParams = web.layoutParams.apply { width = if (dp == 0) FrameLayout.LayoutParams.MATCH_PARENT else (dp * web.resources.displayMetrics.density).toInt() }
        }
        Thread.sleep(700) // The page lays out again at the new width before it is measured.
    }


    private fun umkdFile(id: Int, name: String, size: String) = "<tr class=\"file\"><td><img src=\"/content/icons/file.png\"></td><td><a class=\"downLoad\" href=\"/student/umkd/get/$id/321/2026/1\">$name</a></td>" +
        "<td>-</td><td>1</td><td>$size</td><td>21.09.2026</td><td><div class=\"stars\"></div></td></tr>"
    private val umkd = """<table class="mt"><tr class="top"><td class="ct"><a href="/student/umkd/0/2026/1">УМКД</a> \ <a href="/student/umkd/321/2026/1">Социология</a></td></tr>
        <tr><td class="tt"><div class="inner"><table class="inner"><tr><th></th><th>Название файла</th><th>Описание</th><th>Язык. гр.</th><th>Размер</th><th>Дата загрузки</th><th>Оцените качество материала</th></tr>
        <tr><th colspan="7" id="lectures">Конспекты лекций/тематический материал для практических занятий.</th></tr>""" +
        umkdFile(9102, "Лекция 1. Введение.pdf", "34,68 Кб") + umkdFile(9103, "Лекция 2. Методы.pdf", "1,2 Мб") + umkdFile(9104, "Видеозапись лекции", "0 б") +
        """<tr><th colspan="7">Задания для СРО</th></tr>""" + umkdFile(9105, "СРО 1.docx", "12 Кб") + "</table></div></td></tr></table>"

    /** «Скачать все» under a section's title on a phone and beside it on a wide screen; a real tap sends that section's files. */
    @SuppressLint("SetJavaScriptEnabled")
    @Test fun umkdSectionDownloadAllOnAPhone() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val batches = LinkedBlockingQueue<DownloadBatch>()
        var bridge: ShortcutBridge? = null
        try {
            scenario.onActivity { activity ->
                web = WebView(activity); web.settings.javaScriptEnabled = true; web.settings.useWideViewPort = true; web.settings.domStorageEnabled = true
                val root = FrameLayout(activity); root.setBackgroundColor(0xFFF1F4F7.toInt()); root.addView(web)
                activity.setContentView(root)
                ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
                    val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
                }
                ViewCompat.requestApplyInsets(root)
                // The page is synthetic: the screenshots need the window that first launch secures for the sign-in form.
                activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                channel = ChannelGuard(web)
                bridge = ShortcutBridge(web, channel!!, onDownloadAll = { batches.add(it) }) { }
            }
            load(umkd, "/student/umkd/321/2026/1", "bachelor")
            assertEquals("Only the section with two or more files has the button", "1", eval("document.querySelectorAll('button.univer-app-fold-all').length"))
            val shape = "(function(){var b=document.querySelector('button.univer-app-fold-all'),h=document.getElementById('lectures'),r=b.getBoundingClientRect(),t=h.getBoundingClientRect();" +
                "b.scrollIntoView({block:'center'});r=b.getBoundingClientRect();t=h.getBoundingClientRect();var row=h.parentNode.getBoundingClientRect(),s=getComputedStyle(h);" +
                "var hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);" +
                "return [Math.round(r.left),Math.round(r.right),Math.round(r.top),Math.round(r.bottom),Math.round(r.height),b.scrollWidth<=b.clientWidth+1,hit===b," +
                "r.left>=0&&r.right<=innerWidth,r.top>=row.top-0.5&&r.bottom<=row.bottom+0.5,Math.round(t.bottom),Math.round(t.right-parseFloat(s.paddingRight)),getComputedStyle(b,'::after').content].join('|');})()"
            for (dp in listOf(0, 320)) {
                width(dp)
                val p = string(shape)!!.split('|')
                assertTrue("height ≥ 40 at $dp: $p", p[4].toInt() >= 40)
                assertTrue("the label is never cut at $dp: $p", p[5] == "true")
                assertTrue("nothing covers the button at $dp: $p", p[6] == "true")
                assertTrue("inside the screen at $dp: $p", p[7] == "true")
                assertTrue("inside the heading's row at $dp: $p", p[8] == "true")
                assertTrue("under the title on a phone at $dp: $p", p[2].toInt() >= p[9].toInt() - 1)
                assertEquals("the label is shown at $dp", "\"Скачать все\"", p[11])
                overflow("umkd-$dp")
                capture("parity-umkd-download-all-${if (dp == 0) 412 else dp}")
            }
            width(0)
            tap("button.univer-app-fold-all")
            val batch = batches.poll(5, TimeUnit.SECONDS)
            assertEquals("Конспекты лекций/тематический материал для практических занятий.", batch?.section)
            assertEquals(listOf("https://univer.kaznu.kz/student/umkd/get/9102/321/2026/1", "https://univer.kaznu.kz/student/umkd/get/9103/321/2026/1"), batch?.urls)
            assertEquals("The tap does not fold the section", "\"true\"", eval("document.getElementById('lectures').getAttribute('aria-expanded')"))
            // A wide screen (tablet, landscape) puts the button beside the title, after the space the title leaves for it.
            width(700)
            val wide = string(shape)!!.split('|')
            assertTrue("beside the title on a wide screen: $wide", wide[0].toInt() >= wide[10].toInt() - 1 && wide[8] == "true" && wide[6] == "true")
        } finally {
            instrumentation.runOnMainSync { bridge?.close(); channel?.close() }
            scenario.close()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Test fun windowsHomeBachelorServicesScheduleAndGradesOnAPhone() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val received = LinkedBlockingQueue<ShortcutRequest>()
        var bridge: ShortcutBridge? = null
        try {
            scenario.onActivity { activity ->
                web = WebView(activity); web.settings.javaScriptEnabled = true; web.settings.useWideViewPort = true; web.settings.domStorageEnabled = true
                val root = FrameLayout(activity); root.setBackgroundColor(0xFFF1F4F7.toInt()); root.addView(web)
                activity.setContentView(root)
                ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
                    val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
                }
                ViewCompat.requestApplyInsets(root)
                channel = ChannelGuard(web)
                bridge = ShortcutBridge(web, channel!!) { received.add(it) }
            }
            load(news, "/news/student", "home")
            // Fixed tiles (4 study routes, Moodle, Е-услуги) and the two pins, in order, with tab icons.
            assertEquals("8", eval("document.querySelectorAll('.univer-app-quick>.univer-app-tile').length"))
            assertEquals("3", eval("document.querySelectorAll('#l3 a.univer-app-tab-icon').length"))
            assertEquals("true", eval("!!document.getElementById('univer-style')&&getComputedStyle(document.body).fontFamily.indexOf('Roboto')>=0"))
            overflow("home"); capture("parity-home-412")
            width(320); overflow("home-320"); capture("parity-home-320")
            // The grid reacts to its new width: a name that no longer fits beside its icon goes under it, with every other name.
            Thread.sleep(300)
            assertEquals("A word on a tile is never split", "true", eval("var q=document.querySelector('.univer-app-quick');q.classList.contains('univer-app-quick-stacked')&&" +
                "Array.from(q.querySelectorAll('.univer-app-tile>a')).every(a=>a.scrollWidth<=a.clientWidth)"))
            width(0)
            Thread.sleep(300)
            assertEquals("At full width the names stand beside the icons again", "false", eval("document.querySelector('.univer-app-quick').classList.contains('univer-app-quick-stacked')"))
            // A site script posting to the channel itself has no key: the bridge drops it (review L2, 16 Nov 2026).
            eval("window.__univerAppEvents&&window.__univerAppEvents.postMessage('univer-pin:'+JSON.stringify({order:['https://univer.kaznu.kz/student/messages/']}))")
            // Without document-start scripts the unkeyed channel stays by design.
            if (channel!!.active) assertNull("A forged order is dropped", received.poll(2, TimeUnit.SECONDS))
            // WCAG 2.5.7: holding a tile without moving offers "earlier/later" buttons; no dragging is needed.
            received.clear()
            hold(".univer-app-quick>.univer-app-tile>a")
            assertEquals("true", eval("document.querySelector('.univer-app-quick>.univer-app-tile').classList.contains('univer-app-moving')"))
            assertEquals("true", string("document.querySelector('.univer-app-move-earlier').getAttribute('aria-disabled')"))
            capture("parity-home-move-412")
            // The three edit buttons (earlier, later, remove) must end before the tile's label starts.
            assertEquals("The edit buttons never cover the label", "true", string(
                "String(document.querySelector('.univer-app-moving .univer-app-move-remove').getBoundingClientRect().right <= document.querySelector('.univer-app-moving .univer-app-tile-label').getBoundingClientRect().left + 0.5)"))
            tap(".univer-app-moving .univer-app-move-later")
            val moved = received.poll(5, TimeUnit.SECONDS) as ShortcutRequest.Order
            assertEquals("https://univer.kaznu.kz/student/myschedule/", moved.urls[1])
            assertEquals("Расписание", string("document.querySelectorAll('.univer-app-quick>.univer-app-tile')[1].querySelector('.univer-app-tile-label').textContent"))
            // A tap on the tile in edit mode closes the mode instead of opening the page.
            tap(".univer-app-moving>a")
            assertEquals("0", eval("document.querySelectorAll('.univer-app-moving').length"))
            assertEquals("/news/student", string("location.pathname"))
            // Touch drag: hold the first tile, slide it onto the third and lift; the order reaches the bridge once.
            received.clear()
            val first = string("document.querySelector('.univer-app-quick>.univer-app-tile .univer-app-tile-label').textContent")
            hold(".univer-app-quick>.univer-app-tile:nth-child(1)>a", ".univer-app-quick>.univer-app-tile:nth-child(3)>a")
            assertTrue(received.poll(5, TimeUnit.SECONDS) is ShortcutRequest.Order)
            assertEquals(first, string("document.querySelectorAll('.univer-app-quick>.univer-app-tile')[2].querySelector('.univer-app-tile-label').textContent"))
            assertEquals("Nothing stays lifted", "0", eval("document.querySelectorAll('.univer-app-lifted,.univer-app-drop-target').length"))
            // The bin: dragging a tile onto it removes the tile (unpins it, or hides a built-in one) and the bin goes away.
            received.clear()
            assertEquals("The bin is hidden until a tile is dragged", "0", string("getComputedStyle(document.querySelector('.univer-app-trash')).opacity"))
            val tilesBefore = string("String(document.querySelectorAll('.univer-app-quick>.univer-app-tile').length)")
            hold(".univer-app-quick>.univer-app-tile:nth-child(1)>a", ".univer-app-trash")
            val removal = received.poll(5, TimeUnit.SECONDS) as ShortcutRequest.Pin
            assertEquals(false, removal.pin)
            Thread.sleep(700)
            assertEquals((tilesBefore!!.toInt() - 1).toString(), string("String(document.querySelectorAll('.univer-app-quick>.univer-app-tile').length)"))
            assertEquals("The bin leaves with the finger", "0", eval("document.documentElement.classList.contains('univer-app-tile-drag')?1:0"))
            eval("document.querySelector('.univer-app-menu').open=true")
            overflow("sections"); capture("parity-sections-412")

            load(bachelor, "/student/bachelor/", "bachelor")
            assertEquals("2", eval("document.querySelectorAll('.univer-app-gpa>div').length"))
            // The app bar names the section by its tab, not by the summary panel; the panel is the schedule, its caption the day shown.
            assertEquals("[\"Бакалавр\"]", eval(com.univerflow.kaznu.web.PageScripts.HEADING))
            assertEquals("Расписание", string("document.querySelector('.univer-app-overview-today h2').textContent"))
            assertEquals("Завтра, вторник, 3 ноября", string("window.__univerOverviewV1.dayCaption(new Date(2026,10,3),new Date(2026,10,2,19,0))"))
            assertEquals("Сегодня, понедельник, 2 ноября", string("window.__univerOverviewV1.dayCaption(new Date(2026,10,2),new Date(2026,10,2,9,0))"))
            assertEquals("Среда, 4 ноября", string("window.__univerOverviewV1.dayCaption(new Date(2026,10,4),new Date(2026,10,2,19,0))"))
            Thread.sleep(1500)
            assertTrue(string("document.querySelector('.univer-app-overview-grades')?.textContent||''")!!.contains("Базы данных"))
            // "Show all (N)" hides the extra items with [hidden]; the card layout must not keep them on screen.
            eval("""(function(){var s=document.querySelector('.univer-app-overview');var l=document.createElement('ol');l.className='univer-app-overview-lessons';l.id='probe-hidden';
                for(var i=0;i<2;i++){var li=document.createElement('li');li.className='univer-app-overview-lesson';li.hidden=i==1;l.appendChild(li);}s.appendChild(l);
                var c=document.createElement('ul');c.className='univer-app-overview-courses';c.id='probe-hidden-courses';var ci=document.createElement('li');ci.hidden=true;c.appendChild(ci);s.appendChild(c);})()""")
            assertEquals("none", string("getComputedStyle(document.querySelector('#probe-hidden>li:nth-child(2)')).display"))
            assertEquals("none", string("getComputedStyle(document.querySelector('#probe-hidden-courses>li')).display"))
            assertEquals("grid", string("getComputedStyle(document.querySelector('#probe-hidden>li:nth-child(1)')).display"))
            eval("document.getElementById('probe-hidden').remove();document.getElementById('probe-hidden-courses').remove()")
            assertEquals("The name stays inside the greeting sentence", "inline", string("getComputedStyle(document.querySelector('td.univer-app-panel-body>b')).display"))
            overflow("bachelor"); capture("parity-bachelor-412")
            eval("window.scrollTo(0, document.querySelector('.univer-app-overview-grid').getBoundingClientRect().top + scrollY - 8)")
            capture("parity-bachelor-overview-412")

            load(desk, "/ssc/", "ssc")
            Thread.sleep(1200)
            assertEquals("true", eval("document.querySelector('.univer-app-credential-hidden')!==null"))
            assertTrue(string("document.querySelector('.univer-app-overview-requests-panel')?.textContent||''")!!.contains("Справка для военкомата"))
            overflow("services"); capture("parity-services-412")

            load(liveDesk, "/ssc/", "ssc")
            Thread.sleep(1200)
            assertEquals("The desk entries share the row", "grid", string("getComputedStyle(document.querySelector('.univer-app-desk-cards')).display"))
            assertEquals("The card says what the login opens, the panel keeps its own title", "true", eval("(document.querySelector('.univer-app-credentials>h3')?.textContent||'').startsWith('Логин и пароль для системы print.kaznu.kz')&&" +
                "document.querySelector('td.univer-app-panel-head').textContent.trim()==='Услуги'"))
            assertEquals("A request stands on its panel's inner edge", "true", eval("Math.abs(document.querySelector('.univer-app-overview-request').getBoundingClientRect().left-" +
                "document.querySelector('.univer-app-overview-requests-panel h2').getBoundingClientRect().left)<1"))
            assertEquals("Only a bare title gives up its top padding", "12px", string("getComputedStyle(document.querySelector('td.univer-app-panel-head')).paddingTop"))

            load(liveRequests, "/ssc/StatementList", "ssc")
            Thread.sleep(600)
            overflow("requests")
            assertEquals("A status reads under its label, one word on one line", "true", eval("(function(){var c=document.querySelector('tr.univer-app-card-row>td.univer-app-status');" +
                "return parseFloat(getComputedStyle(c,'::before').width)>30&&c.getBoundingClientRect().height<60})()"))
            assertEquals("A field with nothing in it takes no room", "none", string("getComputedStyle(document.querySelector('tr.univer-app-card-row>td[data-univer-field=Уведомления]')).display"))
            assertEquals("A toolbar stands on the panel's inner edge", "true", eval("Math.abs(document.querySelector('#openButton').getBoundingClientRect().left-document.querySelector('.univer-app-request-table').getBoundingClientRect().left)<1"))
            capture("parity-requests-412")

            load(schedule, "/student/myschedule/", "bachelor")
            assertEquals("true", eval("!!document.querySelector('.univer-app-now')&&!!document.querySelector('.univer-app-schedule-modes')"))
            overflow("schedule"); capture("parity-schedule-412")
            width(320); overflow("schedule-320"); capture("parity-schedule-320"); width(0)

            load(grades, "/student/attendance/full/", "bachelor")
            overflow("grades"); capture("parity-grades-412")
            width(320); overflow("grades-320"); capture("parity-grades-320"); width(0)
        } finally {
            instrumentation.runOnMainSync { bridge?.close(); channel?.close(); if (::web.isInitialized) web.destroyFixture() }
            scenario.close()
        }
    }
}
