package com.univerflow.kaznu.grades

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.pm.ApplicationInfo
import android.util.Log
import com.univerflow.kaznu.data.GradeStore
import com.univerflow.kaznu.ui.withDeadline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The periodic look at the journal, the one after leaving the app, and short retries after a failed read
 * ([GradeAlerts]): bound by JobScheduler only (BIND_JOB_SERVICE), not exported. Only a failed read schedules a short
 * retry (1 min, then 5 min); one stopped by the system is not retried early.
 */
class GradeCheckJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** The look running for each job id: stopping one leaves the other jobs running. */
    private val running = mutableMapOf<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        running[params.jobId] = scope.launch {
            val store = GradeStore.of(applicationContext)
            val since = store.generation
            val outcome = try {
                withDeadline(DEADLINE) { GradeCheck(applicationContext).run() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                GradeCheck.Outcome.FAILED
            }
            // Only the outcome, never the page or a grade: it shows how long the site's session lasts.
            if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) Log.d(TAG, "job ${params.jobId}: $outcome")
            withContext(GradeAlerts.lane) {
                GradeAlerts.record(applicationContext, outcome) { store.generation == since }
            }
            running.remove(params.jobId)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val DEADLINE = 90_000L
        const val TAG = "GradeCheck"
    }
}
