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
import com.univerflow.kaznu.data.AppPreferences
import com.univerflow.kaznu.data.CredentialVault
import com.univerflow.kaznu.data.LocalSessionRepository
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.web.AutomationController
import com.univerflow.kaznu.web.AutomationStatus
import com.univerflow.kaznu.web.SitePolicy
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The controller while a submission waits for the site: a held (never answered) login and a notice ticked by the site's
 * calc(). Offline fixtures and a separate synthetic vault only; nothing is sent to Univer.
 */
@RunWith(AndroidJUnit4::class)
class PendingSubmitAutomationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val jquery by lazy { instrumentation.context.assets.open("site-jquery.js").bufferedReader().use { it.readText() } }

    private class Harness(val model: UniverViewModel) {
        lateinit var web: WebView
        lateinit var controller: AutomationController
        @Volatile var status = AutomationStatus.IDLE
    }

    /** One synthetic account and a fixture WebView whose every request [route] answers by path, observed by the controller. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun withController(alias: String, route: (String) -> String, body: Harness.() -> Unit) {
        val context = instrumentation.targetContext
        val vault = CredentialVault(context, "univer.test.$alias")
        val prefsName = "univer.test.$alias.preferences"
        context.deleteSharedPreferences(prefsName)
        val preferences = AppPreferences(context, prefsName)
        preferences.language = AppLanguage.RUSSIAN
        val store = ViewModelStore()
        val model = UniverViewModel(LocalSessionRepository(vault, preferences))
        store.put(alias, model)
        runBlocking { model.state.first { it.ready }; withContext(Dispatchers.Main) { model.save("fixture-user", "fixture-password") } }
        val harness = Harness(model)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var started = false
        try {
            scenario.onActivity { activity ->
                val web = WebView(activity)
                harness.web = web
                activity.setContentView(web)
                web.settings.javaScriptEnabled = true
                harness.controller = AutomationController(web, model, activity.lifecycleScope) { harness.status = it }
                web.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                        WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(route(request.url.path ?: "/").toByteArray()))
                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) { harness.controller.pageStarted() }
                    override fun onPageFinished(view: WebView, url: String?) { harness.controller.pageFinished() }
                }
                started = true
            }
            harness.body()
        } finally {
            if (started) instrumentation.runOnMainSync { harness.controller.pause(); harness.web.destroyFixture() }
            scenario.close()
            runBlocking { withContext(Dispatchers.IO) { vault.delete(); context.deleteSharedPreferences(prefsName) } }
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    private fun Harness.eval(script: String): String {
        val latch = CountDownLatch(1)
        var result = "null"
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { result = it; latch.countDown() } }
        // WebView may discard an evaluation callback during navigation; the condition loop retries.
        latch.await(3, TimeUnit.SECONDS)
        return result
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition() && System.nanoTime() < end) Thread.sleep(150)
        assertTrue("Automation reached the expected state", condition())
    }

    /** The site's login form; its answer is held, as during the 20–30 s the site takes to answer a sign-in. */
    private fun heldLogin(header: String = "") = """<html><head><script>$jquery
        $(document).ready(function(){$('#login_frm').submit(function(){return false;});});</script></head><body>$header
        <form id="login_frm" method="post" action="/"><input type="text" id="_l"><input type="password" id="_p"><input name="btn" type="submit"></form></body></html>"""

    /** prepareNotice answers a plain "ready": the probe that ticked the checkbox sends the notice too. */
    @Test fun noticeIsSentByTheProbeThatTickedItsCheckbox() {
        // The fixture counts the controller's probes and notes the probe of the tick and the probe of the submit.
        val notice = """<html lang="kz"><head><script>
            var api;window.probes=0;window.tickedAt=-1;window.sentAt=-1;window.posts=0;
            Object.defineProperty(window,'__univerAutomationV1',{configurable:true,get:function(){return api;},set:function(v){
                var probe=v.probe,prepare=v.prepareNotice;
                v.probe=function(){window.probes++;return probe.apply(this,arguments);};
                v.prepareNotice=function(){window.tickedAt=window.probes;return prepare.apply(this,arguments);};
                api=v;}});
            function calc(){document.getElementById('btn').disabled=!document.getElementById('check').checked;}
            window.onload=function(){document.getElementById('attentionSt').onsubmit=function(e){e.preventDefault();window.posts++;window.sentAt=window.probes;};};
            </script></head><body><form id="attentionSt" action="/student/attentionStudents/42" method="post">
            <input name="attentions[]" value="-1" hidden><p>Notice fixture</p><input type="checkbox" id="check" onclick="calc();">
            <label for="check">I confirm that I have read this notice</label><input id="btn" type="submit" value="Submit" disabled></form></body></html>"""
        withController("pending.notice", { notice }) {
            instrumentation.runOnMainSync { controller.resume(); web.loadUrl("${SitePolicy.ORIGIN}/student/attentionStudents/42") }
            awaitCondition { eval("window.posts") == "1" }
            val ticked = eval("window.tickedAt")
            assertNotEquals("The controller ticked the checkbox through calc()", "-1", ticked)
            assertEquals("Sent by the probe that ticked it", ticked, eval("window.sentAt"))
            Thread.sleep(1_500)
            assertEquals("Sent once", "1", eval("window.posts"))
        }
    }

    /**
     * A language chosen while the site has not yet answered a sent login clicks no flag: that navigation would abort the
     * login, and the fresh form after it would read as the password's rejection. The site switches after the answer.
     */
    @Test fun languageChosenDuringAPendingLoginWaitsForTheAnswer() {
        val siteLanguage = AtomicReference("ru")
        val signedIn = AtomicBoolean(false)
        val flagRequests = AtomicInteger()
        fun header() = "<div class='header'><img src='/content/icons/${siteLanguage.get()}.png'>" +
            "<a href='/lang/change/ru/'>RU</a><a href='/lang/change/kz/'>KZ</a><a href='/lang/change/en/'>EN</a></div>"
        fun dashboard() = "<html><body>${header()}<p id='inside'>Inside Univer fixture</p><a href='/user/logout'>Выход</a></body></html>"
        withController("pending.language", { path ->
            when {
                path.startsWith("/lang/change/") -> {
                    flagRequests.incrementAndGet()
                    siteLanguage.set(path.removePrefix("/lang/change/").trim('/'))
                    if (signedIn.get()) dashboard() else heldLogin(header())
                }
                path == "/user/login" -> heldLogin(header())
                path == "/fixture/dashboard" -> dashboard()
                else -> "<html></html>"
            }
        }) {
            instrumentation.runOnMainSync { controller.resume(); web.loadUrl("${SitePolicy.ORIGIN}/user/login") }
            awaitCondition { model.guard.pendingDocument != null }
            runBlocking { withContext(Dispatchers.Main) { model.setLanguage(AppLanguage.ENGLISH); controller.applyLanguage(AppLanguage.ENGLISH) } }
            Thread.sleep(3_000)
            assertEquals("No flag click while the login waits for its answer", 0, flagRequests.get())
            assertNotNull("The login is still awaited", model.guard.pendingDocument)
            assertFalse(model.guard.blocked)
            assertEquals(AutomationStatus.SIGNING_IN, status)
            // The site answers the login with a signed-in page; the chosen language follows it.
            signedIn.set(true)
            instrumentation.runOnMainSync { web.evaluateJavascript("location.href='/fixture/dashboard'", null) }
            awaitCondition { siteLanguage.get() == "en" && model.guard.pendingDocument == null }
            awaitCondition { status == AutomationStatus.IDLE }
            Thread.sleep(1_500)
            assertEquals("One switch, after the answer", 1, flagRequests.get())
            assertFalse(model.guard.blocked)
        }
    }

    /**
     * Another page loaded in place of one whose login is still awaited (resume(replacing = true), as a Retry does) drops
     * that attempt: the site's fresh form is a new attempt within the budget, not the rejection of the password.
     */
    @Test fun replacingThePageOfAPendingLoginDoesNotReadTheFreshFormAsARejection() {
        val loginLoads = AtomicInteger()
        withController("pending.replace", { path -> if (path == "/user/login") { loginLoads.incrementAndGet(); heldLogin() } else "<html></html>" }) {
            instrumentation.runOnMainSync { controller.resume(); web.loadUrl("${SitePolicy.ORIGIN}/user/login") }
            awaitCondition { model.guard.pendingDocument != null }
            instrumentation.runOnMainSync { controller.resume(replacing = true); web.loadUrl("${SitePolicy.ORIGIN}/user/login") }
            awaitCondition { loginLoads.get() == 2 }
            Thread.sleep(3_000) // Several probes of the fresh form.
            assertFalse("The fresh form is not the password's rejection", model.guard.blocked)
            assertNull(model.guard.pendingDocument)
            // The dropped login was sent a moment ago: the next one waits for the per-minute budget, then continues.
            assertEquals(AutomationStatus.SIGNING_IN, status)
        }
    }
}
