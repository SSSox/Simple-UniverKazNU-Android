package com.univerflow.kaznu.main

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.univerflow.kaznu.R
import com.univerflow.kaznu.UniverViewModel
import com.univerflow.kaznu.data.DownloadRepository
import com.univerflow.kaznu.ui.context
import com.univerflow.kaznu.domain.DownloadHistoryRules
import com.univerflow.kaznu.domain.DownloadKeys
import com.univerflow.kaznu.domain.DownloadRecord
import com.univerflow.kaznu.domain.StudyFolders
import com.univerflow.kaznu.download.DownloadService
import com.univerflow.kaznu.ui.AppLockSession
import com.univerflow.kaznu.ui.FileActions
import com.univerflow.kaznu.ui.FileViewerActivity
import com.univerflow.kaznu.web.AppSection
import com.univerflow.kaznu.web.SingleSignOnPolicy
import com.univerflow.kaznu.web.SitePolicy
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Starts downloads from the site and its viewers: checks the address, asks for storage on Android 9 and older, and
 * signs in again for a file an ended session refused (once per file in five minutes).
 */
class DownloadLauncher(private val activity: AppCompatActivity, private val model: UniverViewModel, private val host: Host) {
    interface Host : ScreenFeedback {
        val sessionChanging: Boolean
        val sessionEpoch: Int
        /** The website's renderer is gone: nothing can sign in until the student retries. */
        val rendererUnavailable: Boolean
        /** The address of the page a download starts from: the open viewer's, else the website's. */
        val pageUrl: String?
        /** The visible heading the file came from; the Downloads list groups files by it. */
        val pageSource: String
        val userAgent: String
        suspend fun materialFolder(url: String, page: String?): String? = null
        /** Folders of UMKD files on the open course page, read in one pass; null when that page is no longer open. */
        suspend fun materialFolders(urls: List<String>, page: String?): Map<String, String>? = null
        /** «Скачать все» handed [started] files of [section] to the service; [present] were already saved and in place. */
        fun sectionQueued(section: String, started: Int, present: Int) {}
        fun openExternal(url: String)
        fun openDownloads()
        fun downloadStarted()
        fun unsupported()
        /** Reloads the page (the site sends it to its login form) or renews the Moodle session; false when it cannot. */
        fun signInAgain(moodle: Boolean): Boolean
    }

