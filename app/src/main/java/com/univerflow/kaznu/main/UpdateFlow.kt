package com.univerflow.kaznu.main

import android.content.ActivityNotFoundException
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.univerflow.kaznu.R
import com.univerflow.kaznu.UniverViewModel
import com.univerflow.kaznu.databinding.ViewUpdateProgressBinding
import com.univerflow.kaznu.domain.Updates
import com.univerflow.kaznu.ui.AppLockSession
import com.univerflow.kaznu.ui.context
import com.univerflow.kaznu.update.UpdateFailure
import com.univerflow.kaznu.update.UpdateManager
import com.univerflow.kaznu.update.UpdatePhase
import com.univerflow.kaznu.update.UpdateState
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** The dialog at entry, the download progress and the system's install confirmation on the main screen. */
class UpdateFlow(private val activity: AppCompatActivity, private val model: UniverViewModel, private val host: Host) {
    interface Host : ScreenFeedback {
        val sessionChanging: Boolean
        val settingsShowing: Boolean
        fun refreshSettings()
    }

    private val updates = UpdateManager.get(activity)
    private var prompt: AlertDialog? = null
    private var progress: AlertDialog? = null
    private var progressView: ViewUpdateProgressBinding? = null
    private var reported = updates.state.value.failure

    val state get() = updates.state.value

    /** Every return to the screen: the check, and an install that was waiting for the student's permission. */
    fun entered() {
        if (updates.installWaiting) {
            updates.waitForInstallPermission(false)
            if (canInstall()) updates.install()
        }
        updates.checkIfDue()
    }

    fun check() = updates.check()

    fun install() {
        if (canInstall()) { updates.install(); return }
        updates.waitForInstallPermission(true)
        Toast.makeText(activity, host.text(R.string.update_allow), Toast.LENGTH_LONG).show()
        try { activity.startActivity(android.content.Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${activity.packageName}".toUri())) }
        catch (_: ActivityNotFoundException) { updates.waitForInstallPermission(false); host.message(R.string.open_failed) }
    }

    private fun canInstall() = Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls()

    fun render(update: UpdateState) {
        update.confirm?.let { confirm ->
            updates.confirmOpened()
            try { activity.startActivity(confirm) }
            catch (_: ActivityNotFoundException) { updates.installResult(PackageInstaller.STATUS_FAILURE, null) }
        }
        if (update.failure != reported) {
            reported = update.failure
            // A failed check is only a line in Settings: the student did not ask for it at entry.
            update.failure?.takeIf { it != UpdateFailure.CHECK }?.let { host.message(failureText(it)) }
        }
        host.refreshSettings()
        refresh()
    }

    /** Shows what the state asks for, once the lock screen and the settings sheet leave room for it. */
    fun refresh() {
        val update = updates.state.value
        val app = model.state.value
        if (!app.ready || host.sessionChanging || (app.lock.enabled && (app.locked || AppLockSession.locked))) return
        showProgress(update)
        if (update.phase == UpdatePhase.IDLE && !host.settingsShowing && prompt?.isShowing != true &&
            Updates.prompt(update.available, update.skipped, update.answered)) showPrompt(update)
    }

    private fun showPrompt(update: UpdateState) {
        val version = update.available ?: return
        val labels = model.state.value.language.context(activity)
        val never = MaterialCheckBox(labels).apply { text = host.text(R.string.update_never) }
        val padding = (20 * activity.resources.displayMetrics.density).toInt()
        val frame = FrameLayout(labels).apply { setPadding(padding, padding / 2, padding, 0); addView(never) }
        prompt = MaterialAlertDialogBuilder(activity)
            .setIcon(R.drawable.ic_downloads)
            .setTitle(host.text(R.string.update_title))
            .setMessage(labels.getString(R.string.update_message, version, update.current))
            .setView(frame)
            .setNegativeButton(host.text(R.string.update_later)) { _, _ -> if (never.isChecked) updates.skip(version) else updates.later() }
            .setPositiveButton(host.text(R.string.update_install)) { _, _ -> install() }
            .setOnCancelListener { if (never.isChecked) updates.skip(version) else updates.later() }
            .show().also(host::track)
    }

    private fun showProgress(update: UpdateState) {
        val busy = update.phase == UpdatePhase.DOWNLOADING || update.phase == UpdatePhase.INSTALLING
        if (!busy) { progress?.dismiss(); progress = null; progressView = null; return }
        if (progress?.isShowing != true) {
            val view = ViewUpdateProgressBinding.inflate(LayoutInflater.from(activity))
            progressView = view
            progress = MaterialAlertDialogBuilder(activity).setTitle(host.text(R.string.update_section)).setView(view.root).setCancelable(false)
                .setNegativeButton(host.text(R.string.cancel)) { _, _ -> updates.cancel() }.show().also(host::track)
        }
        val labels = model.state.value.language.context(activity)
        progressView?.apply {
            val installing = update.phase == UpdatePhase.INSTALLING
            updateProgressText.text = if (installing) labels.getString(R.string.update_preparing) else labels.getString(R.string.update_downloading, update.progress)
            updateProgress.setProgressCompat(if (installing) 100 else update.progress, true)
        }
    }

    /** The Settings line: the installed version, what the last check found, and when it ran. */
    fun status(): String {
        val update = updates.state.value
        val language = model.state.value.language
        val labels = language.context(activity)
        val found = when {
            update.phase == UpdatePhase.CHECKING -> labels.getString(R.string.update_checking)
            update.phase == UpdatePhase.DOWNLOADING -> labels.getString(R.string.update_downloading, update.progress)
            update.phase == UpdatePhase.INSTALLING -> labels.getString(R.string.update_preparing)
            update.failure != null -> labels.getString(failureText(update.failure))
            update.available != null -> labels.getString(R.string.update_available, update.available)
            update.checkedAt > 0 -> labels.getString(R.string.update_latest)
            else -> null
        }
        val checked = update.checkedAt.takeIf { it > 0 }?.let {
            val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.forLanguageTag(language.localeCode))
            labels.getString(R.string.update_checked, format.format(Date(it)))
        }
        return listOfNotNull(labels.getString(R.string.update_current, update.current), found, checked).joinToString("\n")
    }

    private fun failureText(failure: UpdateFailure) = when (failure) {
        UpdateFailure.CHECK -> R.string.update_check_failed
        UpdateFailure.DOWNLOAD -> R.string.update_download_failed
        UpdateFailure.VERIFY -> R.string.update_verify_failed
        UpdateFailure.SIGNATURE -> R.string.update_signature_failed
        UpdateFailure.INSTALL -> R.string.update_install_failed
    }
}
