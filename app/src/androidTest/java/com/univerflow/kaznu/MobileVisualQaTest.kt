package com.univerflow.kaznu

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.widget.FrameLayout
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Shareable visual evidence contains only synthetic content, never the configured account. */
@RunWith(AndroidJUnit4::class)
class MobileVisualQaTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var web: WebView
    private fun eval(script: String) {
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { done.countDown() } }
        assertTrue(done.await(10, TimeUnit.SECONDS))
    }
    private fun capture(name: String) {
        Thread.sleep(1_000)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull(bitmap)
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "qa").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @SuppressLint("SetJavaScriptEnabled")
    @Test fun syntheticPhoneMenuDialogsKeyboardAndNoticeScreenshots() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val loaded = CountDownLatch(1)
        val mobile = listOf("univer-dom.js", "univer-lesson.js", "univer-mobile-css.js", "univer-mobile.js").joinToString("\n") { name -> instrumentation.targetContext.assets.open(name).bufferedReader().use { it.readText() } }
        val html = """<html><head><meta name="viewport" content="width=1000"><style>
          body{font-family:sans-serif;background:#6b92be}.wrappertable{width:1000px;background:white}
          .tool{float:left;width:25%;background:#cde3fa}.top-center{height:300px}
          #simplemodal-container{position:fixed;left:900px;top:700px;width:800px;height:500px;z-index:1002}
          .simplemodal-wrap{height:100%;width:100%}#simplemodal-overlay{position:fixed;background:#1237;z-index:1001}
          </style></head><body><table class="wrappertable"><tr id="l1"><td class="header">ҚазҰУ · Univer 2.0 — тестовый экран</td></tr>
          <tr id="l4"><td><table id="tools"><tr><td>
          <table class="tool"><tr><td class="top-center"><ul><li><a href="#">Расписание занятий</a></li><li><a href="#">Индивидуальный учебный план</a></li>
          <li><a href="#">Учебно-методический комплекс дисциплины (УМКД)</a></li></ul></td></tr><tr><td class="bottom-center">Обучение</td></tr></table>
          <table class="tool"><tr><td class="top-center"><ul><li><a href="#">Текущая успеваемость</a></li><li><a href="#">Результаты экзаменов</a></li></ul></td></tr><tr><td class="bottom-center">Контроль</td></tr></table>
          <table class="tool"><tr><td class="top-center"><ul><li><a href="#">Транскрипт</a></li><li><a href="#">Приказы</a></li></ul></td></tr><tr><td class="bottom-center">Документы</td></tr></table>
          </td></tr></table></td></tr></table>
          <div id="simplemodal-overlay" style="display:none"></div><div id="simplemodal-container" style="display:none"><a class="simplemodal-close" title="Close"></a><div class="simplemodal-wrap">
          <div id="auth_modal" style="display:none"><form id="modal_auth_frm" action="#">
          <table><tr><td></td><td>Введите ваш логин и пароль (№ <b id="auth_try">1</b>)</td><td></td></tr><tr><td></td><td>
          <label>Логин</label><input readonly type="text" id="modal_auth_login" value="test-student"><label>Пароль</label>
          <input type="password" id="modal_auth_pass" autocomplete="off"><input id="modal_auth_ajl_btn" type="submit" value="Войти"></td><td></td></tr></table></form></div>
          <div id="modal_agreement" style="display:none;width:90%;padding:5%"><form id="agree_frm"><p><b>Дополнительное соглашение — тестовый документ</b></p>
          <p class="MsoNormal" style="text-indent:47px"><span style="font-size:10pt">Этот синтетический текст используется для проверки переноса строк, читаемости и вертикальной прокрутки. Персональных данных здесь нет.</span></p></form>
          <center><label for="iconfirm">Прочитал договор</label><input id="iconfirm" type="checkbox"><div>
          <div id="confirm-block" style="float:left;width:50%"><button class="btn-confirm">Согласен</button></div><div style="float:left;width:50%"><button>Закрыть</button></div></div></center></div>
          </div></div><form id="attentionSt" action="/student/attentionStudents/42" style="display:none"><div class="parent"><div class="block" style="position:absolute;width:800px;margin:-300px;left:40%;top:40%">
          <p><b>Уведомление — тестовый экран</b></p><p>Сізге хабарлаймыз / Please be informed / Уведомляем. Текст должен полностью помещаться на экране и оставаться читаемым.</p>
          <input id="check" type="checkbox"><label for="check">«Мен бұл хабарламаны оқығанымды растаймын» / «I confirm that I have read this notice» / «Я подтверждаю, что прочел данное уведомление»</label>
          <input id="btn" type="submit" value="Жiберу/Submit/Отправить"></div></div></form></body></html>"""
        try {
            scenario.onActivity { activity ->
                web = WebView(activity)
                web.settings.javaScriptEnabled = true
                web.settings.useWideViewPort = true
                val root = FrameLayout(activity)
                root.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                activity.setContentView(root)
                ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
                    val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                    insets
                }
                ViewCompat.requestApplyInsets(root)
                web.webViewClient = object : WebViewClient() { override fun onPageFinished(view: WebView, url: String?) { loaded.countDown() } }
                web.loadDataWithBaseURL("https://univer.kaznu.kz/fixture/visual", html, "text/html", "UTF-8", null)
            }
            assertTrue(loaded.await(15, TimeUnit.SECONDS))
            eval(mobile + "\nwindow.__univerMobileV1.setEnabled(true,'ru');document.querySelector('.univer-app-menu').open=true;document.querySelector('.univer-app-category').open=true;")
            capture("mobile-menu")
            instrumentation.runOnMainSync { web.layoutParams = web.layoutParams.apply { width = (320 * web.resources.displayMetrics.density).toInt() } }
            capture("narrow-menu")
            eval("document.querySelector('.wrappertable').style.display='none';document.getElementById('simplemodal-container').style.display='block';document.getElementById('simplemodal-overlay').style.display='block';document.getElementById('auth_modal').style.display='block';")
            capture("reauth-phone")
            eval("document.getElementById('modal_auth_pass').focus()")
            instrumentation.runOnMainSync {
                web.requestFocus()
                (web.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(web, InputMethodManager.SHOW_IMPLICIT)
            }
            Thread.sleep(1_000)
            capture("reauth-keyboard")
            instrumentation.runOnMainSync {
                (web.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(web.windowToken, 0)
                web.clearFocus()
            }
            eval("document.getElementById('auth_modal').style.display='none';document.getElementById('modal_agreement').style.display='block';var p=document.querySelector('#agree_frm .MsoNormal');for(var i=0;i<20;i++)p.parentNode.appendChild(p.cloneNode(true));")
            capture("agreement-text")
            eval("document.getElementById('simplemodal-container').scrollTop=100000")
            capture("agreement-controls")
            eval("document.getElementById('simplemodal-container').style.display='none';document.getElementById('simplemodal-overlay').style.display='none';document.getElementById('attentionSt').style.display='block';window.scrollTo(0,0);")
            capture("notice-phone")
        } finally {
            if (::web.isInitialized) instrumentation.runOnMainSync { web.destroyFixture() }
            scenario.close()
        }
    }
}
