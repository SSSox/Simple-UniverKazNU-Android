package com.univerflow.kaznu.main

import android.app.Dialog
import android.content.ActivityNotFoundException
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.univerflow.kaznu.R
import com.univerflow.kaznu.UniverViewModel
import com.univerflow.kaznu.domain.GradeReport
import com.univerflow.kaznu.report.GradeReportDrawing
import com.univerflow.kaznu.report.GradeReportFiles
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
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * «Поделиться успеваемостью» on the journal: the choice of a PDF, images of its pages or printing, the report drawn off
 * the main thread in the app's language, then the share sheet or the print dialog. A report finished after logout, a lock or
 * another session is dropped, never shown.
 */
class ReportFlow(private val activity: AppCompatActivity, private val model: UniverViewModel, private val host: Host) {
    interface Host : ScreenFeedback {
        val sessionEpoch: Int
    }

    private enum class Output { PDF, PNG, PRINT }

    private var making: Job? = null
    private var choice: Dialog? = null

    fun offer(report: GradeReport) {
        if (choice?.isShowing == true || making?.isActive == true) return
        GradeReportFiles.prune(activity)
        val labels = model.state.value.language.context(activity)
        val items = arrayOf(labels.getString(R.string.report_pdf), labels.getString(R.string.report_png), labels.getString(R.string.report_print))
        choice = MaterialAlertDialogBuilder(activity).setIcon(R.drawable.ic_share)
            .setTitle(labels.getString(R.string.report_dialog_title))
            .setItems(items) { _, which -> make(report, Output.entries[which]) }
            .setNegativeButton(labels.getString(R.string.cancel), null)
            .show().also(host::track)
    }

    /** Ends a report on its way: logout or a new session. */
    fun cancel() { making?.cancel(); making = null }

    private fun make(report: GradeReport, output: Output) {
        val language = model.state.value.language
        val epoch = host.sessionEpoch
        making = activity.lifecycleScope.launch {
            val labels = language.context(activity)
            val locale = Locale.forLanguageTag(language.localeCode)
            val now = Date()
            val title = labels.getString(R.string.report_file, SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(now))
            var target: File? = null
            var delivered = false
            try {
                val made = try {
                    withContext(Dispatchers.Default) {
                        val drawing = GradeReportDrawing(labels, language, report, DateFormat.getDateInstance(DateFormat.LONG, locale).format(now))
                        val file = GradeReportFiles.create(activity, title, if (output == Output.PNG) "png" else "pdf")
                        target = file
                        if (output == Output.PNG) drawing.writePng(file) { ensureActive() } else listOf(file).also { drawing.writePdf(file) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                } catch (_: OutOfMemoryError) {
                    null
                }
                // Logout deletes the reports folder under a running report, so its failure is not news to anyone either.
                if (!isActive || epoch != host.sessionEpoch || model.state.value.locked || AppLockSession.locked) return@launch
                if (made == null) { host.message(R.string.report_error); return@launch }
                delivered = deliver(made, output, title)
            } finally {
                // A report that never reached the share sheet or the printer (failed, cancelled by logout, locked) is not kept.
                if (!delivered) target?.parentFile?.deleteRecursively()
            }
        }
    }

    private fun deliver(files: List<File>, output: Output, title: String): Boolean = try {
        when (output) {
            Output.PRINT -> GradeReportFiles.print(activity, files.first(), title)
            Output.PDF -> activity.startActivity(GradeReportFiles.share(activity, files, "application/pdf", title))
            Output.PNG -> activity.startActivity(GradeReportFiles.share(activity, files, "image/png", title))
        }
        true
    } catch (_: ActivityNotFoundException) {
        host.message(R.string.report_error); false
    } catch (_: IllegalStateException) {
        host.message(R.string.report_error); false // The screen went away before the print dialog could open.
    }
}
