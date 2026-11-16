package com.univerflow.kaznu.web

import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** The link window's own WebView profile ([LinkPolicy.PROFILE]); every call runs on the main thread. */
object LinkProfile {
    /** Separate profiles need a recent WebView; without them links open in the browser, never in the shared profile. */
    fun available(): Boolean = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)

    /** Puts a new [view] in the link profile; it must be the first call on the view, before settings and clients. */
    fun attach(view: WebView) {
        ProfileStore.getInstance().getOrCreateProfile(LinkPolicy.PROFILE)
        WebViewCompat.setProfile(view, LinkPolicy.PROFILE)
    }

    fun cookies(): android.webkit.CookieManager = ProfileStore.getInstance().getOrCreateProfile(LinkPolicy.PROFILE).cookieManager

    /**
     * Erases the cookies, sign-ins and site storage of the link window and reports true only once WebView confirmed the
     * cookies gone (and the browsing data, where WebView can report it). Univer's own profile is not touched.
     */
    suspend fun clear(): Boolean {
        if (!available()) return true
        val profile = ProfileStore.getInstance().getProfile(LinkPolicy.PROFILE) ?: return true
        val storage = if (WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
            withTimeoutOrNull(15_000) {
                suspendCancellableCoroutine { done ->
                    WebStorageCompat.deleteBrowsingData(profile.webStorage) { if (done.isActive) done.resume(true) }
                }
            } ?: false
        } else { profile.webStorage.deleteAllData(); true }
        val cookies = withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine { done -> profile.cookieManager.removeAllCookies { if (done.isActive) done.resume(true) } }
        } ?: false
        profile.cookieManager.flush()
        return storage && cookies
    }
}
