package com.univerflow.kaznu.main

import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Message
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.isVisible
import com.univerflow.kaznu.R
import com.univerflow.kaznu.UniverViewModel
import com.univerflow.kaznu.ui.DocumentViewer
import com.univerflow.kaznu.ui.NoticeLoadingOverlay
import com.univerflow.kaznu.web.AutomationController
import com.univerflow.kaznu.web.AutomationStatus
import com.univerflow.kaznu.web.BviStub
import com.univerflow.kaznu.web.ChannelGuard
import com.univerflow.kaznu.web.MobilePageAdapter
import com.univerflow.kaznu.web.NoticePresentation
import com.univerflow.kaznu.web.PageCover
import com.univerflow.kaznu.web.PageScripts
import com.univerflow.kaznu.web.ShortcutBridge
import com.univerflow.kaznu.web.SitePolicy
import com.univerflow.kaznu.web.Trackers
import com.univerflow.kaznu.web.flushCookiesSoon
import com.univerflow.kaznu.web.httpErrorMessage

/** Navigation policy, page lifecycle, errors and renderer loss of the website's WebView. */
class PageClient(private val host: Host) : WebViewClient() {
    /** What the client needs of the screen: its collaborators, the page state it shares, and the screen's own actions. */
    interface Host {
        val model: UniverViewModel
        val automation: AutomationController
        val mobilePage: MobilePageAdapter
        val shortcutBridge: ShortcutBridge
        val channel: ChannelGuard
        val notices: NoticeFlow
        val documents: DocumentViewer
        val noticeLoading: NoticeLoadingOverlay
        val noticePresentation: NoticePresentation
        val cover: PageCover
        val webView: WebView
        val isDestroyed: Boolean
        val sessionChanging: Boolean
        var navigationGetUrl: String?
        var pageEpoch: Long
        var pageFailed: Boolean
        var pageTitle: String?
        var pageLoading: Boolean
        var failedPageUrl: String?
        var rendererUnavailable: Boolean
        var lostPageUrl: String?
        val pageSkeletonVisible: Boolean

        fun onLogoutNavigation(request: WebResourceRequest): Boolean
        fun onWebsiteLanguageNavigation(request: WebResourceRequest): Boolean
        fun route(url: String, userGesture: Boolean): Boolean
        fun openLink(url: String)
        fun leavePage(url: String)
        fun settlePage()
        fun cancelPageAbandon()
        fun showPageSkeleton(url: String?)
        fun showProgress(visible: Boolean)
        fun hideErrorOverlay()
        fun renderHeading()
        fun updatePageTitle()
        fun showError(id: Int, httpStatus: Int? = null)
        fun recoverRenderer()
    }

