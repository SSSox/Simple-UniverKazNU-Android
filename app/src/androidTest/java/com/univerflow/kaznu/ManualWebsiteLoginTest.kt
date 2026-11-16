package com.univerflow.kaznu

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.univerflow.kaznu.main.SiteData
import com.univerflow.kaznu.web.SitePolicy
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Run on an empty QA profile: the production login screen and policies, with no network or stored account. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class ManualWebsiteLoginTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun until(label: String, condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < end) { if (condition()) return; Thread.sleep(100) }
        fail(label)
    }

    private fun evaluate(web: WebView, source: String): String {
        val latch = CountDownLatch(1)
        var result = "null"
        instrumentation.runOnMainSync { web.evaluateJavascript(source) { result = it; latch.countDown() } }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        return result
    }

    @Test fun manualEntryDoesNotSaveCredentialsAndOnlyAnExplicitExternalNavigationOpensAnotherApp() {
        val context = instrumentation.targetContext
        assumeTrue("Use an isolated empty profile", context.noBackupFilesDir.listFiles().orEmpty().none { it.extension == "enc" })
        var cookies = true
        instrumentation.runOnMainSync { cookies = CookieManager.getInstance().hasCookies() }
        assumeTrue("Use an isolated empty cookie store", !cookies)
        val monitor = instrumentation.addMonitor(IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_BROWSABLE); addDataScheme("https")
        }, Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var started = false
        lateinit var web: WebView
        lateinit var client: WebViewClient
        val blockedRedirect = AtomicBoolean()
        try {
            until("Manual sign-in choice is visible") {
                var ready = false
                scenario.onActivity { ready = it.findViewById<android.view.View>(R.id.manual_login)?.isShown == true }
                ready
            }
            scenario.onActivity { activity ->
                web = activity.findViewById(R.id.web_view)
                client = web.webViewClient
                web.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                        WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream("""<html><body><form id="login_frm" action="/user/login" method="post">
                            <label>Логин<input type="text" name="fixture_login"></label><label>Пароль<input type="password" name="fixture_password"></label>
                            <button type="submit">Войти</button></form></body></html>""".toByteArray()))
                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) = client.onPageStarted(view, url, favicon)
                    override fun onPageCommitVisible(view: WebView, url: String?) = client.onPageCommitVisible(view, url)
                    override fun onPageFinished(view: WebView, url: String?) = client.onPageFinished(view, url)
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        client.shouldOverrideUrlLoading(view, request).also { if (request.url.host == "example.org" && it && !request.hasGesture()) blockedRedirect.set(true) }
                }
                activity.findViewById<android.view.View>(R.id.manual_login).performClick()
                started = true
            }
            until("The manual browser opens without native credentials") {
                var ready = false
                scenario.onActivity { activity ->
                    val model = ViewModelProvider(activity)[UniverViewModel::class.java]
                    ready = model.state.value.manualLogin && !model.state.value.hasCredentials && model.credentials == null && SitePolicy.trusted(web.url)
                }
                ready
            }
            assertFalse("Manual entry never creates a protected password record", File(context.noBackupFilesDir, "univer.credentials.v1.enc").exists())
            until("The fixture document has committed before testing its navigation") {
                evaluate(web, "document.readyState==='complete'&&!!document.querySelector('#login_frm input[type=password]')") == "true"
            }
            evaluate(web, "setTimeout(function(){location.href='https://example.org/lecture';},200);true")
            until("The production page client blocks an automatic external navigation") { blockedRedirect.get() }
            assertEquals("No browser or other app was launched by the script", 0, monitor.hits)
            scenario.onActivity {
                val request = object : WebResourceRequest {
                    override fun getUrl() = Uri.parse("https://example.org/lecture")
                    override fun isForMainFrame() = true
                    override fun isRedirect() = false
                    override fun hasGesture() = true
                    override fun getMethod() = "GET"
                    override fun getRequestHeaders() = emptyMap<String, String>()
                }
                assertTrue(client.shouldOverrideUrlLoading(web, request))
            }
            assertEquals("The same safe address is allowed on a user action (the monitor intercepts it locally)", 1, monitor.hits)
        } finally {
            if (started) {
                val cleared = CountDownLatch(1)
                scenario.onActivity { activity -> activity.lifecycleScope.launch {
                    val model = ViewModelProvider(activity)[UniverViewModel::class.java]
                    try { model.endSession(false); SiteData.erase(web); model.sessionCleared() } finally { cleared.countDown() }
                } }
                assertTrue("The isolated test session is cleared", cleared.await(10, TimeUnit.SECONDS))
            }
            scenario.close()
            instrumentation.removeMonitor(monitor)
        }
    }
}
