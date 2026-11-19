package com.univerflow.kaznu.grades

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.univerflow.kaznu.R
import com.univerflow.kaznu.data.AppPreferences
import com.univerflow.kaznu.data.GradeStore
import com.univerflow.kaznu.data.LockVault
import com.univerflow.kaznu.domain.GradeChange
import com.univerflow.kaznu.launch.LaunchTarget
import com.univerflow.kaznu.ui.context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * «Следить за оценками в фоне», off until the student turns it on. On: a JobScheduler job looks at the journal about
 * every [PERIOD] with a network, only while the site's session lasts; off, paused or signed out: no job and no saved
 * grades. After a look finds the session over, no further look goes out until the app shows a page again.
 */
object GradeAlerts {
    const val JOB = 7401
    /** One look a while after the app goes to the background, while its session is still likely to last. */
    const val AFTER_LEAVING = 7403
    private const val NOTIFICATION = 7402
    private const val LEAVING_DELAY = 20 * 60_000L
    private const val CHANNEL = "grades"
    private const val PERIOD = 3 * 60 * 60_000L
    private const val FLEX = 60 * 60_000L
    private const val STATE = "grade_alerts"
    private const val WAITING = "signed_out"

    /** Settings changes and the sign-out erase run one after another, so an older "off" never lands after a newer "on". */
    val lane: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** The main screen is in front: it reads the journal itself, so a background look is not needed. */
    @Volatile var appVisible = false

    /** Tracking as the settings say ([enabled]: on and not paused): sets the job, or removes it with what it kept. */
    fun sync(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        val scheduler = app.getSystemService(JobScheduler::class.java) ?: return
        if (!enabled) { erase(app); return }
        // Scheduling again would restart the period; a job already waiting is left as it is.
        if (scheduler.getPendingJob(JOB) != null) return
        runCatching {
            scheduler.schedule(JobInfo.Builder(JOB, ComponentName(app, GradeCheckJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(PERIOD, FLEX)
                .setPersisted(true)
                .build())
        }
    }

    /** The app went to the background with tracking on: one look after [LEAVING_DELAY], replaced by the next leave. */
    fun left(context: Context) {
        val app = context.applicationContext
        runCatching {
            app.getSystemService(JobScheduler::class.java)?.schedule(JobInfo.Builder(AFTER_LEAVING, ComponentName(app, GradeCheckJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(LEAVING_DELAY)
                .build())
        }
    }

    /** The app is in front again: it reads the journal itself. */
    fun returned(context: Context) {
        runCatching { context.applicationContext.getSystemService(JobScheduler::class.java)?.cancel(AFTER_LEAVING) }
    }

    /** Off, pause or sign-out: no job, no saved grades, no notification left. */
    fun erase(context: Context) {
        val app = context.applicationContext
        runCatching { app.getSystemService(JobScheduler::class.java)?.run { cancel(JOB); cancel(AFTER_LEAVING) } }
        GradeStore.of(app).erase()
        state(app).edit(commit = true) { clear() }
        withdraw(app)
    }

    fun withdraw(context: Context) = NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION)

    /** May a background look go out now? Reads preferences: off the main thread. */
    fun allowed(context: Context): Boolean =
        !appVisible && !state(context).getBoolean(WAITING, false) && GradeCheck.allowedBy(AppPreferences(context))

    /** The session was over: wait for the app to show a page before looking again. */
    fun signedOut(context: Context) = state(context).edit(commit = true) { putBoolean(WAITING, true) }

    /** A page of the site is on screen, so the session is back. Reads preferences: off the main thread. */
    fun sessionBack(context: Context) {
        val state = state(context)
        if (state.getBoolean(WAITING, false)) state.edit(commit = true) { remove(WAITING) }
    }

    private fun state(context: Context) = context.applicationContext.getSharedPreferences(STATE, Context.MODE_PRIVATE)

    /** Says what is new; with a PIN set, only that there are new grades. */
    fun notify(context: Context, changes: List<GradeChange>) {
        val app = context.applicationContext
        if (changes.isEmpty() || !NotificationManagerCompat.from(app).areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val hidden = runCatching { LockVault(app).exists() }.getOrDefault(true)
        try { NotificationManagerCompat.from(app).notify(NOTIFICATION, notification(app, changes, hidden)) } catch (_: SecurityException) {}
    }

    /** [hidden]: a PIN is set, so no course and no grade show. */
    internal fun notification(context: Context, changes: List<GradeChange>, hidden: Boolean): Notification {
        val words = AppPreferences(context).language.context(context)
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(words.getString(R.string.channel_grades)).setDescription(words.getString(R.string.channel_grades_description)).build())
        val open = PendingIntent.getActivity(context, 4, LaunchTarget.JOURNAL.intent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val some = words.getString(R.string.grades_appeared)
        val base = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_grades)
            .setColor(ContextCompat.getColor(context, R.color.md_primary))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(open)
            .setAutoCancel(true)
        if (hidden) return base.setContentTitle(some).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).build()
        val lines = changes.map { words.getString(R.string.grade_line, it.course, it.label, it.score) }
        val public = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_grades).setContentTitle(some).build()
        return base.setContentTitle(words.getString(if (changes.size == 1) R.string.grade_new else R.string.grades_new))
            .setContentText(lines.joinToString("; "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.take(MAX_LINES).joinToString("\n")))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public).build()
    }

    private const val MAX_LINES = 8
}
