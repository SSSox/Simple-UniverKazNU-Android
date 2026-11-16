package com.univerflow.kaznu

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.univerflow.kaznu.web.MobilePageAdapter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The page polish module shared with Windows, drawn by a real WebView with every page script: notes, facts, warnings,
 * file cards and arrows, the menu item that holds two links, the dark theme and Original. Synthetic pages only.
 */
@RunWith(AndroidJUnit4::class)
class PagePolishQaTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var web: WebView
    private val scripts by lazy {
        MobilePageAdapter.SCRIPTS.joinToString("\n") { instrumentation.targetContext.assets.open(it).bufferedReader().use { reader -> reader.readText() } }
    }

    private fun eval(script: String): String {
        val done = CountDownLatch(1)
        var result = "null"
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { result = it; done.countDown() } }
        assertTrue(done.await(15, TimeUnit.SECONDS))
        return result
    }
    private fun capture(name: String) {
        Thread.sleep(900)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull(bitmap)
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "qa").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun page(path: String, body: String, dark: Boolean = false) {
        val loaded = CountDownLatch(1)
        val html = "<html><head><meta charset=\"utf-8\"></head><body><table class=\"wrappertable\"><tr id=\"l1\"><td class=\"header\">КазНУ</td></tr>" +
            "<tr id=\"l4\"><td>$body</td></tr></table></body></html>"
        instrumentation.runOnMainSync {
            web.webViewClient = object : WebViewClient() { override fun onPageFinished(view: WebView, url: String?) { loaded.countDown() } }
            web.loadDataWithBaseURL("https://univer.kaznu.kz$path", html, "text/html", "UTF-8", null)
        }
        assertTrue(loaded.await(15, TimeUnit.SECONDS))
        eval(scripts + "\nwindow.__univerMotion=false;window.__univerDark=$dark;window.__univerMobileV1.setNativeChrome(true);" +
            "window.__univerMobileV1.setEnabled(true,'ru');window.__univerStyleV1&&window.__univerStyleV1.apply(true);true")
        Thread.sleep(500)
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Test fun polishedPagesMenuDarkThemeAndOriginal() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                // Only this isolated synthetic screen is captured; no account is shown.
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                web = WebView(activity).apply { settings.javaScriptEnabled = true; settings.useWideViewPort = true; settings.domStorageEnabled = false }
                val root = FrameLayout(activity)
                root.addView(web, FrameLayout.LayoutParams(-1, -1))
                activity.setContentView(root)
                ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
                    val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                    insets
                }
                ViewCompat.requestApplyInsets(root)
            }
            val advisor = "<table class=\"inner\"><tr class=\"top\"><td class=\"ct\">Данные о моем эдвайзере .</td></tr>" +
                "<tr><td class=\"ct\"><img src=\"/img/icons/info.png\"> Уважаемый студент, на этой странице вы можете увидеть информацию о своём эдвайзере.</td></tr>" +
                "<tr><td class=\"ct\"><table class=\"xp\"><tr><td><li>Ф.И.О.:</li></td><td>Синтетический Эдвайзер А. Б.</td></tr>" +
                "<tr><td><li>E-mail:</li></td><td></td></tr><tr><td><li>Рабочий телефон:</li></td><td></td></tr></table></td></tr>" +
                "<tr><td class=\"ct\"><p style=\"color:red\">Проверьте контактные данные эдвайзера до начала семестра.</p></td></tr></table>"
            page("/student/advicer/", advisor)
            val state = eval("(function(){var f=document.querySelector('.up-facts'),n=document.querySelector('.up-note'),a=document.querySelector('.up-alert');" +
                "return [!!f&&f.getBoundingClientRect().height>40,!!n&&getComputedStyle(n).borderRadius,!!a&&getComputedStyle(a).display," +
                "Array.from(document.querySelectorAll('.up-fact-value.up-none')).length,document.querySelector('tr.top>td.ct').textContent.trim()].join('|');})()")
            assertEquals("facts, note, warning, two blank values and the heading without its period", "\"true|12px|block|2|Данные о моем эдвайзере\"", state)
            capture("polish-advisor")

            page("/student/advicer/", advisor, dark = true)
            assertTrue(eval("document.documentElement.classList.contains('univer-app-dark')&&!!document.querySelector('.up-facts')") == "true")
            capture("polish-advisor-dark")

            val files = "<table class=\"inner\"><tr class=\"top\"><td class=\"ct\">Инструкции</td></tr>" +
                "<tr><td class=\"ct\"><img src=\"/img/pdf.png\"><a href=\"/files/a.pdf\">Синтетическая инструкция для студентов.pdf</a><br>" +
                "<img src=\"/img/pdf.png\"><a href=\"/files/b.pdf\">Ещё одна инструкция.pdf</a></td></tr>" +
                "<tr><td class=\"tb\"><a href=\"/student/contract/list/1\"><img src=\"/img/left.png\"></a> 2026–2027 <a href=\"/student/contract/list/3\"><img src=\"/img/right.png\"></a></td></tr></table>"
            page("/student/contract/list/", files)
            val cards = eval("(function(){var f=Array.from(document.querySelectorAll('a.up-file')),p=document.querySelector('a.up-prev');" +
                "return [f.length,f.every(function(a){return a.getBoundingClientRect().height>=40;}),!!p&&p.getAttribute('aria-label'),p&&p.getBoundingClientRect().width>=40].join('|');})()")
            assertEquals("two file cards and touch-sized period arrows", "\"2|true|Предыдущая страница|true\"", cards)
            capture("polish-files")

            // Задолженности, Практика, Пересдача FX: an empty "Информация" panel, a named document behind "Ссылка", a shouting memo.
            val notices = "<table class=\"mt\" id=\"info\"><tr class=\"top\"><td class=\"ct\">Информация</td></tr><tr class=\"mid\"><td class=\"ct\"> </td></tr></table>" +
                "<table class=\"mt\"><tr class=\"top\"><td class=\"ct\">Ссылки</td></tr><tr class=\"mid\"><td class=\"ct\"><div id=\"doc\"><b>Инструкция для студентов</b> <a href=\"/Content/helper/practik/Practika.docx\">Ссылка</a></div></td></tr>" +
                "<tr class=\"mid\"><td class=\"ct\" id=\"memo\"><b><font style=\"color:red\">ВНИМАНИЕ!</font></b><br><b>ПАМЯТКА ПО ПОВТОРНОМУ ИЗУЧЕНИЮ ДИСЦИПЛИН</b><br>1. Заявку подаёт студент.</td></tr></table>"
            page("/student/fail/", notices)
            val noticeState = eval("(function(){var d=document.getElementById('doc'),i=document.getElementById('info');" +
                "return [!!d&&d.classList.contains('up-doc')&&d.getBoundingClientRect().height>=56,!!i&&i.getBoundingClientRect().height===0,document.querySelector('#memo font').textContent].join('|');})()")
            assertEquals("an empty heading panel hides, a named document is a card, a memo stops shouting", "\"true|true|Внимание!\"", noticeState)
            capture("polish-notices")

            val menu = "<table id=\"tools\"><tr><td class=\"center\"><table class=\"tool\"><tr><td class=\"top-center\"><ul>" +
                "<li><a href=\"/student/myschedule/\">Расписание</a> <a href=\"/student/myexam/schedule/\">Расписание экзаменов</a></li>" +
                "<li><a href=\"/student/academcalendar/\">Академический календарь</a></li><li><a href=\"/student/educplan/\">Учебный план</a></li></ul></td></tr>" +
                "<tr><td class=\"bottom-center\">Учебный процесс</td></tr></table></td></tr></table>"
            page("/student/bachelor/", menu)
            eval("document.querySelector('.univer-app-menu').open=true;Array.from(document.querySelectorAll('.univer-app-category')).forEach(function(c){c.open=true;});true")
            Thread.sleep(400)
            val split = eval("(function(){var li=Array.from(document.querySelectorAll('.univer-app-category li')).find(function(n){return n.querySelectorAll(':scope>.univer-app-pin-row').length>1;});" +
                "if(!li)return 'no shared item';var r=li.querySelectorAll(':scope>.univer-app-pin-row'),a=r[0].getBoundingClientRect(),b=r[1].getBoundingClientRect(),s=getComputedStyle(r[0]);" +
                "return [parseFloat(s.borderTopWidth)>0&&s.borderTopStyle,b.top>=a.bottom,a.height>=44&&b.height>=44,getComputedStyle(li).borderTopWidth].join('|');})()")
            // A 1px outline is drawn on whole device pixels, so its computed width depends on the screen density.
            assertEquals("a menu item with two links shows two outlined touch rows", "\"solid|true|true|0px\"", split)
            capture("polish-menu")

            page("/student/advicer/", advisor)
            eval("window.__univerStyleV1&&window.__univerStyleV1.apply(false);window.__univerMobileV1.setEnabled(false,'ru');true")
            Thread.sleep(300)
            assertEquals("Original removes every polish change", "\"0|false|Данные о моем эдвайзере .\"",
                eval("[Array.from(document.querySelectorAll('[class],[data-up-hidden]')).filter(function(n){return n.hasAttribute('data-up-hidden')||Array.from(n.classList).some(function(c){return c.indexOf('up-')===0;});}).length,!!document.getElementById('univer-polish-style'),document.querySelector('tr.top>td.ct').textContent.trim()].join('|')"))
        } finally {
            if (::web.isInitialized) instrumentation.runOnMainSync { web.destroyFixture() }
            scenario.close()
        }
    }
}
