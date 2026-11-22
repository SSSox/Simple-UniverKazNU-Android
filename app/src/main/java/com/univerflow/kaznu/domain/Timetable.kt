package com.univerflow.kaznu.domain

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/**
 * One class of the saved week. [day]: Monday = 0; [start] and [end]: minutes of the day on the site's clock;
 * [from] and [until]: the course dates as epoch days, when the site gives them.
 */
data class TimetableLesson(val day: Int, val start: Int, val end: Int, val subject: String, val type: String, val room: String,
    val faculty: String, val teacher: String, val from: Long?, val until: Long?)

/** A class on one date: [date] as an epoch day, [startAt] and [endAt] as moments in the phone's zone. */
data class LessonTime(val lesson: TimetableLesson, val date: Long, val startAt: Long, val endAt: Long)

/**
 * The week of the schedule the student last opened, kept on the phone for the widget, class reminders and the
 * offline schedule. A class meets on its weekday within its course dates; one without them is taken to repeat for
 * [UNDATED_WEEKS] weeks after the saved one, after which the phone stops guessing.
 */
data class Timetable(val owner: String, val week: Long, val saved: Long, val lessons: List<TimetableLesson>) {
    /** The classes on [date] (an epoch day), by start time. */
    fun on(date: Long): List<TimetableLesson> = lessons.filter { lesson ->
        lesson.day == weekday(date) && (lesson.from == null || date >= lesson.from) &&
            date <= (lesson.until ?: (week + 7 * (UNDATED_WEEKS + 1) - 1))
    }.sortedWith(compareBy({ it.start }, { it.end }))

    fun times(date: Long, zone: TimeZone): List<LessonTime> = on(date).map { LessonTime(it, date, at(date, it.start, zone), at(date, it.end, zone)) }

    /**
     * The first class that starts after [now] + [lead] and after [after] (the class last reminded of: an alarm that
     * fires early must not bring it up again), looking two weeks ahead.
     */
    fun next(now: Long, zone: TimeZone, lead: Long = 0, after: Long = Long.MIN_VALUE): LessonTime? {
        val today = epochDay(now, zone)
        for (date in today..today + 14) times(date, zone).firstOrNull { it.startAt - lead > now && it.startAt > after }?.let { return it }
        return null
    }

    companion object {
        const val MESSAGE_PREFIX = "univer-schedule:"
        const val MAX_LENGTH = 65536
        const val MAX_LESSONS = 80
        const val UNDATED_WEEKS = 3
        private const val DAY = 86_400_000L
        private val UTC: TimeZone = TimeZone.getTimeZone("UTC")
        private val DATE = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")
        private val CLOCK = Regex("^(\\d{2}):(\\d{2})$")

        /** Monday = 0 … Sunday = 6; epoch day 0 (1 January 1970) was a Thursday. */
        fun weekday(date: Long): Int = Math.floorMod(date + 3, 7L).toInt()

        fun epochDay(moment: Long, zone: TimeZone): Long = Math.floorDiv(moment + zone.getOffset(moment), DAY)

        /** [minute] of [date] on the wall clock of [zone]. */
        fun at(date: Long, minute: Int, zone: TimeZone): Long {
            val day = GregorianCalendar(UTC).apply { timeInMillis = date * DAY }
            return GregorianCalendar(zone).apply {
                clear()
                set(day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH), minute / 60, minute % 60)
            }.timeInMillis
        }

