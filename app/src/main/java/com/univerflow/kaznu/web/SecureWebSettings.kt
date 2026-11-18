package com.univerflow.kaznu.web

import android.annotation.SuppressLint
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("SetJavaScriptEnabled")
fun WebView.configureSecureSettings() {
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        useWideViewPort = true
        loadWithOverviewMode = false
        builtInZoomControls = true
        displayZoomControls = false
        setSupportZoom(true)
        setSupportMultipleWindows(true)
        javaScriptCanOpenWindowsAutomatically = false
        allowFileAccess = false
        allowContentAccess = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        cacheMode = WebSettings.LOAD_DEFAULT
        if (Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true
    }
    CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(this@configureSecureSettings, false) }
}

/**
 * The offline file viewer: no JavaScript, storage, local files or cache, zoomable; every request is answered by
 * the app itself (see FileViewerActivity), so nothing in a document can reach the network.
 */
fun WebView.configureOfflineViewer() {
    settings.apply {
        javaScriptEnabled = false
        domStorageEnabled = false
        allowFileAccess = false
        allowContentAccess = false
        builtInZoomControls = true
        displayZoomControls = false
        setSupportZoom(true)
        useWideViewPort = true
        loadWithOverviewMode = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        cacheMode = WebSettings.LOAD_NO_CACHE
        setSupportMultipleWindows(false)
    }
}

/** A throwaway window that only reports the URL of a user gesture. It never runs script or reads local files. */
@SuppressLint("SetJavaScriptEnabled")
fun WebView.configureNavigationCapture() {
    settings.apply {
        javaScriptEnabled = false
        domStorageEnabled = false
        allowFileAccess = false
        allowContentAccess = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
    }
}

/**
 * The main window keeps the pages it left in memory, so Back shows one at once, as it was, without a request or an
 * adapter pass (about 150 ms instead of a second or more). Clearing the history (sign-out, a new login) drops them.
 */
fun WebView.enableBackForwardCache() {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.BACK_FORWARD_CACHE)) WebSettingsCompat.setBackForwardCacheEnabled(settings, true)
}

private val cookieWriter = Executors.newSingleThreadExecutor { Thread(it, "univer-cookies").apply { isDaemon = true } }
private val cookieWriteQueued = AtomicBoolean()

/**
 * Writes the cookie store to disk off the main thread: on the main thread the write held every finished page for 4 to
 * 47 ms. Requests made while one is queued share it. Leaving the app (onPause) still writes synchronously.
 */
fun flushCookiesSoon() {
    if (cookieWriteQueued.compareAndSet(false, true)) cookieWriter.execute {
        cookieWriteQueued.set(false)
        runCatching { CookieManager.getInstance().flush() }
    }
}
