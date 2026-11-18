package com.univerflow.kaznu

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** The site's WebView answers the Google Analytics tag locally; the request never reaches the network. */
@RunWith(AndroidJUnit4::class)
class TrackerBlockTest {
    private fun asset(url: String) = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(url)
        override fun isForMainFrame() = false
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = emptyMap<String, String>()
    }

    @Test fun theSitesAnalyticsTagIsAnsweredLocallyAndUniverAssetsLoadAsBefore() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val web = activity.findViewById<WebView>(R.id.web_view)
                listOf("https://www.googletagmanager.com/gtag/js?id=G-TEST", "https://www.google-analytics.com/g/collect?v=2&tid=G-TEST",
                    "https://region1.analytics.google.com/g/collect?v=2").forEach {
                    val reply = web.webViewClient.shouldInterceptRequest(web, asset(it))
                    assertEquals(it, 204, reply?.statusCode)
                    assertEquals(it, -1, reply?.data?.use { body -> body.read() })
                }
                assertNull(web.webViewClient.shouldInterceptRequest(web, asset("https://univer.kaznu.kz/assets/app.js")))
                assertNull(web.webViewClient.shouldInterceptRequest(web, asset("https://farabi.university/logo.png")))
            }
        }
    }
}
