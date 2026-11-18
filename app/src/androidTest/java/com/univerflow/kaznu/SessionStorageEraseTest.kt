package com.univerflow.kaznu

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.main.SiteData
import com.univerflow.kaznu.ui.DownloadsActivity
import com.univerflow.kaznu.web.configureSecureSettings
import com.univerflow.kaznu.web.enableBackForwardCache
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Only a local synthetic cache key in an isolated fixture WebView. */
@RunWith(AndroidJUnit4::class)
class SessionStorageEraseTest {
    @Test fun anUnloadingDocumentCannotRestoreThePreviousAccountsLocalStorage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scenario = ActivityScenario.launch<DownloadsActivity>(DownloadsActivity.intent(instrumentation.targetContext, AppLanguage.RUSSIAN))
        lateinit var web: WebView
        var loaded = CountDownLatch(1)
        fun eval(source: String): String {
            val callback = CountDownLatch(1)
            var result = "null"
            instrumentation.runOnMainSync { web.evaluateJavascript(source) { result = it; callback.countDown() } }
            assertTrue(callback.await(10, TimeUnit.SECONDS)); return result
        }
        fun loadFixture(url: String = "https://univer.kaznu.kz/fixture") {
            loaded = CountDownLatch(1)
            instrumentation.runOnMainSync { web.loadUrl(url) }
            assertTrue(loaded.await(10, TimeUnit.SECONDS))
        }
        try {
            scenario.onActivity { activity ->
                // As in the main window: Back keeps left pages in memory, which the erase must drop too.
                web = WebView(activity); web.configureSecureSettings(); web.enableBackForwardCache(); activity.setContentView(web)
                web.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                        WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream("<html><body>Local session fixture</body></html>".toByteArray()))
                    override fun onPageFinished(view: WebView, url: String?) { if (url?.startsWith("https://univer.kaznu.kz/fixture") == true) loaded.countDown() }
                }
            }
            loadFixture()
            loadFixture("https://univer.kaznu.kz/fixture/next")
            instrumentation.runOnMainSync { assertTrue(web.canGoBack()) }
            // The document the erase unloads writes the old account's key on its way out.
            assertEquals("true", eval("localStorage.setItem('qa_previous_account','before');addEventListener('pagehide',function(){localStorage.setItem('qa_previous_account','after');});true"))
            val erased = CountDownLatch(1)
            var failure: Throwable? = null
            scenario.onActivity { activity -> activity.lifecycleScope.launch {
                try { SiteData.erase(web) } catch (error: Throwable) { failure = error } finally { erased.countDown() }
            } }
            assertTrue(erased.await(15, TimeUnit.SECONDS))
            assertNull(failure)
            instrumentation.runOnMainSync { assertFalse("Old account pages are not left in Back history", web.canGoBack()) }
            loadFixture()
            assertEquals("No cache of the previous account after erase", "null", eval("localStorage.getItem('qa_previous_account')"))
        } finally {
            instrumentation.runOnMainSync { web.destroyFixture() }
            scenario.close()
        }
    }
}
