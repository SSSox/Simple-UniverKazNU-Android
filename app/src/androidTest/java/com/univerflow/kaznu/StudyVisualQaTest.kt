package com.univerflow.kaznu

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Only synthetic content is exported; the installed account is never captured. */
@RunWith(AndroidJUnit4::class)
class StudyVisualQaTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var web: WebView
    private fun eval(js: String) {
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync { web.evaluateJavascript(js) { latch.countDown() } }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
    }
    private fun capture(name: String) {
        Thread.sleep(500)
        val image = instrumentation.uiAutomation.takeScreenshot()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "qa").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    private fun load(content: String, path: String) {
        val latch = CountDownLatch(1)
        val html = """<html><head><style>body{font-family:Arial}.wrappertable{width:1000px;background:white}
            #logo{width:260px;height:70px;font:28px/44px Arial}.links{float:right;min-width:400px}.mt{width:100%;table-layout:fixed}.mt td{border:1px solid #ccc}
            </style></head><body><table class="wrappertable"><tr id="l1"><td class="header">Казахский национальный университет имени аль-Фараби
            <a href="/lang/change/kz/"><img src="/content/icons/kz.png" alt="Қаз"></a><img src="/content/icons/ru.png" alt="Рус"><a href="/lang/change/en/"><img src="/content/icons/en.png" alt="Eng"></a></td></tr>
            <tr id="l2"><td><table class="inner"><tr><td class="left"></td><td class="center"><div id="logo">Univer 2.0</div><div class="links"><a href="/user/pass/">Тестовый аккаунт</a><br><a href="/user/logout">Выйти</a></div></td><td class="right"></td></tr></table></td></tr>
            <tr id="l3"><td><table class="inner"><tr><td class="center"><a href="/">Главная</a><a class="active" href="/student/bachelor/">Бакалавр</a><a href="/ssc/">E-услуги</a></td></tr></table></td></tr>
            <tr id="l4"><td class="center"><table class="mt"><tr class="top"><td class="lt"></td><td class="ct">Учебный процесс</td><td class="rt"></td></tr><tr class="mid"><td class="lt"></td><td class="tt">$content</td><td class="rt"></td></tr><tr class="empty"><td colspan="9"></td></tr></table></td></tr></table></body></html>"""
        instrumentation.runOnMainSync {
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) { latch.countDown() }
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val shapes = when (request.url.lastPathSegment) {
                        "kz.png" -> "<rect width='24' height='16' fill='#00a9ce'/><circle cx='12' cy='8' r='4' fill='#ffd046'/>"
                        "ru.png" -> "<rect width='24' height='16' fill='white'/><path d='M0 8h24' stroke='#2456a4' stroke-width='5'/><path d='M0 13h24' stroke='#d84247' stroke-width='5'/>"
                        else -> "<rect width='24' height='16' fill='white'/><path d='M0 3h24M0 8h24M0 13h24' stroke='#d84247' stroke-width='2'/><rect width='10' height='8' fill='#2456a4'/>"
                    }
                    val svg = "<svg xmlns='http://www.w3.org/2000/svg' width='24' height='16'>$shapes</svg>"
                    return WebResourceResponse("image/svg+xml", "UTF-8", ByteArrayInputStream(svg.toByteArray()))
                }
            }
            // All resource requests are intercepted locally; no credentials or site requests.
            web.loadDataWithBaseURL("https://univer.kaznu.kz$path", html, "text/html", "UTF-8", null)
        }
        assertTrue(latch.await(15, TimeUnit.SECONDS))
        val mobile = listOf("univer-dom.js", "univer-lesson.js", "univer-mobile-css.js", "univer-mobile.js").joinToString("\n") { name -> instrumentation.targetContext.assets.open(name).bufferedReader().use { it.readText() } }
        eval(mobile + "\nwindow.__univerMobileV1.setEnabled(true,'ru');")
    }
    @SuppressLint("SetJavaScriptEnabled")
    @Test fun headerScheduleAndMaterialCardsAtPhoneWidths() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                web = WebView(activity); web.settings.javaScriptEnabled = true; web.settings.useWideViewPort = true
                val root = FrameLayout(activity); root.addView(web)
                activity.setContentView(root)
                ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
                    val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
                }
                ViewCompat.requestApplyInsets(root)
            }
            val schedule = """<select id="weekSelect"><option>Неделя 05. 28.09.2026–04.10.2026</option></select><table class="schedule"><tr><th></th><th>ПН</th><th>ВТ</th><th>СР</th><th>ЧТ</th><th>ПТ</th><th>СБ</th></tr>
                <tr><td>09:00-09:50</td><td><div class="groups"><div><p class="teacher">Введение в информационную безопасность (семинар)</p><p class="teacher">Тестовый преподаватель</p><p class="params"><span>ФИТ</span><span>Ауд.: 205</span></p><p>Период с 01.09 по 12.12</p></div></div></td><td></td><td></td><td></td><td></td><td></td></tr></table>"""
            load(schedule, "/student/myschedule/")
            eval("document.querySelector('.univer-app-days button').click()")
            capture("study-header-schedule")
            instrumentation.runOnMainSync { web.layoutParams = web.layoutParams.apply { width = (320 * web.resources.displayMetrics.density).toInt() } }
            capture("study-schedule-320")
            val files = """<table class="inner"><tr><th></th><th>Название файла</th><th>Описание</th><th>Язык. гр.</th><th>Размер</th><th>Дата загрузки</th><th>Оцените качество материала</th></tr><tr><th colspan="7">Силлабус</th></tr>
                <tr class="file"><td></td><td><a class="downLoad" href="/student/umkd/get/42/99/2026/1">Силлабус — введение в информационную безопасность.pdf</a></td><td>Программа дисциплины и темы занятий</td><td>Русский</td><td>631 Кб</td><td>04.10.2026</td><td><div class="stars"><svg class="star rating" width="23" height="25"><polygon points="9.9,1.1 3.3,21.78 19.8,8.58 0,8.58 16.5,21.78" fill="#aaa"/></svg></div></td></tr></table>"""
            load(files, "/student/umkd/99/2026/1")
            capture("study-materials-320")
            load("""<table class="inner"><tr><td>Факультет:</td><td>Информационных технологий</td><td>Курс:</td><td>2</td></tr><tr><td>Специальность:</td><td>Информационная безопасность</td><td>Форма обучения:</td><td>Очная</td></tr></table>
                <table class="inner"><tr><th>Курс</th><th>№ и дата приказа</th><th>Содержание приказа</th></tr><tr><td>2</td><td>№ 123 от 01.09.2026</td><td>Тестовая запись о переводе на следующий курс</td></tr></table>""", "/student/profile/")
            capture("records-profile-320")
            load("""<table class="inner"><tr><th>#</th><th>Услуга</th><th></th></tr><tr><td>1</td><td>Справка об обучении в университете</td><td><a href="#">Открыть</a></td></tr><tr><td>2</td><td>Получение транскрипта</td><td><a href="#">Открыть</a></td></tr></table>""", "/ssc/UserServiceList")
            capture("records-services-320")
            load("""<table class="inner"><tr><th>Дисциплины</th><th>Цикл</th><th>Крд.</th><th>Тип контроля</th><th>Язык. гр.</th></tr><tr><td colspan="5">1 семестр</td></tr><tr><td>Основы информационной безопасности и защиты данных</td><td>БД</td><td>5</td><td>Экзамен</td><td>Русский</td></tr></table>""", "/student/educplan/")
            capture("records-curriculum-320")
            load("""<table width="1900"><tr><td><h1>Транскрипт</h1><p style="font-size:10pt;text-indent:50px">Тестовый документ. Текст переносится в пределах экрана. Таблица оценок прокручивается отдельно от остального документа.</p><table><tr><td>Дисциплина</td><td>Кредиты</td><td>Оценка</td><td>GPA</td><td>Буквенная оценка</td></tr><tr><td>Информационная безопасность</td><td>5</td><td>95</td><td>4.0</td><td>A</td></tr></table></td></tr></table>""", "/student/transcript/2/")
            capture("records-document-320")
            load("""<table class=mt><tr><td class=ct>Инструкции (1 файл)</td></tr><tr><td class=ts></td></tr><tr><td><div class="inner scrool200" style="width:900px;height:200px"><table class=inner><tr class="file link" id=42><td></td><td>1</td><td>Инструкция: как найти расписание занятий и учебные материалы</td><td>06.10.2026</td><td>120 Кб</td><td>4</td></tr></table></div></td></tr></table>""", "/my/docs/")
            eval("document.querySelector('.univer-app-catalog').open=true")
            capture("usability-catalog-320")
            load("""<table><tr><td><table id=gpa-table class=inner><tr><th colspan=2>Дисциплина</th><th colspan=2>Количество кредитов</th><th colspan=2>Оценка</th></tr><tr><td colspan=2><input type=text value='Введение в специальность'></td><td colspan=2><input type=number value=5></td><td colspan=2><select><option>4.0 (A)</option></select></td></tr></table></td><td></td><td><table><tr><th>Оценка</th><th>Баллы</th></tr><tr><td>A</td><td>4.0</td></tr></table></td></tr></table>""", "/my/calculatorgpa/")
            capture("usability-gpa-320")
            load("""<table class=mt><tr><td class=ct>Как найти расписание?</td></tr><tr><td class=tt><table class=inner><tr><th>Ответ:</th></tr><tr><td>Откройте Бакалавр, затем Учебный процесс и Расписание.</td></tr></table></td></tr></table><table class=mt><tr><td class=ct>Где найти учебные материалы?</td></tr><tr><td class=tt><table class=inner><tr><th>Ответ:</th></tr><tr><td>Выберите УМКД в учебных разделах.</td></tr></table></td></tr></table>""", "/my/faq/")
            eval("document.querySelector('.univer-app-faq').open=true")
            capture("usability-faq-320")
        } finally { if (::web.isInitialized) instrumentation.runOnMainSync { web.destroyFixture() }; scenario.close() }
    }
}
