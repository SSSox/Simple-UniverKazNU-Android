package com.univerflow.kaznu

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.print.PrintManager
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
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.GravityCompat
import androidx.core.view.OneShotPreDrawListener
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
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import com.univerflow.kaznu.data.AppPreferences
import com.univerflow.kaznu.data.CredentialVault
import com.univerflow.kaznu.data.DownloadRepository
import com.univerflow.kaznu.data.LocalSessionRepository
import com.univerflow.kaznu.data.LockPreferences
import com.univerflow.kaznu.data.LockVault
import com.univerflow.kaznu.data.GradeStore
import com.univerflow.kaznu.data.ExamStore
import com.univerflow.kaznu.data.LastGradesStore
import com.univerflow.kaznu.data.TimetableStore
import com.univerflow.kaznu.databinding.ActivityMainBinding
import com.univerflow.kaznu.databinding.SettingsSheetBinding
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.domain.DownloadState
import com.univerflow.kaznu.domain.HomeShortcuts
import com.univerflow.kaznu.domain.Notices
import com.univerflow.kaznu.domain.ReminderSettings
import com.univerflow.kaznu.domain.Shortcuts
import com.univerflow.kaznu.domain.StudyFolders
import com.univerflow.kaznu.domain.TextZoom
import com.univerflow.kaznu.domain.GradeTotals
import com.univerflow.kaznu.domain.ExamItem
import com.univerflow.kaznu.domain.ExamKind
import com.univerflow.kaznu.domain.LastGrades
import com.univerflow.kaznu.domain.CalendarEvent
import com.univerflow.kaznu.domain.Timetable
import com.univerflow.kaznu.download.DownloadEvent
import com.univerflow.kaznu.download.DownloadService
import com.univerflow.kaznu.launch.AppShortcuts
import com.univerflow.kaznu.launch.CalendarHandoff
import com.univerflow.kaznu.launch.LaunchTarget
import com.univerflow.kaznu.main.DiagnosticsExport
import com.univerflow.kaznu.main.DownloadLauncher
import com.univerflow.kaznu.main.LockFlow
import com.univerflow.kaznu.main.MainChrome
import com.univerflow.kaznu.main.NetworkWatch
import com.univerflow.kaznu.main.NoticeFlow
import com.univerflow.kaznu.main.PageClient
import com.univerflow.kaznu.main.ReportFlow
import com.univerflow.kaznu.main.ScheduleFlow
import com.univerflow.kaznu.main.SettingsForms
import com.univerflow.kaznu.main.SiteData
import com.univerflow.kaznu.main.StatusBanner
import com.univerflow.kaznu.main.TextSizeDialog
import com.univerflow.kaznu.main.UpdateFlow
import com.univerflow.kaznu.report.GradeReportFiles
import com.univerflow.kaznu.grades.GradeAlerts
import com.univerflow.kaznu.today.ExamReminders
import com.univerflow.kaznu.today.TodaySync
import com.univerflow.kaznu.ui.AppLockSession
import com.univerflow.kaznu.ui.DocumentViewer
import com.univerflow.kaznu.ui.DownloadsActivity
import com.univerflow.kaznu.ui.FileChooser
import com.univerflow.kaznu.ui.FileViewerActivity
import com.univerflow.kaznu.ui.LanguageResources
import com.univerflow.kaznu.ui.LinkViewerActivity
import com.univerflow.kaznu.ui.LockUi
import com.univerflow.kaznu.ui.Motion
import com.univerflow.kaznu.ui.NoticeLoadingOverlay
import com.univerflow.kaznu.ui.ThemeMode
import com.univerflow.kaznu.ui.LastGradesActivity
import com.univerflow.kaznu.ui.TimetableActivity
import com.univerflow.kaznu.ui.attempt
import com.univerflow.kaznu.ui.launchCatching
import com.univerflow.kaznu.ui.paintSystemBands
import com.univerflow.kaznu.update.UpdateManager
import com.univerflow.kaznu.web.AppSection
import com.univerflow.kaznu.web.AutomationController
import com.univerflow.kaznu.web.BviStub
import com.univerflow.kaznu.web.AutomationStatus
import com.univerflow.kaznu.web.ChannelGuard
import com.univerflow.kaznu.web.LinkOpening
import com.univerflow.kaznu.web.LinkPolicy
import com.univerflow.kaznu.web.LinkProfile
import com.univerflow.kaznu.web.LogoutNavigation
import com.univerflow.kaznu.web.MobilePageAdapter
import com.univerflow.kaznu.web.NoticePresentation
import com.univerflow.kaznu.web.PageCover
import com.univerflow.kaznu.web.PageScripts
import com.univerflow.kaznu.web.RetiredWebViewClient
import com.univerflow.kaznu.web.ShortcutBridge
import com.univerflow.kaznu.web.SingleSignOnPolicy
import com.univerflow.kaznu.web.GradesMessage
import com.univerflow.kaznu.web.SitePolicy
import com.univerflow.kaznu.web.Trackers
import com.univerflow.kaznu.web.WebsiteLanguageSync
import com.univerflow.kaznu.web.captureNewWindow
import com.univerflow.kaznu.web.configureSecureSettings
import com.univerflow.kaznu.web.enableBackForwardCache
import com.univerflow.kaznu.web.evaluate
import com.univerflow.kaznu.web.flushCookiesSoon
import com.univerflow.kaznu.web.httpErrorMessage
import com.univerflow.kaznu.web.webErrorText
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The website in a WebView with its automation, session and recovery. Chrome, settings, the app lock, downloads and
 * notifications are their own parts ([MainChrome], [SettingsForms], [LockFlow], [DownloadLauncher], [NoticeFlow]);
 * this class wires them.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var model: UniverViewModel
    private lateinit var automation: AutomationController
    private lateinit var mobilePage: MobilePageAdapter
    private lateinit var shortcutBridge: ShortcutBridge
    /** Keys the app's own page messages; registered before the first page so every main-frame page has it. */
    private lateinit var channel: ChannelGuard
    private lateinit var notices: NoticeFlow
    private lateinit var websiteLanguage: WebsiteLanguageSync
    private lateinit var documents: DocumentViewer
    /** The link window answers with a Univer or Moodle page the user tapped there. */
    /** The answer to the notification permission asked for class reminders. */
    private var notificationsAnswer: ((Boolean) -> Unit)? = null
    private val notificationsPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        notificationsAnswer?.invoke(granted)
        notificationsAnswer = null
    }
    /** The reminder setting and language the widget and the alarm were last brought in step with. */
    private var todayKey: Pair<ReminderSettings, AppLanguage>? = null
    private var gradesKey: Boolean? = null
    private val linkWindow = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringExtra(LinkViewerActivity.RESULT_URL)?.let(::linkReturned)
    }
    private var downloadStartedBar: Snackbar? = null
    private lateinit var noticeLoading: NoticeLoadingOverlay
    private lateinit var noticePresentation: NoticePresentation
    private lateinit var lockUi: LockUi
    private lateinit var chrome: MainChrome
    private lateinit var banner: StatusBanner
    private lateinit var lock: LockFlow
    private lateinit var forms: SettingsForms
    private lateinit var downloads: DownloadLauncher
    private lateinit var reports: ReportFlow
    private lateinit var schedule: ScheduleFlow
    private lateinit var updates: UpdateFlow
    private val hosts = Hosts()
    private val languages = LanguageResources(this)
    private val logoutNavigation = LogoutNavigation { forms.confirmSignOut() }
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
    private var reportedDrawn = false
    /** A shortcut's (or the widget's) section still to open: it waits for the account, the PIN and a running login. */
    private var launchTarget: LaunchTarget? = null
    /** The language the launcher shortcuts were last named in; null once removed, unset before the first render. */
    private var shortcutsLanguage: Any? = Unit
    private var sessionChanging = false
    private var sessionEpoch = 0
    private var automationStatus = AutomationStatus.IDLE
    private var errorMessageId = R.string.network_error
    private var errorHttpStatus: Int? = null
    private var restoredWebState: Bundle? = null
    private var backPending = false
    private var failedPageUrl: String? = null
    private var navigationGetUrl: String? = null
    /** Until when (elapsed realtime) the online test's redirect may follow the student's tap; main thread only. */
    private var onlineTestUntil = 0L
    private var pageFailed = false
    private var pageEpoch = 0L
    private var rendererUnavailable = false
    /** The section shown when the renderer was lost; the recreated screen opens it again. */
    private var lostPageUrl: String? = null
    private var rendererRecoveryPending = false
    private val pageRecoveryTask = Runnable { recoverFailedPage() }
    // The page plays its own entrance; the placeholder leaves at once instead of fading over it.
    private val cover = PageCover { visible, atOnce ->
        when {
            !visible -> Motion.drop(binding.pageSkeleton)
            atOnce -> Motion.cover(binding.pageSkeleton)
            else -> Motion.show(binding.pageSkeleton, true)
        }
    }
    /** The network is back: a page that failed for want of it is loaded again shortly. */
    private val network = NetworkWatch(this) {
        if (!isDestroyed && pageFailed && errorHttpStatus == null && errorMessageId == R.string.network_error)
            schedulePageRecovery(1_000)
    }
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
        // The page modules are read once the first frame is drawn, while the first page loads: its pass then finds them
        // ready. Read during onCreate they held the asset lock the WebView's start needs and delayed that frame.
        OneShotPreDrawListener.add(binding.root) { binding.root.post { MobilePageAdapter.preload(assets, lifecycleScope) } }
        AppLockSession.initialize(this)
        lockUi = LockUi(this, ::text)
        chrome = MainChrome(this, binding)
        banner = StatusBanner(binding)
        lock = LockFlow(this, model, lockUi, hosts)
        forms = SettingsForms(this, model, lock, hosts)
        downloads = DownloadLauncher(this, model, hosts)
        reports = ReportFlow(this, model, hosts)
        schedule = ScheduleFlow(this, model, hosts)
        updates = UpdateFlow(this, model, hosts)
        // Leaving the app for longer than the chosen time locks it: the password leaves memory and the PIN is asked again.
        AppLockSession.track(this) {
            if (model.state.value.lock.enabled) binding.drawer.visibility = View.INVISIBLE
            launchCatching { model.lockNow() }
        }
        lock.restore(savedInstanceState)
        downloads.restore(savedInstanceState)
        noticeLoading = NoticeLoadingOverlay(binding.webView, binding.noticeLoadingOverlay)
        noticePresentation = NoticePresentation(binding.webView)
        val light = !ThemeMode.isDark(this)
        WindowCompat.getInsetsController(window, binding.root).apply { isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light }
        restoreState(savedInstanceState)
        // A recreated screen still holds the Intent it was started with: that request was already followed.
        if (savedInstanceState == null) launchTarget = requested(intent)
        applyContentInsets()
        binding.pageSkeleton.isVisible = true
        createPageParts()
        documents = createDocuments()
        bindControls()
        collectUpdates()
    }

    private fun restoreState(savedInstanceState: Bundle?) {
        restoredWebState = savedInstanceState?.getBundle("web_state")
        lostPageUrl = savedInstanceState?.getString("lost_page")
        reopenSettings = savedInstanceState?.getBoolean("settings_open") == true
        forms.tab = savedInstanceState?.getInt("settings_tab") ?: 0
        handledDownloadEvent = savedInstanceState?.getString("download_event")
        sessionEpoch = savedInstanceState?.getInt("session_epoch", 0) ?: 0
        launchTarget = LaunchTarget.parse(LaunchTarget.ACTION, savedInstanceState?.getString("launch_target"))
        restoredDocument = savedInstanceState?.getBundle("document_state")
        if (restoredDocument != null && savedInstanceState != null) documentSourcePosition = Pair(
            savedInstanceState.getInt("document_source_x"), savedInstanceState.getInt("document_source_y"))
    }

    // The drawer handles its own insets; the content frame keeps the app bar and website clear of system bars and the keyboard.
    private fun applyContentInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.content) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            view.paintSystemBands(bars.top)
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
            followLaunchTarget()
        }
        websiteLanguage = WebsiteLanguageSync(model, lifecycleScope, { selected ->
            // The flag itself performs the website switch. Render native labels without a second request.
            rendered = rendered.copy(language = selected)
            automation.websiteLanguageSelected()
            mobilePage.apply(model.state.value.mobileLayout, selected)
        }, { message(R.string.storage_error) })
        configureWebView()
        channel = ChannelGuard(binding.webView)
        mobilePage = MobilePageAdapter(binding.webView, nativeChrome = true, channel = channel, onConcealed = cover::concealed, onContentReady = {
            if (!rendererUnavailable && !sessionChanging && !pageFailed && SitePolicy.trusted(binding.webView.url)) {
                // A page Back restores from memory gets no onPageFinished: its readiness also ends a failed load beneath it.
                cover.loaded()
                endPull()
                firstPageShown()
                Motion.progress(binding.loading, false)
                noticePresentation.apply()
                automation.pageFinished()
                updatePageTitle()
                notices.scan()
                followLaunchTarget()
                if (!SitePolicy.loginPage(binding.webView.url)) lifecycleScope.launch(Dispatchers.IO) { GradeAlerts.sessionBack(applicationContext) }
                readJournal()
            }
        })
        // Home tile pins and order arrive from page taps; the validated, stored list is sent back to the page.
        // «Скачать все» and the grade report only from the page in view: never from a viewer over it or a changing or locked session.
        shortcutBridge = ShortcutBridge(binding.webView, channel, onDownloadAll = { batch ->
            if (pageInView()) downloads.downloadAll(batch.section, batch.urls)
        }, onReport = { report -> if (pageInView()) report?.let(reports::offer) ?: message(R.string.report_error) },
            onCalendar = { event ->
                if (pageInView()) when {
                    event == null -> message(R.string.calendar_error)
                    // The neighbouring week was not read: said first, the student decides.
                    event.partial -> schedule.confirm(text(R.string.calendar_part), { openInCalendar(event) })
                    else -> openInCalendar(event)
                }
            }, onTimetable = ::keepTimetable, onGrades = ::keepGrades, onExams = ::keepExams,
            onSchedule = { request -> if (pageInView()) schedule.offer(request) }) { request ->
            lifecycleScope.launch {
                if (!attempt(false) { model.updateShortcuts(request) }) message(R.string.pin_failed)
                shortcutBridge.configure(model.state.value.shortcuts)
            }
        }
        notices = NoticeFlow(this, binding, model, hosts)
    }

    private fun openInCalendar(event: CalendarEvent) {
        if (!CalendarHandoff.open(this, event)) message(R.string.calendar_missing)
    }

    /** The document and service (Moodle) window over the page, with its own automation for each kind. */
    private fun createDocuments() = DocumentViewer(this, binding.content, { web ->
        AutomationController(web, model, lifecycleScope, recover = { target ->
            if (!sessionChanging && web === documents.webView) web.loadUrl(target)
        }) { automationStatus = it; renderBanner() }
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
        }, serviceRetry = { model.singleSignOnGuard.renewal() }, chooseFiles = fileChooser::show,
        serviceSignedIn = downloads::signedIn, serviceSignedOut = { message(R.string.service_logged_out) })

    /**
     * The week the schedule page or Бакалавр read, kept for the widget, reminders and the offline schedule. Not while the
     * session changes: a page still open during a sign-out must not leave its week behind.
     */
    private fun keepTimetable(timetable: Timetable) {
        if (sessionChanging || model.sessionClearPending || !model.state.value.canBrowse) return
        val since = TimetableStore.generation
        val context = applicationContext
        lifecycleScope.launch(Dispatchers.IO) { if (TimetableStore.write(context, timetable, since)) TodaySync.changed(context) }
    }

    /** The exams or midterms the schedule page lists, kept for exam reminders; under the same guard as [keepTimetable]. */
    private fun keepExams(kind: ExamKind, items: List<ExamItem>) {
        if (sessionChanging || model.sessionClearPending || !model.state.value.canBrowse) return
        val since = ExamStore.generation
        val context = applicationContext
        lifecycleScope.launch(Dispatchers.IO) { if (ExamStore.replace(context, kind, items, since)) ExamReminders.schedule(context) }
    }

    /**
     * The totals the student sees become the background check's base, so a grade already seen is not announced: the
     * journal is read here on every display, in either layout; Кабинет's grades panel sends its copy. Only with
     * tracking on and not while the session changes. The app's language names the base, as in the background check.
     */
    private fun keepGrades(totals: GradeTotals) {
        val state = model.state.value
        if (state.paused || sessionChanging || model.sessionClearPending || !state.canBrowse ||
            !SitePolicy.gradesPage(binding.webView.url)) return
        val seen = totals.copy(language = state.language.siteCode)
        keepLastGrades(seen)
        if (!state.gradeAlerts) return
        val store = GradeStore.of(applicationContext)
        val since = store.generation
        lifecycleScope.launch(Dispatchers.IO) { store.keep(seen, since) }
    }

    /** The totals as the error screen's «Последние оценки», with or without background alerts. */
    private fun keepLastGrades(totals: GradeTotals) {
        val saved = LastGrades.of(totals, System.currentTimeMillis()) ?: return
        val store = LastGradesStore.of(applicationContext)
        val since = store.generation
        lifecycleScope.launch(Dispatchers.IO) { store.write(saved, since) }
    }

    private fun readJournal() {
        if (!SitePolicy.journalPage(binding.webView.url)) return
        binding.webView.evaluateJavascript(GradesMessage.READ) { value ->
            if (!isDestroyed && SitePolicy.journalPage(binding.webView.url)) GradesMessage.fromScript(value)?.let(::keepGrades)
        }
    }

    /** Background grade checks follow the setting: on only while an account can browse and is not paused. */
    private fun syncGrades(state: AppState) {
        val enabled = state.gradeAlerts && state.canBrowse && !state.paused && !model.sessionClearPending
        if (enabled == gradesKey) return
        gradesKey = enabled
        val context = applicationContext
        lifecycleScope.launch(GradeAlerts.lane) { GradeAlerts.sync(context, enabled) }
    }

    /** «Расписание без сети» under a network error, once a week is saved. */
    private fun offerOfflineSchedule() {
        binding.errorOffline.isVisible = false
        if (errorMessageId != R.string.network_error && errorMessageId != R.string.tls_error) return
        val context = applicationContext
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { TimetableStore.read(context) != null }
            binding.errorOffline.isVisible = saved && pageFailed && (errorMessageId == R.string.network_error || errorMessageId == R.string.tls_error)
        }
    }

    /** «Последние оценки» under a network error, once totals are saved. */
    private fun offerLastGrades() {
        binding.errorGrades.isVisible = false
        if (errorMessageId != R.string.network_error && errorMessageId != R.string.tls_error) return
        val store = LastGradesStore.of(applicationContext)
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { store.read() != null }
            binding.errorGrades.isVisible = saved && pageFailed && (errorMessageId == R.string.network_error || errorMessageId == R.string.tls_error)
        }
    }

    /** The widget and the class alarm follow the reminder setting and the app's language. */
    private fun syncToday(state: AppState) {
        val key = state.reminders to state.language
        if (key == todayKey) return
        todayKey = key
        val context = applicationContext
        lifecycleScope.launch(Dispatchers.IO) { TodaySync.changed(context) }
    }

    private fun bindControls() {
        chrome.configure(canOpenDrawer = { !documents.isOpen }, onToolbar = ::toolbarAction, onNavigation = ::navigationAction)
        binding.errorRetry.setOnClickListener { retryFailedPage() }
        binding.errorOffline.setOnClickListener { startActivity(TimetableActivity.intent(this, model.state.value.language)) }
        binding.errorGrades.setOnClickListener { startActivity(LastGradesActivity.intent(this, model.state.value.language)) }
        binding.errorBack.setOnClickListener {
            if (sessionChanging) return@setOnClickListener
            if (model.sessionClearPending) finishInterruptedSessionClear()
            else if (rendererUnavailable) retry()
            else if (binding.webView.canGoBack()) binding.webView.goBack()
            else binding.webView.loadUrl(startPageUrl())
        }
        binding.statusAction.setOnClickListener { retry(signIn = true) }
        configurePull()
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
            repeatOnLifecycle(Lifecycle.State.STARTED) { UpdateManager.get(this@MainActivity).state.collect(updates::render) }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadRepository.get(this@MainActivity).records.collect { list ->
                    chrome.downloads(list.count { it.state == DownloadState.RUNNING }, model.state.value)
                }
            }
        }
    }

    private fun labels() = languages.of(model.state.value.language)

    private fun text(id: Int) = labels().getString(id)

    private fun pageInView() = !documents.isOpen && !sessionChanging && !rendererUnavailable && !model.state.value.locked

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
            // The old account's pages must not come back while its data is wiped, or after a wipe that stopped halfway
            // (the next render tries that wipe again).
            sessionChanging -> Unit
            model.sessionClearPending -> leaveApp()
            webOpened -> {
                if (backPending) return
                backPending = true
                mobilePage.dismissNavigation { dismissed ->
                    backPending = false
                    // A sign-out may have started while the page answered.
                    if (!dismissed && !isFinishing && !isDestroyed && !sessionChanging && !model.sessionClearPending) {
                        if (binding.webView.canGoBack()) binding.webView.goBack() else leaveApp()
                    }
                }
            }
            else -> leaveApp()
        }
    }

    private fun toolbarAction(id: Int): Boolean {
        when (id) {
            R.id.action_notifications -> notices.show()
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
            R.id.nav_notices -> { notices.show(); return false }
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
        // A material that is a teacher's link opens like the link it is, once; behind the lock it is dropped, not saved for later.
        if (event.link != null) {
            DownloadService.consume(event.id)
            downloadStartedBar?.dismiss()
            if (!model.state.value.locked && !AppLockSession.locked) route(event.link)
            return
        }
        if (event.present != null && event.recordId != null) {
            DownloadService.consume(event.id)
            downloadStartedBar?.dismiss()
            downloads.offerPresent(event.recordId, event.present)
            return
        }
        if (event.loginUrl != null && downloads.signInFor(event.loginUrl, event.batch)) { message(R.string.download_relogin); return }
        val bar = Snackbar.make(binding.content, text(event.message), Snackbar.LENGTH_LONG)
        if (event.uri != null && event.recordId != null) bar.setAction(text(R.string.open_file)) {
            if (!model.state.value.locked && !AppLockSession.locked)
                startActivity(FileViewerActivity.intent(this, event.recordId, model.state.value.language))
        } else if (event.message == R.string.download_login) bar.setAction(text(R.string.sign_in)) { retry(signIn = true) }
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
        publishShortcuts(state)
        syncToday(state)
        syncGrades(state)
        val justUnlocked = rendered.locked && !state.locked
        rendered = rendered.copy(locked = state.locked)
        AppLockSession.update(state.lock.enabled, state.locked, state.lock.autoLockMs, justUnlocked)
        // The gate can latch before the ViewModel snapshot arrives. Cover the page either way.
        if (state.lock.enabled && (state.locked || AppLockSession.locked)) { lock.showUnlockScreen(state); return }
        lock.releaseUnlockScreen()
        configureNoticePresentation()
        lock.applyWindowSecurity()
        binding.noticeLoadingOverlay.contentDescription = text(R.string.preparing_page)
        renderPageSettings(state)
        binding.errorMessage.text = languages.of(state.language).webErrorText(errorMessageId, errorHttpStatus)
        binding.errorRetry.text = text(R.string.retry)
        binding.errorOffline.text = text(R.string.offline_schedule)
        binding.errorGrades.text = text(R.string.last_grades)
        binding.errorBack.text = text(R.string.return_to_site)
        val changedLanguage = rendered.language != null && rendered.language != state.language
        rendered = rendered.copy(language = state.language)
        localizeChrome(state)
        if (model.sessionClearPending && !sessionChanging) { finishInterruptedSessionClear(); return }
        if (state.canBrowse) renderSession(state, changedLanguage) else renderFirstRun(state)
        updateDrawerLock()
        if (reopenSettings) { reopenSettings = false; if (state.canBrowse) forms.show() }
        // onResume skipped the checks while the lock was up; unlocking in place must start them.
        if (justUnlocked) { notices.scan(); notices.startChecks(); updates.entered() }
        updates.refresh()
        followLaunchTarget()
    }

    /** Page adaptation, animations, home tiles and text size follow the settings; the page is re-adapted only on a change. */
    private fun renderPageSettings(state: AppState) {
        val changedMobileLayout = rendered.mobileLayout != state.mobileLayout
        val changedAnimations = rendered.animations != null && rendered.animations != state.animations
        val changedShortcuts = rendered.shortcuts != null && rendered.shortcuts != state.shortcuts
        val changedLanguage = rendered.language != state.language
        val previousShortcuts = rendered.shortcuts
        rendered = rendered.copy(mobileLayout = state.mobileLayout, animations = state.animations, shortcuts = state.shortcuts)
        mobilePage.animations = state.animations
        Motion.enabled = state.animations
        if (previousShortcuts != state.shortcuts) {
            mobilePage.shortcuts = ShortcutBridge.script(state.shortcuts)
            // Both placeholders draw the home page with the student's own tiles, so the hand-off after a notice does not jump.
            Shortcuts.tiles(state.shortcuts).let { binding.pageSkeleton.tiles = it; binding.noticeSkeleton.tiles = it }
        }
        if (!rendererUnavailable && changedShortcuts) shortcutBridge.configure(state.shortcuts)
        if (!rendererUnavailable && (changedMobileLayout || changedAnimations || changedLanguage))
            mobilePage.apply(state.mobileLayout, state.language)
        if (!rendererUnavailable) binding.webView.settings.textZoom = baseTextZoom * state.textZoom / 100
    }

    /** No saved account yet: the first-run form instead of the website. */
    private fun renderFirstRun(state: AppState) {
        noticeLoading.dismiss()
        firstPageShown()
        binding.setupContainer.isVisible = true
        binding.mainColumn.isVisible = false
        binding.webView.isVisible = false
        Motion.progress(binding.loading, false)
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
        binding.pageSkeleton.isVisible = model.state.value.mobileLayout
        binding.root.postDelayed(pageAbandoned, LEAVE_TIMEOUT_MS)
        val restored = restoredWebState?.let { binding.webView.restoreState(it) }
        restoredWebState = null
        val lost = lostPageUrl?.takeIf(SitePolicy::resumable)
        lostPageUrl = null
        when {
            restored != null && SitePolicy.trusted(binding.webView.url) -> automation.pageFinished()
            lost != null -> loadPage(lost)
            // A shortcut's section opens first instead of the news while the site session is there; without one the
            // language page and login come first and the section follows (followLaunchTarget).
            else -> launchTarget?.section?.takeIf {
                SitePolicy.hasSession(CookieManager.getInstance().getCookie(SitePolicy.ORIGIN))
            }?.let { loadPage(it.url) } ?: automation.open()
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
        chrome.notices(if (state.canBrowse) Notices.unread(state.notices) else 0, state)
        notices.render(state)
        renderHeading()
    }

    /** Startup ends with the first page (or its error screen) in view: the system's start metrics count up to here. */
    private fun firstPageShown() {
        if (reportedDrawn) return
        reportedDrawn = true
        reportFullyDrawn()
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
            // The first heading on Бакалавр belongs to the summary panels, not to the page.
            pageTitle = if (currentUrl().orEmpty().toUri().path?.trimEnd('/').equals("/student/bachelor", ignoreCase = true)) text(R.string.cabinet)
                else runCatching { JSONArray(result) }.getOrNull()?.optString(0)?.takeIf { it.isNotBlank() }
            pageLoading = false
            renderHeading()
        }
    }

    private fun updateDrawerLock() = chrome.drawerUsable(model.state.value.canBrowse && !documents.isOpen && !sessionChanging)

    /** Univer's own Moodle hand-off, the same route as the site's useful link and the home tile. */
    private fun openMoodle() {
        if (sessionChanging || !model.state.value.canBrowse || model.state.value.paused) return
        if (rendererUnavailable) { retry(); return }
        route(SingleSignOnPolicy.ENTRY)
    }

    private fun openSection(section: AppSection) {
        if (sessionChanging || model.sessionClearPending || !model.state.value.canBrowse) return
        if (rendererUnavailable) { retry(); return }
        if (documents.isOpen) documents.close()
        loadPage(section.url)
    }

    /**
     * A GET of a trusted page with the error cleared and automation running; its address is the page's retry target.
     * [quietly] is an attempt the student did not ask for: the error screen stands until a page commits, so one that
     * fails again changes nothing on screen.
     */
    private fun loadPage(url: String, quietly: Boolean = false) {
        pageFailed = false
        // Another document replaces this one: a login still in flight here would read its fresh form as a rejection.
        automation.resume(replacing = true)
        navigationGetUrl = url
        if (quietly) awaitPage()
        else {
            binding.errorOverlay.isVisible = false
            leavePage(url)
        }
        binding.webView.loadUrl(url)
    }

    /** Answers a tap at once: the shape of the next page fades in over this one, and [pageAbandoned] ends the wait. */
    private fun startPageUrl() = SitePolicy.startPageUrl(model.state.value.startPage)

    private fun leavePage(url: String) {
        binding.pageSkeleton.show(url)
        cover.leave(covered = model.state.value.mobileLayout && SitePolicy.trusted(url))
        chrome.heading(SitePolicy.section(url), pageTitle, true, ::text)
        Motion.progress(binding.loading, true)
        awaitPage()
    }

    private fun awaitPage() {
        binding.root.removeCallbacks(pageAbandoned)
        binding.root.postDelayed(pageAbandoned, LEAVE_TIMEOUT_MS)
    }

    /**
     * Posted on the root, not the WebView: a lost renderer detaches and destroys the WebView, and removeCallbacks on a
     * detached view no longer reaches the queue, so the wait would end on a destroyed screen.
     */
    private val pageAbandoned = Runnable { if (!isDestroyed) settlePage() }

    /** Back to the page that is on screen: nothing replaced it. */
    private fun settlePage() {
        binding.root.removeCallbacks(pageAbandoned)
        endPull()
        if (cover.abandoned()) { showError(errorMessageId, errorHttpStatus); return }
        Motion.progress(binding.loading, false)
        renderHeading()
    }

    /** Opens the website's full section list (the adapted #tools menu) on the current page. */
    private fun showSiteSections() {
        if (documents.isOpen || rendererUnavailable || !SitePolicy.trusted(binding.webView.url)) return
        binding.webView.evaluateJavascript(PageScripts.SECTIONS) { opened -> if (opened != "true" && !isDestroyed) openSection(AppSection.HOME) }
    }

    /**
     * Pull to refresh: the gesture starts only when the page itself is pulled past its top and the screen is the
     * student's (no login, notice, PIN, document window or session change). The circle stays until the page shows or
     * fails, at most [PULL_TIMEOUT_MS].
     */
    private fun configurePull() {
        val pull = binding.pullRefresh
        pull.setColorSchemeColors(MaterialColors.getColor(pull, androidx.appcompat.R.attr.colorPrimary))
        pull.setProgressBackgroundColorSchemeColor(MaterialColors.getColor(pull, com.google.android.material.R.attr.colorSurfaceContainerHigh))
        pull.allowed = ::pullAllowed
        pull.setOnRefreshListener(::pullToRefresh)
    }

    private fun pullAllowed(): Boolean {
        val state = model.state.value
        return state.ready && state.canBrowse && !state.locked && !AppLockSession.locked && webOpened && !sessionChanging &&
            !model.sessionClearPending && !documents.isOpen && !rendererUnavailable && !pageFailed &&
            automationStatus == AutomationStatus.IDLE && !binding.noticeLoadingOverlay.isVisible
    }

    /** Typed text and an open window of the site stay: the page is not loaded again under them. */
    private fun pullToRefresh() {
        val url = binding.webView.url
        if (!pullAllowed() || !SitePolicy.trusted(url) || SitePolicy.loginPage(url)) { endPull(); return }
        binding.root.postDelayed(endPullLater, PULL_TIMEOUT_MS)
        val epoch = pageEpoch
        var answered = false
        val refresh = Runnable {
            if (answered || isDestroyed) return@Runnable
            answered = true
            if (epoch == pageEpoch && pullAllowed()) refreshPage() else endPull()
        }
        // A page too busy to answer is the one most worth loading again.
        binding.root.postDelayed(refresh, PULL_GUARD_MS)
        binding.webView.evaluateJavascript(PageScripts.REFRESH_GUARD) { answer ->
            if (answered || isDestroyed) return@evaluateJavascript
            when (answer?.trim('"')) {
                "edited" -> { answered = true; binding.root.removeCallbacks(refresh); endPull(); message(R.string.refresh_kept_input) }
                "dialog" -> { answered = true; binding.root.removeCallbacks(refresh); endPull(); message(R.string.refresh_kept_dialog) }
                else -> { binding.root.removeCallbacks(refresh); refresh.run() }
            }
        }
    }

    private val endPullLater = Runnable { if (!isDestroyed) endPull() }

    private fun endPull() {
        binding.root.removeCallbacks(endPullLater)
        if (binding.pullRefresh.isRefreshing) binding.pullRefresh.isRefreshing = false
    }

    /** A GET of the current address: a form result is never posted again. */
    private fun refreshPage() {
        if (documents.isOpen) return
        if (rendererUnavailable || pageFailed) { retryFailedPage(); return }
        val url = binding.webView.url?.takeIf(SitePolicy::trusted) ?: run { retry(); return }
        automation.resume(replacing = true)
        navigationGetUrl = url
        binding.webView.loadUrl(url)
    }

    private fun setMobileLayout(enabled: Boolean, form: SettingsSheetBinding? = null) {
        if (enabled == model.state.value.mobileLayout) return
        launchCatching({
            if (form != null) { forms.feedback(form, R.string.storage_error); forms.update(form, forms.isFirst(form)) } else message(R.string.storage_error)
        }) { model.setMobileLayout(enabled) }
    }

    private fun setTextZoom(percent: Int) {
        if (TextZoom.clamp(percent) == model.state.value.textZoom) return
        launchCatching({ message(R.string.storage_error) }) { model.setTextZoom(percent) }
    }

    private fun showTextSize() {
        track(TextSizeDialog.show(this, labels(), model.state.value.textZoom, ::setTextZoom))
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

    private fun manualLogin(form: SettingsSheetBinding) {
        if (sessionChanging) return
        sessionChanging = true
        form.manualLogin.isEnabled = false
        lifecycleScope.launch {
            try { model.startManualLogin(); forms.dismiss() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { forms.feedback(form, R.string.storage_error) }
            finally { sessionChanging = false; if (isActive) { forms.update(form, first = true); render(model.state.value) } }
        }
    }

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
                if (changed) clearSession()
                forms.dismiss()
                binding.errorOverlay.isVisible = false
                automationStatus = AutomationStatus.IDLE
                lock.maybeOfferPin()
                if (!first && !changed && wasPaused) {
                    noticePresentation.setEnabled(true, retry = true)
                    binding.webView.loadUrl(startPageUrl())
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

    /** Sign out, and the forgotten-PIN reset: the session is wiped and the saved login removed. */
    private fun endSession() {
        if (sessionChanging) return
        sessionChanging = true
        model.guard.stop()
        lifecycleScope.launch {
            try {
                model.endSession(delete = true)
                clearSession()
                automationStatus = AutomationStatus.IDLE
                forms.dismiss()
            } catch (cancelled: CancellationException) { throw cancelled }
            // The site's Logout link and the forgotten PIN have no sheet open: the failure is shown on the screen.
            catch (_: Exception) { if (forms.showing) forms.sheetError(R.string.storage_error) else message(R.string.storage_error) }
            finally { sessionChanging = false; if (isActive) render(model.state.value) }
        }
    }

    /** A logout or account change was committed but the Activity was recreated before the site session was wiped. */
    private fun finishInterruptedSessionClear() {
        if (sessionChanging) return
        sessionChanging = true
        lifecycleScope.launch {
            var cleared = false
            try { clearSession(); cleared = true }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                binding.setupContainer.isVisible = false
                binding.mainColumn.isVisible = true
                binding.webView.isVisible = false
                showError(R.string.storage_error)
                binding.errorOverlay.isVisible = true
            }
            finally { sessionChanging = false; if (isActive && cleared) render(model.state.value) }
        }
    }

    /**
     * Ends the site session on this screen: its parts forget it, the site's data is wiped, the model hears that the wipe
     * is done, and the next render opens the website afresh.
     */
    private suspend fun clearSession() {
        noticeLoading.dismiss()
        noticePresentation.setEnabled(false)
        notices.reset()
        sessionEpoch++
        downloads.reset()
        fileChooser.clear()
        reports.cancel()
        schedule.cancel()
        GradeReportFiles.clear(this)
        withContext(Dispatchers.IO) { TodaySync.erase(applicationContext) }
        withContext(GradeAlerts.lane) { GradeAlerts.erase(applicationContext) }
        withContext(Dispatchers.IO) { LastGradesStore.of(applicationContext).erase() }
        gradesKey = null
        DownloadService.cancelAll(this)
        documents.close(false)
        documentSourcePosition = null
        restoredDocument = null
        automation.pause()
        pageTitle = null
        SiteData.erase(binding.webView.takeUnless { rendererUnavailable }, this)
        model.sessionCleared()
        webOpened = false
        restoredWebState = null
    }

    // Page errors and recovery.

    /** Opens the site afresh. Only the student's own "Sign in" ([signIn]) ends the pause they chose by signing out. */
    private fun retry(signIn: Boolean = false) {
        if (sessionChanging || !model.state.value.canBrowse) return
        documents.close(false)
        documentSourcePosition = null
        failedPageUrl = null
        pageFailed = false
        launchCatching({ message(R.string.storage_error) }) {
            if (model.state.value.hasCredentials) model.retrySignIn(signIn)
            if (rendererUnavailable) { recreate(); return@launchCatching }
            automationStatus = AutomationStatus.IDLE
            binding.errorOverlay.isVisible = false
            if (model.state.value.paused) configureNoticePresentation() else noticePresentation.setEnabled(true, retry = true)
            automation.resume(replacing = true)
            leavePage(startPageUrl())
            automation.open()
            renderBanner()
        }
    }

    private fun retryFailedPage() {
        if (model.sessionClearPending) { finishInterruptedSessionClear(); return }
        if (rendererUnavailable) { retry(); return }
        val url = failedPageUrl?.takeIf(SitePolicy::trusted)
        if (url == null || model.state.value.paused) { retry(); return }
        if (sessionChanging || !model.state.value.canBrowse) return
        pageFailed = false
        binding.errorOverlay.isVisible = false
        configureNoticePresentation()
        automation.resume(replacing = true)
        leavePage(url)
        binding.webView.loadUrl(url) // Only URLs observed in a GET request can reach this branch.
    }

    /** A connection failure or a temporary server answer: worth loading again by itself. */
    private fun retryableError() = !rendererUnavailable && ((errorMessageId == R.string.network_error && errorHttpStatus == null) ||
        errorHttpStatus?.let { it >= 500 || it == 408 || it == 429 } == true)

    /** Schedules [recoverFailedPage] after the page backoff's wait (or [delay]). */
    private fun schedulePageRecovery(delay: Long? = null) {
        binding.root.removeCallbacks(pageRecoveryTask)
        notices.stopChecks()
        if (pageFailed && retryableError())
            binding.root.postDelayed(pageRecoveryTask, delay ?: model.pageRecovery.delay(SystemClock.elapsedRealtime()))
    }

    /**
     * The automatic Retry of a failed page while the screen is visible: a GET of its address (the start page after a
     * failed POST), never a replayed form. Attempt budgets are not reset, unlike the explicit Retry.
     */
    private fun recoverFailedPage() {
        val state = model.state.value
        if (!pageFailed || !retryableError() || sessionChanging || documents.isOpen || !state.canBrowse ||
            state.paused || state.locked || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        model.pageRecovery.spend(SystemClock.elapsedRealtime())
        pageFailed = false
        configureNoticePresentation()
        loadPage(failedPageUrl?.takeIf(SitePolicy::trusted) ?: startPageUrl(), quietly = true)
        notices.startChecks()
    }

    /**
     * A renderer the system killed in the background or that crashed: the screen is recreated by itself while it is
     * visible, ever more slowly if the renderer keeps failing. The recreated screen reopens the section by a GET.
     */
    private fun recoverRenderer() {
        val state = model.state.value
        if (!rendererUnavailable || rendererRecoveryPending || sessionChanging || !state.canBrowse || state.paused ||
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

    private fun configureNoticePresentation() {
        val state = model.state.value
        noticePresentation.setEnabled(state.hasCredentials && !state.paused && !model.guard.blocked &&
            !sessionChanging && !pageFailed && !rendererUnavailable && automationStatus in setOf(
                AutomationStatus.IDLE, AutomationStatus.SIGNING_IN, AutomationStatus.NOTICE_CONFIRMING))
    }

    private fun renderBanner() {
        val state = model.state.value
        if (!state.hasCredentials) { banner.hide(); return }
        banner.render(state.paused, automationStatus, state.language, ::text)
        localizeChrome(state)
    }

    private fun showError(id: Int, httpStatus: Int? = null) {
        binding.root.removeCallbacks(pageAbandoned)
        endPull()
        cover.failed()
        noticeLoading.dismiss()
        noticePresentation.setEnabled(false)
        pageFailed = true
        errorMessageId = id
        errorHttpStatus = httpStatus
        binding.errorMessage.text = labels().webErrorText(id, httpStatus)
        binding.errorIcon.setImageResource(if (id == R.string.network_error || id == R.string.tls_error) R.drawable.ic_cloud_off else R.drawable.ic_warning)
        Motion.show(binding.errorOverlay, model.state.value.canBrowse)
        offerOfflineSchedule()
        offerLastGrades()
        Motion.progress(binding.loading, false)
        pageLoading = false
        renderHeading()
        firstPageShown()
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
        binding.webView.enableBackForwardCache()
        baseTextZoom = binding.webView.settings.textZoom
        binding.webView.webViewClient = PageClient(pageHost)
        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(webView: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams) =
                fileChooser.show(callback, params)
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (!isUserGesture) return false
                // A page's new window only tells where it wanted to go; that address is routed like a link.
                captureNewWindow(this@MainActivity, resultMsg) { request ->
                    if (!logoutNavigation.onNavigation(request)) {
                        if (!sessionChanging) websiteLanguage.onNavigation(request)
                        val url = request.url.toString()
                        if (!route(url, request.isForMainFrame && !request.isRedirect && request.method.equals("GET", true))) binding.webView.loadUrl(url)
                    }
                }
                return true
            }
        }
        binding.webView.setDownloadListener { url, agent, disposition, mime, _ ->
            settlePage()
            downloads.download(url, agent, disposition, mime)
        }
    }

    /** What the website client may use of this screen. */
    private val pageHost = object : PageClient.Host {
        override val model get() = this@MainActivity.model
        override val automation get() = this@MainActivity.automation
        override val mobilePage get() = this@MainActivity.mobilePage
        override val shortcutBridge get() = this@MainActivity.shortcutBridge
        override val channel get() = this@MainActivity.channel
        override val notices get() = this@MainActivity.notices
        override val documents get() = this@MainActivity.documents
        override val noticeLoading get() = this@MainActivity.noticeLoading
        override val noticePresentation get() = this@MainActivity.noticePresentation
        override val cover get() = this@MainActivity.cover
        override val webView get() = binding.webView
        override val isDestroyed get() = this@MainActivity.isDestroyed
        override val sessionChanging get() = this@MainActivity.sessionChanging
        override var navigationGetUrl by this@MainActivity::navigationGetUrl
        override var pageEpoch by this@MainActivity::pageEpoch
        override var pageFailed by this@MainActivity::pageFailed
        override var pageTitle by this@MainActivity::pageTitle
        override var pageLoading by this@MainActivity::pageLoading
        override var failedPageUrl by this@MainActivity::failedPageUrl
        override var rendererUnavailable by this@MainActivity::rendererUnavailable
        override var lostPageUrl by this@MainActivity::lostPageUrl
        override val pageSkeletonVisible get() = binding.pageSkeleton.isVisible

        override fun onLogoutNavigation(request: WebResourceRequest) = logoutNavigation.onNavigation(request)
        override fun onWebsiteLanguageNavigation(request: WebResourceRequest) = websiteLanguage.onNavigation(request)
        override fun route(url: String, userGesture: Boolean) = this@MainActivity.route(url, userGesture)
        override fun openLink(url: String) = this@MainActivity.openLink(url)
        override fun leavePage(url: String) = this@MainActivity.leavePage(url)
        override fun settlePage() = this@MainActivity.settlePage()
        override fun cancelPageAbandon() { binding.root.removeCallbacks(pageAbandoned) }
        override fun showPageSkeleton(url: String?) = binding.pageSkeleton.show(url)
        override fun showProgress(visible: Boolean) = Motion.progress(binding.loading, visible)
        override fun hideErrorOverlay() = Motion.show(binding.errorOverlay, false)
        override fun renderHeading() = this@MainActivity.renderHeading()
        override fun updatePageTitle() = this@MainActivity.updatePageTitle()
        override fun showError(id: Int, httpStatus: Int?) = this@MainActivity.showError(id, httpStatus)
        override fun recoverRenderer() = this@MainActivity.recoverRenderer()
    }

    /** Where a link goes: the document viewer, a download, the website itself, or another app. */
    private fun route(url: String, userGesture: Boolean = true): Boolean {
        if (sessionChanging || model.sessionClearPending) return true
        if (SingleSignOnPolicy.opensViewer(url)) {
            if (SitePolicy.trusted(url) || userGesture) openDocument(url)
            return true
        }
        if (SitePolicy.documentDownload(url)) {
            downloads.download(url, binding.webView.settings.userAgentString, null, null)
            return true
        }
        if (SitePolicy.printDocument(url)) { openDocument(url); return true }
        if (SitePolicy.trusted(url)) return false
        if (!userGesture) return true
        val uri = url.toUri()
        if (uri.scheme == "http" && uri.host.equals(SitePolicy.ORIGIN.toUri().host, true)) {
            binding.webView.loadUrl(uri.buildUpon().scheme("https").build().toString())
        } else openLink(url)
        return true
    }

    /** A tapped link to another site: the app's link window, the system (mail, phone, http, or by the user's choice), or nothing. */
    private fun openLink(url: String) {
        when (LinkPolicy.open(url, model.state.value.externalLinks, LinkProfile.available())) {
            LinkOpening.APP -> try { linkWindow.launch(LinkViewerActivity.intent(this, url, model.state.value.language)) }
                catch (_: ActivityNotFoundException) { openExternal(url) }
            LinkOpening.BROWSER -> openExternal(url)
            LinkOpening.BLOCKED -> message(R.string.open_failed)
        }
    }

    /** A Univer or Moodle page tapped in the link window opens here, signed in; logout, language and sign-in pages never do. */
    private fun linkReturned(url: String) {
        if (sessionChanging || model.sessionClearPending || model.state.value.locked || AppLockSession.locked || !LinkPolicy.handBack(url)) return
        if (SitePolicy.trusted(url) && !SitePolicy.documentDownload(url)) {
            if (documents.isOpen) documents.close()
            binding.webView.loadUrl(url)
        } else route(url)
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
        if (!SitePolicy.external(url)) { message(R.string.open_failed); return }
        val uri = url.toUri()
        try { startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)) }
        catch (_: ActivityNotFoundException) { message(R.string.open_failed) }
        catch (_: SecurityException) { message(R.string.open_failed) }
    }

    // Lifecycle.

    /** A shortcut, the widget or a notification while the screen runs: the same request as at a cold start. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requested(intent)?.let { launchTarget = it; followLaunchTarget() }
    }

    /** The section an Intent asks for; the launcher hears that it was used, to rank the shortcuts. */
    private fun requested(intent: Intent?): LaunchTarget? = LaunchTarget.of(intent)?.also { target ->
        val context = applicationContext
        lifecycleScope.launch(Dispatchers.IO) { AppShortcuts.used(context, target) }
    }

    /**
     * Opens the requested section once nothing else owns the page: the account open and unlocked, no session change,
     * no login, notice or agreement in flight (the page they wait on would be replaced, and the login form read as a
     * rejection). Without an account the first-run form is the answer and the request is dropped.
     */
    private fun followLaunchTarget() {
        val target = launchTarget ?: return
        val state = model.state.value
        if (!state.ready || isDestroyed) return
        if (!state.canBrowse) { launchTarget = null; return }
        if (state.locked || AppLockSession.locked || sessionChanging || model.sessionClearPending || !webOpened) return
        if (target.section == null) { launchTarget = null; openDownloads(); return }
        if (rendererUnavailable || automationStatus != AutomationStatus.IDLE) return
        launchTarget = null
        if (SitePolicy.section(currentUrl()) != target.section || documents.isOpen || pageFailed) openSection(target.section)
    }

    /** The launcher's long-press list, named in the app's language while an account is open, removed without one. */
    private fun publishShortcuts(state: AppState) {
        val language = state.language.takeIf { state.canBrowse }
        if (language == shortcutsLanguage) return
        shortcutsLanguage = language
        val context = applicationContext
        lifecycleScope.launch(Dispatchers.IO) { AppShortcuts.update(context, language) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        launchTarget?.let { outState.putString("launch_target", it.name) }
        outState.putString("download_event", handledDownloadEvent)
        lock.save(outState)
        outState.putInt("session_epoch", sessionEpoch)
        outState.putBoolean("settings_open", forms.showing && !sessionChanging)
        outState.putInt("settings_tab", forms.tab)
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
            !SitePolicy.loginPage(binding.webView.url) && !SitePolicy.readNotice(binding.webView.url)) {
            val state = Bundle()
            binding.webView.saveState(state)
            outState.putBundle("web_state", state)
        }
        if (rendererUnavailable && !sessionChanging) lostPageUrl?.let { outState.putString("lost_page", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        GradeAlerts.appVisible = true
        GradeAlerts.returned(applicationContext)
        network.register()
        // A shared or printed report stays only as long as its share may still read it: an hour, not until the next one.
        lifecycleScope.launch(Dispatchers.IO) { GradeReportFiles.prune(applicationContext) }
    }

    override fun onStop() {
        GradeAlerts.appVisible = false
        model.state.value.let { if (it.gradeAlerts && it.canBrowse && !it.paused && !sessionChanging && !model.sessionClearPending) GradeAlerts.left(applicationContext) }
        network.unregister()
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
        if (documents.isOpen) documents.resume() else { automation.resume(); notices.scan() }
        notices.startChecks()
        updates.entered()
    }

    override fun onPause() {
        // With a PIN set the window turns secure before the system takes the recent-apps picture.
        lock.inForeground = false
        lock.applyWindowSecurity()
        notices.stopChecks()
        automation.pause(); CookieManager.getInstance().flush()
        documents.pause()
        if (!rendererUnavailable) binding.webView.onPause()
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation keeps the Activity (manifest configChanges): views sized once from the configuration follow it here.
        if (::forms.isInitialized) forms.resizeFirstRun()
    }

    override fun onDestroy() {
        // A view's pending runnables still run after it leaves the window: none may reach a destroyed screen.
        binding.root.removeCallbacks(pageRecoveryTask)
        notices.stopChecks()
        binding.root.removeCallbacks(pageAbandoned)
        dismissTransientDialogs()
        lockUi.hide()
        noticeLoading.close()
        noticePresentation.close()
        mobilePage.close()
        shortcutBridge.close()
        channel.close()
        notices.close()
        automation.close()
        documents.close(false)
        downloads.reset()
        fileChooser.cancel()
        forms.dismiss()
        if (!rendererUnavailable) {
            binding.webView.stopLoading()
            binding.webView.webChromeClient = null
            binding.webView.webViewClient = RetiredWebViewClient()
            (binding.webView.parent as? android.view.ViewGroup)?.removeView(binding.webView)
            binding.webView.destroy()
        }
        super.onDestroy()
    }

    /** What the screen's parts ask of the page and the session. */
    private inner class Hosts : DownloadLauncher.Host, LockFlow.Host, SettingsForms.Host, UpdateFlow.Host, NoticeFlow.Host, ReportFlow.Host, ScheduleFlow.Host {
        override fun text(id: Int) = this@MainActivity.text(id)
        override fun labels() = this@MainActivity.labels()
        override fun message(id: Int) = this@MainActivity.message(id)
        override fun track(dialog: Dialog) = this@MainActivity.track(dialog)
        override val sessionChanging get() = this@MainActivity.sessionChanging
        override val sessionEpoch get() = this@MainActivity.sessionEpoch
        override fun requestNotifications(granted: (Boolean) -> Unit) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                granted(androidx.core.app.NotificationManagerCompat.from(this@MainActivity).areNotificationsEnabled())
                return
            }
            notificationsAnswer = granted
            notificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        override val channel get() = this@MainActivity.channel
        override val rendererUnavailable get() = this@MainActivity.rendererUnavailable
        override val automationStatus get() = this@MainActivity.automationStatus
        override val inForeground get() = lock.inForeground
        override val documentOpen get() = documents.isOpen
        override val serviceOpen get() = documents.isService
        override val pageUrl get() = documents.webView?.url ?: currentUrl()
        override val pageSource get() = when {
            documents.isService -> documents.webView?.title?.takeUnless { it.startsWith("http") }.orEmpty()
            documents.isOpen -> ""
            else -> pageTitle.orEmpty()
        }
        override val userAgent: String get() = binding.webView.settings.userAgentString
        override suspend fun materialFolder(url: String, page: String?): String? {
            val expectedPage = page ?: return null
            if (rendererUnavailable || sessionChanging || documents.isOpen || !SitePolicy.materialFile(url) || page != currentUrl()) return null
            val epoch = pageEpoch
            val session = sessionEpoch
            val raw = withTimeoutOrNull(3_000) { binding.webView.evaluate(PageScripts.materialPlace(expectedPage, url)) } ?: return null
            if (epoch != pageEpoch || session != sessionEpoch || sessionChanging) return null
            return runCatching {
                val json = JSONObject(JSONTokener(raw).nextValue() as? String ?: return null)
                StudyFolders.relative(json.optString("course"), json.optString("section"))
            }.getOrNull()
        }
        override suspend fun materialFolders(urls: List<String>, page: String?): Map<String, String>? {
            val expectedPage = page ?: return null
            if (rendererUnavailable || sessionChanging || documents.isOpen || page != currentUrl()) return null
            val epoch = pageEpoch
            val session = sessionEpoch
            val raw = withTimeoutOrNull(5_000) { binding.webView.evaluate(PageScripts.materialPlaces(expectedPage, urls)) } ?: return null
            if (epoch != pageEpoch || session != sessionEpoch || sessionChanging) return null
            return runCatching {
                val json = JSONObject(JSONTokener(raw).nextValue() as? String ?: return null)
                urls.associateWith { url -> json.optJSONObject(url)?.let { StudyFolders.relative(it.optString("course"), it.optString("section")) } ?: StudyFolders.ROOT }
            }.getOrNull()
        }
        override fun sectionQueued(section: String, started: Int, present: Int) {
            val name = if (section.length > 60) section.take(59).trimEnd() + "…" else section
            val message = when {
                started > 0 && present > 0 -> labels().getString(R.string.download_all_started_present, name, started, present)
                started > 0 -> labels().getString(R.string.download_all_started, name, started)
                present > 0 -> labels().getString(R.string.download_all_present, name)
                else -> text(R.string.download_failed)
            }
            downloadStartedBar = Snackbar.make(binding.content, message, Snackbar.LENGTH_LONG)
                .setAction(text(R.string.downloads)) { openDownloads() }.also { it.show() }
        }
        override fun openExternal(url: String) = this@MainActivity.openExternal(url)
        override fun openDownloads() = this@MainActivity.openDownloads()
        override fun saveDiagnostics() {
            if (model.state.value.locked || AppLockSession.locked) return
            downloads.withStorage {
                lifecycleScope.launch {
                    val state = model.state.value
                    val name = try { withContext(Dispatchers.IO) { DiagnosticsExport.save(applicationContext, state) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                    Snackbar.make(binding.content, name?.let { labels().getString(R.string.diagnostics_saved, it) } ?: text(R.string.diagnostics_failed), Snackbar.LENGTH_LONG).show()
                }
            }
        }
        override fun downloadStarted() {
            downloadStartedBar = Snackbar.make(binding.content, text(R.string.download_started), Snackbar.LENGTH_LONG)
                .setAction(text(R.string.downloads)) { openDownloads() }.also { it.show() }
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
        override fun openNoticePage(url: String) {
            if (rendererUnavailable) { retry(); return }
            if (documents.isOpen) documents.close()
            if (!route(url)) loadPage(url)
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
        override fun dismissDialogs() { dismissTransientDialogs(); forms.dismiss(); notices.dismiss() }
        override fun eraseAccount() = endSession()
        override fun save(form: SettingsSheetBinding, first: Boolean) = this@MainActivity.save(form, first)
        override fun signOut() = endSession()
        override fun manualLogin(form: SettingsSheetBinding) = this@MainActivity.manualLogin(form)
        override fun setMobileLayout(enabled: Boolean, form: SettingsSheetBinding?) = this@MainActivity.setMobileLayout(enabled, form)
        override fun setTextZoom(percent: Int) = this@MainActivity.setTextZoom(percent)
        override val settingsShowing get() = forms.showing
        override fun refreshSettings() = forms.refresh()
        override val updateState get() = updates.state
        override val updateStatus get() = updates.status()
        override fun checkUpdates() = updates.check()
        override fun installUpdate() = updates.install()
    }

    private companion object {
        /** How long a tapped link may take to start a page before the screen goes back to the page it left. */
        const val LEAVE_TIMEOUT_MS = 15_000L
        /** How long a pull waits for the page to say it has typed text or an open window. */
        const val PULL_GUARD_MS = 1_500L
        /** The pull's circle goes away by then even if the page neither shows nor fails. */
        const val PULL_TIMEOUT_MS = 20_000L
    }
}