    private var legacyRequest: Bundle? = null
    /** Files and sections an ended session refused, fetched once the sign-in that serves them all has finished. */
    private val loginRequests = mutableListOf<Bundle>()
    /** Recent «Скачать все» runs by batch, so a run an ended session refused goes on after the sign-in. */
    private val sections = LinkedHashMap<String, Bundle>()
    private val loginAttempts = mutableMapOf<String, Long>()
    private val notificationPermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val storagePermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = legacyRequest
        legacyRequest = null
        if (granted) pending?.let { if (it.containsKey(URLS)) startAll(it) else start(it) } else host.message(R.string.download_permission)
    }

    fun restore(saved: Bundle?) { legacyRequest = saved?.getBundle(LEGACY) }

    fun save(outState: Bundle) { legacyRequest?.let { outState.putBundle(LEGACY, it) } }

    /** Forgets pending requests: the session that asked for them is gone. */
    fun reset() { legacyRequest = null; loginRequests.clear(); sections.clear() }

    fun download(url: String, userAgent: String, disposition: String?, mime: String?) {
        if (!SingleSignOnPolicy.downloadAllowed(url)) {
            if (url.startsWith("https://") || url.startsWith("http://")) host.openExternal(url) else host.unsupported()
            return
        }
        if (host.sessionChanging || model.state.value.paused) return
        val request = request(url, userAgent, disposition, mime)
        if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(activity,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            if (legacyRequest == null) { legacyRequest = request; storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
        } else start(request)
    }

    /**
     * «Скачать все» of a UMKD section: every file not saved yet goes to the service, which runs two at a time. Folders are
     * read from the page for all files at once, before the first transfer; files already saved and in place are skipped
     * without the "already downloaded" question, and one message reports the result.
     */
    fun downloadAll(section: String, urls: List<String>) {
        if (host.sessionChanging || model.state.value.paused || model.state.value.locked || urls.isEmpty()) return
        // Only a UMKD page has the button: a message from any other page asks for nothing, not even storage.
        if (SitePolicy.section(host.pageUrl) != AppSection.MATERIALS) return
        val request = request(urls.first(), host.userAgent, null, null).apply { putStringArrayList(URLS, ArrayList(urls)); putString(SECTION, section) }
        if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(activity,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            if (legacyRequest == null) { legacyRequest = request; storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
        } else startAll(request)
    }

    private fun startAll(request: Bundle) {
        activity.lifecycleScope.launch {
            model.state.first { it.ready }
            val urls = request.getStringArrayList(URLS)?.filter(SitePolicy::materialFile).orEmpty()
            fun current(): Boolean = !host.sessionChanging && !model.sessionClearPending && !model.state.value.paused &&
                !model.state.value.locked && model.state.value.canBrowse && request.getInt("epoch", -1) == host.sessionEpoch
            if (urls.isEmpty() || !current()) return@launch
            // A run brought back after a sign-in keeps the folders it read at first: by then the app may show another
            // page. A page that does not answer in time still gets its files, in the UMKD folder like a single file's.
            val stored = request.getBundle(FOLDERS)
            val folders = stored?.let { saved -> saved.keySet().associateWith { saved.getString(it).orEmpty() } }
                ?: host.materialFolders(urls, request.getString("referer")).orEmpty()
            val history = DownloadRepository.get(activity)
            history.awaitLoaded()
            if (!current()) return@launch
            val records = history.records.value
            val present = withContext(Dispatchers.IO) {
                urls.filter { url -> DownloadHistoryRules.previous(records, DownloadKeys.of(url))?.let { FileActions.exists(activity, it) } == true }.toSet()
            }
            if (!current()) return@launch
            val batch = UUID.randomUUID().toString()
            request.putBundle(FOLDERS, Bundle().apply { folders.forEach { (url, folder) -> putString(url, folder) } })
            sections[batch] = request
            while (sections.size > 4) sections.remove(sections.keys.first())
            var started = 0
            for (url in urls) {
                if (url in present) continue
                try {
                    DownloadService.start(activity, url, request.getString("agent").orEmpty(), request.getString("referer"), null, null,
                        model.state.value.language, request.getString("source").orEmpty(), folders[url] ?: StudyFolders.ROOT, batch)
                    started++
                } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { break }
            }
            host.sectionQueued(request.getString(SECTION).orEmpty(), started, present.size)
            if (started > 0) askForNotifications()
        }
    }

    private fun request(url: String, userAgent: String, disposition: String?, mime: String?) = Bundle().apply {
        putString("url", url); putString("agent", userAgent)
        putString("referer", host.pageUrl?.takeIf { SingleSignOnPolicy.sameService(url, it) })
        putString("disposition", disposition); putString("mime", mime)
        putInt("epoch", host.sessionEpoch); putString("source", host.pageSource)
    }

    /**
     * A file refused by an ended session: sign in again and fetch it afterwards. A file of a «Скачать все» run ([batch])
     * brings the whole section back after the sign-in, with its page, heading and folders and with the files saved
     * meanwhile skipped; once per section of a course in five minutes, like a single file. One sign-in serves every file
     * refused while it runs.
     */
    fun signInFor(url: String, batch: String? = null): Boolean {
        val state = model.state.value
        if (host.sessionChanging || state.paused || !state.hasCredentials || host.rendererUnavailable) return false
        val now = SystemClock.elapsedRealtime()
        loginAttempts.entries.removeAll { now - it.value > LOGIN_RETRY_MS }
        val run = batch?.let { sections[it] }
        val attempt = run?.let { "section:" + it.getString("referer") + "|" + it.getString(SECTION) } ?: url
        if (attempt in loginAttempts) return false
        val moodle = SingleSignOnPolicy.moodle(url)
        if (!moodle && !SitePolicy.trusted(url)) return false
        loginAttempts[attempt] = now
        loginRequests.removeAll { now - it.getLong("at") > LOGIN_WAIT_MS }
        val signingIn = loginRequests.any { it.getBoolean(MOODLE) == moodle }
        val request = (run?.let { Bundle(it) } ?: request(url, host.userAgent, null, null)).apply { putLong("at", now); putBoolean(MOODLE, moodle) }
        loginRequests += request
        if (signingIn) return true
        if (!host.signInAgain(moodle)) { loginRequests.remove(request); return false }
        return true
    }

    /** Called once automation has signed in: the refused files and sections are fetched if they were refused recently. */
    fun signedIn() {
        val now = SystemClock.elapsedRealtime()
        val ready = loginRequests.filter { now - it.getLong("at") <= LOGIN_WAIT_MS }
        loginRequests.clear()
        ready.forEach { if (it.containsKey(URLS)) startAll(it) else start(it) }
    }

    private fun start(request: Bundle, again: Boolean = false) {
        activity.lifecycleScope.launch {
            model.state.first { it.ready }
            val url = request.getString("url") ?: return@launch
            fun current(): Boolean = !host.sessionChanging && !model.sessionClearPending && !model.state.value.paused &&
                model.state.value.canBrowse && request.getInt("epoch", -1) == host.sessionEpoch
            if (!current() || !SingleSignOnPolicy.downloadAllowed(url)) return@launch
            if (SitePolicy.materialFile(url) && !request.containsKey("folder")) {
                request.putString("folder", host.materialFolder(url, request.getString("referer")) ?: StudyFolders.ROOT)
                if (!current()) return@launch
            }
            if (!again) {
                // The same file is already in Downloads: offer it instead of saving a second copy ("name (1).pdf").
                val history = DownloadRepository.get(activity)
                history.awaitLoaded()
                if (!current()) return@launch
                val earlier = DownloadHistoryRules.previous(history.records.value, DownloadKeys.of(url))
                // A file not in its place now is saved again; the Downloads screen takes the old record off after a second check.
                val exists = earlier != null && withContext(Dispatchers.IO) { FileActions.exists(activity, earlier) }
                if (!current()) return@launch
                if (earlier != null && exists) {
                    if (!activity.isFinishing && !activity.isDestroyed && !model.state.value.locked) offerDownloaded(earlier, request)
                    return@launch
                }
            }
            if (!current()) return@launch
            try {
                DownloadService.start(activity, url, request.getString("agent").orEmpty(), request.getString("referer"),
                    request.getString("disposition"), request.getString("mime"), model.state.value.language, request.getString("source").orEmpty(), request.getString("folder"))
                host.downloadStarted()
                askForNotifications()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { host.message(R.string.download_failed) }
        }
    }

    /** Progress and "done" notifications need the permission on Android 13+; it is asked once. */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val preferences = activity.getSharedPreferences("univer.downloads", Context.MODE_PRIVATE)
        if (preferences.getBoolean("notifications_requested", false)) return
        preferences.edit { putBoolean("notifications_requested", true) }
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** "Already downloaded": open the saved copy, or download it again on request. */
    private fun offerDownloaded(record: DownloadRecord, request: Bundle) {
        val labels = model.state.value.language.context(activity)
        val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.forLanguageTag(model.state.value.language.localeCode))
            .format(Date(record.finished ?: record.started))
        host.track(MaterialAlertDialogBuilder(activity).setIcon(R.drawable.ic_downloads)
            .setTitle(labels.getString(R.string.download_again_title))
            .setMessage(labels.getString(R.string.download_again_message, record.fileName, date))
            .setNeutralButton(labels.getString(R.string.cancel), null)
            .setNegativeButton(labels.getString(R.string.download_again)) { _, _ -> start(request, again = true) }
            .setPositiveButton(labels.getString(R.string.open_file)) { _, _ ->
                if (!model.state.value.locked && !AppLockSession.locked)
                    activity.startActivity(FileViewerActivity.intent(activity, record.id, model.state.value.language))
            }.show())
    }

    private companion object {
        const val LEGACY = "legacy_download"
        const val URLS = "urls"
        const val SECTION = "section"
        const val FOLDERS = "folders"
        const val MOODLE = "moodle"
        const val LOGIN_RETRY_MS = 300_000L
        const val LOGIN_WAIT_MS = 120_000L
    }
}

/** What the main screen's parts need to talk to the student: app-language strings, messages, dialogs closed on lock. */
interface ScreenFeedback {
    fun text(id: Int): String
    fun message(id: Int)
    /** Registers a dialog that the lock screen closes. */
    fun track(dialog: android.app.Dialog)
}
