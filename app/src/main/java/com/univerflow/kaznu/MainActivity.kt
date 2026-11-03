package com.univerflow.kaznu

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.print.PrintManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.data.AppPreferences
import com.univerflow.kaznu.data.CredentialVault
import com.univerflow.kaznu.data.DownloadRepository
import com.univerflow.kaznu.data.LocalSessionRepository
import com.univerflow.kaznu.data.LockPreferences
import com.univerflow.kaznu.data.LockVault
import com.univerflow.kaznu.ui.context
import com.univerflow.kaznu.databinding.ActivityMainBinding
import com.univerflow.kaznu.databinding.SettingsSheetBinding
import com.univerflow.kaznu.domain.DownloadState
import com.univerflow.kaznu.domain.HomeShortcuts
import com.univerflow.kaznu.domain.Notices
import com.univerflow.kaznu.domain.SiteNotice
import com.univerflow.kaznu.domain.TextZoom
import com.univerflow.kaznu.download.DownloadEvent
import com.univerflow.kaznu.download.DownloadService
import com.univerflow.kaznu.main.DownloadLauncher
import com.univerflow.kaznu.main.LockFlow
import com.univerflow.kaznu.main.MainChrome
import com.univerflow.kaznu.main.NoticesSheet
import com.univerflow.kaznu.main.SettingsForms
import com.univerflow.kaznu.ui.AppLockSession
import com.univerflow.kaznu.ui.DocumentViewer
import com.univerflow.kaznu.ui.DownloadsActivity
import com.univerflow.kaznu.ui.FileChooser
import com.univerflow.kaznu.ui.FileViewerActivity
import com.univerflow.kaznu.ui.LockUi
import com.univerflow.kaznu.ui.Motion
import com.univerflow.kaznu.ui.NoticeLoadingOverlay
import com.univerflow.kaznu.ui.ThemeMode
import com.univerflow.kaznu.web.AppSection
import com.univerflow.kaznu.web.AutomationController
import com.univerflow.kaznu.web.AutomationStatus
import com.univerflow.kaznu.web.LogoutNavigation
import com.univerflow.kaznu.web.MobilePageAdapter
import com.univerflow.kaznu.web.NoticeBridge
import com.univerflow.kaznu.web.NoticePresentation
import com.univerflow.kaznu.web.PageScripts
import com.univerflow.kaznu.web.ShortcutBridge
import com.univerflow.kaznu.web.SingleSignOnPolicy
import com.univerflow.kaznu.web.SitePolicy
import com.univerflow.kaznu.web.WebsiteLanguageSync
import com.univerflow.kaznu.web.configureNavigationCapture
import com.univerflow.kaznu.web.configureSecureSettings
import com.univerflow.kaznu.web.httpErrorMessage
import com.univerflow.kaznu.web.webErrorText
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import kotlin.coroutines.resume