    /** Until when (elapsed realtime) the online test's redirect may follow the student's tap; main thread only. */
    private var onlineTestUntil = 0L

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        // WebView omits shouldOverrideUrlLoading for POST: it must not send a form to another origin.
        if (request.isForMainFrame && !SitePolicy.trusted(request.url.toString()))
            return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(),
                java.io.ByteArrayInputStream(byteArrayOf()))
        if (Trackers.blocked(request.url.host)) return Trackers.emptyReply()
        val address = request.url.toString()
        if (address.contains("bvi", ignoreCase = true) && BviStub.stubs(address, request.method, host.model.state.value.mobileLayout,
                CookieManager.getInstance().getCookie(SitePolicy.ORIGIN))) return BviStub.reply()
        return null
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return !SitePolicy.trusted(request.url.toString())
        if (host.onLogoutNavigation(request)) return true
        // «Онлайн тест»: the student's tap arms the one server redirect to the test portal that answers it; the portal opens
        // like a tapped link (the link window or the browser), and the page that never left stops showing its cover.
        val address = request.url.toString()
        if (SitePolicy.onlineTestExit(address, request.method, request.isRedirect, SystemClock.elapsedRealtime() < onlineTestUntil)) {
            onlineTestUntil = 0L; host.settlePage(); host.openLink(address); return true
        }
        if (SitePolicy.onlineTestTap(address, request.method, request.hasGesture(), request.isRedirect)) onlineTestUntil = SystemClock.elapsedRealtime() + ONLINE_TEST_WINDOW_MS
        else if (request.hasGesture() && !request.isRedirect) onlineTestUntil = 0L
        // The site sends a request of an ended session to its login form: reopen that page after the login.
        if (SitePolicy.loginPage(request.url.toString()) && (request.isRedirect || !request.hasGesture()))
            host.navigationGetUrl?.takeIf(SitePolicy::resumable)?.let(host.automation::resumeAfterLogin)
        else if (request.hasGesture() && !request.isRedirect) host.automation.resumeAfterLogin(null)
        host.navigationGetUrl = request.url.toString().takeIf { request.method.equals("GET", true) && SitePolicy.trusted(it) }
        if (!host.sessionChanging && host.onWebsiteLanguageNavigation(request)) return false
        // A tap on Главная or the news pager opens the direct news list; the login redirect still passes
        // through the site's notice route.
        if (!host.sessionChanging && request.hasGesture() && !request.isRedirect) {
            SitePolicy.newsShortcut(request.url.toString())?.takeIf { it != request.url.toString() }?.let {
                host.navigationGetUrl = it; host.leavePage(it); view.loadUrl(it); return true
            }
        }
        return host.route(request.url.toString(), SitePolicy.externalNavigation(request.url.toString(), request.hasGesture(), request.isRedirect, request.method)).also { routed ->
            if (!routed && !host.sessionChanging && request.hasGesture() && !request.isRedirect) host.leavePage(request.url.toString())
        }
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        host.pageEpoch++
        host.navigationGetUrl = host.navigationGetUrl?.takeIf { it == url }
        host.automation.pageStarted()
        host.cancelPageAbandon()
        host.cover.started()
        // A tap already named the page coming; the redirects on the way there must not change its shape.
        if (!host.pageSkeletonVisible) host.showPageSkeleton(url)
        host.mobilePage.pageStarted(url)
        if (SitePolicy.trusted(url)) {
            if (SitePolicy.readNotice(url) &&
                host.model.state.value.hasCredentials && !host.model.state.value.paused && !host.model.guard.blocked)
                host.noticeLoading.status(AutomationStatus.NOTICE_CONFIRMING)
            host.showProgress(true)
            host.pageFailed = false
            host.pageTitle = null; host.pageLoading = true
            host.renderHeading()
        } else if (url != "about:blank") view.stopLoading()
    }

    override fun onPageFinished(view: WebView, url: String?) {
        if (url != view.url) return
        host.noticePresentation.apply()
        if (!host.cover.leaving) host.showProgress(false)
        flushCookiesSoon()
        host.mobilePage.apply(host.model.state.value.mobileLayout, host.model.state.value.language)
        if (!SitePolicy.trusted(url) || host.sessionChanging) return
        val finishedEpoch = host.pageEpoch
        view.evaluateJavascript(PageScripts.STATUS) { result ->
            if (host.isDestroyed || finishedEpoch != host.pageEpoch || url != view.url || host.sessionChanging) return@evaluateJavascript
            // The request callback knows its method and retry URL. A later status probe
            // must not erase that information or replace a network/TLS failure.
            if (host.pageFailed) return@evaluateJavascript
            val status = result.toIntOrNull() ?: 0
            if (status >= 400) {
                host.failedPageUrl = host.navigationGetUrl
                host.showError(httpErrorMessage(status), status)
            } else {
                pageArrived()
                host.cover.loaded()
                host.model.pageRecovery.reset()
                host.automation.pageFinished()
                if (!host.model.state.value.mobileLayout) host.updatePageTitle()
            }
        }
    }

    override fun onPageCommitVisible(view: WebView, url: String?) {
        pageArrived()
        host.noticePresentation.apply()
        host.mobilePage.apply(host.model.state.value.mobileLayout, host.model.state.value.language)
    }

    /** A load that fails starts like any other, so only a committed document takes the error screen away. */
    private fun pageArrived() {
        if (!host.pageFailed) host.hideErrorOverlay()
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        // A tapped link still has the old address in view.url here, and the browser's own error page comes next.
        if (request.isForMainFrame) {
            host.failedPageUrl = request.url.toString().takeIf { request.method.equals("GET", true) && SitePolicy.trusted(it) }
            host.showError(R.string.network_error)
        }
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
        if (request.isForMainFrame && request.url.toString() == view.url && response.statusCode >= 400) {
            host.failedPageUrl = request.url.toString().takeIf { request.method.equals("GET", true) && SitePolicy.trusted(it) }
            host.showError(httpErrorMessage(response.statusCode), response.statusCode)
        }
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
        // A tapped link never commits after this: without the error screen it would just wait for the cover's timeout.
        if (error.url == view.url || error.url == host.navigationGetUrl) {
            host.failedPageUrl = host.navigationGetUrl?.takeIf(SitePolicy::trusted)
            host.showError(R.string.tls_error)
        }
    }

    override fun onFormResubmission(view: WebView, dontResend: Message, resend: Message) { dontResend.sendToTarget() }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        host.noticeLoading.dismiss()
        host.noticePresentation.close()
        host.mobilePage.close()
        host.shortcutBridge.close()
        host.channel.close()
        host.notices.rendererGone()
        host.rendererUnavailable = true
        host.pageEpoch++
        host.lostPageUrl = view.url?.takeIf(SitePolicy::resumable)
        host.documents.close(false)
        host.automation.close() // Detaches an attempt in flight; the attempt budgets stay.
        host.webView.isVisible = false
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
        host.failedPageUrl = null
        host.showError(R.string.webview_crashed)
        // A recreated screen reopens the section by a GET; a POST is never replayed.
        host.recoverRenderer()
        return true
    }

    private companion object {
        const val ONLINE_TEST_WINDOW_MS = 30_000L
    }
}
