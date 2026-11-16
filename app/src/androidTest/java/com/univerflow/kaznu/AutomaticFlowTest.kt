package com.univerflow.kaznu

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.data.AppPreferences
import com.univerflow.kaznu.data.CredentialVault
import com.univerflow.kaznu.web.AutomationController
import com.univerflow.kaznu.web.AutomationStatus
import com.univerflow.kaznu.ui.NoticeLoadingOverlay
import com.univerflow.kaznu.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Full controller + ViewModel + Keystore + WebView flow, with every network request intercepted offline. */
@RunWith(AndroidJUnit4::class)
class AutomaticFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var web: WebView
    private fun eval(script: String): String {
        val latch = CountDownLatch(1)
        var result = "null"
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { result = it; latch.countDown() } }
        // WebView may discard an evaluation callback during navigation; the condition loop will retry.
        latch.await(3, TimeUnit.SECONDS)
        return result
    }
    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(150)
        assertTrue("Automatic flow reached expected state", condition())
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Test fun savedCredentialsLoginNoticeSessionReuseAndFailedRedirectBudget() {
        val context = instrumentation.targetContext
        val vault = CredentialVault(context, "univer.test.flow")
        val prefsName = "univer.test.flow.preferences"
        context.deleteSharedPreferences(prefsName)
        val preferences = AppPreferences(context, prefsName)
        preferences.language = AppLanguage.RUSSIAN
        preferences.autoLoginPaused = false
        val store = ViewModelStore()
        val model = UniverViewModel(com.univerflow.kaznu.data.LocalSessionRepository(vault, preferences))
        store.put("flow", model)
        runBlocking { model.state.first { it.ready }; withContext(Dispatchers.Main) { model.save("fixture-user", "fixture-password") } }
        val jquery = instrumentation.context.assets.open("site-jquery.js").bufferedReader().use { it.readText() }
        val login = """<html><head><script>$jquery
          $(document).ready(function(){ $('#login_frm').submit(function(){
            if (localStorage.getItem('fixture-reject')) { location.href='/user/login?again=1'; return false; }
            if ($('#_randomLogin').attr('value')==='fixture-user' && $('#_randomPassword').attr('value')==='fixture-password') {
              localStorage.setItem('fixture-logins',String(Number(localStorage.getItem('fixture-logins')||0)+1));
              document.cookie='fixture_session=1; path=/; Secure'; location.href='/student/attentionStudents/42';
            } return false;
          });});</script></head><body><div class="header"><img src="/content/icons/ru.png"></div>
          <form id="login_frm" method="post" action="/"><input type="text" id="_randomLogin"><input type="password" id="_randomPassword"><input name="btn" type="submit"></form></body></html>"""
        val notice = """<html lang="kz"><head><script>
          function calc(){document.getElementById('btn').disabled=!document.getElementById('check').checked;}
          window.onload=function(){document.getElementById('attentionSt').onsubmit=function(e){e.preventDefault();
            localStorage.setItem('fixture-notices',String(Number(localStorage.getItem('fixture-notices')||0)+1));location.href='/fixture/dashboard';};};
          </script></head><body><form id="attentionSt" action="/student/attentionStudents/42" method="post">
          <input name="attentions[]" value="-1" hidden><p>Notice fixture for semester</p><input type="checkbox" id="check" onclick="calc();">
          <label for="check">I confirm that I have read this notice</label><input id="btn" type="submit" value="Жiберу/Submit/Отправить" disabled></form></body></html>"""
        val boot = """<html><script>location.replace(document.cookie.indexOf('fixture_session=1')>=0?'/fixture/dashboard':'/user/login');</script></html>"""
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var controller: AutomationController
        lateinit var loading: NoticeLoadingOverlay
        lateinit var fixtureBinding: ActivityMainBinding
        var noticeWasCovered = false
        var status = AutomationStatus.IDLE
        val flagRequests = AtomicInteger()
        val initialLoaded = CountDownLatch(1)
        try {
            scenario.onActivity { activity ->
                fixtureBinding = ActivityMainBinding.inflate(activity.layoutInflater)
                activity.setContentView(fixtureBinding.root)
                web = fixtureBinding.webView
                loading = NoticeLoadingOverlay(web, fixtureBinding.noticeLoadingOverlay)
                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = true
                controller = AutomationController(web, model, activity.lifecycleScope) {
                    status = it
                    loading.status(it)
                    if (it == AutomationStatus.NOTICE_CONFIRMING)
                        noticeWasCovered = fixtureBinding.noticeLoadingOverlay.visibility == android.view.View.VISIBLE
                }
                web.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        val html = when {
                            request.url.path == "/" -> boot
                            request.url.path?.startsWith("/lang/change/") == true -> {
                                flagRequests.incrementAndGet()
                                boot
                            }
                            request.url.path == "/user/login" -> login
                            request.url.path == "/student/attentionStudents/42" -> notice
                            request.url.path == "/fixture/dashboard" -> "<html><body><p id='inside'>Inside Univer fixture</p><a href='/user/logout'>Выход</a></body></html>"
                            request.url.path == "/student/umkd/" -> "<html><body><p id='materials'>Course materials fixture</p><a href='/user/logout'>Выход</a></body></html>"
                            else -> "<html></html>"
                        }
                        return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray()))
                    }
                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) { controller.pageStarted() }
                    override fun onPageFinished(view: WebView, url: String?) { initialLoaded.countDown(); controller.pageFinished() }
                }
                // Reset only synthetic fixture data; production credentials/cookies are untouched.
                web.loadDataWithBaseURL("https://univer.kaznu.kz/", "<html><script>localStorage.removeItem('fixture-logins');localStorage.removeItem('fixture-notices');document.cookie='fixture_session=; Max-Age=0; path=/; Secure';</script></html>", "text/html", "UTF-8", null)
            }
            assertTrue(initialLoaded.await(15, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { loading.beginStartup(); controller.resume(); controller.open() }
            awaitCondition { eval("!!document.getElementById('inside')") == "true" }
            assertEquals("\"1\"", eval("localStorage.getItem('fixture-logins')"))
            assertEquals("Startup reuses the session without an unconditional flag redirect", 0, flagRequests.get())
            assertEquals("\"1\"", eval("localStorage.getItem('fixture-notices')"))
            assertTrue("The real notice handler ran beneath the native loading screen", noticeWasCovered)
            awaitCondition { status == AutomationStatus.IDLE }
            // The cover stops counting at once; the view itself takes a moment to fade away.
            instrumentation.runOnMainSync { assertFalse(loading.covering) }
            awaitCondition { fixtureBinding.noticeLoadingOverlay.visibility == android.view.View.GONE }
            instrumentation.runOnMainSync { assertEquals(android.view.View.VISIBLE, web.visibility) }

            // Reopen with a still-active cookie: no new native credential entry or login submission.
            instrumentation.runOnMainSync { controller.open() }
            Thread.sleep(3_000)
            awaitCondition { eval("!!document.getElementById('inside')") == "true" }
            assertEquals("\"1\"", eval("localStorage.getItem('fixture-logins')"))

            // The same notice can legitimately appear again after stable normal site content.
            instrumentation.runOnMainSync { web.loadUrl("https://univer.kaznu.kz/student/attentionStudents/42") }
            awaitCondition { eval("localStorage.getItem('fixture-notices')") == "\"2\"" }
            awaitCondition { eval("!!document.getElementById('inside')") == "true" }

            // A session ending again right after a login waits a minute instead of looping or stopping for good.
            instrumentation.runOnMainSync { web.loadUrl("https://univer.kaznu.kz/user/login") }
            awaitCondition { status == AutomationStatus.SIGNING_IN }
            Thread.sleep(2_000)
            assertFalse(model.guard.blocked)
            assertEquals("\"1\"", eval("localStorage.getItem('fixture-logins')"))
            instrumentation.runOnMainSync { web.loadUrl("https://univer.kaznu.kz/fixture/dashboard") }
            awaitCondition { eval("!!document.getElementById('inside')") == "true" }

            // An ended session on a section page: sign in, confirm the notice, then return to that page.
            instrumentation.runOnMainSync { model.guard.retry() }
            eval("document.cookie='fixture_session=; Max-Age=0; path=/; Secure'")
            instrumentation.runOnMainSync {
                controller.resumeAfterLogin("https://univer.kaznu.kz/student/umkd/")
                web.loadUrl("https://univer.kaznu.kz/user/login")
            }
            awaitCondition { eval("!!document.getElementById('materials')") == "true" }
            assertEquals("\"2\"", eval("localStorage.getItem('fixture-logins')"))
            awaitCondition { status == AutomationStatus.IDLE }

            // A rejected password stops; a signed-in page opened later (a manual login) lifts the stop.
            instrumentation.runOnMainSync { model.guard.retry() }
            eval("localStorage.setItem('fixture-reject','1');document.cookie='fixture_session=; Max-Age=0; path=/; Secure'")
            instrumentation.runOnMainSync { web.loadUrl("https://univer.kaznu.kz/user/login") }
            awaitCondition { model.guard.blocked }
            assertEquals(AutomationStatus.LOGIN_FAILED, status)
            Thread.sleep(1_500)
            assertTrue("No automatic retry after a rejection", model.guard.blocked)
            eval("localStorage.removeItem('fixture-reject');document.cookie='fixture_session=1; path=/; Secure'")
            instrumentation.runOnMainSync { web.loadUrl("https://univer.kaznu.kz/fixture/dashboard") }
            awaitCondition { !model.guard.blocked && status == AutomationStatus.IDLE }
            assertEquals("\"2\"", eval("localStorage.getItem('fixture-logins')"))
        } finally {
            if (::web.isInitialized) {
                eval("localStorage.removeItem('fixture-reject');localStorage.removeItem('fixture-logins');localStorage.removeItem('fixture-notices');document.cookie='fixture_session=; Max-Age=0; path=/; Secure'")
                instrumentation.runOnMainSync { controller.pause(); loading.close(); web.destroyFixture() }
            }
            scenario.close()
            runBlocking { withContext(Dispatchers.IO) { vault.delete(); context.deleteSharedPreferences(prefsName) } }
            instrumentation.runOnMainSync { store.clear() }
        }
    }
}
