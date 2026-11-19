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
import com.univerflow.kaznu.data.LockVault
import com.univerflow.kaznu.data.TimetableStore
import com.univerflow.kaznu.domain.LessonTime
import com.univerflow.kaznu.domain.Timetable
import com.univerflow.kaznu.launch.LaunchTarget
import com.univerflow.kaznu.ui.context
import java.util.TimeZone

/**
 * «Напоминать о парах»: one alarm at a time, for the next class of the saved week, [lead] minutes before it. Exact
 * where Android allows it; otherwise within the ten minutes before that moment (Android's shortest window since 14),
 * so it comes early rather than late; a phone in deep sleep may still hold it until it wakes. The class reminded of is
 * remembered, so an early alarm does not bring it up twice. The notification hides the class with a PIN set, as the
 * widget does.
 */
object ClassReminders {
    private const val CHANNEL = "classes"
    private const val NOTIFICATION = 7301
    private const val EXTRA_START = "start"
    private const val MINUTE = 60_000L
    private const val WINDOW = 10 * MINUTE
    private const val STATE = "class_reminders"
    private const val SHOWN = "shown"

    /** Sets (or removes) the alarm for the next class. Reads a file: off the main thread. */
    fun schedule(context: Context) {
        val app = context.applicationContext
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return
        val settings = AppPreferences(app).reminders
        val lead = settings.lead * MINUTE
        val shown = state(app).getLong(SHOWN, Long.MIN_VALUE)
        val next = if (settings.enabled) TimetableStore.read(app)?.next(System.currentTimeMillis() + MINUTE / 2, TimeZone.getDefault(), lead, shown) else null
        if (next == null) { alarms.cancel(alarm(app, 0)); return }
        val at = next.startAt - lead
        val intent = alarm(app, next.startAt)
        runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms())
                AlarmManagerCompat.setExactAndAllowWhileIdle(alarms, AlarmManager.RTC_WAKEUP, at, intent)
            else alarms.setWindow(AlarmManager.RTC_WAKEUP, at - WINDOW, WINDOW, intent)
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

    /** The class at [start] is still in the saved week and reminders are on: says so, then sets the next one. */
    internal fun remind(context: Context, start: Long) {
        val app = context.applicationContext
        val settings = AppPreferences(app).reminders
        val now = System.currentTimeMillis()
        val zone = TimeZone.getDefault()
        val shown = state(app).getLong(SHOWN, Long.MIN_VALUE)
        val lesson = if (settings.enabled && start > now && start > shown)
            TimetableStore.read(app)?.times(Timetable.epochDay(start, zone), zone)?.firstOrNull { it.startAt == start } else null
        if (lesson != null) state(app).edit(commit = true) { putLong(SHOWN, start) }
        if (lesson != null && allowed(app)) {
            // The permission can be taken away between the check and the call.
            try { NotificationManagerCompat.from(app).notify(NOTIFICATION, notification(app, lesson, now, runCatching { LockVault(app).exists() }.getOrDefault(true))) } catch (_: SecurityException) {}
        }
        schedule(app)
    }

    private fun allowed(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    /** [hidden]: a PIN is set, so only the time shows. */
    internal fun notification(context: Context, lesson: LessonTime, now: Long, hidden: Boolean): Notification {
        val words = AppPreferences(context).language.context(context)
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(words.getString(R.string.channel_classes)).setDescription(words.getString(R.string.channel_classes_description)).build())
        val time = Timetable.clock(lesson.lesson.start)
        val soon = words.getString(R.string.reminder_soon)
        val starts = words.getString(R.string.reminder_starts, time)
        val open = PendingIntent.getActivity(context, 2, LaunchTarget.SCHEDULE.intent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val minutes = ((lesson.startAt - now + MINUTE - 1) / MINUTE).toInt().coerceAtLeast(1)
        val details = listOf(lesson.lesson.type, lesson.lesson.room.takeIf { it.isNotEmpty() }?.let { words.getString(R.string.widget_room, it) }, starts)
            .filter { !it.isNullOrEmpty() }.joinToString(" · ")
        val base = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_calendar)
            .setColor(ContextCompat.getColor(context, R.color.md_primary))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter((lesson.endAt - now).coerceAtLeast(MINUTE))
        val public = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_calendar).setContentTitle(soon).setContentText(starts).build()
        return if (hidden) base.setContentTitle(soon).setContentText(starts).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).build()
        else base.setContentTitle(words.getString(R.string.reminder_title, minutes, lesson.lesson.subject)).setContentText(details)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public).build()
    }

    private fun alarm(context: Context, start: Long) = PendingIntent.getBroadcast(context, 3,
        Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_START, start), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** The alarm itself: explicit, not exported. */
    class ReminderReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val start = intent.getLongExtra(EXTRA_START, 0)
            Background.run(this) { remind(context, start) }
        }
    }
}
