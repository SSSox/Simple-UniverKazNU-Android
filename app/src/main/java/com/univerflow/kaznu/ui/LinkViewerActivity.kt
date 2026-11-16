package com.univerflow.kaznu.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.isVisible
import com.google.android.material.snackbar.Snackbar
import com.univerflow.kaznu.R
import com.univerflow.kaznu.databinding.ActivityLinkViewerBinding
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.web.LinkOpening
import com.univerflow.kaznu.web.LinkPolicy
import com.univerflow.kaznu.web.LinkProfile
import com.univerflow.kaznu.web.LinkStep
import com.univerflow.kaznu.web.SitePolicy

/**
 * Pages of other sites inside the app, like Moodle's panel, in a WebView profile of their own ([LinkProfile]): their
 * sign-ins stay between runs and never meet the Univer session. No JavaScript bridge, local files, extra windows,
 * permissions or certificate exceptions; files go to the browser. A tap on a Univer or Moodle page returns it to the main
 * screen as this screen's result.
 */
class LinkViewerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLinkViewerBinding
    private lateinit var language: AppLanguage
    private val localized by lazy { language.context(this) }
    private var web: WebView? = null
    private var start = ""
    /** The page the error view's button opens again, or null while no error is shown. */
    private var failedUrl: String? = null
    private var errorOpensBrowser = false
    /** The main-frame address being loaded, to tell its certificate failure from one of an embedded resource. */
    private var pending: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        language = AppLanguage.fromCode(intent.getStringExtra(EXTRA_LANGUAGE))
        start = intent.getStringExtra(EXTRA_URL).orEmpty()
        // The address is checked again here: only an https page on a named host, and only with a separate profile.
        if (!LinkProfile.available() || LinkPolicy.open(start, preferBrowser = false, windowAvailable = true) != LinkOpening.APP) {
            finish(); return
        }
        binding = ActivityLinkViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        AppLockSession.secondaryScreen(this, binding.root, ime = true)
        val view = try { createWebView() } catch (_: Exception) {
            // No usable WebView right now (it may be updating): the browser opens the page instead.
            openSystem(start); finish(); return
        }
        web = view
        binding.linkFrame.addView(view, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        bindBar()
        showPage(start, null)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { web?.takeIf { it.canGoBack() }?.goBack() ?: finish() }
        })
        if (savedInstanceState?.let(view::restoreState) == null) { pending = start; view.loadUrl(start) }
    }

    private fun createWebView(): WebView {
        val view = WebView(this)
        LinkProfile.attach(view)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            if (Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true
        }
        LinkProfile.cookies().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(view, false) }
        view.webViewClient = Client()
        view.webChromeClient = Chrome()
        view.setDownloadListener { url, _, _, _, _ -> openSystem(url) }
        return view
    }

    private fun bindBar() {
        binding.linkClose.contentDescription = text(R.string.done)
        binding.linkReload.contentDescription = text(R.string.refresh)
        binding.linkBrowser.contentDescription = text(R.string.browser)
        listOf(binding.linkClose, binding.linkReload, binding.linkBrowser).forEach { androidx.appcompat.widget.TooltipCompat.setTooltipText(it, it.contentDescription) }
        binding.linkClose.setOnClickListener { finish() }
        binding.linkReload.setOnClickListener { failedUrl?.let { retry(it) } ?: web?.reload() }
        binding.linkBrowser.setOnClickListener { openSystem(failedUrl ?: web?.url?.takeIf(SitePolicy::external) ?: start) }
        binding.linkError.documentRetry.setOnClickListener {
            val url = failedUrl ?: return@setOnClickListener
            if (errorOpensBrowser) openSystem(url) else retry(url)
        }
    }

    private fun retry(url: String) { hideError(); pending = url; web?.loadUrl(url) }

    /** The page title over its site's address; the address alone until the title arrives. */
    private fun showPage(url: String?, title: String?) {
        val host = url?.let { runCatching { it.toUri().host }.getOrNull() }.orEmpty()
        // While the error view is up, the title is WebView's own error page, not the site's.
        val name = title?.trim()?.takeIf { failedUrl == null && it.isNotEmpty() && it != url && !it.startsWith("http") }
        binding.linkTitle.text = name ?: host
        binding.linkHost.text = host
        binding.linkHost.isVisible = name != null && host.isNotEmpty()
    }

    private fun showError(url: String, message: Int, opensBrowser: Boolean = false) {
        failedUrl = url; errorOpensBrowser = opensBrowser
        showPage(url, null)
        web?.isVisible = false
        binding.linkProgress.hide()
        binding.linkError.documentErrorMessage.text = text(message)
        binding.linkError.documentRetry.text = text(if (opensBrowser) R.string.browser else R.string.retry)
        binding.linkError.documentRetry.setIconResource(if (opensBrowser) R.drawable.ic_open_in_new else R.drawable.ic_refresh)
        binding.linkError.root.isVisible = true
    }

    private fun hideError() {
        failedUrl = null
        binding.linkError.root.isVisible = false
        web?.isVisible = true
    }

    /** Mail, phone, plain http and files go to the system; nothing else ever leaves this screen. */
    private fun openSystem(url: String?) {
        if (!SitePolicy.external(url)) return
        try { startActivity(Intent(Intent.ACTION_VIEW, url!!.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)) }
        catch (_: ActivityNotFoundException) { message(R.string.open_failed) }
        catch (_: SecurityException) { message(R.string.open_failed) }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            // Frames may show what pages embed; other schemes (intent:, file:, apps) never load anywhere.
            if (!request.isForMainFrame) return request.url.scheme?.lowercase() !in setOf("https", "http", "about", "data", "blob")
            return when (LinkPolicy.step(url, request.hasGesture(), request.isRedirect)) {
                LinkStep.STAY -> { pending = url; false }
                LinkStep.MAIN -> { setResult(RESULT_OK, Intent().putExtra(RESULT_URL, url)); finish(); true }
                LinkStep.SYSTEM -> { openSystem(url); true }
                LinkStep.BLOCK -> {
                    // A redirect nowhere allowed leaves an empty window: say so and offer the browser.
                    if (view.url.isNullOrEmpty() || view.url == "about:blank" || !view.canGoBack())
                        if (request.isRedirect && SitePolicy.external(url)) showError(url, R.string.link_blocked, opensBrowser = true)
                    true
                }
            }
        }
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            pending = url
            if (failedUrl != null && url != failedUrl) hideError()
            binding.linkProgress.show()
            showPage(url, null)
        }
        override fun onPageFinished(view: WebView, url: String?) {
            binding.linkProgress.hide()
            showPage(url, view.title)
            LinkProfile.cookies().flush()
        }
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) showError(request.url.toString(), R.string.link_failed)
        }
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            if (error.url == pending || error.url == view.url) showError(error.url, R.string.link_certificate, opensBrowser = true)
        }
        override fun onFormResubmission(view: WebView, dontResend: Message, resend: Message) { dontResend.sendToTarget() }
        // A crash of another site's page closes this screen, never the app.
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (view === web) web = null
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            if (!isFinishing) finish()
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            binding.linkProgress.setProgressCompat(newProgress, true)
            if (newProgress >= 100) binding.linkProgress.hide()
        }
        override fun onReceivedTitle(view: WebView, title: String?) { showPage(view.url, title) }
        override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
            callback.invoke(origin, false, false)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web?.saveState(outState)
    }

    override fun onResume() { super.onResume(); web?.onResume() }

    override fun onPause() {
        web?.onPause()
        // A sign-in a site just gave is written now, not only when WebView gets to it.
        if (web != null) LinkProfile.cookies().flush()
        super.onPause()
    }

    override fun onDestroy() {
        web?.let { view ->
            view.stopLoading()
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
        web = null
        super.onDestroy()
    }

    private fun text(id: Int) = localized.getString(id)
    private fun message(id: Int) = Snackbar.make(binding.root, text(id), Snackbar.LENGTH_LONG).show()

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_LANGUAGE = "language"
        const val RESULT_URL = "url"
        fun intent(context: Context, url: String, language: AppLanguage): Intent =
            Intent(context, LinkViewerActivity::class.java).putExtra(EXTRA_URL, url).putExtra(EXTRA_LANGUAGE, language.siteCode)
    }
}
