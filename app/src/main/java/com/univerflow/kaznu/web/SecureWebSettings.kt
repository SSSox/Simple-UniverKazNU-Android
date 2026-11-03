package com.univerflow.kaznu.web

import android.annotation.SuppressLint
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

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
