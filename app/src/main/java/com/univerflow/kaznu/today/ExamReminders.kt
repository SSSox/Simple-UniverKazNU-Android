package com.univerflow.kaznu.today

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.AlarmManagerCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.univerflow.kaznu.R
import com.univerflow.kaznu.data.AppPreferences
import com.univerflow.kaznu.data.ExamStore
import com.univerflow.kaznu.data.LockVault
import com.univerflow.kaznu.domain.ExamKind
import com.univerflow.kaznu.domain.ExamReminder
import com.univerflow.kaznu.domain.Timetable
import com.univerflow.kaznu.launch.LaunchTarget
import com.univerflow.kaznu.ui.context
import java.util.TimeZone

/**
 * Reminders for the exams and midterms of the saved lists, under the same switch and lead time as [ClassReminders]:
 * one at 19:00 the evening before and one lead minutes before the start. Same mechanics too: one alarm at a time
 * (exact where Android allows, otherwise the ten minutes before), the last reminder shown is remembered so an early
 * alarm does not bring it up twice, and a PIN reduces the text to the time. It has its own channel, so the student can
 * silence exams without silencing classes, and the evening notice is not cut short by the exam starting.
 * [TodaySync] sets the alarm again after a restart.
 */
object ExamReminders {
    private const val CHANNEL = "exams"
    private const val NOTIFICATION = 7302
    private const val EXTRA_AT = "at"
    private const val MINUTE = 60_000L
    private const val WINDOW = 10 * MINUTE
    private const val STATE = "exam_reminders"
    private const val SHOWN = "shown"

    /** Sets (or removes) the alarm for the next reminder. Reads a file: off the main thread. */
    fun schedule(context: Context) {
        val app = context.applicationContext
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return
        val settings = AppPreferences(app).reminders
        val shown = state(app).getLong(SHOWN, Long.MIN_VALUE)
        val next = if (settings.enabled) ExamStore.read(app)?.next(System.currentTimeMillis() + MINUTE / 2, TimeZone.getDefault(), settings.lead * MINUTE, shown) else null
        if (next == null) { alarms.cancel(alarm(app, 0)); return }
        val intent = alarm(app, next.at)
        runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms())
                AlarmManagerCompat.setExactAndAllowWhileIdle(alarms, AlarmManager.RTC_WAKEUP, next.at, intent)
            else alarms.setWindow(AlarmManager.RTC_WAKEUP, next.at - WINDOW, WINDOW, intent)
        }
    }

    /** Sign-out: no alarm, no reminder left on screen and nothing remembered. */
    fun cancel(context: Context) {
        val app = context.applicationContext
        app.getSystemService(AlarmManager::class.java)?.cancel(alarm(app, 0))
        NotificationManagerCompat.from(app).cancel(NOTIFICATION)
        state(app).edit(commit = true) { clear() }
    }

    private fun state(context: Context) = context.getSharedPreferences(STATE, Context.MODE_PRIVATE)

    /** The reminder due at [at] is still in the saved lists and reminders are on: says so, then sets the next one. */
    internal fun remind(context: Context, at: Long) {
        val app = context.applicationContext
        val settings = AppPreferences(app).reminders
        val now = System.currentTimeMillis()
        val zone = TimeZone.getDefault()
        val shown = state(app).getLong(SHOWN, Long.MIN_VALUE)
        val due = if (settings.enabled && at > shown)
            ExamStore.read(app)?.next(at - 1, zone, settings.lead * MINUTE, shown)?.takeIf { it.at == at && it.item.startAt(zone) > now } else null
        if (due != null) state(app).edit(commit = true) { putLong(SHOWN, at) }
        if (due != null && allowed(app)) {
            // The permission can be taken away between the check and the call.
            try { NotificationManagerCompat.from(app).notify(NOTIFICATION, notification(app, due, now, runCatching { LockVault(app).exists() }.getOrDefault(true))) } catch (_: SecurityException) {}
        }
        schedule(app)
    }

    private fun allowed(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    /** [hidden]: a PIN is set, so only the kind of event and its time show. */
    internal fun notification(context: Context, reminder: ExamReminder, now: Long, hidden: Boolean): Notification {
        val words = AppPreferences(context).language.context(context)
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(words.getString(R.string.channel_exams)).setDescription(words.getString(R.string.channel_exams_description)).build())
        val zone = TimeZone.getDefault()
        val item = reminder.item
        val start = item.startAt(zone)
        val kind = words.getString(if (item.kind == ExamKind.EXAM) R.string.exam_kind_exam else R.string.exam_kind_midterm)
        val starts = words.getString(R.string.reminder_starts, Timetable.clock(item.minute))
        val minutes = ((start - now + MINUTE - 1) / MINUTE).toInt().coerceAtLeast(1)
        val soon = if (reminder.evening) words.getString(R.string.exam_tomorrow, kind) else words.getString(R.string.exam_soon, kind)
        val title = if (reminder.evening) words.getString(R.string.exam_tomorrow_title, kind, item.subject)
            else words.getString(R.string.exam_title, minutes, kind, item.subject)
        val details = listOf(item.place.takeIf { it.isNotEmpty() }?.let { words.getString(R.string.widget_room, it) }, starts).filterNotNull().joinToString(" · ")
        val open = PendingIntent.getActivity(context, 7, LaunchTarget.SCHEDULE.intent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val base = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_calendar)
            .setColor(ContextCompat.getColor(context, R.color.md_primary))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter((start - now + 2 * 60 * MINUTE).coerceAtLeast(MINUTE))
        val public = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_calendar).setContentTitle(soon).setContentText(starts).build()
        return if (hidden) base.setContentTitle(soon).setContentText(starts).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).build()
        else base.setContentTitle(title).setContentText(details).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public).build()
    }

    private fun alarm(context: Context, at: Long) = PendingIntent.getBroadcast(context, 6,
        Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_AT, at), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** The alarm itself: explicit, not exported. */
    class ReminderReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val at = intent.getLongExtra(EXTRA_AT, 0)
            Background.run(this) { remind(context, at) }
        }
    }
}
