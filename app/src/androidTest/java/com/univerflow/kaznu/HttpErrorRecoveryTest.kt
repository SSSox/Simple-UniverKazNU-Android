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
import androidx.webkit.WebViewFeature
import com.univerflow.kaznu.web.AutomationController
import com.univerflow.kaznu.web.SitePolicy
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class HttpErrorRecoveryTest {
    private val fixtureUrl = SitePolicy.ORIGIN + "/fixture/http-recovery"
    /** Reserved by RFC 2606: the name never resolves, so the load fails without anything being sent. */
    private val unreachableUrl = "https://fixture.invalid/"
    private val failed = CountDownLatch(1)

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

    @Test fun aLinkThatCannotConnectEndsOnTheErrorScreen() = withFixture("GET") { scenario, first, recovered, _ ->
        assertTrue(first.await(15, TimeUnit.SECONDS))
        scenario.onActivity { it.findViewById<View>(R.id.error_retry).performClick() }
        assertTrue(recovered.await(15, TimeUnit.SECONDS))
        scenario.onActivity { activity ->
            assertEquals(View.GONE, activity.findViewById<View>(R.id.error_overlay).visibility)
            // A page's own navigation: unlike loadUrl, it leaves view.url on this page until the error page commits.
            activity.findViewById<WebView>(R.id.web_view).evaluateJavascript("location.href='$unreachableUrl'", null)
        }
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        var shown = false
        while (!shown && System.nanoTime() < end) {
            Thread.sleep(100)
            scenario.onActivity { shown = it.findViewById<View>(R.id.error_overlay).visibility == View.VISIBLE }
        }
        assertTrue("The browser's error page must not stay on screen", shown)
    }

    @Test fun anAutomaticAttemptThatFailsAgainLeavesTheErrorScreenAlone() = withFixture("GET") { scenario, first, _, _ ->
        assertTrue(first.await(15, TimeUnit.SECONDS))
        val steady = AtomicBoolean(true)
        scenario.onActivity { activity ->
            val overlay = activity.findViewById<View>(R.id.error_overlay)
            overlay.removeCallbacks(field("pageRecoveryTask").get(activity) as Runnable) // This test makes the attempt itself.
        }
        Thread.sleep(400) // The error screen's own fade.
        scenario.onActivity { activity ->
            val overlay = activity.findViewById<View>(R.id.error_overlay)
            overlay.viewTreeObserver.addOnDrawListener { if (overlay.visibility != View.VISIBLE || overlay.alpha < 1f) steady.set(false) }
            MainActivity::class.java.getDeclaredMethod("loadPage", String::class.java, Boolean::class.java).apply { isAccessible = true }
                .invoke(activity, unreachableUrl, true)
        }
        assertTrue("The attempt failed", failed.await(15, TimeUnit.SECONDS))
        Thread.sleep(400)
        assertTrue(steady.get())
        scenario.onActivity { assertEquals(View.VISIBLE, it.findViewById<View>(R.id.error_overlay).visibility) }
    }

    @Test fun aPageBackRestoresAfterAFailedLoadIsNotTakenForTheFailure() = withFixture("GET", commits = true) { scenario, first, recovered, requests ->
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.BACK_FORWARD_CACHE))
        assertTrue(first.await(15, TimeUnit.SECONDS))
        scenario.onActivity { it.findViewById<View>(R.id.error_retry).performClick() }
        assertTrue(recovered.await(15, TimeUnit.SECONDS))
        scenario.onActivity { it.findViewById<WebView>(R.id.web_view).loadUrl(unreachableUrl) }
        assertTrue(failed.await(15, TimeUnit.SECONDS))
        waitFor(scenario) { it.findViewById<View>(R.id.error_overlay).visibility == View.VISIBLE }
        scenario.onActivity { it.findViewById<View>(R.id.error_back).performClick() }
        // Restored from memory: no request and no onPageFinished; the adapter's pass reports the page ready.
        waitFor(scenario) {
            it.findViewById<WebView>(R.id.web_view).url == fixtureUrl && !field("pageLoading").getBoolean(it) &&
                it.findViewById<View>(R.id.error_overlay).visibility != View.VISIBLE
        }
        assumeTrue("Back loaded the page again instead of restoring it", requests.get() == 2)
        // A link that brings no page (a file) ends its wait on this page, not on the old failure's error screen.
        scenario.onActivity { MainActivity::class.java.getDeclaredMethod("settlePage").apply { isAccessible = true }.invoke(it) }
        Thread.sleep(400)
        scenario.onActivity { assertNotEquals(View.VISIBLE, it.findViewById<View>(R.id.error_overlay).visibility) }
    }

    @Test fun backWaitsWhileTheSessionIsWiped() = withFixture("GET") { scenario, first, recovered, _ ->
        assertTrue(first.await(15, TimeUnit.SECONDS))
        scenario.onActivity { it.findViewById<View>(R.id.error_retry).performClick() }
        assertTrue(recovered.await(15, TimeUnit.SECONDS))
        val next = "$fixtureUrl/next"
        scenario.onActivity { it.findViewById<WebView>(R.id.web_view).loadUrl(next) }
        waitFor(scenario) { it.findViewById<WebView>(R.id.web_view).let { web -> web.url == next && web.canGoBack() } }
        scenario.onActivity {
            field("sessionChanging").setBoolean(it, true)
            it.onBackPressedDispatcher.onBackPressed()
            assertFalse("Back must not even ask the page", field("backPending").getBoolean(it))
        }
        Thread.sleep(1000)
        scenario.onActivity {
            assertEquals("The previous page must not come back during a sign-out", next, it.findViewById<WebView>(R.id.web_view).url)
            field("sessionChanging").setBoolean(it, false)
        }
    }

    @Test fun aBackPressedJustBeforeTheWipeDoesNotReturnDuringIt() = withFixture("GET") { scenario, first, recovered, _ ->
        assertTrue(first.await(15, TimeUnit.SECONDS))
        scenario.onActivity { it.findViewById<View>(R.id.error_retry).performClick() }
        assertTrue(recovered.await(15, TimeUnit.SECONDS))
        val next = "$fixtureUrl/next"
        scenario.onActivity { it.findViewById<WebView>(R.id.web_view).loadUrl(next) }
        waitFor(scenario) { it.findViewById<WebView>(R.id.web_view).let { web -> web.url == next && web.canGoBack() } }
        // The page answers Back's question later, after the sign-out has begun.
        scenario.onActivity {
            it.onBackPressedDispatcher.onBackPressed()
            assertTrue("Back asked the page first", field("backPending").getBoolean(it))
            field("sessionChanging").setBoolean(it, true)
        }
        Thread.sleep(1000)
        scenario.onActivity {
            assertFalse("the page answered", field("backPending").getBoolean(it))
            assertEquals("A Back pressed just before a sign-out must not bring the previous page back", next, it.findViewById<WebView>(R.id.web_view).url)
            field("sessionChanging").setBoolean(it, false)
        }
    }

    private fun waitFor(scenario: ActivityScenario<MainActivity>, check: (MainActivity) -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        var done = false
        while (!done && System.nanoTime() < end) {
            scenario.onActivity { done = check(it) }
            if (!done) Thread.sleep(100)
        }
        var state = ""
        if (!done) scenario.onActivity {
            state = "url=" + it.findViewById<WebView>(R.id.web_view).url + " pageLoading=" + field("pageLoading").getBoolean(it) +
                " overlay=" + it.findViewById<View>(R.id.error_overlay).visibility + " history=" +
                it.findViewById<WebView>(R.id.web_view).copyBackForwardList().let { list ->
                    (0 until list.size).joinToString(",") { i -> (if (i == list.currentIndex) "*" else "") + list.getItemAtIndex(i).url }
                }
        }
        assertTrue("still waiting: $state", done)
    }

    /**
     * All fixture traffic stays local; only in-memory UI state changes, never the saved account. [commits] also passes
     * the commit callback on, which a restored page needs to be adapted and shown.
     */
    private fun withFixture(method: String, commits: Boolean = false, verify: (ActivityScenario<MainActivity>, CountDownLatch, CountDownLatch, AtomicInteger) -> Unit) {
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
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        if (request.url.toString() == unreachableUrl) return null
                        val main = request.isForMainFrame && request.url.toString() == fixtureUrl
                        val count = if (main) requests.incrementAndGet() else 0
                        return response(if (count == 1) 503 else 200)
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                        request.url.toString() != unreachableUrl && original.shouldOverrideUrlLoading(view, request)
                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        // Off the trusted origin the app stops the load, and here it has to fail: it is told of a trusted one.
                        if (url == unreachableUrl) { original.onPageStarted(view, fixtureUrl, favicon); return }
                        original.onPageStarted(view, url, favicon)
                        // loadUrl is a GET but does not invoke shouldOverrideUrlLoading.
                        if (url == fixtureUrl) field("navigationGetUrl").set(activity, null)
                    }
                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                        original.onReceivedHttpError(view, request, response)
                    }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                        original.onReceivedError(view, request, error)
                        if (request.isForMainFrame) failed.countDown()
                    }
                    override fun onPageCommitVisible(view: WebView, url: String?) { if (commits) original.onPageCommitVisible(view, url) }
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
