package com.univerflow.kaznu

import android.net.Uri
import android.webkit.WebResourceRequest
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.univerflow.kaznu.web.LogoutNavigation
import com.univerflow.kaznu.web.SitePolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LogoutNavigationTest {
    @Test fun onlyUserTapOnExactMainFrameLogoutRouteEndsSession() {
        var calls = 0
        val navigation = LogoutNavigation { calls++ }
        fun request(url: String, gesture: Boolean = true, frame: Boolean = true, method: String = "GET") = object : WebResourceRequest {
            override fun getUrl() = Uri.parse(url)
            override fun isForMainFrame() = frame
            override fun isRedirect() = false
            override fun hasGesture() = gesture
            override fun getMethod() = method
            override fun getRequestHeaders() = emptyMap<String, String>()
        }
        assertTrue(navigation.onNavigation(request(SitePolicy.ORIGIN + "/user/logout")))
        assertEquals(1, calls)
        listOf(request(SitePolicy.ORIGIN + "/user/logout", gesture = false),
            request(SitePolicy.ORIGIN + "/user/logout", frame = false),
            request(SitePolicy.ORIGIN + "/user/logout", method = "POST"),
            request("https://external.test/user/logout"), request(SitePolicy.ORIGIN + "/user/logout/extra"),
            request(SitePolicy.ORIGIN + "/user/login")).forEach { assertFalse(navigation.onNavigation(it)) }
        assertEquals(1, calls)
    }
}
