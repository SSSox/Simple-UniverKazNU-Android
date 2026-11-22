package com.univerflow.kaznu.domain

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class ExportKind { CALENDAR, PICTURE }

/**
 * One week of an export: the site's number of it ([labelled]: it came from the option's text, not its place in the list),
 * its Monday, the first and last day the site gives it (the semester's first week may start midweek; all epoch days)
 * and its classes. A class on a weekday before [first] is one the site's table lists for the week but the week does not
 * reach: it first meets two weeks on.
 */
data class ExportWeek(val number: Int, val labelled: Boolean, val monday: Long, val first: Long, val last: Long, val lessons: List<TimetableLesson>) {
    val odd: Boolean get() = number % 2 == 1
}

/**
 * The schedule page's shown week and its neighbour (the page read both on the student's press): the site alternates odd and
 * even weeks, so what the calendar repeats every week is only what both weeks have. A neighbour the page could not read is
 * in [missing] (its number) and no other week stands in for it. [until] is the last day the site's week list reaches.
 */
data class SchedulePair(val term: String, val until: Long, val weeks: List<ExportWeek>, val missing: List<Int>) {
    /** Two weeks of the site's cycle, read or not. */
    val alternates: Boolean get() = weeks.size + missing.size == 2

    /** The weeks in date order. */
    val ordered: List<ExportWeek> get() = weeks.sortedBy { it.monday }

    /** Week numbers, ascending: "7–8" for two, "7" for one. */
    val span: String get() = (weeks.map { it.number } + missing).sorted().let { if (it.size == 2) "${it[0]}–${it[1]}" else "${it[0]}" }

    companion object {
        const val MESSAGE_PREFIX = "univer-export:"
        const val MAX_LENGTH = 98304
        private const val MAX_WEEK = 40

        /** The page's message in its own shape: the kind of export and its pair. Anything else, or `{"v":0}` (a page it could not read), is null. */
        fun of(map: Map<String, Any?>): Pair<ExportKind, SchedulePair>? {
            if (map["v"] != 1) return null
            val kind = when (map["kind"]) { "ics" -> ExportKind.CALENDAR; "png" -> ExportKind.PICTURE; else -> return null }
            val term = (map["term"] as? String)?.takeIf { Regex("^\\d{4}/[12]$").matches(it) } ?: return null
            val items = map["weeks"] as? List<*> ?: return null
            val lost = map["missing"] as? List<*> ?: return null
            if (items.isEmpty() || items.size > 2 || lost.size > 1 || items.size + lost.size > 2) return null
            val weeks = items.map { week(it as? Map<*, *> ?: return null) ?: return null }
            val missing = lost.map { (it as? Int)?.takeIf { n -> n in 1..MAX_WEEK } ?: return null }
            val numbers = weeks.map { it.number } + missing
            if (numbers.distinct().size != numbers.size) return null
            // Two read weeks are neighbours: their Mondays one week apart.
            if (weeks.size == 2 && Math.abs(weeks[1].monday - weeks[0].monday) != 7L) return null
            val until = Timetable.date(map["until"]) ?: return null
            if (until < weeks.maxOf { it.last } || until > weeks.minOf { it.monday } + 7 * MAX_WEEK) return null
            return kind to SchedulePair(term, until, weeks, missing)
        }

        private fun week(map: Map<*, *>): ExportWeek? {
            val number = (map["number"] as? Int)?.takeIf { it in 1..MAX_WEEK } ?: return null
            val labelled = map["labelled"] as? Boolean ?: return null
            val monday = Timetable.date(map["monday"])?.takeIf { Timetable.weekday(it) == 0 } ?: return null
            val first = Timetable.date(map["first"])?.takeIf { it in monday..monday + 6 } ?: return null
            val last = Timetable.date(map["last"])?.takeIf { it in first..monday + 6 } ?: return null
            val items = map["lessons"] as? List<*> ?: return null
            if (items.size > Timetable.MAX_LESSONS) return null
            val lessons = items.map { Timetable.lessonOf(it as? Map<*, *> ?: return null, monday) ?: return null }
            return ExportWeek(number, labelled, monday, first, last, lessons)
        }
    }
}

/** One event of the calendar file: its first date, every [step] days (0: once) through [lastDay]. */
data class ScheduleEvent(val uid: String, val lesson: TimetableLesson, val date: Long, val step: Int, val lastDay: Long) {
    val repeats: Boolean get() = step > 0 && date + step <= lastDay
}

/**
 * The calendar file (RFC 5545) of a [SchedulePair]. Times are the site's wall clock, written as floating local times: no
 * zone is guessed. A class in both weeks repeats every week; one in a single week every second week (INTERVAL=2), through
 * the semester's last day or its course dates' end. With the neighbour unread, the read week's classes still repeat every
 * second week (the site alternates), and nothing stands in for the other week. Its UID is made of what the class is (term,
 * parity, weekday, times, name, kind, room), never of the login or the time of export, so an export made again updates
 * the same events instead of adding copies.
 */
