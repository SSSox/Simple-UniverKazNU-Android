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
import com.univerflow.kaznu.web.AutomationController
import com.univerflow.kaznu.web.AutomationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A refused AJAX dialog (an expired key after a long break) ends in the full-page login by itself. Offline fixtures only. */
@RunWith(AndroidJUnit4::class)
class AutomaticRecoveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun awaitCondition(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition() && System.nanoTime() < end) Thread.sleep(150)
        assertTrue("Recovery reached expected state", condition())
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Test fun refusedDialogReloadsThePageAndSignsInOnceThroughTheFullPageForm() {
        val context = instrumentation.targetContext
        val vault = CredentialVault(context, "univer.test.recovery")
        val prefsName = "univer.test.recovery.preferences"
        context.deleteSharedPreferences(prefsName)
        val store = ViewModelStore()
        val model = UniverViewModel(com.univerflow.kaznu.data.LocalSessionRepository(vault, AppPreferences(context, prefsName)))
        store.put("recovery", model)
        runBlocking { model.state.first { it.ready }; withContext(Dispatchers.Main) { model.save("fixture-user", "fixture-password") } }
        val jquery = instrumentation.context.assets.open("site-jquery.js").bufferedReader().use { it.readText() }
        val page = """<html><head><script>$jquery
            function ajl(){var params=$('#modal_auth_frm').serialize();$('#auth_modal').hide();
                $.ajax({type:'POST',url:'/user/ajl',dataType:'json',data:params,success:function(){$('#auth_try').html('2');$('#auth_modal').show();}});}
            $(document).ready(function(){$('#modal_auth_frm').submit(function(){ajl();return false;});});
            </script></head><body><p>Course materials fixture</p><a href="/user/logout">Выход</a>
            <div id="auth_modal"><form id="modal_auth_frm" method="post" action="#"><b id="auth_try">1</b>
            <input readonly id="modal_auth_login" name="login" type="text" value="fixture-user"><input id="modal_auth_pass" name="password" type="password">
            <input id="modal_auth_ajl_btn" type="submit"><input type="hidden" name="key" id="modal_auth_key" value="expired-key">
            <input type="hidden" name="cheat" id="cheat_auth" value="0"></form></div></body></html>"""
        val login = """<html><head><script>$jquery
            $(document).ready(function(){$('#login_frm').submit(function(){
                if ($('#_l').attr('value')==='fixture-user' && $('#_p').attr('value')==='fixture-password') location.href='/fixture/signed-in';
                return false;});});</script></head><body>
            <form id="login_frm" method="post" action="/"><input type="text" id="_l"><input type="password" id="_p"><input name="btn" type="submit"></form></body></html>"""
        val signedIn = "<html><body><p id='inside'>Inside Univer fixture</p><a href='/user/logout'>Выход</a></body></html>"
        val authRequests = AtomicInteger()
        val signIns = AtomicInteger()
        val recovered = Collections.synchronizedList(mutableListOf<String>())
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var web: WebView
        lateinit var controller: AutomationController
        var started = false
        var status = AutomationStatus.IDLE
        try {
            scenario.onActivity { activity ->
                web = WebView(activity)
                activity.setContentView(web)
                web.settings.javaScriptEnabled = true
                controller = AutomationController(web, model, activity.lifecycleScope, recover = { url ->
                    recovered.add(url)
                    // The ended session's page is redirected by the site to its login form.
                    web.loadUrl(if (url.endsWith("/student/umkd/")) "https://univer.kaznu.kz/user/login" else url)
                }) { status = it }
                web.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        var mime = "text/html"
                        val body = when (request.url.path) {
                            "/user/ajl" -> { authRequests.incrementAndGet(); mime = "application/json"; """{"code":0}""" }
                            "/user/login" -> login
                            "/fixture/signed-in" -> { signIns.incrementAndGet(); signedIn }
                            else -> page
                        }
                        return WebResourceResponse(mime, "UTF-8", ByteArrayInputStream(body.toByteArray()))
                    }
                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) { controller.pageStarted() }
                    override fun onPageFinished(view: WebView, url: String?) { controller.pageFinished() }
                }
                started = true
                controller.resume()
                web.loadUrl("https://univer.kaznu.kz/student/umkd/")
            }
            awaitCondition { signIns.get() == 1 }
            assertEquals("One refused dialog", 1, authRequests.get())
            assertEquals(listOf("https://univer.kaznu.kz/student/umkd/"), recovered.toList())
            awaitCondition { status == AutomationStatus.IDLE && !model.guard.blocked }
            Thread.sleep(2_000)
            assertEquals("No further reloads or submissions once signed in", 1, recovered.size)
            assertEquals(1, signIns.get())
            assertEquals(1, authRequests.get())
        } finally {
            if (started) instrumentation.runOnMainSync { controller.pause(); web.destroyFixture() }
            scenario.close()
            runBlocking { withContext(Dispatchers.IO) { vault.delete(); context.deleteSharedPreferences(prefsName) } }
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    /**
     * The login answered after its timeout and landed on the read notice, a page without the header's Logout
     * link. The notice proves the signed-in account: the stop is lifted and the notice is confirmed.
     */
    @SuppressLint("SetJavaScriptEnabled")
    @Test fun noticeAfterAStalledLoginLiftsTheStopAndIsConfirmed() {
        val context = instrumentation.targetContext
        val vault = CredentialVault(context, "univer.test.recovery.notice")
        val prefsName = "univer.test.recovery.notice.preferences"
        context.deleteSharedPreferences(prefsName)
        val store = ViewModelStore()
        val model = UniverViewModel(com.univerflow.kaznu.data.LocalSessionRepository(vault, AppPreferences(context, prefsName)))
        store.put("recovery-notice", model)
        runBlocking { model.state.first { it.ready }; withContext(Dispatchers.Main) { model.save("fixture-user", "fixture-password") } }
        val notice = """<html lang="kz"><head><script>
            function calc(){document.getElementById('btn').disabled=!document.getElementById('check').checked;}
            window.onload=function(){document.getElementById('attentionSt').onsubmit=function(e){e.preventDefault();location.href='/fixture/signed-in';};};
            </script></head><body><form id="attentionSt" action="/student/attentionStudents/42" method="post">
            <input name="attentions[]" value="-1" hidden><p>Notice fixture after a slow login</p><input type="checkbox" id="check" onclick="calc();">
            <label for="check">I confirm that I have read this notice</label><input id="btn" type="submit" value="Submit" disabled></form></body></html>"""
        val signedIn = "<html><body><p id='inside'>Inside Univer fixture</p><a href='/user/logout'>Выход</a></body></html>"
        val confirmations = AtomicInteger()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var web: WebView
        lateinit var controller: AutomationController
        var started = false
        var status = AutomationStatus.IDLE
        try {
            scenario.onActivity { activity ->
                val now = android.os.SystemClock.elapsedRealtime()
                assertTrue(model.guard.tryLogin("stalled-form", now - 70_000))
                assertTrue("The submit is stalled past its timeout", model.guard.loginStillVisible("stalled-form", now))
                web = WebView(activity)
                activity.setContentView(web)
                web.settings.javaScriptEnabled = true
                controller = AutomationController(web, model, activity.lifecycleScope, recover = { web.loadUrl(it) }) { status = it }
                web.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        val body = if (request.url.path == "/fixture/signed-in") { confirmations.incrementAndGet(); signedIn } else notice
                        return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(body.toByteArray()))
                    }
                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) { controller.pageStarted() }
                    override fun onPageFinished(view: WebView, url: String?) { controller.pageFinished() }
                }
                started = true
                controller.resume()
                web.loadUrl("https://univer.kaznu.kz/student/attentionStudents/42")
            }
            awaitCondition { confirmations.get() == 1 }
            assertFalse(model.guard.blocked)
            awaitCondition { status == AutomationStatus.IDLE }
            Thread.sleep(1_500)
            assertEquals("Confirmed once", 1, confirmations.get())
        } finally {
            if (started) instrumentation.runOnMainSync { controller.pause(); web.destroyFixture() }
            scenario.close()
            runBlocking { withContext(Dispatchers.IO) { vault.delete(); context.deleteSharedPreferences(prefsName) } }
            instrumentation.runOnMainSync { store.clear() }
        }
    }
}
