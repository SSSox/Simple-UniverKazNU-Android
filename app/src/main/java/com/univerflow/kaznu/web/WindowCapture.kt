package com.univerflow.kaznu.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/**
 * Rules for the throwaway WebView that captures a new window's target URL. Pure checks: tested without Android.
 *
 * WebView omits shouldOverrideUrlLoading for form POSTs (and subresources), so the temporary WebView blocks every
 * request in shouldInterceptRequest before it can touch the network and routes only a direct main-frame GET.
 */
object WindowCapturePolicy {
    fun shouldRoute(isForMainFrame: Boolean, method: String?, isRedirect: Boolean): Boolean =
        isForMainFrame && !isRedirect && method.equals("GET", ignoreCase = true)

    fun shouldRoute(request: WebResourceRequest): Boolean =
        shouldRoute(request.isForMainFrame, request.method, request.isRedirect)

    fun blockedResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf())
    )
}

/** Coordinates the throwaway window's navigation/interception callbacks and retires it once. Pure: tested without Android. */
internal class WindowCaptureGate<T>(
    private val scheduleRetire: () -> Unit,
    private val retireNow: () -> Unit,
    private val onNavigation: (T) -> Unit,
) {
    @Volatile
    private var closed = false

    fun onOverrideUrlLoading(request: T, isForMainFrame: Boolean, method: String?, isRedirect: Boolean): Boolean {
        if (!closed && WindowCapturePolicy.shouldRoute(isForMainFrame, method, isRedirect)) {
            closed = true
            try { onNavigation(request) } finally { retireNow() }
        } else {
            closed = true
            retireNow()
        }
        return true
    }

    /** Called on WebView's IO thread: refuses the request and schedules retirement on the main thread without routing it. */
    fun onInterceptRequest(): Boolean {
        closed = true
        scheduleRetire()
        return true
    }

    fun onTimeoutOrCrash() {
        closed = true
        retireNow()
    }
}

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
        runCatching { popup.stopLoading() }
        (popup.parent as? ViewGroup)?.removeView(popup)
        popup.destroy()
        onRetired()
    }
    val gate = WindowCaptureGate<WebResourceRequest>(
        scheduleRetire = { timer.post(::retire) },
        retireNow = ::retire,
        onNavigation = onNavigation,
    )
    popup.configureNavigationCapture()
    timer.postDelayed({ gate.onTimeoutOrCrash() }, timeoutMillis)
    popup.webViewClient = object : WebViewClient() {
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            gate.onTimeoutOrCrash()
            return true
        }
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
            gate.onInterceptRequest()
            return WindowCapturePolicy.blockedResponse()
        }
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            gate.onOverrideUrlLoading(request, request.isForMainFrame, request.method, request.isRedirect)
    }
    (transport.obj as WebView.WebViewTransport).webView = popup
    transport.sendToTarget()
}
