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
import com.univerflow.kaznu.data.TimetableStore
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.domain.LessonTime
import com.univerflow.kaznu.domain.Timetable
import com.univerflow.kaznu.domain.TodayPlan
import com.univerflow.kaznu.domain.WidgetList
import com.univerflow.kaznu.domain.WidgetRow
import com.univerflow.kaznu.launch.LaunchTarget
import com.univerflow.kaznu.ui.context
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * «Пары на сегодня» on the home screen, drawn from the saved week only: the widget never reaches the network. Its classes
 * scroll (the day's, then a week ahead under day headings; [TodayWidgetService] makes the rows), and once the saved week
 * has run out it keeps showing that week's classes, marked as the last saved schedule. With a PIN set, the classes show
 * their times alone, as the student chose. A tap opens the schedule (behind the PIN as usual).
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

        /** Draws every placed widget again, refills its list and sets the next redraw. Reads a file: off the main thread. */
        @Suppress("DEPRECATION") // notifyAppWidgetViewDataChanged: the list's adapter is a RemoteViewsService (see draw).
        fun refresh(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(app, TodayWidget::class.java)) }.getOrNull() ?: IntArray(0)
            if (ids.isEmpty()) { cancelTick(app); return }
            val timetable = TimetableStore.read(app)
            val now = System.currentTimeMillis()
            val zone = TimeZone.getDefault()
            val language = AppPreferences(app).language
            ids.forEach { id -> runCatching { manager.updateAppWidget(id, draw(app, timetable, now, zone, language, id)) } }
            runCatching { manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list) }
            scheduleTick(app, TodayPlan.nextChange(timetable?.let { TodayPlan.source(it, now, zone) }, now, zone))
        }

        /** The widget's frame: heading, note, the list (filled by [TodayWidgetService] for widget [id]) and the footer. */
        @Suppress("DEPRECATION") // setRemoteAdapter(Int, Intent): RemoteCollectionItems needs API 31, this widget runs from 24.
        internal fun draw(context: Context, timetable: Timetable?, now: Long, zone: TimeZone, language: AppLanguage, id: Int): RemoteViews {
            val words = language.context(context)
            val locale = Locale.forLanguageTag(language.localeCode)
            val views = RemoteViews(context.packageName, R.layout.widget_today)
            val open = PendingIntent.getActivity(context, 0, LaunchTarget.SCHEDULE.intent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            views.setOnClickPendingIntent(R.id.widget_root, open)
            // The rows add nothing to the intent: an immutable template opens the schedule as it is.
            views.setPendingIntentTemplate(R.id.widget_list, open)
            views.setRemoteAdapter(R.id.widget_list, TodayWidgetService.intent(context, id))
            val list = timetable?.let { TodayPlan.list(it, now, zone) }
            val plan = list?.plan
            val note = when {
                timetable == null -> words.getString(R.string.widget_empty)
                list == null || plan == null -> words.getString(R.string.widget_none_week)
                list.lastKnown -> words.getString(R.string.widget_last_known)
                plan.free -> words.getString(R.string.widget_free)
                plan.finished -> words.getString(R.string.widget_finished)
                else -> null
            }
            val today = Timetable.epochDay(now, zone)
            // "Сегодня · ср, 18 ноября", "Завтра · чт, 19 ноября", or a later day by name: "Понедельник · 23 ноября".
            val named = plan != null && !plan.today && plan.date != today + 1
            views.setTextViewText(R.id.widget_title, when {
                plan == null || plan.today -> words.getString(R.string.widget_today)
                named -> dayName(plan.date, zone, locale)
                else -> words.getString(R.string.widget_tomorrow)
            })
            val shownDay = plan?.let { Timetable.at(it.date, 12 * 60, zone) } ?: now
            views.setTextViewText(R.id.widget_date, format(pattern(locale, if (named) "dMMMM" else "EEEdMMMM"), shownDay, zone, locale))
            views.setTextViewText(R.id.widget_note, note.orEmpty())
            views.setViewVisibility(R.id.widget_note, if (note == null) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.widget_list, if (list?.rows.isNullOrEmpty()) View.GONE else View.VISIBLE)
            views.setTextViewText(R.id.widget_footer, timetable?.let { words.getString(R.string.widget_from, shortDate(it.saved, zone, locale)) }.orEmpty())
            return views
        }

        /** The list's rows, as [TodayWidgetService] hands them to the launcher. */
        internal fun rows(context: Context, list: WidgetList?, hidden: Boolean, language: AppLanguage, zone: TimeZone): List<RemoteViews> {
            val words = language.context(context)
            val locale = Locale.forLanguageTag(language.localeCode)
            return list?.rows.orEmpty().map { row ->
                when (row) {
                    is WidgetRow.Day -> RemoteViews(context.packageName, R.layout.widget_day).apply {
                        setTextViewText(R.id.widget_day, dayName(row.date, zone, locale) + ", " + shortDate(Timetable.at(row.date, 12 * 60, zone), zone, locale))
                        setOnClickFillInIntent(R.id.widget_day, Intent())
                    }
                    is WidgetRow.Lesson -> lesson(context, row.time, row.current, hidden, words)
                }
            }
        }

        private fun lesson(context: Context, time: LessonTime, current: Boolean, hidden: Boolean, words: Context) =
            RemoteViews(context.packageName, R.layout.widget_lesson).apply {
                setTextViewText(R.id.widget_start, Timetable.clock(time.lesson.start))
                setTextViewText(R.id.widget_end, Timetable.clock(time.lesson.end))
                setTextViewText(R.id.widget_subject, if (hidden) words.getString(R.string.widget_class) else time.lesson.subject)
                // The room first: in a narrow widget the type is what may be cut.
                val detail = if (hidden) "" else listOf(time.lesson.room.takeIf { it.isNotEmpty() }?.let { words.getString(R.string.widget_room, it) }, time.lesson.type)
                    .filter { !it.isNullOrEmpty() }.joinToString(" · ")
                setTextViewText(R.id.widget_detail, detail)
                setViewVisibility(R.id.widget_detail, if (detail.isEmpty()) View.GONE else View.VISIBLE)
                setInt(R.id.widget_lesson_box, "setBackgroundResource", if (current) R.drawable.widget_row_now else R.drawable.widget_row)
                setContentDescription(R.id.widget_lesson, if (current) "${words.getString(R.string.timetable_now)}, ${Timetable.clock(time.lesson.start)}" else null)
                setOnClickFillInIntent(R.id.widget_lesson, Intent())
            }

        private fun pattern(locale: Locale, skeleton: String) = DateFormat.getBestDateTimePattern(locale, skeleton)

        private fun format(pattern: String, at: Long, zone: TimeZone, locale: Locale) =
            java.text.SimpleDateFormat(pattern, locale).apply { timeZone = zone }.format(Date(at))

        private fun shortDate(at: Long, zone: TimeZone, locale: Locale) = format(pattern(locale, "dMMMM"), at, zone, locale)

        private fun dayName(date: Long, zone: TimeZone, locale: Locale) =
            format("EEEE", Timetable.at(date, 12 * 60, zone), zone, locale).replaceFirstChar { it.titlecase(locale) }

        private fun tick(context: Context) = PendingIntent.getBroadcast(context, 1, Intent(context, TodayWidget::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

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
