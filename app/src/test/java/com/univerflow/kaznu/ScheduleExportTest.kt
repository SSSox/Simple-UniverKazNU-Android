package com.univerflow.kaznu

import com.univerflow.kaznu.domain.ExportKind
import com.univerflow.kaznu.domain.ExportWeek
import com.univerflow.kaznu.domain.SchedulePair
import com.univerflow.kaznu.domain.ScheduleCalendar
import com.univerflow.kaznu.domain.Timetable
import com.univerflow.kaznu.domain.TimetableLesson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleExportTest {
    private fun day(iso: String) = Timetable.date(iso)!!
    private val odd = day("2026-11-16")   // Monday of week 12 (even), shown
    private val even = day("2026-11-23")  // Monday of week 13 (odd)

    private fun lesson(day: Int, start: Int, subject: String, room: String = "309", from: String? = null, until: String? = null) =
        TimetableLesson(day, start, start + 50, subject, "лекция", room, "ФИТ", "Учителева А. П.", from?.let(::day), until?.let(::day))

    private fun week(number: Int, monday: Long, vararg lessons: TimetableLesson, first: Long = monday) =
        ExportWeek(number, true, monday, first, monday + 6, lessons.toList())

    private fun pair(vararg weeks: ExportWeek, missing: List<Int> = emptyList(), until: String = "2026-12-20") =
        SchedulePair("2026/1", day(until), weeks.toList(), missing)

    private fun lines(ics: String) = ics.split("\r\n").filter { it.isNotEmpty() }
    private fun events(ics: String) = ics.split("BEGIN:VEVENT\r\n").drop(1).map { lines(it.substringBefore("END:VEVENT")) }
    private fun summary(event: List<String>) = event.first { it.startsWith("SUMMARY:") }.removePrefix("SUMMARY:")

    private val shownOnly = lesson(0, 9 * 60, "Политология")
    private val otherOnly = lesson(2, 10 * 60, "Культурология")
    private val both = lesson(4, 11 * 60, "Иностранный язык", room = "101")

    @Test fun aClassOfOneWeekRepeatsEverySecondWeekAndAClassOfBothEveryWeek() {
        val ics = ScheduleCalendar.build(pair(week(12, odd, shownOnly, both), week(13, even, otherOnly, both)), "Расписание", "ru", 0)
        val byName = events(ics).associateBy { summary(it).substringBefore(" (") }
        assertEquals(3, byName.size)
        val politics = byName.getValue("Политология")
        assertTrue(politics.contains("DTSTART:20261116T090000"))
        assertTrue(politics.contains("RRULE:FREQ=WEEKLY;INTERVAL=2;UNTIL=20261220T235900"))
        // The other week's class starts in its own week, a week later than the shown week's Monday class would.
        val culture = byName.getValue("Культурология")
        assertTrue(culture.contains("DTSTART:20261125T100000"))
        assertTrue(culture.contains("RRULE:FREQ=WEEKLY;INTERVAL=2;UNTIL=20261220T235900"))
        // Both weeks have it: one weekly event, not two.
        val language = byName.getValue("Иностранный язык")
        assertTrue(language.contains("DTSTART:20261120T110000"))
        assertTrue(language.contains("RRULE:FREQ=WEEKLY;UNTIL=20261220T235900"))
    }

    @Test fun anUnreadNeighbourIsNamedAndNothingStandsInForIt() {
        val request = pair(week(12, odd, shownOnly, both), missing = listOf(13))
        assertTrue(request.alternates)
        val shown = events(ScheduleCalendar.build(request, "Расписание", "ru", 0))
        assertEquals(listOf("Иностранный язык (лекция)", "Политология (лекция)"), shown.map(::summary).sorted())
        assertTrue("the read week's classes repeat every second week", shown.all { e -> e.any { it.startsWith("RRULE:FREQ=WEEKLY;INTERVAL=2") } })
    }

    @Test fun aSiteWithOnlyOneWeekListedRepeatsNothing() {
        val request = pair(week(12, odd, shownOnly))
        assertFalse(request.alternates)
        assertTrue(events(ScheduleCalendar.build(request, "Расписание", "ru", 0)).single().none { it.startsWith("RRULE") })
    }

    @Test fun aWeekThatStartsMidweekBringsItsUncoveredWeekdaysBackTwoWeeksOn() {
        // The semester's first week starts on Wednesday: the Monday class of its table first meets on the Monday of week 3.
        val start = day("2026-09-02")
        val monday = day("2026-08-31")
        val ics = ScheduleCalendar.build(pair(week(1, monday, lesson(0, 9 * 60, "Политология"), lesson(2, 9 * 60, "Культурология"), first = start),
            week(2, monday + 7, lesson(1, 9 * 60, "Экономика"))), "Расписание", "ru", 0)
        val byName = events(ics).associateBy { summary(it).substringBefore(" (") }
        assertTrue(byName.getValue("Культурология").contains("DTSTART:20260902T090000"))
        // Monday of the first week is not covered: its class first meets two weeks on, on 14 September.
        assertTrue(byName.getValue("Политология").contains("DTSTART:20260914T090000"))
    }

    @Test fun courseDatesMoveTheFirstMeetingAndEndTheSeries() {
        val ics = ScheduleCalendar.build(pair(week(12, odd, lesson(0, 9 * 60, "Политология", from = "2026-11-30", until = "2026-12-14")),
            week(13, even)), "Расписание", "ru", 0)
        val event = events(ics).single()
        // The course starts on the 30th (16 Nov + 14 days) and ends on the 14th, which is the series' second start.
        assertTrue(event.contains("DTSTART:20261130T090000"))
        assertTrue(event.contains("RRULE:FREQ=WEEKLY;INTERVAL=2;UNTIL=20261214T235900"))
        val gone = ScheduleCalendar.build(pair(week(12, odd, lesson(0, 9 * 60, "Политология", from = "2026-11-30", until = "2026-11-30")), week(13, even)), "Расписание", "ru", 0)
        assertTrue("a class that meets only once in its course dates repeats nothing", gone.contains("DTSTART:20261130T090000") && gone.contains("RRULE").not())
        val over = ScheduleCalendar.build(pair(week(12, odd, lesson(0, 9 * 60, "Политология", until = "2026-11-10")), week(13, even)), "Расписание", "ru", 0)
        assertTrue(events(over).isEmpty())
    }

    @Test fun anExportMadeAgainKeepsEveryUidAndOnlyTheStampChanges() {
        val request = pair(week(12, odd, shownOnly, lesson(0, 9 * 60, "Политология"), both), week(13, even, otherOnly, both))
        val first = ScheduleCalendar.build(request, "Расписание", "ru", 1_000_000_000_000)
        val second = ScheduleCalendar.build(request, "Расписание", "ru", 1_900_000_000_000)
        fun uids(ics: String) = lines(ics).filter { it.startsWith("UID:") }
        assertEquals(uids(first), uids(second))
        assertEquals("two identical classes in a week still get their own UIDs", uids(first).size, uids(first).toSet().size)
        assertTrue(uids(first).all { it.endsWith("@simple-univer") })
        assertEquals(lines(first).filterNot { it.startsWith("DTSTAMP") }, lines(second).filterNot { it.startsWith("DTSTAMP") })
    }

    @Test fun textIsEscapedAndLongLinesFoldWithoutSplittingACharacter() {
        assertEquals("a\\;b\\,c\\\\d\\ne", ScheduleCalendar.escape("a;b,c\\d\ne"))
        val long = "SUMMARY:" + "Я".repeat(100)
        val parts = ScheduleCalendar.fold(long)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.toByteArray(Charsets.UTF_8).size <= 75 })
        assertTrue(parts.drop(1).all { it.startsWith(" ") })
        assertEquals(long, parts.first() + parts.drop(1).joinToString("") { it.drop(1) })
    }

    @Suppress("UNCHECKED_CAST")
    private fun tweak(map: Any?, vararg changes: Pair<String, Any?>): Map<String, Any?> = (map as Map<String, Any?>) + changes

    private fun message(vararg changes: Pair<String, Any?>): Map<String, Any?> {
        val lessonMap = mapOf("day" to 0, "start" to "09:00", "end" to "09:50", "subject" to "Политология", "type" to "лекция", "room" to "309", "faculty" to "ФИТ",
            "teacher" to "Учителева А. П.", "from" to null, "until" to null)
        fun week(number: Int, monday: String, last: String) = mapOf("number" to number, "labelled" to true, "monday" to monday, "first" to monday, "last" to last, "lessons" to listOf(lessonMap))
        return mapOf<String, Any?>("v" to 1, "kind" to "ics", "term" to "2026/1", "until" to "2026-12-20",
            "weeks" to listOf(week(12, "2026-11-16", "2026-11-22"), week(13, "2026-11-23", "2026-11-29")), "missing" to emptyList<Int>()) + changes
    }

    @Test fun thePagesMessageIsReadInItsOwnShapeOnly() {
        val (kind, request) = SchedulePair.of(message())!!
        assertEquals(ExportKind.CALENDAR, kind)
        assertEquals(listOf(12, 13), request.weeks.map { it.number })
        assertEquals("12–13", request.span)
        assertEquals(ExportKind.PICTURE, SchedulePair.of(message("kind" to "png"))!!.first)
        val oneRead = SchedulePair.of(message("weeks" to (message()["weeks"] as List<*>).take(1), "missing" to listOf(13)))
        assertNotNull(oneRead)
        assertEquals("12–13", oneRead!!.second.span)
        val refused = listOf(
            "v" to 0, "v" to 2, "kind" to "pdf", "kind" to null, "term" to "2026/3", "until" to "2026-11-20", "until" to "2028-01-01", "until" to "20.12.2026",
            "weeks" to emptyList<Any>(), "weeks" to null, "missing" to listOf(13), "missing" to listOf(0), "missing" to listOf("13"), "missing" to listOf(14, 15))
        for (change in refused) assertNull(change.toString(), SchedulePair.of(message(change)))
        val weeks = message()["weeks"] as List<*>
        assertNull("two weeks that are not neighbours", SchedulePair.of(message("weeks" to listOf(weeks[0], tweak(weeks[1], "monday" to "2026-11-30", "first" to "2026-11-30", "last" to "2026-12-02")))))
        assertNull("one number twice", SchedulePair.of(message("weeks" to listOf(weeks[0], tweak(weeks[1], "number" to 12)))))
        assertNull("not a Monday", SchedulePair.of(message("weeks" to listOf(tweak(weeks[0], "monday" to "2026-11-17"), weeks[1]))))
        assertNull("a class out of shape", SchedulePair.of(message("weeks" to listOf(tweak(weeks[0], "lessons" to listOf(mapOf("day" to 9))), weeks[1]))))
    }
}
