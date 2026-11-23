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

    // The saved week's neighbour (week of Monday 23 November): Monday 9:00 Культурология only.
    private fun second(vararg lessons: Map<String, Any?>, week: String = "2026-11-23") = mapOf<String, Any?>("week" to week, "lessons" to lessons.toList())
    private val alternating = Timetable.of(message("second" to second(lesson(0, "09:00", "09:50", "Культурология"))), now, almaty)!!

    @Test fun withTheNeighbouringWeekSavedTheOddAndEvenWeeksFollowTheirParity() {
        // The saved week 16 November: Политология and Культурология on Monday, История on Wednesday.
        assertEquals(listOf("Политология", "Культурология"), alternating.on(day(16)).map { it.subject })
        assertEquals(listOf("История"), alternating.on(day(18)).map { it.subject })
        // The week after belongs to the neighbour: its Monday, and no Wednesday class.
        assertEquals(listOf("Культурология"), alternating.on(day(23)).map { it.subject })
        assertEquals(emptyList<String>(), alternating.on(day(25)).map { it.subject })
        // Two weeks on it is the saved week's again, and so on, forwards and backwards.
        assertEquals(listOf("Политология", "Культурология"), alternating.on(day(30)).map { it.subject })
        assertEquals(listOf("Культурология"), alternating.on(day(9)).map { it.subject })
        assertEquals(listOf("Политология", "Культурология"), alternating.on(day(2)).map { it.subject })
        // Reminders look at the same days: from Sunday evening the next class is the neighbour week's Monday one.
        assertEquals("Культурология", alternating.next(at(22, 20), almaty)!!.lesson.subject)
        assertEquals(day(23), alternating.next(at(22, 20), almaty)!!.date)
    }

    @Test fun aCopyWithoutItsNeighbourKeepsTheOneAlreadySaved() {
        // The same week sent alone (the page sends it at once, the neighbour once a session): the saved neighbour stays.
        assertEquals(alternating.alternate, timetable.keepingAlternateOf(alternating).alternate)
        // The neighbour week shown now takes the saved week as its own neighbour: the parity is the same.
        val other = Timetable.of(message("week" to "2026-11-23", "lessons" to listOf(lesson(0, "09:00", "09:50", "Культурология"))), at(25, 12), almaty)!!
        val kept = other.keepingAlternateOf(alternating)
        assertEquals(timetable.week, kept.alternate!!.week)
        assertEquals(listOf("Политология", "Культурология"), kept.on(day(30)).map { it.subject })
        // Another account, an unrelated week or a copy that brings its own neighbour keep what they have.
        assertNull(Timetable.of(message("owner" to "acc-2"), now, almaty)!!.keepingAlternateOf(alternating).alternate)
        assertNull(Timetable.of(message("week" to "2026-11-30"), at(2, 12, month = 12), almaty)!!.keepingAlternateOf(alternating).alternate)
        assertEquals(alternating, alternating.keepingAlternateOf(timetable))
        assertEquals(timetable, timetable.keepingAlternateOf(null))
    }

    @Test fun aNeighbourBeforeTheSavedWeekPairsTheSameWay() {
        val before = Timetable.of(message("second" to second(lesson(1, "09:00", "09:50", "Физика"), week = "2026-11-09")), now, almaty)!!
        assertEquals(listOf("Физика"), before.on(day(10)).map { it.subject })
        assertEquals(emptyList<String>(), before.on(day(17)).map { it.subject })
        assertEquals(listOf("Физика"), before.on(day(24)).map { it.subject })
    }

    @Test fun aCopyWithoutTheNeighbourRepeatsTheSavedWeekAsBefore() {
        // Files written before the odd and even weeks were kept, and a page whose neighbour could not be read.
        assertNull(timetable.alternate)
        assertEquals(listOf("Политология", "Культурология"), timetable.on(day(23)).map { it.subject })
        assertNotNull(Timetable.restore(message("saved" to now)))
        assertNull(Timetable.restore(message("saved" to now))!!.alternate)
    }

    @Test fun aNeighbourOutOfShapeRefusesTheWholeCopy() {
        val good = lesson(0, "09:00", "09:50", "Культурология")
        val refused = listOf(
            second(good, week = "2026-11-30"), second(good, week = "2026-11-16"), second(good, week = "2026-11-24"), second(good, week = "2026-11-02"),
            second(lesson(7, "09:00", "09:50", "Предмет")), second(lesson(0, "09:50", "09:00", "Предмет")),
            mapOf<String, Any?>("week" to "2026-11-23", "lessons" to "none"), mapOf<String, Any?>("lessons" to listOf(good)))
        for (value in refused) assertNull(value.toString(), Timetable.of(message("second" to value), now, almaty))
        assertNull(Timetable.of(message("second" to "2026-11-23"), now, almaty))
        assertNull(Timetable.of(message("second" to second(*Array(81) { good })), now, almaty))
        assertNotNull("a neighbour week without classes is a week without classes", Timetable.of(message("second" to second()), now, almaty))
    }
}
