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
