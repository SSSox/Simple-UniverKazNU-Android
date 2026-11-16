package com.univerflow.kaznu.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.print.PrintManager
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import android.view.View
import com.univerflow.kaznu.R
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.domain.TextZoom
import com.univerflow.kaznu.web.*

/** A print/service window keeps the source WebView and its scroll position intact. */
class DocumentViewer(
    private val context: Context,
    private val host: FrameLayout,
    private val automationFactory: (WebView) -> AutomationController,
    private val navigate: (String) -> Unit,
    private val download: (String, String, String?, String?) -> Unit,
    private val onClosed: () -> Unit,
    private val languageNavigation: (WebResourceRequest) -> Boolean = { false },
    private val sessionNavigation: (WebResourceRequest) -> Boolean = { false },
    private val serviceAutomationFactory: ((WebView) -> AutomationController)? = null,
    private val serviceRetry: () -> Unit = {},
    /** Moodle upload forms: the system file picker; false lets WebView cancel the input. */
    private val chooseFiles: (ValueCallback<Array<android.net.Uri>>, WebChromeClient.FileChooserParams) -> Boolean = { _, _ -> false },
    /** A Moodle hand-off reached Moodle again (files refused by an ended session can be fetched now). */
    private val serviceSignedIn: () -> Unit = {},
    /** The user signed out of Moodle with its own Log out; the window closes instead of signing in again. */
    private val serviceSignedOut: () -> Unit = {},
) {
    /** The open window's frame (bar, loading line, error panel); null while no window is open. */
    private var panel: DocumentPanel? = null
    private var paper: PageSkeleton? = null
    private var failed = false
    private var requestedUrl = "about:blank"
    private var failureMessageId = R.string.document_error_message
    private var failureHttpStatus: Int? = null
    private var pageEpoch = 0L
    private var adapter: MobilePageAdapter? = null
    private var automation: AutomationController? = null
    var isService = false
        private set
    private var serviceEntry = SingleSignOnPolicy.ENTRY
    private var servicePending = false
    private var serviceTimeout: Runnable? = null
    private fun startServiceTimeout(web: WebView) {
        serviceTimeout?.let(web::removeCallbacks)
        servicePending = true
        serviceTimeout = Runnable {
            if (web === webView && servicePending && !failed) failure(R.string.service_login_failed)
        }.also { web.postDelayed(it, 90_000) }
    }
    // Moodle's own sign-in page means its session ended: sign in again through Univer automatically.
    // Only a hand-off that keeps failing (three times within two minutes) stops for an explicit retry.
    private val serviceReentries = ArrayDeque<Long>()
    // The Moodle page the student was on; reopened after its session is renewed (or the Activity recreated).
    private var serviceLastPage: String? = null
    private var serviceResume: String? = null
    private var serviceArrived = false
    private var serviceLoggedOut = false
    // Univer may land on its notice or news instead of Moodle after a login: continue the hand-off.
    private var serviceDetours = 0
    private var serviceDetourEpoch = -1L
    private fun restartService(web: WebView) {
        serviceResume = serviceResume ?: serviceLastPage
        serviceDetours = 0
        // Each renewal is bounded by serviceReentries (three per two minutes); a one-minute spacing
        // here would push a 20-30 s answer past the window's 90 s limit.
        serviceRetry()
        failed = false; panel?.hideError()
        automation?.renew()
        startServiceTimeout(web)
        web.loadUrl(serviceEntry)
    }
    private fun allowed(url: String?) = if (isService) SingleSignOnPolicy.allowed(url) else SitePolicy.trusted(url)
    private val defaultErrorMessage get() = if (isService) R.string.network_error else R.string.document_error_message
    fun automationStatus(status: AutomationStatus) {
        if (isService && status in setOf(AutomationStatus.LOGIN_FAILED, AutomationStatus.ELEMENTS_MISSING))
            failure(R.string.service_login_failed)
        else if (isService && status == AutomationStatus.IDLE) continueHandOff()
    }

    /** Signed in to Univer but shown another Univer page (read notice done, news): ask for Moodle again. */
    private fun continueHandOff() {
        val web = webView ?: return
        val url = web.url
        if (!servicePending || failed || !SitePolicy.trusted(url) || SingleSignOnPolicy.entry(url)) return
        val path = runCatching { java.net.URI(url!!).rawPath.orEmpty() }.getOrDefault("")
        if (Regex("^/(user/login|student/attention)", RegexOption.IGNORE_CASE).containsMatchIn(path)) return
        if (serviceDetourEpoch == pageEpoch) return
        if (serviceDetours >= 3) { failure(R.string.service_login_failed); return }
        serviceDetourEpoch = pageEpoch
        serviceDetours++
        web.loadUrl(serviceEntry)
    }

    /** Closes what the page itself has open over its content (a table on its own screen, the site menu); false for none. */
    fun dismissNavigation(callback: (Boolean) -> Unit) = adapter?.dismissNavigation(callback) ?: callback(false)

    /** Android Back: earlier Moodle pages first, then the window closes. */
    fun goBack(): Boolean {
        val web = webView ?: return false
        if (!isService || failed || !web.canGoBack()) return false
        val previous = web.copyBackForwardList().let { it.getItemAtIndex(it.currentIndex - 1)?.url }
        if (!SingleSignOnPolicy.moodle(previous)) return false
        web.goBack()
        return true
    }

    /** Moodle's session ended: sign in through Univer again; a hand-off that keeps failing stops for Retry. */
    private fun reenterService(view: WebView) {
        val now = SystemClock.elapsedRealtime()
        while (serviceReentries.firstOrNull()?.let { now - it > 120_000 } == true) serviceReentries.removeFirst()
        if (serviceReentries.size < 3) { serviceReentries.addLast(now); restartService(view) }
        else failure(R.string.service_session_ended)
    }

    /** A Moodle file was refused by an ended session: renew it through Univer and come back here. */
    fun signInAgain(): Boolean {
        val web = webView ?: return false
        if (!isService || servicePending) return isService
        reenterService(web) // The same budget as a Moodle sign-in page: three renewals in two minutes.
        return true
    }

    var webView: WebView? = null
        private set
    private var language = AppLanguage.RUSSIAN
    /** The app-language strings, built again only when the language changes (every main-screen render updates this window). */
    private val languages = LanguageResources(context)
    private var readable = true
    private var textZoom = TextZoom.DEFAULT
    private var baseTextZoom = 100
    val isOpen get() = panel != null

    fun open(url: String, language: AppLanguage, saved: Bundle? = null, service: Boolean = false) {
        close(false)
        isService = service
        servicePending = service
        // Never retain a signed service URL in saved Activity state or retry it.
        serviceEntry = SingleSignOnPolicy.ENTRY
        serviceReentries.clear()
        // A direct link to a Moodle page whose session has ended must come back to that page after the
        // hand-off, not land on the dashboard.
        serviceResume = (saved?.getString("service_resume") ?: url.takeIf { saved == null && !SingleSignOnPolicy.entry(it) })
            ?.takeIf { service && SingleSignOnPolicy.moodleResumable(it) }
        serviceLastPage = null; serviceArrived = false; serviceLoggedOut = false
        serviceDetours = 0; serviceDetourEpoch = -1L
        // Opening the service is an explicit user action: its sign-in skips the minute between logins once
        // (the window gives up after 90 s); the three logins per ten minutes still apply.
        if (service && saved == null) serviceRetry()
        this.language = language
        requestedUrl = url
        failed = false
        failureMessageId = defaultErrorMessage
        failureHttpStatus = null
        readable = saved?.getBoolean("readable", true) ?: true
        // The current language, not the one at opening: the labels must not switch back after a language change.
        // A closed layer stays on screen while it fades, so its controls check that the window is still open.
        val frame = DocumentPanel(context, onBack = { close() }, onMode = { if (isOpen) { readable = !readable; update(this@DocumentViewer.language) } },
            onPrint = ::printDocument, onRetry = { if (isOpen) retryPage() })
        panel = frame
        val web = WebView(context)
        web.id = R.id.document_web
        webView = web
        web.configureSecureSettings()
        if (isService) {
            web.settings.setSupportMultipleWindows(false)
            web.isSaveEnabled = false
        }
        baseTextZoom = web.settings.textZoom
        // Like the main screen's placeholder: it takes taps meant for the page under it, but is no stop for TalkBack.
        val sheet = PageSkeleton(context).apply {
            showDocument(); isClickable = true; isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; visibility = View.GONE
        }
        paper = sheet
        adapter = MobilePageAdapter(web, onConcealed = { hidden -> if (web === webView) Motion.show(sheet, hidden) }, onContentReady = {
            if (web === webView && !failed) {
                panel?.loading(false)
                automation?.pageFinished()
            }
        })
        automation = if (isService) serviceAutomationFactory?.invoke(web) ?: automationFactory(web) else automationFactory(web)
        if (isService) startServiceTimeout(web)
        web.webViewClient = DocumentClient()
        web.webChromeClient = DocumentChrome()
        web.setDownloadListener { link, agent, disposition, mime, _ -> download(link, agent, disposition, mime) }
        val fill = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val stage = FrameLayout(context).apply { addView(web, fill); addView(sheet, FrameLayout.LayoutParams(fill)) }
        frame.addStage(stage)
        host.addView(frame.root, FrameLayout.LayoutParams(fill))
        if (saved == null) Motion.enter(frame.root)
        update(language)
        automation?.resume()
        val restored = saved?.getBundle("web")?.let { web.restoreState(it) }
        if (restored == null || !allowed(web.url)) web.loadUrl(url)
    }

    /** Explicit Retry: a fresh GET; a credential or signed POST is never reloaded. */
    private fun retryPage() {
        if (isService) {
            serviceRetry()
            if (webView == null) open(serviceEntry, language, service = true)
            else webView?.let(::restartService)
        } else if (SitePolicy.trusted(requestedUrl)) {
            if (webView == null) open(requestedUrl, language)
            else { automation?.resume(replacing = true); webView?.loadUrl(requestedUrl) }
        }
    }

    /** Navigation policy, page lifecycle and errors of the document or service window. */
    private inner class DocumentClient : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            // Android does not invoke shouldOverrideUrlLoading for POST. Reject an unknown
            // main-frame/form target before a direct request can send a hand-off token.
            if ((request.isForMainFrame || (isService && request.method.equals("POST", true))) &&
                !allowed(request.url.toString())) {
                view.post { if (view === webView) failure(if (isService) R.string.service_navigation_blocked else R.string.document_error_message) }
                return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(),
                    java.io.ByteArrayInputStream(byteArrayOf()))
            }
            return null
        }
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val destination = request.url.toString()
            if (!request.isForMainFrame) return !allowed(destination)
            if (sessionNavigation(request)) return true
            if (languageNavigation(request)) { automation?.websiteLanguageSelected(); return false }
            if (isService) {
                // Return false preserves the site's GET or POST and its hidden fields.
                if (SingleSignOnPolicy.allowed(destination)) {
                    // Moodle's "sign in via Univer" link is a user request for a new hand-off.
                    if (SingleSignOnPolicy.entry(destination) && request.hasGesture()) { serviceRetry(); automation?.renew() }
                    if (SingleSignOnPolicy.moodleLogout(destination) && request.hasGesture()) serviceLoggedOut = true
                    // Remember the page asked for: an ended session redirects it to Moodle's sign-in.
                    if (!servicePending && !request.isRedirect && request.method.equals("GET", true) &&
                        SingleSignOnPolicy.moodleResumable(destination))
                        serviceLastPage = destination
                    return false
                }
                if (SitePolicy.externalNavigation(destination, request.hasGesture(), request.isRedirect, request.method)) navigate(destination)
                else failure(R.string.service_navigation_blocked)
                return true
            }
            if (SitePolicy.trusted(destination) && request.url.path == "/__univer_document_back__") { close(); return true }
            if (SitePolicy.printDocument(destination) || (SitePolicy.trusted(destination) &&
                    (request.url.path?.startsWith("/lang/change/") == true || request.url.path?.startsWith("/user/") == true))) return false
            if (SitePolicy.trusted(destination) || SitePolicy.externalNavigation(destination, request.hasGesture(), request.isRedirect, request.method))
                navigate(destination)
            return true
        }
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (url != view.url && allowed(view.url)) return
            pageEpoch++
            automation?.pageStarted()
            adapter?.pageStarted(url)
            failed = false
            panel?.hideError()
            panel?.loading(true)
            if (allowed(url)) requestedUrl = url!!
            else if (url != "about:blank") { view.stopLoading(); failure(if (isService) R.string.service_navigation_blocked else R.string.document_error_message) }
        }
        override fun onPageCommitVisible(view: WebView, url: String?) {
            adapter?.apply(readable, this@DocumentViewer.language)
            fitMoodle(view, url)
        }
        override fun onPageFinished(view: WebView, url: String?) {
            if (url != view.url) return
            if (isService) update(this@DocumentViewer.language)
            fitMoodle(view, url)
            panel?.loading(false)
            CookieManager.getInstance().flush()
            adapter?.apply(readable, this@DocumentViewer.language)
            if (SitePolicy.trusted(url) && !failed && !isService) {
                // Legacy print links call window.close()/history.back(), which have no usable source history here.
                view.evaluateJavascript(PageScripts.DOCUMENT_BACK, null)
            }
            val finishedEpoch = pageEpoch
            if (allowed(url)) view.evaluateJavascript(PageScripts.STATUS) { result ->
                if (view !== webView || finishedEpoch != pageEpoch || url != view.url) return@evaluateJavascript
                if (failed) return@evaluateJavascript
                val status = result.toIntOrNull() ?: 0
                if (status >= 400) failure(httpErrorMessage(status, defaultErrorMessage), status)
                else {
                    if (isService && SingleSignOnPolicy.moodle(url)) moodleLoaded(view, url!!, finishedEpoch)
                    else automation?.pageFinished()
                }
            }
        }
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) { handler.cancel(); if (error.url == view.url) failure(R.string.tls_error) }
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) { if (request.isForMainFrame && request.url.toString() == view.url) failure() }
        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (request.isForMainFrame && request.url.toString() == view.url && response.statusCode >= 400)
                failure(httpErrorMessage(response.statusCode, defaultErrorMessage), response.statusCode)
        }
        override fun onFormResubmission(view: WebView, dontResend: Message, resend: Message) { dontResend.sendToTarget() }
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (view === webView) {
                pageEpoch++
                failure(R.string.webview_crashed)
                adapter?.close()
                automation?.close()
                automation = null; adapter = null; webView = null
            }
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            return true
        }
    }

    /** After a Moodle page loads: the hand-off is done, a signed-out page signs in again, the page asked for is reopened. */
    private fun moodleLoaded(view: WebView, url: String, finishedEpoch: Long) {
        if (servicePending) serviceArrived = true
        servicePending = false
        serviceTimeout?.let(view::removeCallbacks)
        automation?.handOffCompleted()
        if (serviceLoggedOut) { close(); serviceSignedOut(); return }
        if (SingleSignOnPolicy.moodleSignIn(url)) view.evaluateJavascript(PageScripts.MOODLE_LOGIN_FORM) { form ->
            if (view === webView && finishedEpoch == pageEpoch && !failed && form == "true") reenterService(view)
        } else view.evaluateJavascript(PageScripts.MOODLE_SIGNED_OUT) signedOut@{ signedOut ->
            if (view !== webView || finishedEpoch != pageEpoch || failed) return@signedOut
            // An ended session continues as Moodle's guest ("guest access", Log in link) or not logged in.
            if (signedOut == "true") { reenterService(view); return@signedOut }
            if (!SingleSignOnPolicy.moodleResumable(url)) return@signedOut
            if (serviceArrived) {
                // Univer's login/hand-off steps leave the history: Back stays inside Moodle.
                serviceArrived = false
                view.clearHistory()
                serviceSignedIn()
                val resume = serviceResume
                serviceResume = null
                if (resume != null && resume != url) { view.loadUrl(resume); return@signedOut }
            }
            serviceLastPage = url
        }
    }

    /** Upload forms, progress, window.close() and pop-ups (whose address is routed like a link). */
    private inner class DocumentChrome : WebChromeClient() {
        override fun onShowFileChooser(webView: WebView, callback: ValueCallback<Array<android.net.Uri>>, params: FileChooserParams): Boolean {
            if (webView !== this@DocumentViewer.webView) return false
            return chooseFiles(callback, params)
        }
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            panel?.progress(newProgress, failed)
        }
        override fun onCloseWindow(window: WebView) { close() }
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            // Service authentication stays in the same browsing context; no POST is rebuilt.
            if (isService) return false
            if (!isUserGesture) return false
            captureNewWindow(context, resultMsg) { request ->
                if (!request.isForMainFrame || !request.method.equals("GET", true) || request.isRedirect) return@captureNewWindow
                if (!sessionNavigation(request)) {
                    val target = request.url.toString()
                    if (SitePolicy.printDocument(target)) this@DocumentViewer.webView?.loadUrl(target) else navigate(target)
                }
            }
            return true
        }
    }

    private val moodleFit by lazy { context.assets.open("univer-moodle.js").bufferedReader().use { it.readText() } }
    /** Keeps Moodle pages within the phone width (styles only; the script checks the origin itself). */
    private fun fitMoodle(view: WebView, url: String?) {
        if (isService && view === webView && SingleSignOnPolicy.moodle(url)) view.evaluateJavascript(moodleFit, null)
    }

    private fun failure(messageId: Int = defaultErrorMessage, httpStatus: Int? = null) {
        failed = true
        failureMessageId = messageId
        failureHttpStatus = httpStatus
        paper?.let { Motion.show(it, false) }
        panel?.showError(languages.of(language).webErrorText(failureMessageId, failureHttpStatus))
        automation?.pause()
    }

    fun update(language: AppLanguage, textZoom: Int = this.textZoom) {
        this.language = language
        this.textZoom = textZoom
        webView?.settings?.textZoom = baseTextZoom * textZoom / 100
        val frame = panel ?: return // Nothing on screen to label; the next open() labels its own window.
        val localized = languages.of(language)
        val titleId = when {
            isService -> R.string.service_moodle
            requestedUrl.contains("/transcript/") -> R.string.document_transcript
            requestedUrl.contains("/recbook/") -> R.string.document_recordbook
            requestedUrl.contains("/iup/") -> R.string.document_iup
            requestedUrl.contains("/interactive/syllabus/") -> R.string.document_syllabus
            else -> R.string.document_title
        }
        val subtitle = if (isService) runCatching { java.net.URI(requestedUrl).host }.getOrNull() else localized.getString(R.string.document_subtitle)
        frame.label(localized, titleId, subtitle, isService, readable, localized.webErrorText(failureMessageId, failureHttpStatus))
        adapter?.apply(readable, language)
    }

    /** Prints or saves the document as shown, through the system print dialog. */
    private fun printDocument() {
        val web = webView ?: return
        val manager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        val name = panel?.titleText?.ifBlank { null } ?: "Univer"
        try { manager.print(name, web.createPrintDocumentAdapter(name), null) }
        catch (_: Exception) { /* Without a print service the document simply stays open. */ }
    }
    fun applyLanguage(language: AppLanguage) { update(language); if (!isService) automation?.applyLanguage(language) }
    fun save(): Bundle? = webView?.let { web ->
        Bundle().apply {
            putBoolean("readable", readable); putString("url", if (isService) serviceEntry else web.url); putBoolean("service", isService)
            if (isService) {
                putString("service_entry", serviceEntry)
                // An ordinary Moodle address (no session key or token) to return to after the safe hand-off.
                (serviceResume ?: serviceLastPage)?.takeIf(SingleSignOnPolicy::moodleResumable)?.let { putString("service_resume", it) }
            }
            // A service may have just submitted a password or SSO token: recreation starts a safe GET.
            else putBundle("web", Bundle().also { web.saveState(it) })
        }
    }
    fun resume() { webView?.onResume(); automation?.resume() }
    fun pause() { automation?.pause(); webView?.onPause() }
    fun close(notify: Boolean = true) {
        val hadWindow = isOpen
        isService = false; servicePending = false
        serviceLastPage = null; serviceResume = null; serviceArrived = false; serviceLoggedOut = false
        serviceTimeout?.let { webView?.removeCallbacks(it) }; serviceTimeout = null
        automation?.close(); automation = null; adapter?.close(); adapter = null
        val web = webView?.apply { stopLoading(); webChromeClient = null; webViewClient = RetiredWebViewClient() }
        val layer = panel?.root
        webView = null
        panel = null
        // The page stays in its layer while it fades; a session that is ending removes both at once.
        val remove = {
            web?.apply { (parent as? ViewGroup)?.removeView(this); destroy() }
            layer?.let(host::removeView)
            Unit
        }
        if (layer != null && notify) Motion.leave(layer, remove) else remove()
        if (hadWindow && notify) onClosed()
        paper = null
    }
}
