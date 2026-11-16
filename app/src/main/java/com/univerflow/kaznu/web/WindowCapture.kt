package com.univerflow.kaznu.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Answers a page's new window with a throwaway WebView that only reports where the window wanted to go. A window that
 * never navigates would keep a renderer alive, so it closes by itself; the timer is a Handler's because a View's own
 * postDelayed waits for the view to attach, and this view never does.
 */
fun captureNewWindow(context: Context, transport: Message, timeoutMillis: Long = 3_000, onRetired: () -> Unit = {},
    onNavigation: (WebResourceRequest) -> Unit) {
    val popup = WebView(context)
    val timer = Handler(Looper.getMainLooper())
    var retired = false
    fun retire() {
        if (retired) return
        retired = true
        timer.removeCallbacksAndMessages(null)
        (popup.parent as? ViewGroup)?.removeView(popup)
        popup.destroy()
        onRetired()
    }
    popup.configureNavigationCapture()
    timer.postDelayed(::retire, timeoutMillis)
    popup.webViewClient = object : WebViewClient() {
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean { retire(); return true }
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            onNavigation(request)
            retire()
            return true
        }
    }
    (transport.obj as WebView.WebViewTransport).webView = popup
    transport.sendToTarget()
}
