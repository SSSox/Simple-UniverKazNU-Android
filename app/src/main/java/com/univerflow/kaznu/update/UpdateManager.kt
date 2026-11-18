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
import kotlinx.coroutines.isActive
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
    /** The version whose dialog was answered in this run: "Later" holds it until the next launch. */
    val answered: String? = null)

/** One per process, so a download goes on through a rotation or a theme change of the screen that started it. */
class UpdateManager private constructor(private val context: Context) {
    private val client = UpdateClient()
    private val preferences = AppPreferences(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val folder = File(context.cacheDir, "updates")
    /** Clears what an earlier process left; an attempt waits for it, so the wipe cannot take that attempt's file. */
    private val cleared = scope.launch { folder.deleteRecursively() }
    private val mutable = MutableStateFlow(load())
    val state = mutable.asStateFlow()
    private var work: Job? = null
    @Volatile private var session = NO_SESSION
    private var attemptedAt = 0L

    /** The student went to allow installing from this app; the install continues on return, even after a restart. */
    val installWaiting get() = runCatching { preferences.updateResume }.getOrDefault(false)

    private fun load(): UpdateState {
        val current = UpdatePackage.installedVersion(context)
        return runCatching {
            UpdateState(current, preferences.updateVersion?.takeIf { Updates.newer(it, current) }, preferences.updateSkipped, preferences.updateChecked)
        }.getOrDefault(UpdateState(current))
    }

    /** The automatic check at entry. It never depends on the student's choices: they only decide what is shown. */
    fun checkIfDue() {
        val now = System.currentTimeMillis()
        if (Updates.due(now, mutable.value.checkedAt, firstInRun = attemptedAt == 0L) && now - attemptedAt >= Updates.RETRY_INTERVAL_MS) check()
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

    /** "Later": the dialog returns at the next launch, or at once for a newer version. */
    fun later(version: String) { mutable.update { it.copy(answered = version) } }

    /** "Do not show again": this version stays in Settings, and a newer one is offered again. */
    fun skip(version: String) {
        runCatching { preferences.updateSkipped = version }
        mutable.update { it.copy(skipped = version, answered = version) }
    }

    fun waitForInstallPermission(waiting: Boolean) {
        runCatching { preferences.updateResume = waiting }
        if (waiting) mutable.update { it.copy(answered = it.available) }
    }

    fun install() {
        val version = mutable.value.available ?: return
        if (!begin(UpdatePhase.DOWNLOADING)) return
        mutable.update { it.copy(answered = it.available) }
        work = scope.launch {
            val coroutine = currentCoroutineContext()
            var apk: File? = null
            try {
                cleared.join()
                // Cancel can leave an attempt blocked in a read; with its own file it cannot delete or truncate the retry's.
                val file = attemptFile(folder, version).also { apk = it }
                download(version, file)
                coroutine.ensureActive()
                mutable.update { it.copy(phase = UpdatePhase.INSTALLING) }
                if (!UpdatePackage.sameApp(context, file)) { if (coroutine.isActive) fail(UpdateFailure.SIGNATURE); return@launch }
                // Cancel can be pressed up to here: the file must not reach the installer after it.
                coroutine.ensureActive()
                commit(file)
            } catch (cancelled: CancellationException) { throw cancelled }
            // A cancelled attempt reports nothing: the state may already belong to the next one.
            catch (_: ChecksumMismatch) { if (coroutine.isActive) fail(UpdateFailure.VERIFY) }
            catch (_: InstallFailed) { if (coroutine.isActive) fail(UpdateFailure.INSTALL) }
            catch (_: Exception) { if (coroutine.isActive) fail(UpdateFailure.DOWNLOAD) }
            // The installer session keeps its own copy, so the cached file is not needed after commit either.
            finally { apk?.delete() }
        }
    }

    private suspend fun download(version: String, apk: File) {
        val coroutine = currentCoroutineContext()
        val expected = client.checksum(version)
        val actual = apk.outputStream().use { output ->
            client.download(version, output, { coroutine.ensureActive() }) { percent ->
                if (coroutine.isActive) mutable.update { it.copy(progress = percent) }
            }
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
        val id = try { installer.createSession(params) } catch (e: Exception) { throw InstallFailed(e) }
        session = id
        try {
            installer.openSession(id).use { open ->
                open.openWrite("base.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output) }
                    open.fsync(output)
                }
                // The installer fills the status into this intent, so it has to stay mutable.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                open.commit(PendingIntent.getBroadcast(context, 0, Intent(context, UpdateInstallReceiver::class.java), flags).intentSender)
            }
        } catch (e: Exception) {
            // Closing does not end a session: left open, it keeps its staged bytes. Cancel may have abandoned it already.
            runCatching { installer.abandonSession(id) }
            if (session == id) session = NO_SESSION
            throw InstallFailed(e)
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
        mutable.update { it.copy(phase = UpdatePhase.IDLE, progress = 0, confirm = null, failure = failure) }
    }

    private fun fail(failure: UpdateFailure) { mutable.update { it.copy(phase = UpdatePhase.IDLE, progress = 0, failure = failure) } }

    private fun begin(phase: UpdatePhase): Boolean {
        var started = false
        mutable.update { if (it.phase != UpdatePhase.IDLE) it else { started = true; it.copy(phase = phase, progress = 0, failure = null) } }
        return started
    }

    private class ChecksumMismatch : IOException()
    /** The download was fine; the system installer refused or failed to take it. */
    private class InstallFailed(cause: Throwable) : Exception(cause)

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

/** A new file for each download attempt, even when two attempts fetch the same version. */
internal fun attemptFile(folder: File, version: String): File =
    File.createTempFile("app-$version-", ".apk", folder.apply { mkdirs() })
