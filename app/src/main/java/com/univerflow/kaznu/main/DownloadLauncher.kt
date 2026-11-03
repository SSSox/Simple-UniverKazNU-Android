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
import com.univerflow.kaznu.download.DownloadService
import com.univerflow.kaznu.ui.AppLockSession
import com.univerflow.kaznu.ui.FileActions
import com.univerflow.kaznu.ui.FileViewerActivity
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
        fun openExternal(url: String)
        fun openDownloads()
        fun downloadStarted()
        fun unsupported()
        /** Reloads the page (the site sends it to its login form) or renews the Moodle session; false when it cannot. */
        fun signInAgain(moodle: Boolean): Boolean
    }

    private var legacyRequest: Bundle? = null
    private var loginRequest: Bundle? = null
    private val loginAttempts = mutableMapOf<String, Long>()
    private val notificationPermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val storagePermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = legacyRequest
        legacyRequest = null
        if (granted) pending?.let(::start) else host.message(R.string.download_permission)
    }

    fun restore(saved: Bundle?) { legacyRequest = saved?.getBundle(LEGACY) }

    fun save(outState: Bundle) { legacyRequest?.let { outState.putBundle(LEGACY, it) } }

    /** Forgets pending requests: the session that asked for them is gone. */
    fun reset() { legacyRequest = null; loginRequest = null }

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

    private fun request(url: String, userAgent: String, disposition: String?, mime: String?) = Bundle().apply {
        putString("url", url); putString("agent", userAgent)
        putString("referer", host.pageUrl?.takeIf { SingleSignOnPolicy.sameService(url, it) })
        putString("disposition", disposition); putString("mime", mime)
        putInt("epoch", host.sessionEpoch); putString("source", host.pageSource)
    }

    /** A file refused by an ended session: sign in again and fetch it afterwards. */
    fun signInFor(url: String): Boolean {
        val state = model.state.value
        if (host.sessionChanging || state.paused || !state.hasCredentials || host.rendererUnavailable) return false
        val now = SystemClock.elapsedRealtime()
        loginAttempts.entries.removeAll { now - it.value > LOGIN_RETRY_MS }
        if (url in loginAttempts) return false
        val moodle = SingleSignOnPolicy.moodle(url)
        if (!moodle && !SitePolicy.trusted(url)) return false
        loginAttempts[url] = now
        loginRequest = request(url, host.userAgent, null, null).apply { putLong("at", now) }
        if (!host.signInAgain(moodle)) { loginRequest = null; return false }
        return true
    }

    /** Called once automation has signed in: the refused file is fetched if the sign-in was recent. */
    fun signedIn() {
        val request = loginRequest ?: return
        loginRequest = null
        if (SystemClock.elapsedRealtime() - request.getLong("at") <= LOGIN_WAIT_MS) start(request)
    }

    private fun start(request: Bundle, again: Boolean = false) {
        activity.lifecycleScope.launch {
            model.state.first { it.ready }
            val url = request.getString("url") ?: return@launch
            if (host.sessionChanging || model.state.value.paused || !model.state.value.hasCredentials ||
                request.getInt("epoch", -1) != host.sessionEpoch || !SingleSignOnPolicy.downloadAllowed(url)) return@launch
            if (!again) {
                // The same file is already in Downloads: offer it instead of saving a second copy ("name (1).pdf").
                val history = DownloadRepository.get(activity)
                history.awaitLoaded()
                val earlier = DownloadHistoryRules.previous(history.records.value, DownloadKeys.of(url))
                if (earlier != null && withContext(Dispatchers.IO) { FileActions.exists(activity, earlier) }) {
                    if (!activity.isFinishing && !activity.isDestroyed && !model.state.value.locked) offerDownloaded(earlier, request)
                    return@launch
                }
            }
            try {
                DownloadService.start(activity, url, request.getString("agent").orEmpty(), request.getString("referer"),
                    request.getString("disposition"), request.getString("mime"), model.state.value.language, request.getString("source").orEmpty())
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
