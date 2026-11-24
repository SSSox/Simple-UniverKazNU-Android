package com.univerflow.kaznu

import android.os.Build
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WebViewRecoveryTest {
    @Test fun aNewWindowThatNeverNavigatesClosesByItself() {
        val retired = java.util.concurrent.CountDownLatch(1)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val parent = WebView(activity)
                val message = android.os.Handler(android.os.Looper.getMainLooper()) { true }.obtainMessage()
                message.obj = parent.WebViewTransport()
                com.univerflow.kaznu.web.captureNewWindow(activity, message, timeoutMillis = 50, onRetired = retired::countDown) {
                    fail("Nothing navigated")
                }
                parent.postDelayed({ parent.destroy() }, 1_000)
            }
            assertTrue("The unattached window must not wait for a layout pass that never comes", retired.await(2, TimeUnit.SECONDS))
        }
    }

    @Test fun aNewWindowFormPostIsBlockedBeforeTransportAndNeverRouted() {
        val retired = java.util.concurrent.CountDownLatch(1)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val parent = WebView(activity)
                val transport = parent.WebViewTransport()
                val message = android.os.Handler(android.os.Looper.getMainLooper()) { true }.obtainMessage()
                message.obj = transport
                com.univerflow.kaznu.web.captureNewWindow(activity, message, timeoutMillis = 5_000, onRetired = retired::countDown) {
                    fail("A POST in a new window must never be routed")
                }
                val popup = checkNotNull(transport.webView)
                val post = object : android.webkit.WebResourceRequest {
                    override fun getUrl() = android.net.Uri.parse("https://external.invalid/collect")
                    override fun isForMainFrame() = true
                    override fun isRedirect() = false
                    override fun hasGesture() = true
                    override fun getMethod() = "POST"
                    override fun getRequestHeaders() = emptyMap<String, String>()
                }
                val blocked = popup.webViewClient.shouldInterceptRequest(popup, post)
                assertEquals(403, blocked?.statusCode)
                blocked?.data?.close()
                parent.postDelayed({ parent.destroy() }, 1_000)
            }
            assertTrue("Blocked popup request must retire the temporary WebView", retired.await(2, TimeUnit.SECONDS))
        }
    }

    @Test fun rendererFailureShowsNativeRecoveryAndRecreatesTheSignedInScreenBySelf() {
        org.junit.Assume.assumeTrue(Build.VERSION.SDK_INT >= 26)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Thread.sleep(1000)
            var automatic = false
            scenario.onActivity { activity ->
                val state = ViewModelProvider(activity)[UniverViewModel::class.java].state.value
                automatic = state.hasCredentials && !state.paused && !state.locked
                val web = activity.findViewById<WebView>(R.id.web_view)
                val handled = web.webViewClient.onRenderProcessGone(web, object : RenderProcessGoneDetail() {
                    override fun didCrash() = true
                    override fun rendererPriorityAtExit() = WebView.RENDERER_PRIORITY_IMPORTANT
                })
                assertTrue(handled)
                assertNull(web.parent)
                assertFalse(activity.isFinishing)
                val text = activity.findViewById<TextView>(R.id.error_message).text.toString()
                assertTrue(text.isNotBlank())
                // Navigation and settings stay reachable from the app bar while the page is unavailable.
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.toolbar).visibility)
            }
            // With a saved account the screen is recreated with a new page by itself; without one it waits for the user.
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var recovered = false
            while (!recovered && System.nanoTime() < end) {
                Thread.sleep(250)
                scenario.onActivity { recovered = it.findViewById<WebView>(R.id.web_view)?.parent != null }
            }
            assertEquals(automatic, recovered)
        }
    }
}