/**
 * The website in a WebView with its automation, session and recovery. Chrome, settings, the app lock and downloads
 * are their own parts ([MainChrome], [SettingsForms], [LockFlow], [DownloadLauncher]); this class wires them.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var model: UniverViewModel
    private lateinit var automation: AutomationController
    private lateinit var mobilePage: MobilePageAdapter
    private lateinit var shortcutBridge: ShortcutBridge
    private lateinit var noticeBridge: NoticeBridge
    private lateinit var noticesSheet: NoticesSheet
    private lateinit var websiteLanguage: WebsiteLanguageSync
    private lateinit var documents: DocumentViewer
    private lateinit var noticeLoading: NoticeLoadingOverlay
    private lateinit var noticePresentation: NoticePresentation
    private lateinit var lockUi: LockUi
    private lateinit var chrome: MainChrome
    private lateinit var lock: LockFlow
    private lateinit var forms: SettingsForms
    private lateinit var downloads: DownloadLauncher
    private val hosts = Hosts()
    private val logoutNavigation = LogoutNavigation { endSession(false) }
    private val fileChooser = FileChooser(this, { text(R.string.choose_file) }, { message(R.string.open_failed) })
    private val transientDialogs = mutableListOf<Dialog>()
    /**
     * What the last render put on screen. A change is the difference of two snapshots, so a new setting cannot be
     * added without deciding how it is compared; null means "not rendered yet".
     */
    private data class Rendered(
        val mobileLayout: Boolean? = null,
        val animations: Boolean? = null,
        val shortcuts: HomeShortcuts? = null,
        val language: AppLanguage? = null,
        /** The previous ViewModel lock, so a real unlock can be told from a stale "still open" snapshot. */
        val locked: Boolean = false,
    )
    private var rendered = Rendered()
    private var restoredDocument: Bundle? = null
    private var documentSourcePosition: Pair<Int, Int>? = null
    private var handledDownloadEvent: String? = null
    private var webOpened = false
    private var sessionChanging = false
    private var sessionEpoch = 0
    private var automationStatus = AutomationStatus.IDLE
    private var errorMessageId = R.string.network_error
    private var errorHttpStatus: Int? = null
    private var restoredWebState: Bundle? = null
    private var backPending = false
    private var failedPageUrl: String? = null
    private var navigationGetUrl: String? = null
    private var pageFailed = false
    private var pageEpoch = 0L
    private var rendererUnavailable = false
    /** The section shown when the renderer was lost; the recreated screen opens it again. */
    private var lostPageUrl: String? = null
    private var rendererRecoveryPending = false
    private val pageRecoveryTask = Runnable { recoverFailedPage() }
    /** While the screen is in front, the site is read for notifications on this interval even without navigation. */
    private val noticeTicker: Runnable = Runnable { scanNotices(); binding.root.postDelayed(noticeTicker, NOTICE_CHECK_MS) }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var baseTextZoom = 100
    private var pageTitle: String? = null
    private var pageLoading = false
    /** The settings sheet was open when the screen was recreated (a theme change or a rotation): it opens again. */
    private var reopenSettings = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this, viewModelFactory {
            initializer {
                UniverViewModel(LocalSessionRepository(CredentialVault(application), AppPreferences(application),
                    LockVault(application), LockPreferences(application)))
            }
        })[UniverViewModel::class.java]
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        AppLockSession.initialize(this)
        lockUi = LockUi(this, ::text)
        chrome = MainChrome(this, binding)
        lock = LockFlow(this, model, lockUi, hosts)
        forms = SettingsForms(this, model, lock, hosts)
        downloads = DownloadLauncher(this, model, hosts)
        // Leaving the app for longer than the chosen time locks it: the password leaves memory and the PIN is asked again.
        AppLockSession.track(this) {
            if (model.state.value.lock.enabled) binding.drawer.visibility = View.INVISIBLE
            lifecycleScope.launch { try { model.lockNow() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { } }
        }
        lock.restore(savedInstanceState)
        downloads.restore(savedInstanceState)
        noticeLoading = NoticeLoadingOverlay(binding.webView, binding.noticeLoadingOverlay)
        noticePresentation = NoticePresentation(binding.webView)
        val light = !ThemeMode.isDark(this)
        WindowCompat.getInsetsController(window, binding.root).apply { isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light }
        restoreState(savedInstanceState)
        applyContentInsets()
        createPageParts()
        documents = createDocuments()
        bindControls()
        collectUpdates()
    }

    private fun restoreState(savedInstanceState: Bundle?) {
        restoredWebState = savedInstanceState?.getBundle("web_state")
        lostPageUrl = savedInstanceState?.getString("lost_page")
        reopenSettings = savedInstanceState?.getBoolean("settings_open") == true
        handledDownloadEvent = savedInstanceState?.getString("download_event")
        sessionEpoch = savedInstanceState?.getInt("session_epoch", 0) ?: 0
        restoredDocument = savedInstanceState?.getBundle("document_state")
        if (restoredDocument != null && savedInstanceState != null) documentSourcePosition = Pair(
            savedInstanceState.getInt("document_source_x"), savedInstanceState.getInt("document_source_y"))
    }

    // The drawer handles its own insets; the content frame keeps the app bar and website clear of system bars and the keyboard.
    private fun applyContentInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.content) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    /** The parts working on the main website page: automation, the language flags, the page adapter and home tiles. */
    private fun createPageParts() {
        automation = AutomationController(binding.webView, model, lifecycleScope, signedIn = downloads::signedIn,
            resumeTarget = model.resumeTarget, recover = ::loadPage) {
            automationStatus = it
            configureNoticePresentation()
            noticeLoading.status(it)
            renderBanner()
        }
        websiteLanguage = WebsiteLanguageSync(model, lifecycleScope, { selected ->
            // The flag itself performs the website switch. Render native labels without a second request.
            rendered = rendered.copy(language = selected)
            automation.websiteLanguageSelected()
            mobilePage.apply(model.state.value.mobileLayout, selected)
        }, { message(R.string.storage_error) })
        configureWebView()
        mobilePage = MobilePageAdapter(binding.webView, nativeChrome = true, onContentReady = {
            if (!rendererUnavailable && !sessionChanging && !pageFailed && SitePolicy.trusted(binding.webView.url)) {
                binding.loading.isVisible = false
                noticePresentation.apply()
                automation.pageFinished()
                updatePageTitle()
                scanNotices()
            }
        })
        // Home tile pins and order arrive from page taps; the validated, stored list is sent back to the page.
        shortcutBridge = ShortcutBridge(binding.webView) { request ->
            lifecycleScope.launch {
                val accepted = try { model.updateShortcuts(request) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
                if (!accepted) message(R.string.pin_failed)
                shortcutBridge.configure(model.state.value.shortcuts)
            }
        }
        // The site's own marks (a menu count, a "complete the questionnaire first" message) become notifications.
        noticeBridge = NoticeBridge(binding.webView, NOTICE_CHECK_MS) { scan, session ->
            val epoch = sessionEpoch
            lifecycleScope.launch {
                val fresh = try { model.applyNoticeScan(scan, session) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { emptyList() }
                if (fresh.isNotEmpty() && epoch == sessionEpoch && !noticesSheet.showing && !isDestroyed) announceNotices(fresh)
            }
        }
        noticesSheet = NoticesSheet(this, onOpen = ::openNotice,
            onRead = { key -> changeNotices { model.markNoticeRead(key) } },
            onReadAll = { changeNotices { model.markAllNoticesRead() } },
            onRefresh = { scanNotices(refresh = true) })
    }

    /** Reads the main page for notifications once it is a signed-in page, not the login form or the read notice. */
    private fun scanNotices(refresh: Boolean = false) {
        val state = model.state.value
        if (rendererUnavailable || sessionChanging || documents.isOpen || !state.hasCredentials || state.paused || state.locked ||
            automationStatus != AutomationStatus.IDLE) return
        val url = binding.webView.url
        if (!SitePolicy.trusted(url) || SitePolicy.loginPage(url) || url?.toUri()?.path?.matches(NOTICE_PATH) == true) return
        noticeBridge.scan(refresh, model.noticeSession)
    }

    private fun changeNotices(change: suspend () -> Unit) {
        lifecycleScope.launch {
            try { change() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { message(R.string.storage_error) }
        }
    }

    private fun showNotices() {
        val state = model.state.value
        if (!state.hasCredentials || state.locked || sessionChanging || noticesSheet.showing) return
        noticesSheet.show(state)
    }

    private fun announceNotices(fresh: List<SiteNotice>) {
        val labels = model.state.value.language.context(this)
        val text = if (fresh.size == 1) labels.getString(R.string.notices_new_one, fresh[0].title)
            else labels.getString(R.string.notices_new_many, fresh.size)
        Snackbar.make(binding.content, text, Snackbar.LENGTH_LONG).setAction(labels.getString(R.string.notices_show)) { showNotices() }.show()
    }

    /** Opens the page that resolves [notice] (the questionnaire list, the section with the message) and marks it read. */
    private fun openNotice(notice: SiteNotice) {
        if (sessionChanging || !SitePolicy.trusted(notice.url)) return
        changeNotices { model.markNoticeRead(notice.key) }
        if (rendererUnavailable) { retry(); return }
        if (documents.isOpen) documents.close()
        if (!route(notice.url)) loadPage(notice.url)
    }

    /** The document and service (Moodle) window over the page, with its own automation for each kind. */
    private fun createDocuments() = DocumentViewer(this, binding.content, { web ->
        AutomationController(web, model, lifecycleScope) { automationStatus = it; renderBanner() }
    }, { url ->
        if (SitePolicy.trusted(url) && !SitePolicy.documentDownload(url)) {
            documents.close()
            binding.webView.loadUrl(url)
        } else route(url)
    }, downloads::download, ::documentClosed, { request -> !sessionChanging && websiteLanguage.onNavigation(request) }, logoutNavigation::onNavigation,
        serviceAutomationFactory = { web ->
            AutomationController(web, model, lifecycleScope, model.singleSignOnGuard, synchronizeLanguage = false) {
                documents.automationStatus(it)
            }
        }, serviceRetry = { model.singleSignOnGuard.retry() }, chooseFiles = fileChooser::show,
        serviceSignedIn = downloads::signedIn, serviceSignedOut = { message(R.string.service_logged_out) })

    private fun bindControls() {
        chrome.configure(canOpenDrawer = { !documents.isOpen }, onToolbar = ::toolbarAction, onNavigation = ::navigationAction)
        binding.errorRetry.setOnClickListener { retryFailedPage() }
        binding.errorBack.setOnClickListener {
            if (rendererUnavailable) retry()
            else if (binding.webView.canGoBack()) binding.webView.goBack()
            else binding.webView.loadUrl(SitePolicy.HOME)
        }
        binding.statusAction.setOnClickListener { retry() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = back()
        })
    }

    /** Session state, download events and the running-download count, each while the screen is started. */
    private fun collectUpdates() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect { render(it) } }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { DownloadService.events.collect { event -> event?.let(::downloadEvent) } }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadRepository.get(this@MainActivity).records.collect { list ->
                    chrome.downloads(list.count { it.state == DownloadState.RUNNING }, model.state.value)
                }
            }
        }
    }

    private fun text(id: Int) = model.state.value.language.context(this).getString(id)

    private fun message(id: Int) { Snackbar.make(binding.content, text(id), Snackbar.LENGTH_LONG).show() }

    /** A dialog the lock screen closes. */
    private fun track(dialog: Dialog) {
        transientDialogs += dialog
        dialog.setOnDismissListener { transientDialogs -= dialog }
    }

    private fun dismissTransientDialogs() { transientDialogs.toList().forEach { it.dismiss() }; transientDialogs.clear() }

    private fun back() {
        when {
            lock.showing -> lockUi.handleBack()
            binding.drawer.isDrawerOpen(GravityCompat.START) -> binding.drawer.closeDrawer(GravityCompat.START)
            forms.showing -> forms.dismiss()
            documents.isOpen -> {
                if (backPending) return
                backPending = true
                // A table opened on its own screen closes first, as the site menu does on the page.
                documents.dismissNavigation { dismissed ->
                    backPending = false
                    if (!dismissed && documents.isOpen && !isFinishing && !isDestroyed && !documents.goBack()) documents.close()
                }
            }
            rendererUnavailable -> finish()
            webOpened -> {
                if (backPending) return
                backPending = true
                mobilePage.dismissNavigation { dismissed ->
                    backPending = false
                    if (!dismissed && !isFinishing && !isDestroyed) {
                        if (binding.webView.canGoBack()) binding.webView.goBack() else leaveApp()
                    }
                }
            }
            else -> leaveApp()
        }
    }

    private fun toolbarAction(id: Int): Boolean {
        when (id) {
            R.id.action_notifications -> showNotices()
            R.id.action_downloads -> openDownloads()
            R.id.action_refresh -> refreshPage()
            R.id.action_reading -> setMobileLayout(!model.state.value.mobileLayout)
            R.id.action_text_size -> showTextSize()
            R.id.action_print -> printPage()
            R.id.action_browser -> openInBrowser()
            R.id.action_settings -> forms.show()
            else -> return false
        }
        return true
    }

    /** Sections stay checked in the drawer; actions (Moodle, Downloads, settings…) do not. */
    private fun navigationAction(id: Int): Boolean {
        when (id) {
            R.id.nav_home -> openSection(AppSection.HOME)
            R.id.nav_schedule -> openSection(AppSection.SCHEDULE)
            R.id.nav_journal -> openSection(AppSection.JOURNAL)
            R.id.nav_materials -> openSection(AppSection.MATERIALS)
            R.id.nav_attestation -> openSection(AppSection.ATTESTATION)
            R.id.nav_distance -> openSection(AppSection.DISTANCE)
            R.id.nav_moodle -> { openMoodle(); return false }
            R.id.nav_notices -> { showNotices(); return false }
            R.id.nav_downloads -> { openDownloads(); return false }
            R.id.nav_all_sections -> { showSiteSections(); return false }
            R.id.nav_settings -> { forms.show(); return false }
            R.id.nav_browser -> { openInBrowser(); return false }
        }
        return true
    }

    private fun downloadEvent(event: DownloadEvent) {
        if (event.id == handledDownloadEvent || sessionChanging) return
        handledDownloadEvent = event.id
        if (event.loginUrl != null && downloads.signInFor(event.loginUrl)) { message(R.string.download_relogin); return }
        val bar = Snackbar.make(binding.content, text(event.message), Snackbar.LENGTH_LONG)
        if (event.uri != null && event.recordId != null) bar.setAction(text(R.string.open_file)) {
            if (!model.state.value.locked && !AppLockSession.locked)
                startActivity(FileViewerActivity.intent(this, event.recordId, model.state.value.language))
        } else if (event.message == R.string.download_login) bar.setAction(text(R.string.sign_in)) { retry() }
        else bar.setAction(text(R.string.downloads)) { openDownloads() }
        bar.show()
    }

    /** The document viewer closed: back to the page where it was opened, at the same scroll position. */
    private fun documentClosed() {
        val position = documentSourcePosition
        documentSourcePosition = null
        val source = binding.webView.url
        updateDrawerLock()
        if (model.state.value.hasCredentials && !lock.forceSecure()) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (!sessionChanging) {
            automation.resume()
            binding.webView.postDelayed({
                if (!sessionChanging && binding.webView.url == source && !documents.isOpen)
                    position?.let { binding.webView.scrollTo(it.first, it.second) }
            }, 350)
        }
    }

    private fun render(state: AppState) {
        if (!state.ready) return
        val justUnlocked = rendered.locked && !state.locked
        rendered = rendered.copy(locked = state.locked)
        AppLockSession.update(state.lock.enabled, state.locked, state.lock.autoLockMs, justUnlocked)
        // The gate can latch before the ViewModel snapshot arrives. Cover the page either way.
        if (state.lock.enabled && (state.locked || AppLockSession.locked)) { lock.showUnlockScreen(state); return }
        lock.releaseUnlockScreen()
        configureNoticePresentation()
        lock.applyWindowSecurity()
        binding.noticeLoadingText.text = text(R.string.preparing_page)
        renderPageSettings(state)
        binding.errorMessage.text = state.language.context(this).webErrorText(errorMessageId, errorHttpStatus)
        binding.errorRetry.text = text(R.string.retry)
        binding.errorBack.text = text(R.string.return_to_site)
        val changedLanguage = rendered.language != null && rendered.language != state.language
        rendered = rendered.copy(language = state.language)
        localizeChrome(state)
        if (model.sessionClearPending && !sessionChanging) { finishInterruptedSessionClear(); return }
        if (state.hasCredentials) renderSession(state, changedLanguage) else renderFirstRun(state)
        updateDrawerLock()
        if (reopenSettings) { reopenSettings = false; if (state.hasCredentials) forms.show() }
        // onResume skipped the checks while the lock was up; unlocking in place must start them.
        if (justUnlocked) { scanNotices(); startNoticeChecks() }
    }

    /** Page adaptation, animations, home tiles and text size follow the settings; the page is re-adapted only on a change. */
    private fun renderPageSettings(state: AppState) {
        val changedMobileLayout = rendered.mobileLayout != state.mobileLayout
        val changedAnimations = rendered.animations != null && rendered.animations != state.animations
        val changedShortcuts = rendered.shortcuts != null && rendered.shortcuts != state.shortcuts
        val changedLanguage = rendered.language != state.language
        rendered = rendered.copy(mobileLayout = state.mobileLayout, animations = state.animations, shortcuts = state.shortcuts)
        mobilePage.animations = state.animations
        Motion.enabled = state.animations
        mobilePage.shortcuts = ShortcutBridge.script(state.shortcuts)
        if (!rendererUnavailable && changedShortcuts) shortcutBridge.configure(state.shortcuts)
        if (!rendererUnavailable && (changedMobileLayout || changedAnimations || changedLanguage))
            mobilePage.apply(state.mobileLayout, state.language)
        if (!rendererUnavailable) binding.webView.settings.textZoom = baseTextZoom * state.textZoom / 100
    }

    /** No saved account yet: the first-run form instead of the website. */
    private fun renderFirstRun(state: AppState) {
        noticeLoading.dismiss()
        binding.setupContainer.isVisible = true
        binding.mainColumn.isVisible = false
        binding.webView.isVisible = false
        binding.loading.isVisible = false
        binding.errorOverlay.isVisible = false
        binding.statusBar.isVisible = false
        forms.showFirstRun(binding.setupContainer, state.storageError)
    }

    /** The website with its session: opened once, then switched to a newly chosen language. */
    private fun renderSession(state: AppState, changedLanguage: Boolean) {
        forms.closeFirstRun(binding.setupContainer)
        binding.setupContainer.isVisible = false
        binding.mainColumn.isVisible = true
        binding.webView.isVisible = !rendererUnavailable
        if (state.paused) noticeLoading.dismiss()
        if (rendererUnavailable) recoverRenderer()
        if (!webOpened && !sessionChanging && !rendererUnavailable) openWeb()
        else if (changedLanguage && !sessionChanging && !rendererUnavailable) {
            if (documents.isOpen) documents.applyLanguage(state.language) else automation.applyLanguage(state.language)
        }
        documents.update(state.language, state.textZoom)
        forms.refresh()
        renderBanner()
        lock.maybeOfferPin()
    }

    /** The first page of a session: the restored one after a recreation, otherwise the automation's start. */
    private fun openWeb() {
        webOpened = true
        automation.resume()
        val restored = restoredWebState?.let { binding.webView.restoreState(it) }
        restoredWebState = null
        val lost = lostPageUrl?.takeIf(SitePolicy::resumable)
        lostPageUrl = null
        when {
            restored != null && SitePolicy.trusted(binding.webView.url) -> automation.pageFinished()
            lost != null -> loadPage(lost)
            else -> automation.open()
        }
        restoredDocument?.let { saved ->
            val service = saved.getBoolean("service")
            saved.getString("url")?.takeIf { if (service) SingleSignOnPolicy.allowed(it) else SitePolicy.trusted(it) }
                ?.let { openDocument(if (service) saved.getString("service_entry")?.takeIf(SingleSignOnPolicy::opensViewer)
                    ?: SingleSignOnPolicy.ENTRY else it, saved) }
            restoredDocument = null
        }
    }

    private fun localizeChrome(state: AppState) {
        chrome.localize(state)
        chrome.notices(if (state.hasCredentials) Notices.unread(state.notices) else 0, state)
        noticesSheet.render(state)
        renderHeading()
    }

    /**
     * Back on the first page sends the app behind, as Android 12+ does for a launcher task: the session and page stay
     * ready for the next start (the lock still applies after its timeout), and closing no longer tears the WebView down.
     */
    private fun leaveApp() { if (!moveTaskToBack(true)) finish() }

    /** The main WebView's address, or null once its renderer is gone and the view destroyed. */
    private fun currentUrl(): String? = if (rendererUnavailable) null else binding.webView.url

    private fun renderHeading() = chrome.heading(SitePolicy.section(currentUrl()), pageTitle, pageLoading, ::text)

    private fun updatePageTitle() {
        val epoch = pageEpoch
        val url = binding.webView.url ?: return
        if (!SitePolicy.trusted(url)) return
        binding.webView.evaluateJavascript(PageScripts.HEADING) { result ->
            if (isDestroyed || epoch != pageEpoch || url != binding.webView.url) return@evaluateJavascript
            pageTitle = runCatching { JSONArray(result) }.getOrNull()?.optString(0)?.takeIf { it.isNotBlank() }
            pageLoading = false
            renderHeading()
        }
    }

    private fun updateDrawerLock() = chrome.drawerUsable(model.state.value.hasCredentials && !documents.isOpen && !sessionChanging)

    /** Univer's own Moodle hand-off, the same route as the site's useful link and the home tile. */
    private fun openMoodle() {
        if (sessionChanging || !model.state.value.hasCredentials || model.state.value.paused) return
        if (rendererUnavailable) { retry(); return }
        route(SingleSignOnPolicy.ENTRY)
    }

    private fun openSection(section: AppSection) {
        if (sessionChanging || !model.state.value.hasCredentials) return
        if (rendererUnavailable) { retry(); return }
        if (documents.isOpen) documents.close()
        loadPage(section.url)
    }

    /** A GET of a trusted page with the error cleared and automation running; its address is the page's retry target. */
    private fun loadPage(url: String) {
        pageFailed = false
        binding.errorOverlay.isVisible = false
        automation.resume()
        navigationGetUrl = url
        binding.webView.loadUrl(url)
    }

    /** Opens the website's full section list (the adapted #tools menu) on the current page. */
    private fun showSiteSections() {
        if (documents.isOpen || rendererUnavailable || !SitePolicy.trusted(binding.webView.url)) return
        binding.webView.evaluateJavascript(PageScripts.SECTIONS) { opened -> if (opened != "true" && !isDestroyed) openSection(AppSection.HOME) }
    }

    /** A GET of the current address: a form result is never posted again. */
    private fun refreshPage() {
        if (documents.isOpen) return
        if (rendererUnavailable || pageFailed) { retryFailedPage(); return }
        val url = binding.webView.url?.takeIf(SitePolicy::trusted) ?: run { retry(); return }
        automation.resume()
        navigationGetUrl = url
        binding.webView.loadUrl(url)
    }

    private fun setMobileLayout(enabled: Boolean, form: SettingsSheetBinding? = null) {
        if (enabled == model.state.value.mobileLayout) return
        lifecycleScope.launch {
            try { model.setMobileLayout(enabled) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (form != null) { forms.feedback(form, R.string.storage_error); forms.update(form, forms.isFirst(form)) } else message(R.string.storage_error)
            }
        }
    }

    private fun setTextZoom(percent: Int) {
        if (TextZoom.clamp(percent) == model.state.value.textZoom) return
        lifecycleScope.launch {
            try { model.setTextZoom(percent) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message(R.string.storage_error) }
        }
    }

    private fun showTextSize() {
        val labels = model.state.value.language.context(this)
        val density = resources.displayMetrics.density
        // Dialogs need the activity's window; only their strings come from the app-language context.
        val value = TextView(this).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            gravity = Gravity.CENTER
        }
        val slider = Slider(this).apply {
            valueFrom = TextZoom.MIN.toFloat(); valueTo = TextZoom.MAX.toFloat(); stepSize = 10f
            this.value = model.state.value.textZoom.toFloat()
            setLabelFormatter { getString(R.string.percent_value, it.toInt()) }
            contentDescription = labels.getString(R.string.text_size)
        }
        value.text = getString(R.string.percent_value, slider.value.toInt())
        slider.addOnChangeListener { _, current, fromUser -> value.text = getString(R.string.percent_value, current.toInt()); if (fromUser) setTextZoom(current.toInt()) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val side = (24 * density).toInt()
            setPadding(side, (8 * density).toInt(), side, 0)
            addView(value); addView(slider)
        }
        MaterialAlertDialogBuilder(this).setTitle(labels.getString(R.string.text_size)).setIcon(R.drawable.ic_text_size)
            .setView(content).setPositiveButton(labels.getString(R.string.done), null)
            .setNeutralButton(labels.getString(R.string.reset)) { _, _ -> setTextZoom(TextZoom.DEFAULT) }.show().also(::track)
    }

    private fun printPage() {
        if (rendererUnavailable || !SitePolicy.trusted(binding.webView.url)) return
        val manager = getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        val name = binding.toolbar.title?.toString()?.ifBlank { null } ?: "Univer"
        try { manager.print(name, binding.webView.createPrintDocumentAdapter(name), null) }
        catch (_: Exception) { message(R.string.print_failed) }
    }

    private fun openInBrowser() {
        val url = (documents.webView?.url ?: currentUrl())?.takeIf(SitePolicy::trusted) ?: SitePolicy.HOME
        openExternal(url)
    }

    private fun openDownloads() {
        if (model.state.value.locked || AppLockSession.locked) return
        startActivity(DownloadsActivity.intent(this, model.state.value.language))
    }

    // Session: save, log out, erase.

    private fun save(form: SettingsSheetBinding, first: Boolean) {
        if (sessionChanging) return
        val login = form.login.text?.toString().orEmpty().trim()
        val password = form.password.text?.toString().orEmpty()
        form.loginLayout.error = if (login.isEmpty()) text(R.string.login_required) else null
        form.passwordLayout.error = if (password.isEmpty()) text(R.string.password_required) else null
        if (login.isEmpty() || password.isEmpty()) {
            (if (login.isEmpty()) form.login else form.password).requestFocus()
            return
        }
        val changed = model.credentials?.let { it.login != login || it.password != password } ?: true
        val wasPaused = model.state.value.paused
        sessionChanging = true
        form.save.isEnabled = false
        form.save.text = text(R.string.saving)
        automation.pause()
        documents.pause()
        lifecycleScope.launch {
            try {
                model.save(login, password)
                if (changed) { clearSession(); model.sessionCleared(); webOpened = false; restoredWebState = null }
                forms.dismiss()
                binding.errorOverlay.isVisible = false
                automationStatus = AutomationStatus.IDLE
                lock.maybeOfferPin()
                if (!first && !changed && wasPaused) {
                    noticePresentation.setEnabled(true, retry = true)
                    binding.webView.loadUrl(SitePolicy.HOME)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { forms.feedback(form, R.string.storage_error) }
            finally {
                sessionChanging = false
                if (isActive) {
                    form.save.isEnabled = true
                    forms.update(form, first)
                    render(model.state.value)
                    if (rendererUnavailable) recreate()
                    else if (documents.isOpen) documents.resume() else automation.resume()
                }
            }
        }
    }

    private fun endSession(delete: Boolean) {
        if (sessionChanging) return
        sessionChanging = true
        model.guard.stop()
        lifecycleScope.launch {
            try {
                model.endSession(delete)
                clearSession()
                model.sessionCleared()
                webOpened = false
                restoredWebState = null
                automationStatus = AutomationStatus.IDLE
                forms.dismiss()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { forms.sheetError(R.string.storage_error) }
            finally { sessionChanging = false; if (isActive) render(model.state.value) }
        }
    }

    /** A logout or account change was committed but the Activity was recreated before the site session was wiped. */
    private fun finishInterruptedSessionClear() {
        sessionChanging = true
        lifecycleScope.launch {
            try { clearSession(); model.sessionCleared(); webOpened = false; restoredWebState = null }
            catch (cancelled: CancellationException) { throw cancelled }
            finally { sessionChanging = false; if (isActive) render(model.state.value) }
        }
    }

    private suspend fun clearSession() {
        noticeLoading.dismiss()
        noticePresentation.setEnabled(false)
        noticesSheet.dismiss()
        noticeBridge.reset()
        sessionEpoch++
        downloads.reset()
        fileChooser.clear()
        DownloadService.cancelAll(this)
        documents.close(false)
        documentSourcePosition = null
        restoredDocument = null
        automation.pause()
        pageTitle = null
        if (!rendererUnavailable) {
            binding.webView.stopLoading()
            binding.webView.loadUrl("about:blank")
        }
        WebStorage.getInstance().deleteAllData()
        if (!rendererUnavailable) {
            binding.webView.clearCache(true)
            binding.webView.clearHistory()
            binding.webView.clearSslPreferences()
        }
        suspendCancellableCoroutine { continuation ->
            CookieManager.getInstance().removeAllCookies {
                CookieManager.getInstance().flush()
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }

    // Page errors and recovery.

    private fun retry() {
        if (sessionChanging || !model.state.value.hasCredentials) return
        documents.close(false)
        documentSourcePosition = null
        failedPageUrl = null
        pageFailed = false
        lifecycleScope.launch {
            try {
                model.resumeAutoLogin()
                if (rendererUnavailable) { recreate(); return@launch }
                automationStatus = AutomationStatus.IDLE
                binding.errorOverlay.isVisible = false
                noticePresentation.setEnabled(true, retry = true)
                automation.resume()
                automation.open()
                renderBanner()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message(R.string.storage_error) }
        }
    }

    private fun retryFailedPage() {
        if (rendererUnavailable) { retry(); return }
        val url = failedPageUrl?.takeIf(SitePolicy::trusted)
        if (url == null || model.state.value.paused) { retry(); return }
        if (sessionChanging || !model.state.value.hasCredentials) return
        pageFailed = false
        binding.errorOverlay.isVisible = false
        configureNoticePresentation()
        automation.resume()
        binding.webView.loadUrl(url) // Only URLs observed in a GET request can reach this branch.
    }

    /** A connection failure or a temporary server answer: worth loading again by itself. */
    private fun retryableError() = !rendererUnavailable && ((errorMessageId == R.string.network_error && errorHttpStatus == null) ||
        errorHttpStatus?.let { it >= 500 || it == 408 || it == 429 } == true)

    /** Schedules [recoverFailedPage] after the page backoff's wait (or [delay]). */
    private fun schedulePageRecovery(delay: Long? = null) {
        binding.root.removeCallbacks(pageRecoveryTask)
        binding.root.removeCallbacks(noticeTicker)
        if (pageFailed && retryableError())
            binding.root.postDelayed(pageRecoveryTask, delay ?: model.pageRecovery.delay(SystemClock.elapsedRealtime()))
    }

    /**
     * The automatic Retry of a failed page while the screen is visible: a GET of its address (the start page after a
     * failed POST), never a replayed form. Attempt budgets are not reset, unlike the explicit Retry.
     */
    private fun recoverFailedPage() {
        val state = model.state.value
        if (!pageFailed || !retryableError() || sessionChanging || documents.isOpen || !state.hasCredentials ||
            state.paused || state.locked || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        model.pageRecovery.spend(SystemClock.elapsedRealtime())
        pageFailed = false
        configureNoticePresentation()
        loadPage(failedPageUrl?.takeIf(SitePolicy::trusted) ?: SitePolicy.HOME)
        startNoticeChecks()
    }

    /**
     * A renderer the system killed in the background or that crashed: the screen is recreated by itself while it is
     * visible, ever more slowly if the renderer keeps failing. The recreated screen reopens the section by a GET.
     */
    private fun recoverRenderer() {
        val state = model.state.value
        if (!rendererUnavailable || rendererRecoveryPending || sessionChanging || !state.hasCredentials || state.paused ||
            state.locked || forms.showing || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        rendererRecoveryPending = true
        val now = SystemClock.elapsedRealtime()
        val delay = model.rendererRecovery.delay(now)
        model.rendererRecovery.spend(now)
        binding.root.postDelayed({
            if (!isDestroyed && !isFinishing && rendererUnavailable && !sessionChanging) recreate()
            else rendererRecoveryPending = false
        }, delay)
    }

    /** The network is back: a page that failed for want of it is loaded again shortly. */
    private fun registerNetworkCallback() {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    if (!isDestroyed && pageFailed && errorHttpStatus == null && errorMessageId == R.string.network_error)
                        schedulePageRecovery(1_000)
                }
            }
        }
        try { connectivity.registerDefaultNetworkCallback(callback); networkCallback = callback }
        catch (_: RuntimeException) { /* Too many callbacks or no permission: the backoff alone still retries. */ }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        try { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
        catch (_: RuntimeException) { }
    }

    private fun configureNoticePresentation() {
        val state = model.state.value
        noticePresentation.setEnabled(state.hasCredentials && !state.paused && !model.guard.blocked &&
            !sessionChanging && !pageFailed && !rendererUnavailable && automationStatus in setOf(
                AutomationStatus.IDLE, AutomationStatus.SIGNING_IN, AutomationStatus.NOTICE_CONFIRMING))
    }

    private fun renderBanner() {
        if (!model.state.value.hasCredentials) { binding.statusBar.isVisible = false; return }
        val paused = model.state.value.paused
        val id = when {
            paused -> R.string.paused
            automationStatus == AutomationStatus.LOGIN_FAILED -> R.string.login_failed
            automationStatus == AutomationStatus.NOTICE_FAILED -> R.string.notice_failed
            automationStatus == AutomationStatus.AGREEMENT_FAILED -> R.string.agreement_failed
            automationStatus == AutomationStatus.LANGUAGE_FAILED -> R.string.language_failed
            automationStatus == AutomationStatus.ELEMENTS_MISSING -> R.string.elements_missing
            else -> null
        }
        Motion.show(binding.statusBar, id != null, rise = true)
        if (id != null) binding.statusText.text = text(id)
        // A paused session is information; a failed automation step needs the user's attention.
        val container = if (paused) R.color.md_secondary_container else R.color.warning_container
        val content = if (paused) R.color.md_on_secondary_container else R.color.on_warning_container
        binding.statusBar.backgroundTintList = ColorStateList.valueOf(getColor(container))
        binding.statusText.setTextColor(getColor(content))
        binding.statusIcon.setImageResource(if (paused) R.drawable.ic_info else R.drawable.ic_warning)
        binding.statusIcon.imageTintList = ColorStateList.valueOf(getColor(content))
        binding.statusAction.isVisible = automationStatus != AutomationStatus.SIGNING_IN || paused
        binding.statusAction.text = text(if (paused) R.string.sign_in else R.string.retry)
        localizeChrome(model.state.value)
    }

    private fun showError(id: Int, httpStatus: Int? = null) {
        noticeLoading.dismiss()
        noticePresentation.setEnabled(false)
        pageFailed = true
        errorMessageId = id
        errorHttpStatus = httpStatus
        binding.errorMessage.text = model.state.value.language.context(this).webErrorText(id, httpStatus)
        binding.errorIcon.setImageResource(if (id == R.string.network_error || id == R.string.tls_error) R.drawable.ic_cloud_off else R.drawable.ic_warning)
        Motion.show(binding.errorOverlay, model.state.value.hasCredentials)
        binding.loading.isVisible = false
        pageLoading = false
        renderHeading()
        automation.pause()
        // An error page is not the site's answer to a sign-in in flight: the next fresh form is not its rejection.
        model.guard.detach()
        schedulePageRecovery()
    }

    // The main WebView.

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        binding.webView.configureSecureSettings()
        baseTextZoom = binding.webView.settings.textZoom
        binding.webView.webViewClient = PageClient()
        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(webView: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams) =
                fileChooser.show(callback, params)
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (!isUserGesture) return false
                // A page's new window only tells where it wanted to go; that address is routed like a link.
                val popup = WebView(this@MainActivity)
                var retired = false
                // A window that never navigates leaves the callback without a navigation event, so it is closed by itself.
                fun retire() {
                    if (retired) return
                    retired = true
                    (popup.parent as? android.view.ViewGroup)?.removeView(popup)
                    popup.destroy()
                }
                popup.configureNavigationCapture()
                popup.postDelayed(::retire, 3_000)
                popup.webViewClient = object : WebViewClient() {
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        retired = true
                        (view.parent as? android.view.ViewGroup)?.removeView(view)
                        view.destroy()
                        return true
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url.toString()
                        if (logoutNavigation.onNavigation(request)) { retire(); return true }
                        if (!sessionChanging) websiteLanguage.onNavigation(request)
                        if (!route(url)) binding.webView.loadUrl(url)
                        retire()
                        return true
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }
        binding.webView.setDownloadListener { url, agent, disposition, mime, _ -> downloads.download(url, agent, disposition, mime) }
    }

    /** Navigation policy, page lifecycle, errors and renderer loss of the website's WebView. */
    private inner class PageClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return !SitePolicy.trusted(request.url.toString())
            if (logoutNavigation.onNavigation(request)) return true
            // The site sends a request of an ended session to its login form: reopen that page after the login.
            if (SitePolicy.loginPage(request.url.toString()) && (request.isRedirect || !request.hasGesture()))
                navigationGetUrl?.takeIf(SitePolicy::resumable)?.let(automation::resumeAfterLogin)
            else if (request.hasGesture() && !request.isRedirect) automation.resumeAfterLogin(null)
            navigationGetUrl = request.url.toString().takeIf { request.method.equals("GET", true) && SitePolicy.trusted(it) }
            if (!sessionChanging && websiteLanguage.onNavigation(request)) return false
            // A tap on Главная or the news pager opens the direct news list; the login redirect still passes
            // through the site's notice route.
            if (!sessionChanging && request.hasGesture() && !request.isRedirect) {
                SitePolicy.newsShortcut(request.url.toString())?.takeIf { it != request.url.toString() }?.let {
                    navigationGetUrl = it; view.loadUrl(it); return true
                }
            }
            return route(request.url.toString())
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            pageEpoch++
            navigationGetUrl = navigationGetUrl?.takeIf { it == url }
            automation.pageStarted()
            mobilePage.pageStarted(url)
            if (SitePolicy.trusted(url)) {
                if (url != null && url.toUri().path?.matches(NOTICE_PATH) == true &&
                    model.state.value.hasCredentials && !model.state.value.paused && !model.guard.blocked)
                    noticeLoading.status(AutomationStatus.NOTICE_CONFIRMING)
                binding.loading.isVisible = true
                binding.errorOverlay.isVisible = false
                pageFailed = false
                pageTitle = null; pageLoading = true
                renderHeading()
            } else if (url != "about:blank") view.stopLoading()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (url != view.url) return
            noticePresentation.apply()
            binding.loading.isVisible = false
            CookieManager.getInstance().flush()
            mobilePage.apply(model.state.value.mobileLayout, model.state.value.language)
            if (!SitePolicy.trusted(url) || sessionChanging) return
            val finishedEpoch = pageEpoch
            view.evaluateJavascript(PageScripts.STATUS) { result ->
                if (isDestroyed || finishedEpoch != pageEpoch || url != view.url || sessionChanging) return@evaluateJavascript
                // The request callback knows its method and retry URL. A later status probe
                // must not erase that information or replace a network/TLS failure.
                if (pageFailed) return@evaluateJavascript
                val status = result.toIntOrNull() ?: 0
                if (status >= 400) {
                    failedPageUrl = navigationGetUrl
                    showError(httpErrorMessage(status), status)
                } else {
                    model.pageRecovery.reset()
                    automation.pageFinished()
                    if (!model.state.value.mobileLayout) updatePageTitle()
                }
            }
        }

        override fun onPageCommitVisible(view: WebView, url: String?) {
            noticePresentation.apply()
            mobilePage.apply(model.state.value.mobileLayout, model.state.value.language)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame && request.url.toString() == view.url) {
                failedPageUrl = request.url.toString().takeIf { request.method.equals("GET", true) && SitePolicy.trusted(it) }
                showError(R.string.network_error)
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (request.isForMainFrame && request.url.toString() == view.url && response.statusCode >= 400) {
                failedPageUrl = request.url.toString().takeIf { request.method.equals("GET", true) && SitePolicy.trusted(it) }
                showError(httpErrorMessage(response.statusCode), response.statusCode)
            }
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            if (error.url == view.url) {
                failedPageUrl = navigationGetUrl?.takeIf(SitePolicy::trusted)
                showError(R.string.tls_error)
            }
        }

        override fun onFormResubmission(view: WebView, dontResend: Message, resend: Message) { dontResend.sendToTarget() }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            noticeLoading.dismiss()
            noticePresentation.close()
            mobilePage.close()
            shortcutBridge.close()
            noticeBridge.close()
            rendererUnavailable = true
            pageEpoch++
            lostPageUrl = view.url?.takeIf(SitePolicy::resumable)
            documents.close(false)
            automation.close() // Detaches an attempt in flight; the attempt budgets stay.
            binding.webView.isVisible = false
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view.destroy()
            failedPageUrl = null
            showError(R.string.webview_crashed)
            // A recreated screen reopens the section by a GET; a POST is never replayed.
            recoverRenderer()
            return true
        }
    }

    /** Where a link goes: the document viewer, a download, the website itself, or another app. */
    private fun route(url: String): Boolean {
        if (sessionChanging) return true
        if (SingleSignOnPolicy.opensViewer(url)) { openDocument(url); return true }
        if (SitePolicy.documentDownload(url)) {
            downloads.download(url, binding.webView.settings.userAgentString, null, null)
            return true
        }
        if (SitePolicy.printDocument(url)) { openDocument(url); return true }
        if (SitePolicy.trusted(url)) return false
        val uri = url.toUri()
        if (uri.scheme == "http" && uri.host.equals(SitePolicy.ORIGIN.toUri().host, true)) {
            binding.webView.loadUrl(uri.buildUpon().scheme("https").build().toString())
        } else openExternal(url)
        return true
    }

    private fun openDocument(url: String, saved: Bundle? = null) {
        automation.pause()
        if (!documents.isOpen && documentSourcePosition == null)
            documentSourcePosition = Pair(binding.webView.scrollX, binding.webView.scrollY)
        binding.drawer.closeDrawer(GravityCompat.START, false)
        val service = saved?.getBoolean("service") ?: SingleSignOnPolicy.opensViewer(url)
        documents.open(url, model.state.value.language, saved, service = service)
        if (service || !model.state.value.hasCredentials || lock.forceSecure()) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        documents.update(model.state.value.language, model.state.value.textZoom)
        updateDrawerLock()
    }

    private fun openExternal(url: String) {
        val uri = url.toUri()
        if (uri.scheme !in listOf("http", "https", "mailto", "tel")) { message(R.string.open_failed); return }
        try { startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)) }
        catch (_: ActivityNotFoundException) { message(R.string.open_failed) }
    }

    // Lifecycle.

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("download_event", handledDownloadEvent)
        lock.save(outState)
        outState.putInt("session_epoch", sessionEpoch)
        outState.putBoolean("settings_open", forms.showing && !sessionChanging)
        if (!sessionChanging) downloads.save(outState)
        if (!sessionChanging) documents.save()?.let {
            outState.putBundle("document_state", it)
            documentSourcePosition?.let { position ->
                outState.putInt("document_source_x", position.first)
                outState.putInt("document_source_y", position.second)
            }
        }
        // A restored login form or read notice may come from the cache of an ended session (an expired key, a
        // confirmation the server never records): the start page asks the site for the current one instead.
        if (webOpened && !sessionChanging && !rendererUnavailable && SitePolicy.trusted(binding.webView.url) &&
            !SitePolicy.loginPage(binding.webView.url) && binding.webView.url?.toUri()?.path?.matches(NOTICE_PATH) != true) {
            val state = Bundle()
            binding.webView.saveState(state)
            outState.putBundle("web_state", state)
        }
        if (rendererUnavailable && !sessionChanging) lostPageUrl?.let { outState.putString("lost_page", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        registerNetworkCallback()
    }

    override fun onStop() {
        unregisterNetworkCallback()
        super.onStop()
    }

    override fun onResume() {
        super.onResume(); if (!rendererUnavailable) binding.webView.onResume()
        lock.inForeground = true
        lock.applyWindowSecurity()
        lock.autoPromptBiometric()
        if (rendererUnavailable) { recoverRenderer(); return }
        if (model.state.value.locked) return
        if (pageFailed) schedulePageRecovery()
        if (documents.isOpen) documents.resume() else { automation.resume(); scanNotices() }
        startNoticeChecks()
    }

    /** (Re)starts the periodic notice check; it stops while the app is paused, locked or a page is failed. */
    private fun startNoticeChecks() {
        binding.root.removeCallbacks(noticeTicker)
        if (lock.inForeground) binding.root.postDelayed(noticeTicker, NOTICE_CHECK_MS)
    }

    override fun onPause() {
        // With a PIN set the window turns secure before the system takes the recent-apps picture.
        lock.inForeground = false
        lock.applyWindowSecurity()
        binding.root.removeCallbacks(noticeTicker)
        automation.pause(); CookieManager.getInstance().flush()
        documents.pause()
        if (!rendererUnavailable) binding.webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        binding.root.removeCallbacks(pageRecoveryTask)
        dismissTransientDialogs()
        lockUi.hide()
        noticeLoading.close()
        noticePresentation.close()
        mobilePage.close()
        shortcutBridge.close()
        noticeBridge.close()
        noticesSheet.dismiss()
        automation.close()
        documents.close(false)
        downloads.reset()
        fileChooser.cancel()
        forms.dismiss()
        if (!rendererUnavailable) {
            binding.webView.stopLoading()
            binding.webView.webChromeClient = null
            binding.webView.webViewClient = WebViewClient()
            (binding.webView.parent as? android.view.ViewGroup)?.removeView(binding.webView)
            binding.webView.destroy()
        }
        super.onDestroy()
    }

    /** What the screen's parts ask of the page and the session. */
    private inner class Hosts : DownloadLauncher.Host, LockFlow.Host, SettingsForms.Host {
        override fun text(id: Int) = this@MainActivity.text(id)
        override fun message(id: Int) = this@MainActivity.message(id)
        override fun track(dialog: Dialog) = this@MainActivity.track(dialog)
        override val sessionChanging get() = this@MainActivity.sessionChanging
        override val sessionEpoch get() = this@MainActivity.sessionEpoch
        override val rendererUnavailable get() = this@MainActivity.rendererUnavailable
        override val serviceOpen get() = documents.isService
        override val pageUrl get() = documents.webView?.url ?: currentUrl()
        override val pageSource get() = when {
            documents.isService -> documents.webView?.title?.takeUnless { it.startsWith("http") }.orEmpty()
            documents.isOpen -> ""
            else -> pageTitle.orEmpty()
        }
        override val userAgent: String get() = binding.webView.settings.userAgentString
        override fun openExternal(url: String) = this@MainActivity.openExternal(url)
        override fun openDownloads() = this@MainActivity.openDownloads()
        override fun downloadStarted() {
            Snackbar.make(binding.content, text(R.string.download_started), Snackbar.LENGTH_LONG).setAction(text(R.string.downloads)) { openDownloads() }.show()
        }
        override fun unsupported() {
            Snackbar.make(binding.content, text(R.string.unsupported_download), Snackbar.LENGTH_LONG)
                .setAction(text(R.string.browser)) { currentUrl()?.let(::openExternal) }.show()
        }
        override fun signInAgain(moodle: Boolean): Boolean {
            // Moodle files renew the Moodle session through Univer; Univer files reload the page, which the site sends to its login form.
            if (moodle) return documents.isService && documents.signInAgain()
            if (documents.isOpen) documents.close()
            loadPage(binding.webView.url?.takeIf(SitePolicy::trusted) ?: SitePolicy.HOME)
            return true
        }
        override fun coverPage(covered: Boolean) {
            if (covered) {
                // Hidden and unreadable for accessibility services, as well as covered: the page is not in view at all.
                binding.drawer.visibility = View.INVISIBLE
                binding.drawer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                automation.pause()
                documents.pause()
            } else {
                binding.drawer.visibility = View.VISIBLE
                binding.drawer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                if (webOpened && !rendererUnavailable) { if (documents.isOpen) documents.resume() else automation.resume() }
            }
        }
        override fun dismissDialogs() { dismissTransientDialogs(); forms.dismiss(); noticesSheet.dismiss() }
        override fun eraseAccount() = endSession(true)
        override fun save(form: SettingsSheetBinding, first: Boolean) = this@MainActivity.save(form, first)
        override fun endSession(delete: Boolean) = this@MainActivity.endSession(delete)
        override fun setMobileLayout(enabled: Boolean, form: SettingsSheetBinding?) = this@MainActivity.setMobileLayout(enabled, form)
        override fun setTextZoom(percent: Int) = this@MainActivity.setTextZoom(percent)
    }

    private companion object {
        val NOTICE_PATH = Regex("/student/attentionStudents/[0-9]+/?")
        /** The notification check: the open page, and the sections when [NoticeBridge] last fetched them this long ago. */
        const val NOTICE_CHECK_MS = 10 * 60_000L
    }
}
