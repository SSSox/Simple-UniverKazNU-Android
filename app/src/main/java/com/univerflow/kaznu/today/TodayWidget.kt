package com.univerflow.kaznu.today

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.RemoteViews
import com.univerflow.kaznu.R
import com.univerflow.kaznu.data.AppPreferences
import com.univerflow.kaznu.data.LockVault
import com.univerflow.kaznu.data.TimetableStore
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.domain.LessonTime
import com.univerflow.kaznu.domain.Timetable
import com.univerflow.kaznu.domain.TodayPlan
import com.univerflow.kaznu.launch.LaunchTarget
import com.univerflow.kaznu.ui.context
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * «Пары на сегодня» on the home screen, drawn from the saved week only: the widget never reaches the network. With a PIN
 * set, the classes show their times alone, as the student chose. A tap opens the schedule (behind the PIN as usual).
 */
class TodayWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Background.run(this) { refresh(context) }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        Background.run(this) { refresh(context) }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TICK) Background.run(this) { refresh(context) } else super.onReceive(context, intent)
    }

    override fun onDisabled(context: Context) = cancelTick(context)

    companion object {
        private const val ACTION_TICK = "com.univerflow.kaznu.action.WIDGET_TICK"
        private val STARTS = intArrayOf(R.id.widget_start_1, R.id.widget_start_2, R.id.widget_start_3, R.id.widget_start_4)
        private val ENDS = intArrayOf(R.id.widget_end_1, R.id.widget_end_2, R.id.widget_end_3, R.id.widget_end_4)
        private val ROWS = intArrayOf(R.id.widget_row_1, R.id.widget_row_2, R.id.widget_row_3, R.id.widget_row_4)
        private val SUBJECTS = intArrayOf(R.id.widget_subject_1, R.id.widget_subject_2, R.id.widget_subject_3, R.id.widget_subject_4)
        private val DETAILS = intArrayOf(R.id.widget_detail_1, R.id.widget_detail_2, R.id.widget_detail_3, R.id.widget_detail_4)

        /** Draws every placed widget again and sets the next redraw. Reads a file: off the main thread. */
        fun refresh(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(app, TodayWidget::class.java)) }.getOrNull() ?: IntArray(0)
            if (ids.isEmpty()) { cancelTick(app); return }
            val timetable = TimetableStore.read(app)
            val now = System.currentTimeMillis()
            val zone = TimeZone.getDefault()
            val language = AppPreferences(app).language
            val hidden = runCatching { LockVault(app).exists() }.getOrDefault(true)
            ids.forEach { id ->
                val height = runCatching { manager.getAppWidgetOptions(id) }.getOrNull()?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)?.takeIf { it > 0 }
                runCatching { manager.updateAppWidget(id, draw(app, timetable, now, zone, language, hidden, height)) }
            }
            scheduleTick(app, TodayPlan.nextChange(timetable, now, zone))
        }

        /**
         * As many whole class rows as [height] (dp, null when the launcher does not say) holds under the heading, the
         * [note] line and above the footer, at the phone's text size: widget_today.xml's paddings and text sizes.
         */
        internal fun rowsFor(height: Int?, note: Boolean, fontScale: Float): Int {
            if (height == null) return TodayPlan.ROWS
            val chrome = 24 + 4 + 35 * fontScale + if (note) 2 + 18 * fontScale else 0f
            val row = 8 + maxOf(40f, 8 + 31 * fontScale)
            return ((height - chrome) / row).toInt().coerceIn(1, TodayPlan.ROWS)
        }

        /** [height]: the widget's height in dp, which decides how many classes fit. */
        internal fun draw(context: Context, timetable: Timetable?, now: Long, zone: TimeZone, language: AppLanguage, hidden: Boolean, height: Int?): RemoteViews {
            val words = language.context(context)
            val locale = Locale.forLanguageTag(language.localeCode)
            val views = RemoteViews(context.packageName, R.layout.widget_today)
            views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 0, LaunchTarget.SCHEDULE.intent(context),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            val whole = timetable?.let { TodayPlan.of(it, now, zone) }
            val note = when {
                timetable == null -> words.getString(R.string.widget_empty)
                whole == null -> words.getString(R.string.widget_none_week)
                whole.free -> words.getString(R.string.widget_free)
                whole.finished -> words.getString(R.string.widget_finished)
                else -> null
            }
            val rows = rowsFor(height, note != null, context.resources.configuration.fontScale)
            val plan = timetable?.let { TodayPlan.of(it, now, zone, rows) }
            val today = Timetable.epochDay(now, zone)
            // "Сегодня · ср, 18 ноября", "Завтра · чт, 19 ноября", or a later day by name: "Понедельник · 23 ноября".
            val named = plan != null && !plan.today && plan.date != today + 1
            views.setTextViewText(R.id.widget_title, when {
                plan == null || plan.today -> words.getString(R.string.widget_today)
                named -> dayName(plan, zone, locale)
                else -> words.getString(R.string.widget_tomorrow)
            })
            val shownDay = plan?.let { Timetable.at(it.date, 12 * 60, zone) } ?: now
            views.setTextViewText(R.id.widget_date, format(pattern(locale, if (named) "dMMMM" else "EEEdMMMM"), shownDay, zone, locale))
            views.setTextViewText(R.id.widget_note, note.orEmpty())
            views.setViewVisibility(R.id.widget_note, if (note == null) View.GONE else View.VISIBLE)
            val shown = plan?.lessons.orEmpty()
            ROWS.indices.forEach { i ->
                val lesson = shown.getOrNull(i)
                views.setViewVisibility(ROWS[i], if (lesson == null) View.GONE else View.VISIBLE)
                if (lesson != null) row(views, i, lesson, lesson == plan?.current, hidden, words, zone)
            }
            views.setTextViewText(R.id.widget_footer, timetable?.let { words.getString(R.string.widget_from, shortDate(it.saved, zone, locale)) }.orEmpty())
            return views
        }

        private fun row(views: RemoteViews, i: Int, lesson: LessonTime, current: Boolean, hidden: Boolean, words: Context, zone: TimeZone) {
            views.setTextViewText(STARTS[i], Timetable.clock(lesson.lesson.start))
            views.setTextViewText(ENDS[i], Timetable.clock(lesson.lesson.end))
            views.setTextViewText(SUBJECTS[i], if (hidden) words.getString(R.string.widget_class) else lesson.lesson.subject)
            // The room first: in a narrow widget the type is what may be cut.
            val detail = if (hidden) "" else listOf(lesson.lesson.room.takeIf { it.isNotEmpty() }?.let { words.getString(R.string.widget_room, it) }, lesson.lesson.type)
                .filter { !it.isNullOrEmpty() }.joinToString(" · ")
            views.setTextViewText(DETAILS[i], detail)
            views.setViewVisibility(DETAILS[i], if (detail.isEmpty()) View.GONE else View.VISIBLE)
            views.setInt(ROWS[i], "setBackgroundResource", if (current) R.drawable.widget_row_now else R.drawable.widget_row)
            views.setContentDescription(ROWS[i], if (current) "${words.getString(R.string.timetable_now)}, ${Timetable.clock(lesson.lesson.start)}" else null)
        }

        private fun pattern(locale: Locale, skeleton: String) = DateFormat.getBestDateTimePattern(locale, skeleton)

        private fun format(pattern: String, at: Long, zone: TimeZone, locale: Locale) =
            java.text.SimpleDateFormat(pattern, locale).apply { timeZone = zone }.format(Date(at))

        private fun shortDate(at: Long, zone: TimeZone, locale: Locale) = format(pattern(locale, "dMMMM"), at, zone, locale)

        private fun dayName(plan: TodayPlan, zone: TimeZone, locale: Locale) =
            format("EEEE", Timetable.at(plan.date, 12 * 60, zone), zone, locale).replaceFirstChar { it.titlecase(locale) }

        private fun tick(context: Context) = PendingIntent.getBroadcast(context, 1, Intent(context, TodayWidget::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        /** Not a wake-up: the widget is redrawn when the screen is on to show it. */
        /**
         * Within ten minutes after [at], Android's shortest window without exact alarms (a plain set() may wait an hour);
         * not waking the phone: a sleeping screen shows nothing, and the redraw comes when it wakes.
         */
        private fun scheduleTick(context: Context, at: Long) {
            runCatching { context.getSystemService(AlarmManager::class.java)?.setWindow(AlarmManager.RTC, at + 1000, 10 * 60_000L, tick(context)) }
        }

        private fun cancelTick(context: Context) {
            runCatching { context.getSystemService(AlarmManager::class.java)?.cancel(tick(context)) }
        }
    }
}
