package com.univerflow.kaznu.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.univerflow.kaznu.data.AppPreferences
import com.univerflow.kaznu.domain.Updates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

enum class UpdatePhase { IDLE, CHECKING, DOWNLOADING, INSTALLING }
enum class UpdateFailure { CHECK, DOWNLOAD, VERIFY, SIGNATURE, INSTALL }

data class UpdateState(val current: String, val available: String? = null, val skipped: String? = null, val checkedAt: Long = 0,
    val phase: UpdatePhase = UpdatePhase.IDLE, val progress: Int = 0, val failure: UpdateFailure? = null,
    /** The system's install confirmation, opened by the screen in front. */
    val confirm: Intent? = null,
    /** The dialog at entry has been answered in this run. */
    val answered: Boolean = false)

/** One per process, so a download goes on through a rotation or a theme change of the screen that started it. */
class UpdateManager private constructor(private val context: Context) {
    private val client = UpdateClient()
    private val preferences = AppPreferences(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val folder = File(context.cacheDir, "updates")
    private val mutable = MutableStateFlow(load())
    val state = mutable.asStateFlow()
    private var work: Job? = null
    @Volatile private var session = NO_SESSION
    private var attemptedAt = 0L

    /** The student went to allow installing from this app; the install continues on return, even after a restart. */
    val installWaiting get() = runCatching { preferences.updateResume }.getOrDefault(false)

    init { scope.launch { folder.deleteRecursively() } }

    private fun load(): UpdateState {
        val current = UpdatePackage.installedVersion(context)
        return runCatching {
            UpdateState(current, preferences.updateVersion?.takeIf { Updates.newer(it, current) }, preferences.updateSkipped, preferences.updateChecked)
        }.getOrDefault(UpdateState(current))
    }

    /** The automatic check at entry. It never depends on the student's choices: they only decide what is shown. */
    fun checkIfDue() {
        val now = System.currentTimeMillis()
        if (Updates.due(now, mutable.value.checkedAt) && now - attemptedAt >= Updates.RETRY_INTERVAL_MS) check()
    }

    fun check() {
        if (!begin(UpdatePhase.CHECKING)) return
        attemptedAt = System.currentTimeMillis()
        work = scope.launch {
            try {
                val latest = client.latestVersion()
                val now = System.currentTimeMillis()
                val available = latest?.takeIf { Updates.newer(it, mutable.value.current) }
                runCatching { preferences.updateVersion = available; preferences.updateChecked = now }
                mutable.update { it.copy(available = available, checkedAt = now, phase = UpdatePhase.IDLE) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail(UpdateFailure.CHECK) }
        }
    }

    /** "Later": the dialog returns at the next entry. */
    fun later() { mutable.update { it.copy(answered = true) } }

    /** "Do not show again": this version stays in Settings, and a newer one is offered again. */
    fun skip(version: String) {
        runCatching { preferences.updateSkipped = version }
        mutable.update { it.copy(skipped = version, answered = true) }
    }

    fun waitForInstallPermission(waiting: Boolean) {
        runCatching { preferences.updateResume = waiting }
        if (waiting) mutable.update { it.copy(answered = true) }
    }

    fun install() {
        val version = mutable.value.available ?: return
        if (!begin(UpdatePhase.DOWNLOADING)) return
        mutable.update { it.copy(answered = true) }
        work = scope.launch {
            val apk = File(folder, "app-$version.apk")
            try {
                download(version, apk)
                mutable.update { it.copy(phase = UpdatePhase.INSTALLING) }
                if (!UpdatePackage.sameApp(context, apk)) { apk.delete(); fail(UpdateFailure.SIGNATURE); return@launch }
                commit(apk)
            } catch (cancelled: CancellationException) { apk.delete(); throw cancelled }
            catch (_: ChecksumMismatch) { apk.delete(); fail(UpdateFailure.VERIFY) }
            catch (_: Exception) { apk.delete(); fail(UpdateFailure.DOWNLOAD) }
        }
    }

    private suspend fun download(version: String, apk: File) {
        val coroutine = currentCoroutineContext()
        folder.mkdirs()
        val expected = client.checksum(version)
        val actual = apk.outputStream().use { output ->
            client.download(version, output, { coroutine.ensureActive() }) { percent -> mutable.update { it.copy(progress = percent) } }
        }
        if (actual != expected) throw ChecksumMismatch()
    }

    private fun commit(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            // After the app has installed itself once, Android 12+ can skip the confirmation.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        session = installer.createSession(params)
        installer.openSession(session).use { open ->
            open.openWrite("base.apk", 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                open.fsync(output)
            }
            // The installer fills the status into this intent, so it has to stay mutable.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            open.commit(PendingIntent.getBroadcast(context, 0, Intent(context, UpdateInstallReceiver::class.java), flags).intentSender)
        }
    }

    /** What the system installer answered. */
    fun installResult(status: Int, confirm: Intent?) {
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> mutable.update { it.copy(confirm = confirm) }
            PackageInstaller.STATUS_SUCCESS -> finish(null)
            PackageInstaller.STATUS_FAILURE_ABORTED -> finish(null)
            else -> finish(UpdateFailure.INSTALL)
        }
    }

    fun confirmOpened() { mutable.update { it.copy(confirm = null) } }

    /** The Cancel button of the progress dialog. */
    fun cancel() {
        work?.cancel()
        abandon()
        finish(null)
    }

    private fun abandon() {
        if (session != NO_SESSION) runCatching { context.packageManager.packageInstaller.abandonSession(session) }
        session = NO_SESSION
    }

    private fun finish(failure: UpdateFailure?) {
        session = NO_SESSION
        scope.launch { folder.deleteRecursively() }
        mutable.update { it.copy(phase = UpdatePhase.IDLE, progress = 0, confirm = null, failure = failure) }
    }

    private fun fail(failure: UpdateFailure) { mutable.update { it.copy(phase = UpdatePhase.IDLE, progress = 0, failure = failure) } }

    private fun begin(phase: UpdatePhase): Boolean {
        var started = false
        mutable.update { if (it.phase != UpdatePhase.IDLE) it else { started = true; it.copy(phase = phase, progress = 0, failure = null) } }
        return started
    }

    private class ChecksumMismatch : IOException()

    companion object {
        private const val NO_SESSION = -1
        // Holds the application context, which lives as long as the process.
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile private var instance: UpdateManager? = null
        fun get(context: Context): UpdateManager = instance ?: synchronized(this) {
            instance ?: UpdateManager(context.applicationContext).also { instance = it }
        }
    }
}
