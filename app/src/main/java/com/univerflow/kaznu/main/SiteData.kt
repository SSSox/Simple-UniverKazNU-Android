package com.univerflow.kaznu.main

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay
import com.univerflow.kaznu.ui.withDeadline
import com.univerflow.kaznu.web.evaluate
import com.univerflow.kaznu.web.configureSecureSettings
import com.univerflow.kaznu.web.jsString
import com.univerflow.kaznu.web.SitePolicy
import com.univerflow.kaznu.web.LinkProfile
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What the website leaves in the app's WebView profile, wiped when its session ends or the account changes. */
object SiteData {
    /**
     * Replaces the page with a blank one and drops its history (Back keeps pages in memory, and a later step may fail),
     * then erases DOM storage, the cache, TLS decisions and every cookie;
     * awaits the provider's deletion callback, or verifies DOM storage at the app's origins on older providers,
     * then removes and flushes cookies. [page] can be null after renderer loss; [context] is still required.
     * A step that gets no answer in its time fails the erase (it is not a cancellation), so the caller reports it and
     * the pending clear stays for the next attempt.
     */
    suspend fun erase(page: WebView?, context: Context? = page?.context) {
        if (page != null) {
            page.stopLoading()
            page.loadUrl("about:blank")
            // loadUrl is asynchronous. The old document's pagehide/unload handlers can still write storage until
            // the blank document commits; deleting earlier lets them restore the previous account's cache.
            withDeadline(5_000) {
                while (page.evaluate("location.href==='about:blank'&&document.readyState==='complete'") != "true") delay(40)
            }
            page.clearHistory()
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
            withDeadline(15_000) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    WebStorageCompat.deleteBrowsingData(WebStorage.getInstance()) { if (continuation.isActive) continuation.resume(Unit) }
                }
            }
        } else {
            // The framework API has no completion callback. Older providers can leave a live renderer's DOM cache
            // readable after deleteAllData returns. Clear and verify the app's exact origins in inert local documents.
            clearLegacyDomStorage(checkNotNull(context))
            WebStorage.getInstance().deleteAllData()
        }
        if (page != null) {
            page.clearCache(true)
            page.clearSslPreferences()
        }
        withDeadline(10_000) {
            suspendCancellableCoroutine { continuation ->
                CookieManager.getInstance().removeAllCookies {
                    CookieManager.getInstance().flush()
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        }
        // The link window's sites go with the account, as the Windows app replaces its web profile.
        try { LinkProfile.clear() } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { }
    }

    private suspend fun clearLegacyDomStorage(context: Context) {
        val cleaner = WebView(context)
        cleaner.configureSecureSettings()
        try {
            for (origin in listOf(SitePolicy.ORIGIN, "https://dl.kaznu.kz", "https://dl.kaznu.edu.kz")) withDeadline(5_000) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val target = "$origin/"
                    cleaner.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                            val expected = request.isForMainFrame && request.url.toString() == target
                            // Every request is answered here. The clearing document never goes to a server.
                            return WebResourceResponse("text/html", "UTF-8", if (expected) 200 else 403, if (expected) "OK" else "Blocked",
                                mapOf("Cache-Control" to "no-store"), ByteArrayInputStream("<html><body></body></html>".toByteArray()))
                        }
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                        override fun onPageFinished(view: WebView, url: String?) {
                            if (url != target || !continuation.isActive) return
                            view.evaluateJavascript("(function(){if(location.origin!==${jsString(origin)})return false;localStorage.clear();sessionStorage.clear();return localStorage.length===0&&sessionStorage.length===0;})()") {
                                if (continuation.isActive) {
                                    if (it == "true") continuation.resume(Unit) else continuation.resumeWithException(IOException("Site storage could not be cleared"))
                                }
                            }
                        }
                    }
                    cleaner.loadUrl(target)
                }
            }
        } finally { cleaner.stopLoading(); cleaner.destroy() }
    }
}
