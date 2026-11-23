package com.univerflow.kaznu

import com.univerflow.kaznu.domain.CalendarEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.GregorianCalendar
import java.util.TimeZone

class CalendarEventTest {
    private val almaty: TimeZone = TimeZone.getTimeZone("Asia/Almaty")
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int) =
        GregorianCalendar(almaty).apply { clear(); set(year, month - 1, day, hour, minute) }.timeInMillis
    private val now = at(2026, 11, 20, 12, 0)
    private fun message(vararg changes: Pair<String, Any?>): Map<String, Any?> =
        mapOf<String, Any?>("v" to 1, "title" to "Политология (лекция)", "start" to "2026-11-23T09:00", "end" to "2026-11-23T09:50",
            "place" to "Ауд. 309, ФИТ", "notes" to "Учителева А. П.", "until" to "2026-12-15") + changes
    private fun of(map: Map<String, Any?>) = CalendarEvent.of(map, now, almaty)

    @Test fun aClassIsTheSitesWallClockInThePhonesZoneRepeatingWeeklyThroughItsLastDay() {
        val event = of(message())!!
        assertEquals("Политология (лекция)", event.title)
        assertEquals(at(2026, 11, 23, 9, 0), event.start)
        assertEquals(at(2026, 11, 23, 9, 50), event.end)
        assertEquals("Ауд. 309, ФИТ", event.place)
        assertEquals("Учителева А. П.", event.notes)
        // 15 December 23:59 in Almaty (UTC+5) is 18:59 UTC: a class on the last day is still in the series.
        assertEquals("FREQ=WEEKLY;UNTIL=20261215T185900Z", event.rule)
    }

    @Test fun anExamGoesInOnceAndEmptyTextIsAllowed() {
        val exam = of(message("until" to null, "place" to null, "notes" to "  "))!!
        assertNull(exam.rule)
        assertEquals("", exam.place)
        assertEquals("", exam.notes)
        assertEquals("Экзамен: Политология", of(message("title" to "  Экзамен:\n Политология "))!!.title)
    }

    @Test fun anythingOutsideTheMessagesOwnShapeIsRefused() {
        val refused = listOf(
            "v" to 2, "v" to "1", "title" to "", "title" to "   ", "title" to "x".repeat(201), "title" to 5,
            "place" to "x".repeat(201), "notes" to "x".repeat(501), "notes" to listOf("a"),
            "start" to "2026-11-23 09:00", "start" to "2026-02-30T09:00", "start" to "2026-11-23T25:00", "start" to null,
            "end" to "2026-11-23T09:00", "end" to "2026-11-23T08:00", "end" to "2026-11-23T21:01",
            "until" to "2026-11-22", "until" to "2027-11-24", "until" to "15.12.2026", "until" to 20261215)
        for (change in refused) assertNull(change.toString(), of(message(change)))
        assertNotNull("a 12-hour event is allowed", of(message("end" to "2026-11-23T21:00")))
    }

    @Test fun theStartStaysBetweenYesterdayAndTwoYearsAhead() {
        assertNotNull(of(message("start" to "2026-11-19T12:30", "end" to "2026-11-19T13:00", "until" to null)))
        assertNull(of(message("start" to "2026-11-19T11:00", "end" to "2026-11-19T12:00", "until" to null)))
        assertNull(of(message("start" to "2028-11-22T09:00", "end" to "2028-11-22T10:00", "until" to null)))
    }

    @Test fun aClassOfOneOfTheTwoWeeksRepeatsEverySecondWeek() {
        val event = of(message("interval" to 2, "partial" to true))!!
        assertEquals("FREQ=WEEKLY;INTERVAL=2;UNTIL=20261215T185900Z", event.rule)
        assertEquals(true, event.partial)
        assertEquals("the default is every week, the other week read", "FREQ=WEEKLY;UNTIL=20261215T185900Z", of(message())!!.rule)
        assertEquals(false, of(message("interval" to 1))!!.partial)
        for (change in listOf("interval" to 3, "interval" to 0, "interval" to "2", "partial" to "yes", "partial" to 1)) assertNull(change.toString(), of(message(change)))
    }
}
