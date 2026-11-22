package com.univerflow.kaznu.main

import android.app.Dialog
import android.content.ActivityNotFoundException
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.univerflow.kaznu.R
import com.univerflow.kaznu.UniverViewModel
import com.univerflow.kaznu.domain.ExportKind
import com.univerflow.kaznu.domain.SchedulePair
import com.univerflow.kaznu.domain.ScheduleCalendar
import com.univerflow.kaznu.report.GradeReportFiles
import com.univerflow.kaznu.report.ScheduleDrawing
import com.univerflow.kaznu.ui.AppLockSession
import com.univerflow.kaznu.ui.context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The schedule page's «В календарь» (both weeks as one calendar file) and «Картинкой» (both weeks side by side as a PNG),
 * and a class card's «В календарь» when the neighbouring week could not be read. The file is made off the main thread in the app's language, in the reports folder
 * of the cache (removed after an hour and on logout), and opened in the calendar app, else offered to any app that takes
 * it. A week that could not be read is said first and the student decides; nothing is guessed in its place. A file finished
 * after logout, a lock or another session is dropped.
 */
class ScheduleFlow(private val activity: AppCompatActivity, private val model: UniverViewModel, private val host: Host) {
    interface Host : ScreenFeedback {
        val sessionEpoch: Int
    }

    private var making: Job? = null
    private var asking: Dialog? = null

    /** The page's request, or null for a page that could not be read. */
    fun offer(request: Pair<ExportKind, SchedulePair>?) {
        if (making?.isActive == true || asking?.isShowing == true) return
        if (request == null) { host.message(R.string.schedule_error); return }
        make(request.second, request.first)
    }

    /** A dialog with [message] and «Открыть»; [then] runs only on the student's yes. */
    fun confirm(message: String, then: () -> Unit, declined: () -> Unit = {}) {
        val labels = model.state.value.language.context(activity)
        asking = MaterialAlertDialogBuilder(activity).setIcon(R.drawable.ic_share)
            .setMessage(message)
            .setPositiveButton(labels.getString(R.string.schedule_part_open)) { _, _ -> then() }
            .setNegativeButton(labels.getString(R.string.cancel)) { _, _ -> declined() }
            .setOnCancelListener { declined() }
            .show().also(host::track)
    }

    /** Ends a file on its way: logout or a new session. */
    fun cancel() { making?.cancel(); making = null; asking?.dismiss() }

    private fun make(pair: SchedulePair, kind: ExportKind) {
        val language = model.state.value.language
        val epoch = host.sessionEpoch
        making = activity.lifecycleScope.launch {
            val labels = language.context(activity)
            val title = labels.getString(if (pair.weeks.size + pair.missing.size == 2) R.string.schedule_file_weeks else R.string.schedule_file_week, pair.span)
            var target: File? = null
            var delivered = false
            try {
                val made = try {
                    withContext(Dispatchers.Default) {
                        if (kind == ExportKind.CALENDAR) {
                            if (ScheduleCalendar.events(pair).isEmpty()) return@withContext null
                            val file = GradeReportFiles.create(activity, title, "ics")
                            target = file
                            file.writeText(ScheduleCalendar.build(pair, labels.getString(R.string.schedule_calendar_name), language.localeCode, System.currentTimeMillis()), Charsets.UTF_8)
                            file
                        } else {
                            val file = GradeReportFiles.create(activity, title, "png")
                            target = file
                            ScheduleDrawing(labels, language, pair).writePng(file) { ensureActive() }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    host.message(R.string.schedule_error); return@launch
                } catch (_: OutOfMemoryError) {
                    host.message(R.string.schedule_error); return@launch
                }
                if (!isActive || epoch != host.sessionEpoch || model.state.value.locked || AppLockSession.locked) return@launch
                if (made == null) { host.message(R.string.schedule_empty); return@launch }
                delivered = true
                val open = { deliver(made, title, kind) }
                if (pair.missing.isEmpty()) open() else confirm(unreadText(pair, labels, kind), open, declined = { made.parentFile?.deleteRecursively() })
            } finally {
                // A file that never reached the calendar or the share sheet (failed, empty, cancelled by logout, locked) is not kept.
                if (!delivered) target?.parentFile?.deleteRecursively()
            }
        }
    }

    /** Names the week that was not read when the site numbers its weeks (the read one says so), and what is in the file or picture instead. */
    private fun unreadText(pair: SchedulePair, labels: android.content.Context, kind: ExportKind): String {
        val named = pair.weeks.all { it.labelled }
        val picture = kind == ExportKind.PICTURE
        return if (named) labels.getString(if (picture) R.string.schedule_picture_part_named else R.string.schedule_part_named, pair.missing.first(), pair.weeks.first().number)
        else labels.getString(if (picture) R.string.schedule_picture_part_other else R.string.schedule_part_other)
    }

    private fun deliver(file: File, title: String, kind: ExportKind) {
        try {
            if (kind == ExportKind.PICTURE) { activity.startActivity(GradeReportFiles.share(activity, listOf(file), "image/png", title)); return }
            activity.startActivity(GradeReportFiles.view(activity, file, "text/calendar"))
        } catch (_: ActivityNotFoundException) {
            try {
                if (kind == ExportKind.PICTURE) throw ActivityNotFoundException()
                activity.startActivity(GradeReportFiles.share(activity, listOf(file), "text/calendar", title))
            } catch (_: ActivityNotFoundException) {
                host.message(if (kind == ExportKind.PICTURE) R.string.schedule_error else R.string.calendar_missing)
                file.parentFile?.deleteRecursively()
            }
        }
    }
}
