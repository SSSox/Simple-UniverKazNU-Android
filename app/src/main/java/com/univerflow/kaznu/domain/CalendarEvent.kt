package com.univerflow.kaznu.domain

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/**
 * An exam or a class the student sends to their calendar app with «В календарь». Times are the site's wall clock in
 * the phone's zone. A class repeats every week until [until] (the last day of its course dates); an exam does not.
 */
data class CalendarEvent(val title: String, val start: Long, val end: Long, val place: String, val notes: String, val until: Long?,
    val interval: Int = 1, val partial: Boolean = false) {
    /**
     * The calendar's repeat rule: every [interval] weeks on the start's weekday (2 for a class of only one of the site's
     * odd and even weeks), through [until] (in UTC, as RFC 5545 asks). [partial]: the other week of the pair was not read.
     */
    val rule: String? get() = until?.let { "FREQ=WEEKLY;" + (if (interval == 2) "INTERVAL=2;" else "") + "UNTIL=" + SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(it)) }

    companion object {
        const val MESSAGE_PREFIX = "univer-calendar:"
        const val MAX_LENGTH = 4096
        private const val MAX_TITLE = 200
        private const val MAX_PLACE = 200
        private const val MAX_NOTES = 500
        private const val HOUR = 3_600_000L
        private const val DAY = 24 * HOUR
        private val MOMENT = Regex("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2})$")
        private val DATE = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")

        /**
         * The page's message in its own shape only: text within its length, a start from yesterday to two years ahead,
         * an end after it within 12 hours, a last day of repeats from the first day to a year later. Anything else is null.
         */
        fun of(map: Map<String, Any?>, now: Long, zone: TimeZone = TimeZone.getDefault()): CalendarEvent? {
            if (map["v"] != 1) return null
            val title = text(map["title"], MAX_TITLE)?.takeIf { it.isNotEmpty() } ?: return null
            val place = text(map["place"], MAX_PLACE) ?: return null
            val notes = text(map["notes"], MAX_NOTES) ?: return null
            val start = moment(map["start"] as? String, zone) ?: return null
            val end = moment(map["end"] as? String, zone) ?: return null
            if (start < now - DAY || start > now + 731 * DAY || end <= start || end - start > 12 * HOUR) return null
            val until = when (val value = map["until"]) {
                null -> null
                is String -> lastMoment(value, zone)?.takeIf { it >= start && it <= start + 366 * DAY } ?: return null
                else -> return null
            }
            val interval = when (map["interval"]) { null, 1 -> 1; 2 -> 2; else -> return null }
            val partial = when (val value = map["partial"]) { null -> false; is Boolean -> value; else -> return null }
            return CalendarEvent(title, start, end, place, notes, until, interval, partial)
        }

        /** Absent is empty; text is trimmed with its spaces folded, within [max]. */
        private fun text(value: Any?, max: Int): String? = when (value) {
            null -> ""
            is String -> value.replace(Regex("\\s+"), " ").trim().takeIf { it.length <= max }
            else -> null
        }

        private fun moment(value: String?, zone: TimeZone): Long? {
            val parts = MOMENT.matchEntire(value ?: return null)?.groupValues ?: return null
            return at(zone, parts[1].toInt(), parts[2].toInt(), parts[3].toInt(), parts[4].toInt(), parts[5].toInt())
        }

        /** The day's last minute: a class on that day is still in the series. */
        private fun lastMoment(value: String, zone: TimeZone): Long? {
            val parts = DATE.matchEntire(value)?.groupValues ?: return null
            return at(zone, parts[1].toInt(), parts[2].toInt(), parts[3].toInt(), 23, 59)
        }

        private fun at(zone: TimeZone, year: Int, month: Int, day: Int, hour: Int, minute: Int): Long? = runCatching {
            GregorianCalendar(zone).apply {
                isLenient = false
                clear()
                set(year, month - 1, day, hour, minute)
                set(Calendar.SECOND, 0)
            }.timeInMillis
        }.getOrNull()
    }
}
