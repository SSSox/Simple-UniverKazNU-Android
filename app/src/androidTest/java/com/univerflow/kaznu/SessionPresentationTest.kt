package com.univerflow.kaznu

import android.webkit.*
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import com.univerflow.kaznu.web.SessionPresentation
import com.univerflow.kaznu.web.SitePolicy
import com.univerflow.kaznu.web.configureSecureSettings
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Only synthetic DOM and local responses; never changes the configured account or its cookies. */
@RunWith(AndroidJUnit4::class)
class SessionPresentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var web: WebView
    private lateinit var presentation: SessionPresentation
    private val modal = """<div id='auth_modal' style='display:none' aria-hidden='false'>
        <form id='modal_auth_frm' action='#'><input id='modal_auth_login' type='text' name='login' readonly>
        <input id='modal_auth_pass' type='password' name='password'><input id='modal_auth_ajl_btn' type='submit'>
        <input id='modal_auth_key' type='hidden' name='key' value='fixture-key'>
        <input id='cheat_auth' type='hidden' name='cheat' value='0'></form></div>"""
    private val openModal = modal.replace("display:none", "display:block")
    private val framePage = "<p>Current page fixture</p><iframe id='child' src='/fixture/session/frame'></iframe>"
    private val sample = """window.rawAuthFrames=0;window.rawLoginFrames=0;window.halfFormFrames=0;function sample(){
        var auth=document.getElementById('auth_modal');
        if(auth&&!document.getElementById('cheat_auth')&&++window.halfFormFrames===2)new Image().src='/fixture/session/frames-passed';
        if(auth&&auth.getClientRects().length&&getComputedStyle(auth).opacity!=='0'){window.rawAuthFrames++;
            window.rawAuthState=document.readyState+' form complete: '+!!document.getElementById('cheat_auth');}
        if(location.pathname==='/user/login'&&document.body&&getComputedStyle(document.body).opacity!=='0')window.rawLoginFrames++;
        requestAnimationFrame(sample);}requestAnimationFrame(sample);"""

    @Before fun setUp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity {
            web = WebView(it); web.configureSecureSettings(); it.setContentView(web)
            presentation = SessionPresentation(web)
        }
    }

    @After fun tearDown() {
        instrumentation.runOnMainSync { presentation.close(); web.destroyFixture() }
        scenario.close()
    }

    private fun eval(source: String): String {
        val done = CountDownLatch(1); var result = "null"
        instrumentation.runOnMainSync { web.evaluateJavascript(source) { result = it; done.countDown() } }
        assertTrue("JS callback", done.await(10, TimeUnit.SECONDS)); return result
    }

    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        var ready = condition()
        while (!ready && System.nanoTime() < end) { Thread.sleep(40); ready = condition() }
        assertTrue("Expected presentation state", ready)
    }

    /** A slow connection: the parser holds half of the form until the page reports that frames went by. */
    private fun arriving(page: String, framesPassed: CountDownLatch): InputStream {
        val cut = page.indexOf("<input id='modal_auth_pass'")
        val rest = object : InputStream() {
            private val bytes by lazy { framesPassed.await(8, TimeUnit.SECONDS); ByteArrayInputStream(page.drop(cut).toByteArray()) }
            override fun read() = bytes.read()
            override fun read(target: ByteArray, offset: Int, length: Int) = bytes.read(target, offset, length)
        }
        return SequenceInputStream(ByteArrayInputStream(page.take(cut).toByteArray()), rest)
    }

    private fun load(body: String = "<p id='healthy'>Current page fixture</p>$modal", active: Boolean = true,
        url: String = SitePolicy.ORIGIN + "/fixture/session", frame: String = openModal, slowFrame: Boolean = false) {
        val done = CountDownLatch(1)
        val framesPassed = CountDownLatch(1)
        instrumentation.runOnMainSync {
            presentation.setEnabled(active)
            web.webViewClient = object : WebViewClient() {
                override fun onPageCommitVisible(view: WebView, url: String?) { presentation.apply() }
                override fun onPageFinished(view: WebView, url: String?) { presentation.apply(); done.countDown() }
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    if (request.url.path == "/fixture/session/frames-passed") framesPassed.countDown()
                    val inFrame = request.url.path == "/fixture/session/frame"
                    val page = "<html><head><script>$sample</script></head><body>${if (inFrame) frame else body}</body></html>"
                    return WebResourceResponse("text/html", "UTF-8",
                        if (inFrame && slowFrame) arriving(page, framesPassed) else ByteArrayInputStream(page.toByteArray()))
                }
            }
            web.loadUrl(url)
        }
        assertTrue("Fixture complete", done.await(10, TimeUnit.SECONDS))
    }

    @Test fun lateSimpleModalIsConcealedBeforeVisibleFrameAndPreservesAllAuthenticationFields() {
        load()
        eval("var box=document.createElement('div');box.id='simplemodal-container';document.body.appendChild(box);" +
            "box.appendChild(document.getElementById('auth_modal'));var overlay=document.createElement('div');" +
            "overlay.id='simplemodal-overlay';document.body.appendChild(overlay);document.getElementById('auth_modal').style.display='block'")
        await { eval("getComputedStyle(document.getElementById('auth_modal')).opacity") == "\"0\"" }
        assertEquals("0", eval("window.rawAuthFrames"))
        assertEquals("\"0\"", eval("getComputedStyle(document.getElementById('simplemodal-container')).opacity"))
        assertEquals("\"0\"", eval("getComputedStyle(document.getElementById('simplemodal-overlay')).opacity"))
        assertEquals("\"1\"", eval("getComputedStyle(document.getElementById('healthy')).opacity"))
        assertEquals("true", eval("document.getElementById('modal_auth_login').readOnly"))
        assertEquals("\"fixture-key\"", eval("document.getElementById('modal_auth_key').value"))
        assertEquals("\"0\"", eval("document.getElementById('cheat_auth').value"))
        instrumentation.runOnMainSync { presentation.setEnabled(false) }
        await { eval("getComputedStyle(document.getElementById('auth_modal')).opacity") == "\"1\"" }
        assertEquals("\"false\"", eval("document.getElementById('auth_modal').getAttribute('aria-hidden')"))
        assertEquals("false", eval("!!document.getElementById('univer-app-session-style')"))
    }

    @Test fun aDialogStillArrivingStaysHiddenUntilItCanBeVerified() {
        load(framePage, slowFrame = true)
        assertNotEquals("Frames went by with half of the form", "0", eval("document.getElementById('child').contentWindow.halfFormFrames"))
        assertEquals(eval("document.getElementById('child').contentWindow.rawAuthState"), "0",
            eval("document.getElementById('child').contentWindow.rawAuthFrames"))
        await { eval("var w=document.getElementById('child').contentWindow;w.getComputedStyle(w.document.getElementById('auth_modal')).opacity") == "\"0\"" }
        load(framePage, slowFrame = true, frame = openModal.replace("action='#'", "action='https://external.test/user/ajl'"))
        await { eval("var w=document.getElementById('child').contentWindow;w.getComputedStyle(w.document.getElementById('auth_modal')).opacity") == "\"1\"" }
    }

    @Test fun sameOriginFrameUsesTheSameConcealmentAndPauseRestoresIt() {
        load(framePage)
        await { eval("document.getElementById('child').contentDocument.readyState==='complete'") == "true" }
        await { eval("var w=document.getElementById('child').contentWindow;w.getComputedStyle(w.document.getElementById('auth_modal')).opacity") == "\"0\"" }
        assertEquals(eval("document.getElementById('child').contentWindow.rawAuthState"), "0",
            eval("document.getElementById('child').contentWindow.rawAuthFrames"))
        instrumentation.runOnMainSync { presentation.setEnabled(false) }
        await { eval("var w=document.getElementById('child').contentWindow;w.getComputedStyle(w.document.getElementById('auth_modal')).opacity") == "\"1\"" }
    }

    @Test fun fullLoginPageIsConcealedAtDocumentStart() {
        instrumentation.runOnMainSync { assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) }
        load("<form id='login_frm' action='/'><input type='text'><input type='password'><input name='btn' type='submit'></form>",
            url = SitePolicy.ORIGIN + "/user/login")
        assertEquals("\"0\"", eval("getComputedStyle(document.body).opacity"))
        assertEquals("0", eval("window.rawLoginFrames"))
        assertEquals("true", eval("document.querySelector('#login_frm input[type=password]').getClientRects().length>0"))
        instrumentation.runOnMainSync { presentation.setEnabled(false) }
        await { eval("getComputedStyle(document.body).opacity") == "\"1\"" }
    }

    @Test fun timedOutDialogBecomesReadableAndExplicitRetryCanReuseIt() {
        instrumentation.runOnMainSync { presentation.close(); presentation = SessionPresentation(web, 1500) }
        load(modal.replace("display:none", "display:block"))
        await { eval("getComputedStyle(document.getElementById('auth_modal')).opacity") == "\"0\"" }
        await { eval("getComputedStyle(document.getElementById('auth_modal')).opacity") == "\"1\"" }
        eval("document.body.appendChild(document.createElement('p'))")
        assertEquals("false", eval("document.getElementById('auth_modal').hasAttribute('data-univer-app-auth-pending')"))
        instrumentation.runOnMainSync { presentation.setEnabled(true, retry = true) }
        await { eval("getComputedStyle(document.getElementById('auth_modal')).opacity") == "\"0\"" }
    }

    @Test fun pausedOrForeignOriginNeverConcealsLoginAndUnknownDialogIsUntouched() {
        load(modal.replace("display:none", "display:block"), active = false)
        assertEquals("\"1\"", eval("getComputedStyle(document.getElementById('auth_modal')).opacity"))
        load(modal.replace("display:none", "display:block").replace("action='#'", "action='https://external.test/user/ajl'"))
        assertEquals("\"1\"", eval("getComputedStyle(document.getElementById('auth_modal')).opacity"))
        load(modal.replace("display:none", "display:block"), url = "https://univer.kaznu.kz.evil.test/user/login")
        assertEquals("false", eval("!!window.__univerSessionPresentationV1"))
        assertEquals("\"1\"", eval("getComputedStyle(document.body).opacity"))
    }
}
