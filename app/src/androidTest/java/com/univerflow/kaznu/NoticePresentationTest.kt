package com.univerflow.kaznu

import android.view.View
import android.webkit.*
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import com.univerflow.kaznu.web.AutomationController
import com.univerflow.kaznu.web.NoticePresentation
import com.univerflow.kaznu.web.SitePolicy
import com.univerflow.kaznu.web.configureSecureSettings
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Local DOM only; does not write production credentials, preferences or cookies. */
@RunWith(AndroidJUnit4::class)
class NoticePresentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var web: WebView
    private lateinit var presentation: NoticePresentation
    private val imageRelease = CountDownLatch(1)
    private val frameReady = CountDownLatch(1)
    private val form = """<form id='attentionSt' action='/student/attentionStudents/42' aria-hidden='false'>
        <input type='hidden' name='attentions[]' value='-1'><input type='checkbox' id='check' onclick='calc()'>
        <label for='check'>I confirm that I have read this notice</label><input type='submit' id='btn' disabled></form>"""
    private val handlers = """window.posts=0;window.calcCalls=0;
        function calc(){window.calcCalls++;document.getElementById('btn').disabled=false;}
        function submit(e){e.preventDefault();window.posts++;}
        function sample(){var f=document.getElementById('attentionSt');
          if(f&&getComputedStyle(f).opacity!=='0')window.rawNoticeFrames=(window.rawNoticeFrames||0)+1;
          requestAnimationFrame(sample);}requestAnimationFrame(sample);"""
    private val automationSource get() = instrumentation.targetContext.assets.open("univer-automation.js").bufferedReader().use { it.readText() }

    @Before fun setUp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            web = WebView(activity)
            web.configureSecureSettings()
            activity.setContentView(web)
            presentation = NoticePresentation(web)
        }
    }

    @After fun tearDown() {
        imageRelease.countDown()
        instrumentation.runOnMainSync { presentation.close(); web.destroyFixture() }
        scenario.close()
    }

    private fun eval(source: String): String {
        val done = CountDownLatch(1)
        var result = "null"
        instrumentation.runOnMainSync { web.evaluateJavascript(source) { result = it; done.countDown() } }
        assertTrue("JS callback", done.await(10, TimeUnit.SECONDS))
        return result
    }

    private fun await(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        var ready = condition()
        while (!ready && System.nanoTime() < until) {
            Thread.sleep(40)
            ready = condition()
        }
        assertTrue("Condition became true", ready)
    }

    private fun load(body: String = "<p>News fixture</p><div id='target'></div>", active: Boolean = true,
        origin: String = SitePolicy.ORIGIN, frame: Boolean = false) {
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync {
            presentation.setEnabled(active)
            web.webViewClient = object : WebViewClient() {
                override fun onPageCommitVisible(view: WebView, url: String?) { presentation.apply() }
                override fun onPageFinished(view: WebView, url: String?) { presentation.apply(); done.countDown() }
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val html = if (request.url.path == "/fixture/notice/frame")
                        "<html><head><script>$handlers</script></head><body>$form<img src='/fixture/notice/slow.svg'></body></html>"
                    else "<html><head><script>$handlers</script></head><body>$body</body></html>"
                    if (request.url.path == "/fixture/notice/slow.svg") {
                        frameReady.countDown()
                        imageRelease.await(15, TimeUnit.SECONDS)
                        return WebResourceResponse("image/svg+xml", "UTF-8", ByteArrayInputStream("<svg xmlns='http://www.w3.org/2000/svg'/>".toByteArray()))
                    }
                    return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray()))
                }
            }
            web.loadUrl("$origin/fixture/notice/main")
        }
        if (frame) assertTrue(frameReady.await(10, TimeUnit.SECONDS))
        else assertTrue(done.await(10, TimeUnit.SECONDS))
    }

    @Test fun lateNoticeHasNoVisibleFrameAndOriginalHandlersSubmitExactlyOnce() {
        load()
        eval("document.getElementById('target').innerHTML=${JSONObject.quote(form)};document.getElementById('attentionSt').onsubmit=submit")
        await { eval("getComputedStyle(document.getElementById('attentionSt')).opacity") == "\"0\"" }
        eval(automationSource)
        val probe = JSONObject(JSONTokener(eval("JSON.stringify(__univerAutomationV1.probe())")).nextValue() as String)
        assertTrue("Concealment must not defeat automation", probe.getBoolean("notice"))
        val token = JSONObject.quote(probe.getString("document"))
        val episode = JSONObject.quote(probe.getString("noticeEpisode"))
        assertEquals("\"ready\"", eval("__univerAutomationV1.prepareNotice($token,$episode)"))
        eval("__univerAutomationV1.submitNotice($token,$episode);__univerAutomationV1.submitNotice($token,$episode)")
        assertEquals("1", eval("window.posts")); assertEquals("1", eval("window.calcCalls"))
        assertEquals("\"-1\"", eval("document.querySelector('[name=\"attentions[]\"]').value"))
        assertEquals("0", eval("window.rawNoticeFrames||0"))
        instrumentation.runOnMainSync { presentation.setEnabled(false) }
        await { eval("getComputedStyle(document.getElementById('attentionSt')).opacity") == "\"1\"" }
        assertEquals("\"false\"", eval("document.getElementById('attentionSt').getAttribute('aria-hidden')"))
        assertEquals("false", eval("!!document.getElementById('univer-app-notice-style')"))
    }

    @Test fun sameOriginFrameNoticeStaysConcealedWhileItsImageIsLoading() {
        instrumentation.runOnMainSync { assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) }
        load("<p>News fixture</p><iframe id='child' src='/fixture/notice/frame'></iframe>", frame = true)
        await { eval("document.getElementById('child').contentDocument.readyState!=='loading'") == "true" }
        assertEquals("\"interactive\"", eval("document.getElementById('child').contentDocument.readyState"))
        assertEquals("\"0\"", eval("var w=document.getElementById('child').contentWindow;w.getComputedStyle(w.document.getElementById('attentionSt')).opacity"))
        assertEquals("0", eval("document.getElementById('child').contentWindow.rawNoticeFrames||0"))
        instrumentation.runOnMainSync { presentation.setEnabled(false) }
        await { eval("var w=document.getElementById('child').contentWindow;w.getComputedStyle(w.document.getElementById('attentionSt')).opacity") == "\"1\"" }
        instrumentation.runOnMainSync { presentation.setEnabled(true, retry = true) }
        await { eval("var w=document.getElementById('child').contentWindow;w.getComputedStyle(w.document.getElementById('attentionSt')).opacity") == "\"0\"" }
    }

    @Test fun timeoutRestoresFormAndExplicitRetryCanConcealItAgain() {
        instrumentation.runOnMainSync {
            presentation.close()
            presentation = NoticePresentation(web, timeoutMillis = 1500)
        }
        load()
        eval("document.getElementById('target').innerHTML=${JSONObject.quote(form)}")
        await { eval("getComputedStyle(document.getElementById('attentionSt')).opacity") == "\"0\"" }
        await { eval("getComputedStyle(document.getElementById('attentionSt')).opacity") == "\"1\"" }
        eval("document.getElementById('target').appendChild(document.createElement('p'))")
        assertEquals("false", eval("document.getElementById('attentionSt').hasAttribute('data-univer-app-notice-pending')"))
        instrumentation.runOnMainSync { presentation.setEnabled(true, retry = true) }
        await { eval("getComputedStyle(document.getElementById('attentionSt')).opacity") == "\"0\"" }
    }

    @Test fun manualModeUnknownConfirmationAndForeignOriginRemainReadable() {
        load(form, active = false)
        assertEquals("\"1\"", eval("getComputedStyle(document.getElementById('attentionSt')).opacity"))
        instrumentation.runOnMainSync { presentation.setEnabled(true) }
        await { eval("getComputedStyle(document.getElementById('attentionSt')).opacity") == "\"0\"" }
        eval("document.getElementById('attentionSt').action='/student/unrelatedConfirmation'")
        await { eval("getComputedStyle(document.getElementById('attentionSt')).opacity") == "\"1\"" }
        load(form, origin = "https://univer.kaznu.kz.evil.test")
        assertEquals("false", eval("!!window.__univerNoticePresentationV1"))
        assertEquals("\"1\"", eval("getComputedStyle(document.getElementById('attentionSt')).opacity"))
    }

    @Test fun ordinaryMainScreenStartupDoesNotShowFullscreenLoading() {
        scenario.onActivity { activity ->
            val model = ViewModelProvider(activity)[UniverViewModel::class.java]
            (field("automation").get(activity) as AutomationController).pause()
            val mainWeb = activity.findViewById<WebView>(R.id.web_view) ?: field("binding").get(activity).let {
                (it as com.univerflow.kaznu.databinding.ActivityMainBinding).webView
            }
            mainWeb.stopLoading()
            mainWeb.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                    WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream("<html><body>News fixture</body></html>".toByteArray()))
            }
            // Start this offline startup with a clean UI state, regardless of a real notice
            // that may have appeared on the saved account before this fixture replaced the client.
            (field("noticeLoading").get(activity) as com.univerflow.kaznu.ui.NoticeLoadingOverlay).dismiss()
            field("automationStatus").set(activity, com.univerflow.kaznu.web.AutomationStatus.IDLE)
            field("pageFailed").setBoolean(activity, false)
            field("webOpened").setBoolean(activity, false)
            model.guard.retry()
            MainActivity::class.java.getDeclaredMethod("render", AppState::class.java).apply { isAccessible = true }
                .invoke(activity, AppState(ready = true, hasCredentials = true))
            val binding = field("binding").get(activity) as com.univerflow.kaznu.databinding.ActivityMainBinding
            assertEquals("Normal startup uses only the thin progress indicator", View.GONE, binding.noticeLoadingOverlay.visibility)
            (field("automation").get(activity) as AutomationController).pause()
        }
    }

    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
}