object ScheduleCalendar {
    private const val LINE_OCTETS = 75
    private const val SAFETY = 60

    private fun identity(l: TimetableLesson) = listOf(l.day, l.start, l.end, l.subject, l.type, l.room, l.teacher).joinToString("|")

    /** The events: a class of the earlier week that the later one has too is one weekly event, not two. */
    fun events(pair: SchedulePair): List<ScheduleEvent> {
        val weeks = pair.ordered
        val used = HashMap<String, Int>()
        val events = mutableListOf<ScheduleEvent>()
        weeks.forEachIndexed { index, week ->
            val other = weeks.getOrNull(1 - index)
            val seen = HashMap<String, Int>()
            for (lesson in week.lessons.sortedWith(compareBy({ it.day }, { it.start }, { it.subject }))) {
                val key = identity(lesson)
                val nth = seen.merge(key, 1, Int::plus)!!
                val outside = week.monday + lesson.day < week.first
                val shared = !outside && other != null && other.lessons.count { identity(it) == key && other.monday + it.day >= other.first } >= nth
                if (shared && index == 1) continue
                val step = when { shared -> 7; pair.alternates -> 14; else -> 0 }
                var date = week.monday + lesson.day + if (outside) 14 else 0
                var guard = 0
                while (lesson.from != null && date < lesson.from && step > 0 && guard++ < SAFETY) date += step
                val last = minOf(pair.until, lesson.until ?: Long.MAX_VALUE)
                if (date > last || (lesson.from != null && date < lesson.from)) continue
                val parity = if (shared) "b" else if (week.odd) "1" else "0"
                val uidKey = listOf(pair.term, parity, lesson.day, lesson.start, lesson.end, lesson.subject, lesson.type, lesson.room).joinToString("|")
                val count = used.merge(uidKey, 1, Int::plus)!! - 1
                events += ScheduleEvent(uid(uidKey + "|" + count), lesson, date, step, last)
            }
        }
        return events.sortedWith(compareBy({ it.date }, { it.lesson.start }, { it.lesson.subject }))
    }

    /** The file's text, CRLF lines folded at 75 octets. [stamp] is the moment of export (DTSTAMP). */
    fun build(pair: SchedulePair, calendarName: String, language: String, stamp: Long): String {
        val lines = mutableListOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Simple Univer//Schedule//" + language.uppercase(Locale.ROOT), "CALSCALE:GREGORIAN",
            "METHOD:PUBLISH", "X-WR-CALNAME:" + escape(calendarName))
        val dtstamp = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(stamp))
        for (event in events(pair)) {
            val lesson = event.lesson
            lines += "BEGIN:VEVENT"
            lines += "UID:" + event.uid
            lines += "DTSTAMP:$dtstamp"
            lines += "DTSTART:" + floating(event.date, lesson.start)
            lines += "DTEND:" + floating(event.date, lesson.end)
            // The last start the rule may give: the last minute of the last day.
            if (event.repeats) lines += "RRULE:FREQ=WEEKLY;" + (if (event.step == 14) "INTERVAL=2;" else "") + "UNTIL=" + floating(event.lastDay, 24 * 60 - 1)
            lines += "SUMMARY:" + escape(if (lesson.type.isEmpty()) lesson.subject else "${lesson.subject} (${lesson.type})")
            if (lesson.room.isNotEmpty()) lines += "LOCATION:" + escape(lesson.room)
            if (lesson.teacher.isNotEmpty()) lines += "DESCRIPTION:" + escape(lesson.teacher)
            lines += "TRANSP:OPAQUE"
            lines += "END:VEVENT"
        }
        lines += "END:VCALENDAR"
        return buildString { lines.forEach { line -> fold(line).forEach { append(it).append("\r\n") } } }
    }

    /** "20261123T090000" for a date (epoch day) and a minute of the day. */
    fun floating(date: Long, minute: Int): String = Timetable.iso(date).replace("-", "") + String.format(Locale.US, "T%02d%02d00", minute / 60, minute % 60)

    /** TEXT values: backslash, semicolon, comma and line breaks are escaped. */
    fun escape(value: String): String = value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n")

    /** Lines longer than 75 octets go on in lines that start with a space; a character is never split, a surrogate pair neither. */
    fun fold(line: String): List<String> {
        val parts = mutableListOf<String>()
        var current = StringBuilder()
        var octets = 0
        var i = 0
        while (i < line.length) {
            val size = Character.charCount(line.codePointAt(i))
            val unit = line.substring(i, i + size)
            val bytes = unit.toByteArray(Charsets.UTF_8).size
            if (octets + bytes > LINE_OCTETS) { parts += current.toString(); current = StringBuilder(" "); octets = 1 }
            current.append(unit)
            octets += bytes
            i += size
        }
        parts += current.toString()
        return parts
    }

    private fun uid(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) } + "@simple-univer"
}
