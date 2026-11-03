package com.univerflow.kaznu

import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient

/** Fixture teardown follows WebView.destroy's contract; run on the UI thread. */
internal fun WebView.destroyFixture() {
    (parent as? ViewGroup)?.removeView(this)
    webChromeClient = null
    webViewClient = WebViewClient()
    stopLoading()
    destroy()
}
