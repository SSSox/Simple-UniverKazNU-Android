package com.univerflow.kaznu.grades

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.pm.ApplicationInfo
import android.util.Log
import com.univerflow.kaznu.ui.withDeadline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The periodic look at the journal and the one after leaving the app ([GradeAlerts]): bound by JobScheduler only
 * (BIND_JOB_SERVICE), not exported. A look
 * that fails waits for the next period; one stopped by the system is not retried early either.
 */
class GradeCheckJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** The look running for each job id: stopping one leaves the other (periodic or after leaving) running. */
    private val running = mutableMapOf<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        running[params.jobId] = scope.launch {
            try {
                val outcome = withDeadline(DEADLINE) { GradeCheck(applicationContext).run() }
                // Only the outcome, never the page or a grade: it shows how long the site's session lasts.
                if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) Log.d(TAG, "job ${params.jobId}: $outcome")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The next period looks again.
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
