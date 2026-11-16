package com.univerflow.kaznu

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.Gravity
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.appbar.MaterialToolbar
import com.univerflow.kaznu.web.MobilePageAdapter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic observed DOM only. Every resource request is intercepted, account data stays untouched. */
@RunWith(AndroidJUnit4::class)
class AuditUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var web: WebView
    private lateinit var column: LinearLayout
    private lateinit var toolbar: MaterialToolbar

    private fun eval(source: String): String {
        val latch = CountDownLatch(1)
        var value = "null"
        instrumentation.runOnMainSync { web.evaluateJavascript(source) { value = it; latch.countDown() } }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        return value
    }

    private fun capture(name: String) {
        Thread.sleep(350)
        val image = instrumentation.uiAutomation.takeScreenshot()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "qa").apply { mkdirs() }
        File(directory, "audit-1.18-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    private fun width(dp: Int, zoom: Int = 100) {
        instrumentation.runOnMainSync {
            column.layoutParams = column.layoutParams.apply { width = (dp * column.resources.displayMetrics.density).toInt() }
            web.settings.textZoom = zoom
        }
        Thread.sleep(300)
    }

    private fun load(title: String, path: String, content: String) {
        val latch = CountDownLatch(1)
        val html = """<html><head><meta charset="utf-8"><style>body{font-family:Arial}table.wrappertable{width:1000px}table.mt,table.inner{width:100%}.ct{font-size:20px}.inner td{border:1px solid #ddd}</style></head><body>
            <table class="wrappertable"><tr id="l1"><td class="header">КазНУ <a href="/lang/change/en/">English</a></td></tr>
            <tr id="l2"><td><div id="logo">Univer 2.0</div></td></tr><tr id="l3"><td class="center"><a href="/news/student" onclick="window.sectionTap='home';return false">Главная</a><a class="active" href="/student/index" onclick="window.sectionTap='bachelor';return false">Бакалавр</a><a href="/student/ssc" onclick="window.sectionTap='services';return false">E-услуги</a></td></tr>
            <tr id="l4"><td class="center"><table id="tools"><tr><td><ul><li><a href="$path">$title</a></li></ul></td></tr></table>
            <table class="mt"><tr class="top"><td class="ct">$title</td></tr><tr><td class="tt">$content</td></tr></table></td></tr></table></body></html>"""
        instrumentation.runOnMainSync {
            toolbar.title = title
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) { latch.countDown() }
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val main = request.isForMainFrame && request.url.toString() == "https://univer.kaznu.kz$path"
                    return WebResourceResponse(if (main) "text/html" else "text/plain", "UTF-8", if (main) 200 else 404,
                        if (main) "OK" else "Not Found", emptyMap(), ByteArrayInputStream(if (main) html.toByteArray() else ByteArray(0)))
                }
            }
            web.loadUrl("https://univer.kaznu.kz$path")
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        val scripts = MobilePageAdapter.SCRIPTS.joinToString("\n") { instrumentation.targetContext.assets.open(it).bufferedReader().use { reader -> reader.readText() } }
        eval(scripts + "\nwindow.__univerMobileV1.setNativeChrome(true);window.__univerMobileV1.setEnabled(true,'ru');")
        Thread.sleep(200)
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Test fun contentComesFirstAt320And412AndOriginalRestoresInteractiveNodes() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                // Only this isolated synthetic screen can be captured; no credentials are shown.
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                val root = FrameLayout(activity)
                column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                root.addView(column, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))
                toolbar = MaterialToolbar(activity).apply { setNavigationIcon(R.drawable.ic_menu); inflateMenu(R.menu.main_toolbar) }
                column.addView(toolbar, LinearLayout.LayoutParams(-1, (56 * activity.resources.displayMetrics.density).toInt()))
                web = WebView(activity).apply { settings.javaScriptEnabled = true; settings.useWideViewPort = true; settings.domStorageEnabled = false }
                column.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
                activity.setContentView(root)
                ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
                    val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()); view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
                }
                ViewCompat.requestApplyInsets(root)
            }
            width(412)
            load("Новости", "/news/student", """<table class="inner"><tr><th>Дата</th><th>Новость</th></tr><tr class="news"><td><div class="date">11.10.2026</div></td><td><p><b>Опубликовано расписание экзаменов</b></p><p>Проверьте время и аудиторию в разделе расписания. Это синтетический пример новости для проверки интерфейса.</p></td></tr></table>""")
            capture("news-initial")
            assertEquals("Fixture DOM: " + eval("JSON.stringify([location.origin,document.querySelectorAll('tr.news').length,!!window.__univerMobileV1,document.readyState])"), "1", eval("document.querySelectorAll('tr.news').length"))
            assertEquals("News is visible above the fold", "true", eval("document.querySelector('tr.news').getBoundingClientRect().top+120<innerHeight"))
            assertEquals("No floating navigation", "0", eval("document.querySelectorAll('.univer-app-menu-return').length"))
            assertEquals("All sections stays visible while closed", "true", eval("var m=document.querySelector('.univer-app-menu');!m.open&&m.getBoundingClientRect().height>=48&&m.querySelector('summary').textContent==='Все разделы'"))
            assertEquals("All three site tabs stay visible", "true", eval("var t=Array.from(document.querySelectorAll('#l3 a'));t.length===3&&t.map(n=>n.textContent).join('|')==='Главная|Бакалавр|E-услуги'&&t.every(n=>n.getBoundingClientRect().height>=48)"))
            eval("window.siteTabs=Array.from(document.querySelectorAll('#l3 a'));document.querySelector('#l3 a:last-child').click();document.querySelector('.univer-app-menu summary').click()")
            assertEquals("Original tab handler and section disclosure work", "true", eval("window.sectionTap==='services'&&document.querySelector('.univer-app-menu').open"))
            eval("document.querySelector('.univer-app-menu summary').click()")
            assertEquals("The period starts closed", "false", eval("document.querySelector('.univer-app-news-period').open"))
            eval("document.querySelector('.univer-app-news-period>summary').click()")
            assertEquals("Its ranges open inside the page", "true", eval("var p=document.querySelector('.univer-app-news-period-box').getBoundingClientRect();p.height>0&&p.left>=0&&p.right<=innerWidth+1"))
            eval("document.querySelector('.univer-app-news-period>summary').click()")
            capture("news-412")
            width(320, 150)
            assertEquals("Large text stays inside the viewport", "true", eval("document.documentElement.scrollWidth<=innerWidth+2"))
            assertEquals("Top tabs and section menu stay visible at large text", "true", eval("document.querySelector('#l3').getBoundingClientRect().height>=48&&document.querySelector('.univer-app-menu').getBoundingClientRect().height>=48"))
            assertEquals("Large tab labels are not split inside words", "true", eval("Array.from(document.querySelectorAll('#l3 a')).every(n=>{var r=document.createRange();r.selectNodeContents(n);return r.getClientRects().length===1&&n.getBoundingClientRect().right<=innerWidth+1})"))
            capture("news-320-large-text")
            eval("window.__univerMobileV1.setEnabled(false,'ru')")
            assertEquals("Site header returns in Original", "true", eval("document.querySelector('#l1').getBoundingClientRect().height>0&&!document.querySelector('.univer-app-news-tools')"))
            assertEquals("Original tabs are restored without replacing nodes", "true", eval("!document.querySelector('.univer-app-menu')&&window.siteTabs.every((n,i)=>n===document.querySelectorAll('#l3 a')[i])"))
            width(320)
            load("Расписание", "/student/myschedule/", """<select id="weekSelect"><option>Осень 2026 · 12.10–18.10</option></select><table class="schedule"><tr><th></th><th>ПН</th><th>ВТ</th><th>СР</th><th>ЧТ</th><th>ПТ</th><th>СБ</th></tr><tr><td>09:00–09:50</td><td><div class="groups"><div title="Период: 01.09.2026 – 12.12.2026"><p>Информационная безопасность</p><p>Тестовый преподаватель</p><p class="params">Аудитория 205</p></div></div></td><td></td><td></td><td></td><td></td><td></td></tr></table>""")
            eval("document.querySelector('.univer-app-days button').click()")
            assertEquals("One scrolling row of days", "true", eval("var b=Array.from(document.querySelectorAll('.univer-app-days button'));b.every(n=>n.offsetTop===b[0].offsetTop)"))
            eval("window.positionBefore=scrollY;document.querySelector('.univer-app-days button:last-child').click()")
            assertEquals("Selected day is visible without moving the page", "true", eval("var t=document.querySelector('.univer-app-days'),b=t.querySelector('[aria-pressed=true]');b.getBoundingClientRect().right<=t.getBoundingClientRect().right+2&&scrollY===window.positionBefore"))
            eval("document.querySelector('.univer-app-days button').click()")
            assertEquals("Compact hides secondary details", "none", eval("getComputedStyle(document.querySelector('.univer-app-lesson-detail')).display").trim('"'))
            capture("schedule-320-compact")
            eval("document.querySelector('.univer-app-schedule-mode input').click()")
            assertEquals("Detailed reveals the same information", "true", eval("getComputedStyle(document.querySelector('.univer-app-lesson-detail')).display!=='none'"))
            assertEquals("Detailed includes the source date range", "true", eval("var p=document.querySelector('.univer-app-lesson-period');p.textContent==='Даты курса: 01.09.2026 – 12.12.2026'&&getComputedStyle(p).display!=='none'"))
            capture("schedule-320-detailed")
            load("Учебные материалы", "/student/umkd/42/2026/1", """<table class="inner"><tr><th>#</th><th>Название</th><th>Описание</th><th>Язык</th><th>Размер</th><th>Дата</th><th>Оценивание</th></tr><tr class="file"><td>1</td><td><a class="downLoad" href="/student/umkd/get/1/42/2026/1">Силлабус по информационной безопасности.pdf</a></td><td><span id="originalDescription">Программа дисциплины и темы занятий</span></td><td>Русский</td><td>631 КБ</td><td>11.10.2026</td><td><button id="rating" onclick="window.rated=(window.rated||0)+1">Оценить материал</button></td></tr></table>""")
            eval("window.originalRating=document.getElementById('rating');window.originalDescription=document.getElementById('originalDescription')")
            assertEquals("Metadata disclosure starts closed", "false", eval("document.querySelector('.univer-app-material-details').open"))
            capture("materials-320")
            eval("document.querySelector('.univer-app-material-details').open=true;document.getElementById('rating').click();window.__univerMobileV1.setEnabled(false,'ru')")
            assertEquals("Rating and original nodes survive restoration", "true", eval("window.rated===1&&window.originalRating===document.querySelector('tr.file').cells[6].firstChild&&window.originalDescription===document.querySelector('tr.file').cells[2].firstChild"))
            fun course(name: String, id: Int) = """<table class="mt"><tr class="top"><td class="ct">$name ([РК1 + РК2 + Экз] (100)) 01.09.2026 - 12.12.2026</td></tr><tr><td class="ts"><a class="lt" href="/student/attendance/show/$id/2026/1">лекция - Тестовый преподаватель</a></td></tr><tr><td><table class="inner" id="matrix$id"><tr><th rowspan="2">РК1</th><th>01.09</th><th>08.09</th><th>Сумма</th></tr><tr><td>5</td><td>8</td><td>13</td></tr></table></td></tr><tr><td class="ct"><b>РК1 (100): 13 · РК2: —</b></td></tr></table>"""
            load("Успеваемость", "/student/attendance/full/2026/1", course("Социология", 1) + course("Психология", 2))
            // Positions are measured with the style layer the app always applies.
            eval("window.__univerStyleV1.apply(true);document.querySelectorAll('.univer-app-attendance-courses button')[1].click()")
            // One chosen course opens its class log under the summary; the toggle still collapses it.
            capture("journal-320-summary")
            assertEquals("The chosen course opens its class log", "true", eval("!document.getElementById('matrix1').closest('[hidden]')"))
            // The course chips and class types stay in view, as on the desktop; the whole summary still fits the first screen.
            assertEquals("The summary fits the first screen under the course controls", "true", eval("document.querySelector('.univer-app-attendance-summary').getBoundingClientRect().bottom<=innerHeight"))
            eval("document.querySelector('.univer-app-attendance-details-toggle[aria-expanded=true]').click()")
            assertEquals("Class log collapses on demand", "true", eval("document.getElementById('matrix1').closest('[hidden]')!==null"))
            eval("document.querySelector('.univer-app-attendance-details-toggle[aria-expanded=false]').click()")
            assertEquals("Original matrix is available on demand", "true", eval("!document.getElementById('matrix1').closest('[hidden]')&&document.querySelector('#matrix1 th').rowSpan===2"))
            capture("journal-320-details")
        } finally {
            if (::web.isInitialized) instrumentation.runOnMainSync { web.destroyFixture() }
            scenario.close()
        }
    }
}
