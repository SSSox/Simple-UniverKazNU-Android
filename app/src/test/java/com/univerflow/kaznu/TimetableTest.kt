package com.univerflow.kaznu

import com.univerflow.kaznu.domain.Timetable
import com.univerflow.kaznu.domain.TodayPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.GregorianCalendar
import java.util.TimeZone

class TimetableTest {
    private val almaty: TimeZone = TimeZone.getTimeZone("Asia/Almaty")
    private fun at(day: Int, hour: Int, minute: Int = 0, month: Int = 11) =
        GregorianCalendar(almaty).apply { clear(); set(2026, month - 1, day, hour, minute) }.timeInMillis
    private fun lesson(day: Int, start: String, end: String, subject: String, from: String? = null, until: String? = null) =
        mapOf("day" to day, "start" to start, "end" to end, "subject" to subject, "type" to "лекция", "room" to "309", "faculty" to "ФИТ",
            "teacher" to "Учителева А. П.", "from" to from, "until" to until)
    // The week of Monday 16 November 2026: Monday 9:00 and 11:00, Wednesday 10:00 (course dates through 2 December).
    private val week = listOf(lesson(0, "11:00", "11:50", "Культурология"), lesson(0, "09:00", "10:50", "Политология"),
        lesson(2, "10:00", "10:50", "История", from = "2026-09-01", until = "2026-12-02"))
    private fun message(vararg changes: Pair<String, Any?>): Map<String, Any?> =
        mapOf<String, Any?>("v" to 1, "week" to "2026-11-16", "owner" to "acc-1", "lessons" to week) + changes
    private val now = at(18, 12)
    private val timetable = Timetable.of(message(), now, almaty)!!
    private fun day(day: Int, month: Int = 11) = Timetable.epochDay(at(day, 12, month = month), almaty)

    @Test fun onlyTheWeekHoldingTodayIsTakenFromThePage() {
        assertEquals("acc-1", timetable.owner)
        assertEquals(now, timetable.saved)
        assertEquals(3, timetable.lessons.size)
        assertNotNull(Timetable.of(message(), at(22, 23, 59), almaty))
        assertNull("the week before", Timetable.of(message(), at(23, 0, 1), almaty))
        assertNull("a week ahead of today", Timetable.of(message(), at(15, 23), almaty))
        assertNull("not a Monday", Timetable.of(message("week" to "2026-11-17"), now, almaty))
    }

    @Test fun anythingOutsideTheMessagesShapeIsRefused() {
        val refused = listOf(
            message("v" to 2), message("week" to "16.11.2026"), message("owner" to "x".repeat(129)), message("lessons" to "none"),
            message("lessons" to List(81) { lesson(0, "09:00", "09:50", "Предмет") }),
            message("lessons" to listOf(lesson(7, "09:00", "09:50", "Предмет"))),
            message("lessons" to listOf(lesson(0, "09:50", "09:00", "Предмет"))),
            message("lessons" to listOf(lesson(0, "9:00", "09:50", "Предмет"))),
            message("lessons" to listOf(lesson(0, "24:00", "24:30", "Предмет"))),
            message("lessons" to listOf(lesson(0, "09:00", "09:50", ""))),
            message("lessons" to listOf(lesson(0, "09:00", "09:50", "Предмет", from = "2026-12-01", until = "2026-11-01"))),
            message("lessons" to listOf(lesson(0, "09:00", "09:50", "Предмет", until = "2028-01-01"))),
            message("lessons" to listOf(lesson(0, "09:00", "09:50", "Предмет", from = "2026-02-30"))),
            message("lessons" to listOf(lesson(0, "09:00", "09:50", "Предмет") + ("teacher" to 5))))
        for (map in refused) assertNull(map.toString(), Timetable.of(map, now, almaty))
        assertNotNull("an empty week is a week without classes", Timetable.of(message("lessons" to emptyList<Any>()), now, almaty))
    }