        /** [date] (an epoch day) as "2026-11-16". */
        fun iso(date: Long): String = GregorianCalendar(UTC).apply { timeInMillis = date * DAY }
            .let { String.format(Locale.US, "%04d-%02d-%02d", it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH)) }

        /** Minutes of the day as "09:00". */
        fun clock(minutes: Int): String = String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60)

        /** "2026-11-16" as an epoch day; anything else, or a date that does not exist, is null. */
        fun date(value: Any?): Long? {
            val parts = DATE.matchEntire(value as? String ?: return null)?.groupValues ?: return null
            return runCatching {
                GregorianCalendar(UTC).apply { isLenient = false; clear(); set(parts[1].toInt(), parts[2].toInt() - 1, parts[3].toInt()) }.timeInMillis / DAY
            }.getOrNull()
        }

        /**
         * The page's message: its own shape, and only a week that holds today ([now] in [zone]), so a past week the
         * student looks at never replaces the current one.
         */
        fun of(map: Map<String, Any?>, now: Long, zone: TimeZone = TimeZone.getDefault()): Timetable? {
            val timetable = restore(map + ("saved" to now)) ?: return null
            val today = epochDay(now, zone)
            return timetable.takeIf { today >= it.week && today < it.week + 7 }
        }

        /** A saved copy (the message's shape and the moment it was saved); out of shape is null. */
        fun restore(map: Map<String, Any?>): Timetable? {
            if (map["v"] != 1) return null
            val week = date(map["week"])?.takeIf { weekday(it) == 0 } ?: return null
            val saved = (map["saved"] as? Number)?.toLong()?.takeIf { it > 0 } ?: return null
            val owner = text(map["owner"], 128) ?: return null
            val items = map["lessons"] as? List<*> ?: return null
            if (items.size > MAX_LESSONS) return null
            val lessons = items.map { lessonOf(it as? Map<*, *> ?: return null, week) ?: return null }
            return Timetable(owner, week, saved, lessons)
        }

        internal fun lessonOf(map: Map<*, *>, week: Long): TimetableLesson? {
            val day = (map["day"] as? Int)?.takeIf { it in 0..6 } ?: return null
            val start = minutes(map["start"]) ?: return null
            val end = minutes(map["end"])?.takeIf { it > start } ?: return null
            val subject = text(map["subject"], 200)?.takeIf { it.isNotEmpty() } ?: return null
            val from = map["from"]?.let { date(it) ?: return null }
            val until = map["until"]?.let { date(it) ?: return null }
            if (from != null && until != null && from > until) return null
            if (until != null && until > week + 400) return null
            return TimetableLesson(day, start, end, subject, text(map["type"], 60) ?: return null, text(map["room"], 100) ?: return null,
                text(map["faculty"], 100) ?: return null, text(map["teacher"], 200) ?: return null, from, until)
        }

        private fun minutes(value: Any?): Int? {
            val parts = CLOCK.matchEntire(value as? String ?: return null)?.groupValues ?: return null
            val hour = parts[1].toInt(); val minute = parts[2].toInt()
            return if (hour < 24 && minute < 60) hour * 60 + minute else null
        }

        /** Absent is empty; text is trimmed with its spaces folded, within [max]. */
        private fun text(value: Any?, max: Int): String? = when (value) {
            null -> ""
            is String -> value.replace(Regex("\\s+"), " ").trim().takeIf { it.length <= max }
            else -> null
        }
    }
}

/**
 * What the «Сегодня» widget shows at [now]: today's classes while one is still ahead or under way, otherwise the next
 * day with classes within a week. [current] is the class under way; [finished] says today's classes are over, [free]
 * that today has none.
 */
data class TodayPlan(val date: Long, val today: Boolean, val lessons: List<LessonTime>, val current: LessonTime?, val finished: Boolean, val free: Boolean) {
    companion object {
        const val ROWS = 4

        /**
         * Null when no class is left within a week. At most [rows] classes: those still ahead, and finished ones before
         * them while rows are left.
         */
        fun of(timetable: Timetable, now: Long, zone: TimeZone, rows: Int = ROWS): TodayPlan? {
            val today = Timetable.epochDay(now, zone)
            val todays = timetable.times(today, zone)
            val ahead = todays.indexOfFirst { it.endAt > now }
            if (ahead >= 0) {
                val shown = todays.drop(minOf(ahead, (todays.size - rows).coerceAtLeast(0))).take(rows)
                return TodayPlan(today, true, shown, todays.firstOrNull { it.startAt <= now && now < it.endAt }, finished = false, free = false)
            }
            for (date in today + 1..today + 7) {
                val lessons = timetable.times(date, zone)
                if (lessons.isNotEmpty()) return TodayPlan(date, false, lessons.take(rows), null, finished = todays.isNotEmpty(), free = todays.isEmpty())
            }
            return null
        }

        /** The next moment the widget changes on its own: a class beginning or ending today, or the next midnight. */
        fun nextChange(timetable: Timetable?, now: Long, zone: TimeZone): Long {
            val today = Timetable.epochDay(now, zone)
            val midnight = Timetable.at(today + 1, 0, zone)
            val edges = timetable?.times(today, zone)?.flatMap { listOf(it.startAt, it.endAt) }.orEmpty()
            return edges.filter { it > now }.minOrNull()?.coerceAtMost(midnight) ?: midnight
        }
    }
}
