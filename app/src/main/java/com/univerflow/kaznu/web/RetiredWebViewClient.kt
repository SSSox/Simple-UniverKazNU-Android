package com.univerflow.kaznu.web

import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Takes over from a page's client while its WebView is being destroyed. A plain WebViewClient would let a renderer
 * that dies in that moment take the whole app down; the page is going away anyway, so the loss needs no handling.
 */
class RetiredWebViewClient : WebViewClient() {
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean = true
}
