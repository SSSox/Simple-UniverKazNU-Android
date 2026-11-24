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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Actual legacy jQuery AJAX in Android WebView; all requests are intercepted with synthetic data. */
@RunWith(AndroidJUnit4::class)
class AutomaticChallengeFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var web: WebView
    private fun eval(script: String): String {
        val latch = CountDownLatch(1)
        var value = "null"
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { value = it; latch.countDown() } }
        latch.await(3, TimeUnit.SECONDS)
        return value
    }
    private fun awaitCondition(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!condition() && System.nanoTime() < end) Thread.sleep(150)
        assertTrue("Late challenge reached expected state", condition())
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Test fun dashboardRecurrentNoticeAjaxReauthenticationAndAgreementWithFailureStops() {
        val context = instrumentation.targetContext
        val vault = CredentialVault(context, "univer.test.challenges")
        val preferences = AppPreferences(context, "univer.test.challenges.preferences")
        context.deleteSharedPreferences("univer.test.challenges.preferences")
        val store = ViewModelStore()
        val model = UniverViewModel(com.univerflow.kaznu.data.LocalSessionRepository(vault, preferences))
        store.put("challenges", model)
        runBlocking { model.state.first { it.ready }; withContext(Dispatchers.Main) { model.save("fixture-user", "fixture-password") } }
        val jquery = instrumentation.context.assets.open("site-jquery.js").bufferedReader().use { it.readText() }
        val html = """<html><head><script>$jquery
            window.noticePosts=0;window.authDone=0;window.authCaptured=false;window.keepNotice=false;
            function calc(){document.getElementById('btn').disabled=!document.getElementById('check').checked;}
            function showNotice(){$('#check').attr('checked',false);$('#btn').attr('disabled',true);$('#notice').show();}
            function showAuth(){$('#auth_modal').show();}
            function showAgreement(){$('#iconfirm').attr('checked',false);$('.btn-confirm').attr('disabled',true);$('#modal_agreement').show();}
            function ajl(){
                window.authCaptured=$('#modal_auth_login').attr('value')==='fixture-user' && $('#modal_auth_pass').attr('value')==='fixture-password' &&
                  $('#cheat_auth').attr('value')==='0' && !!$('#modal_auth_key').attr('value');
                var params=$('#modal_auth_frm').serialize();$('#auth_modal').hide();
                $.ajax({type:'POST',url:'/user/ajl',dataType:'json',data:params,success:function(ret){
                    if(ret.code===1){$('#modal_auth_key').attr('value',ret.obj);window.authDone++;}
                    else{$('#auth_try').html('2');$('#auth_modal').show();}
                }});
            }
            function agree(){$.post('/user/setagree');$('#modal_agreement').hide();return false;}
            $(document).ready(function(){
                $('#modal_auth_frm').submit(function(){ajl();return false;});
                $('#iconfirm').change(function(){document.querySelector('.btn-confirm').disabled=!this.checked;});
                $('#attentionSt').submit(function(){window.noticePosts++;if(!window.keepNotice)$('#notice').hide();return false;});
                window.fixtureReady=true;
            });
            </script></head><body><p>Normal Univer dashboard fixture</p>
            <div id="notice" style="display:none"><form id="attentionSt" action="/student/attentionStudents/42" method="post">
            <p>Same semester notice</p><input name="attentions[]" value="-1" hidden><input type="checkbox" id="check" onclick="calc()">
            <label for="check">I confirm that I have read this notice</label><input id="btn" type="submit" value="Submit" disabled></form></div>
            <div id="auth_modal" style="display:none"><form id="modal_auth_frm" method="post" action="#">
            <b id="auth_try">1</b><input readonly id="modal_auth_login" name="login" type="text"><input id="modal_auth_pass" name="password" type="password">
            <input id="modal_auth_ajl_btn" type="submit"><input type="hidden" name="key" id="modal_auth_key" value="fixture-key">
            <input type="hidden" name="cheat" id="cheat_auth" value="0"></form></div>
            <div id="modal_agreement" style="display:none"><form id="agree_frm" action="#" method="post"><p>Additional agreement fixture</p></form>
            <label for="iconfirm">Прочитал договор</label><input type="checkbox" id="iconfirm"><button class="btn-confirm" onclick="agree()" disabled>Согласен</button></div>
            </body></html>"""
        val authRequests = AtomicInteger()
        val agreementRequests = AtomicInteger()
        val rejectAuth = AtomicBoolean()
        val rejectAgreement = AtomicBoolean()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var controller: AutomationController
        var status = AutomationStatus.IDLE
        try {
            scenario.onActivity { activity ->
                web = WebView(activity)
                activity.setContentView(web)
                web.settings.javaScriptEnabled = true
                controller = AutomationController(web, model, activity.lifecycleScope, sessionOver = { true }) { status = it }
                web.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        var mime = "text/html"
                        var body = html
                        var failed = false
                        when (request.url.path) {
                            "/user/ajl" -> {
                                assertEquals("POST", request.method)
                                authRequests.incrementAndGet()
                                mime = "application/json"
                                body = if (rejectAuth.get()) """{"code":0}""" else """{"code":1,"obj":"fixture-refreshed-key"}"""
                            }
                            "/user/setagree" -> {
                                assertEquals("POST", request.method)
                                agreementRequests.incrementAndGet()
                                mime = "text/plain"; body = ""; failed = rejectAgreement.get()
                            }
                        }
                        return if (failed) WebResourceResponse(mime, "UTF-8", 503, "Unavailable", emptyMap(), ByteArrayInputStream(body.toByteArray()))
                        else WebResourceResponse(mime, "UTF-8", ByteArrayInputStream(body.toByteArray()))
                    }
                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) { controller.pageStarted() }
                    override fun onPageFinished(view: WebView, url: String?) { controller.pageFinished() }
                }
                controller.resume()
                web.loadUrl("https://univer.kaznu.kz/fixture/challenges")
            }
            awaitCondition { eval("window.fixtureReady===true") == "true" }
            eval("showNotice()")
            awaitCondition { eval("window.noticePosts") == "1" }
            Thread.sleep(4_000)
            eval("showNotice()")
            awaitCondition { eval("window.noticePosts") == "2" }
            Thread.sleep(4_000)

            eval("showAuth()")
            awaitCondition { authRequests.get() == 1 && eval("window.authDone") == "1" && model.guard.pendingReauthentication == null }
            assertEquals("true", eval("window.authCaptured"))
            assertEquals("true", eval("document.getElementById('modal_auth_login').readOnly"))

            // Another genuine challenge shortly after a verified success must use the saved
            // account again, without a guard reset or any credential entry by the user.
            eval("window.authCaptured=false;$('#modal_auth_login').attr('value','');$('#modal_auth_pass').attr('value','');showAuth()")
            awaitCondition { authRequests.get() == 2 && eval("window.authDone") == "2" && model.guard.pendingReauthentication == null }
            assertEquals("true", eval("window.authCaptured"))
            assertFalse(model.guard.blocked)

            eval("showAgreement()")
            awaitCondition { agreementRequests.get() == 1 && eval("document.getElementById('iconfirm').checked") == "true" }
            Thread.sleep(4_000)
            assertEquals(AutomationStatus.IDLE, status)
            assertEquals(1, agreementRequests.get())

            // A network failure cannot be treated as agreement acceptance just because the site hid it.
            rejectAgreement.set(true)
            eval("showAgreement()")
            awaitCondition { status == AutomationStatus.AGREEMENT_FAILED }
            assertEquals(2, agreementRequests.get())

            instrumentation.runOnMainSync { model.guard.retry() }
            rejectAuth.set(true)
            eval("showAuth()")
            awaitCondition { model.guard.blocked }
            assertEquals(AutomationStatus.LOGIN_FAILED, status)
            assertEquals(3, authRequests.get())
            Thread.sleep(1_500)
            assertEquals(3, authRequests.get())
        } finally {
            if (::web.isInitialized) instrumentation.runOnMainSync { controller.pause(); web.destroyFixture() }
            scenario.close()
            runBlocking { withContext(Dispatchers.IO) { vault.delete(); context.deleteSharedPreferences("univer.test.challenges.preferences") } }
            instrumentation.runOnMainSync { store.clear() }
        }
    }
}
