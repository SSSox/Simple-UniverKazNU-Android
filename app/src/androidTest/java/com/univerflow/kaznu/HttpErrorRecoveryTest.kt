package com.univerflow.kaznu

import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.univerflow.kaznu.web.AutomationController
import com.univerflow.kaznu.web.SitePolicy
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class HttpErrorRecoveryTest {
    private val fixtureUrl = SitePolicy.ORIGIN + "/fixture/http-recovery"

    @Test fun getRetrySurvivesTheLaterStatusProbeAndRecoversWithoutStartupRedirect() = withFixture("GET") { scenario, first, recovered, requests ->
        assertTrue(first.await(15, TimeUnit.SECONDS))
        scenario.onActivity { activity ->
            assertEquals(fixtureUrl, field("failedPageUrl").get(activity))
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.error_overlay).visibility)
            assertTrue(activity.findViewById<TextView>(R.id.error_message).text.contains("HTTP 503"))
            activity.findViewById<View>(R.id.error_retry).performClick()
        }
        assertTrue("Retry must reload the failed GET, not the language endpoint", recovered.await(15, TimeUnit.SECONDS))
        scenario.onActivity { activity ->
            assertEquals(View.GONE, activity.findViewById<View>(R.id.error_overlay).visibility)
            assertEquals(2, requests.get())
        }
    }

    @Test fun postFailureHasNoGetRetryUrlAndFormResubmissionIsCancelled() = withFixture("POST") { scenario, first, _, requests ->
        assertTrue(first.await(15, TimeUnit.SECONDS))
        val cancelled = CountDownLatch(1)
        scenario.onActivity { activity ->
            assertNull(field("failedPageUrl").get(activity))
            assertTrue(activity.findViewById<TextView>(R.id.error_message).text.contains("HTTP 503"))
            val web = activity.findViewById<WebView>(R.id.web_view)
            val handler = Handler(Looper.getMainLooper()) { message ->
                assertEquals("POST must never be resubmitted", 1, message.what)
                cancelled.countDown()
                true
            }
            web.webViewClient.onFormResubmission(web, handler.obtainMessage(1), handler.obtainMessage(2))
        }
        assertTrue(cancelled.await(5, TimeUnit.SECONDS))
        assertEquals("A server failure must not trigger automatic requests", 1, requests.get())
    }

    /** All fixture traffic stays local; only in-memory UI state changes, never the saved account. */
    private fun withFixture(method: String, verify: (ActivityScenario<MainActivity>, CountDownLatch, CountDownLatch, AtomicInteger) -> Unit) {
        val first = CountDownLatch(1)
        val recovered = CountDownLatch(1)
        val requests = AtomicInteger()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var ready = false
            while (!ready && System.nanoTime() < deadline) {
                scenario.onActivity { ready = ViewModelProvider(it)[UniverViewModel::class.java].state.value.ready }
                if (!ready) Thread.sleep(50)
            }
            assertTrue(ready)
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[UniverViewModel::class.java]
                model.guard.stop()
                (field("automation").get(activity) as AutomationController).pause()
                field("webOpened").setBoolean(activity, true)
                @Suppress("UNCHECKED_CAST")
                val state = UniverViewModel::class.java.getDeclaredField("mutableState").apply { isAccessible = true }
                    .get(model) as MutableStateFlow<AppState>
                state.value = state.value.copy(hasCredentials = true, paused = false)
                val web = activity.findViewById<WebView>(R.id.web_view)
                web.stopLoading()
                val original = web.webViewClient
                web.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        val main = request.isForMainFrame && request.url.toString() == fixtureUrl
                        val count = if (main) requests.incrementAndGet() else 0
                        return response(if (count == 1) 503 else 200)
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = original.shouldOverrideUrlLoading(view, request)
                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        original.onPageStarted(view, url, favicon)
                        // loadUrl is a GET but does not invoke shouldOverrideUrlLoading.
                        if (url == fixtureUrl) field("navigationGetUrl").set(activity, null)
                    }
                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                        original.onReceivedHttpError(view, request, response)
                    }
                    override fun onFormResubmission(view: WebView, dontResend: android.os.Message, resend: android.os.Message) =
                        original.onFormResubmission(view, dontResend, resend)
                    override fun onPageFinished(view: WebView, url: String?) {
                        if (url != fixtureUrl) return
                        if (requests.get() == 1) {
                            // Providers differ in callbacks for intercepted responses. Deliver the
                            // same callback explicitly, before the real pageFinished status probe.
                            original.onReceivedHttpError(view, request(method), response(503))
                        }
                        original.onPageFinished(view, url)
                        view.evaluateJavascript("0") {
                            if (requests.get() == 1) first.countDown() else recovered.countDown()
                        }
                    }
                }
                field("navigationGetUrl").set(activity, null)
                web.loadUrl(fixtureUrl)
            }
            verify(scenario, first, recovered, requests)
        }
    }

    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun response(status: Int) = WebResourceResponse("text/html", "UTF-8", status,
        if (status == 200) "OK" else "Service Unavailable", emptyMap(),
        ByteArrayInputStream("<html><body>Offline HTTP fixture</body></html>".toByteArray()))
    private fun request(method: String) = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(fixtureUrl)
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = method
        override fun getRequestHeaders() = emptyMap<String, String>()
    }
}
