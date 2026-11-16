package com.univerflow.kaznu

import android.app.Activity
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.ui.LinkViewerActivity
import com.univerflow.kaznu.web.LinkPolicy
import com.univerflow.kaznu.web.LinkProfile
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The link window's profile keeps its own cookies, clears them alone, and hands back only a tapped Univer page. */
@RunWith(AndroidJUnit4::class)
class LinkWindowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Test fun cookiesOfTheLinkWindowAndOfUniverNeverMeetAndClearingRemovesOnlyTheWindowsOnes() {
        assumeTrue("WebView without separate profiles: links open in the browser", LinkProfile.available())
        ActivityScenario.launch<LinkViewerActivity>(LinkViewerActivity.intent(context, "https://example.org/", AppLanguage.RUSSIAN)).use { scenario ->
            var profile = ""
            scenario.onActivity { activity ->
                val web = activity.findViewById<FrameLayout>(R.id.link_frame).getChildAt(0) as WebView
                profile = WebViewCompat.getProfile(web).name
            }
            assertEquals(LinkPolicy.PROFILE, profile)
            main {
                LinkProfile.cookies().setCookie("https://links.example/", "window=1")
                CookieManager.getInstance().setCookie("https://univer.example/", "univer=1")
                assertNull(CookieManager.getInstance().getCookie("https://links.example/"))
                assertNull(LinkProfile.cookies().getCookie("https://univer.example/"))
                assertEquals("window=1", LinkProfile.cookies().getCookie("https://links.example/"))
            }
            val done = CountDownLatch(1)
            var cleared = false
            scenario.onActivity { activity -> activity.lifecycleScope.launch { cleared = LinkProfile.clear(); done.countDown() } }
            assertTrue("clearing answered", done.await(30, TimeUnit.SECONDS))
            assertTrue(cleared)
            main {
                assertNull(LinkProfile.cookies().getCookie("https://links.example/"))
                assertEquals("univer=1", CookieManager.getInstance().getCookie("https://univer.example/"))
                CookieManager.getInstance().setCookie("https://univer.example/", "univer=; Max-Age=0")
            }
        }
    }

    @Test fun aTappedUniverSectionGoesBackAsTheResultAndAScriptedOneStaysBlocked() {
        assumeTrue(LinkProfile.available())
        val section = "https://univer.kaznu.kz/student/myschedule/"
        ActivityScenario.launchActivityForResult<LinkViewerActivity>(LinkViewerActivity.intent(context, "https://example.org/", AppLanguage.RUSSIAN)).use { scenario ->
            scenario.onActivity { activity ->
                val web = activity.findViewById<FrameLayout>(R.id.link_frame).getChildAt(0) as WebView
                assertTrue(web.webViewClient.shouldOverrideUrlLoading(web, request(section, gesture = false)))
                assertFalse("a script does not close the window", activity.isFinishing)
                assertTrue(web.webViewClient.shouldOverrideUrlLoading(web, request("intent://x#Intent;end", gesture = true)))
                assertFalse(activity.isFinishing)
                assertTrue(web.webViewClient.shouldOverrideUrlLoading(web, request(section, gesture = true)))
            }
            assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
            assertEquals(section, scenario.result.resultData.getStringExtra(LinkViewerActivity.RESULT_URL))
        }
    }

    @Test fun theWindowRefusesAnAddressItMustNotShow() {
        for (url in listOf("https://univer.kaznu.kz/student/umkd/", "http://lib.kaznu.kz/", "file:///sdcard/a.pdf")) {
            ActivityScenario.launch<LinkViewerActivity>(LinkViewerActivity.intent(context, url, AppLanguage.RUSSIAN)).use { scenario ->
                assertEquals(url, Lifecycle.State.DESTROYED, scenario.state)
            }
        }
    }

    private fun request(url: String, gesture: Boolean) = object : WebResourceRequest {
        override fun getUrl() = android.net.Uri.parse(url)
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = gesture
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = emptyMap<String, String>()
    }
}