    @Test fun aClassMeetsOnItsWeekdayWithinItsCourseDatesOrThreeWeeksWithoutThem() {
        assertEquals(listOf("Политология", "Культурология"), timetable.on(day(16)).map { it.subject })
        assertEquals(listOf("Политология", "Культурология"), timetable.on(day(7, month = 12)).map { it.subject })
        assertTrue("three weeks after the saved one the phone stops guessing", timetable.on(day(14, month = 12)).isEmpty())
        assertEquals(listOf("История"), timetable.on(day(2, month = 12)).map { it.subject })
        assertTrue("after its last course day", timetable.on(day(9, month = 12)).isEmpty())
        assertTrue("Sunday", timetable.on(day(22)).isEmpty())
        val first = timetable.times(day(16), almaty).first()
        assertEquals(at(16, 9), first.startAt)
        assertEquals(at(16, 10, 50), first.endAt)
    }

    @Test fun theNextClassIsTheFirstStillAheadOfTheLead() {
        assertEquals("История", timetable.next(at(18, 9), almaty)!!.lesson.subject)
        assertNull("starts in 5 minutes: a 10-minute reminder is late", timetable.next(at(18, 9, 55), almaty, lead = 10 * 60_000L)
            ?.takeIf { it.date == day(18) })
        val monday = timetable.next(at(18, 12), almaty)!!
        assertEquals("Политология", monday.lesson.subject)
        assertEquals(at(23, 9), monday.startAt)
        val history = timetable.next(at(18, 9), almaty)!!
        assertNotEquals("the class just reminded of is not reminded of again", history.startAt,
            timetable.next(at(18, 9, 30), almaty, lead = 10 * 60_000L, after = history.startAt)!!.startAt)
    }

    @Test fun theWidgetShowsTodayWhileAClassIsAheadThenTheNextDayWithClasses() {
        val morning = TodayPlan.of(timetable, at(16, 9, 30), almaty)!!
        assertTrue(morning.today)
        assertEquals("Политология", morning.current!!.lesson.subject)
        assertEquals(2, morning.lessons.size)
        val evening = TodayPlan.of(timetable, at(16, 12), almaty)!!
        assertFalse(evening.today)
        assertTrue("today's classes are over", evening.finished)
        assertEquals(day(18), evening.date)
        val free = TodayPlan.of(timetable, at(17, 8), almaty)!!
        assertTrue("Tuesday has no classes", free.free)
        assertEquals(day(18), free.date)
        assertNull("nothing left within a week", TodayPlan.of(Timetable.of(message("lessons" to emptyList<Any>()), now, almaty)!!, now, almaty))
    }

    @Test fun aLongDayKeepsTheClassesStillAheadWithinFourRows() {
        val long = Timetable.of(message("lessons" to (8..13).map { lesson(2, "%02d:00".format(it), "%02d:50".format(it), "Пара $it") }), now, almaty)!!
        val plan = TodayPlan.of(long, at(18, 9, 55), almaty)!!
        assertEquals(listOf("Пара 10", "Пара 11", "Пара 12", "Пара 13"), plan.lessons.map { it.lesson.subject })
        assertNull("between classes nothing is under way", plan.current)
        assertEquals("finished classes fill the rows left", listOf("Пара 10", "Пара 11", "Пара 12", "Пара 13"),
            TodayPlan.of(long, at(18, 12, 20), almaty)!!.lessons.map { it.lesson.subject })
        assertEquals("a two-row widget", listOf("Пара 10", "Пара 11"), TodayPlan.of(long, at(18, 9, 55), almaty, rows = 2)!!.lessons.map { it.lesson.subject })
    }

    @Test fun theWidgetChangesWhenAClassBeginsOrEndsOrAtMidnight() {
        assertEquals(at(16, 9), TodayPlan.nextChange(timetable, at(16, 8), almaty))
        assertEquals(at(16, 10, 50), TodayPlan.nextChange(timetable, at(16, 9, 30), almaty))
        assertEquals(at(17, 0), TodayPlan.nextChange(timetable, at(16, 12), almaty))
        assertEquals(at(17, 0), TodayPlan.nextChange(null, at(16, 12), almaty))
    }
}
