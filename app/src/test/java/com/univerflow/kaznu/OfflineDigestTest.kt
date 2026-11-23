package com.univerflow.kaznu

import com.univerflow.kaznu.domain.ExamItem
import com.univerflow.kaznu.domain.ExamKind
import com.univerflow.kaznu.domain.ExamPlan
import com.univerflow.kaznu.domain.LastGrades
import com.univerflow.kaznu.domain.OfflineDigest
import com.univerflow.kaznu.domain.Timetable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.GregorianCalendar
import java.util.TimeZone

class OfflineDigestTest {
    private val almaty: TimeZone = TimeZone.getTimeZone("Asia/Almaty")
    private fun at(day: Int, hour: Int, minute: Int = 0, month: Int = 11) =
        GregorianCalendar(almaty).apply { clear(); set(2026, month - 1, day, hour, minute) }.timeInMillis
    private fun date(day: Int, month: Int = 11) = Timetable.epochDay(at(day, 12, month = month), almaty)
    private fun lesson(day: Int, start: String, end: String, subject: String) =
        mapOf("day" to day, "start" to start, "end" to end, "subject" to subject, "type" to "лекция", "room" to "309")
    // The week of Monday 16 November 2026: Monday 9:00 and 11:00, Wednesday 10:00.
    private val saved = at(16, 8)
    private val timetable = Timetable.of(mapOf("v" to 1, "week" to "2026-11-16", "owner" to "acc-1", "lessons" to listOf(
        lesson(0, "11:00", "11:50", "Культурология"), lesson(0, "09:00", "10:50", "Политология"),
        lesson(2, "10:00", "10:50", "История"))), saved, almaty)!!
    private val grades = LastGrades("2026/1", "ru", saved, listOf(LastGrades.Row("Социология", "РК1", "85", "100")))

    private fun exam(day: Int, hour: Int, subject: String, month: Int = 12) =
        ExamItem(ExamKind.EXAM, subject, date(day, month), hour * 60, "ГУК 210")

    @Test fun todayShowsTheWholeDayWhileAClassIsAhead() {
        // Monday 11:20: the 9:00 class is over, the 11:00 one under way.
        val digest = OfflineDigest.of(grades, timetable, null, at(16, 11, 20), almaty)
        assertEquals(OfflineDigest.Day.TODAY, digest.day)
        assertEquals(listOf("Политология", "Культурология"), digest.plan!!.lessons.map { it.lesson.subject })
        assertEquals("Культурология", digest.plan!!.current?.lesson?.subject)
        assertEquals(saved, digest.timetableSaved)
        assertFalse(digest.empty)
    }

    @Test fun afterTodaysClassesTheNextDayWithClassesIsNamed() {
        val monday = OfflineDigest.of(null, timetable, null, at(16, 18), almaty)
        assertEquals("Wednesday is two days on", OfflineDigest.Day.LATER, monday.day)
        assertEquals(date(18), monday.plan!!.date)
        assertTrue(monday.plan!!.finished)
        val tuesday = OfflineDigest.of(null, timetable, null, at(17, 18), almaty)
        assertEquals(OfflineDigest.Day.TOMORROW, tuesday.day)
        assertTrue("Tuesday has no classes", tuesday.plan!!.free)
    }

    @Test fun aSavedWeekWithNoClassAheadStillSaysWhenItWasSaved() {
        val digest = OfflineDigest.of(null, timetable.copy(lessons = emptyList()), null, at(16, 9), almaty)
        assertEquals(saved, digest.timetableSaved)
        assertNull(digest.plan)
        assertNull(digest.day)
        assertFalse(digest.empty)
    }

    @Test fun examsAheadComeSoonestFirstWithinTheLimit() {
        val now = at(15, 12, month = 12)
        val items = listOf(exam(20, 9, "Физика"), exam(14, 9, "Прошло"), exam(15, 11, "Началось"), exam(16, 13, "История"),
            exam(15, 14, "Сегодня")) + (21..26).map { exam(it, 9, "Экзамен $it") }
        val digest = OfflineDigest.of(null, null, ExamPlan(saved, items), now, almaty)
        assertEquals(OfflineDigest.EXAM_ROWS, digest.exams.size)
        assertEquals(listOf("Сегодня", "История", "Физика", "Экзамен 21", "Экзамен 22"), digest.exams.map { it.subject })
        assertFalse(digest.empty)
    }

    @Test fun examsAreGroupedByDayWithHowFarEachDayIs() {
        val items = listOf(exam(16, 13, "История"), exam(15, 14, "Физика"), exam(16, 9, "Право"), exam(20, 9, "Химия"))
        val evening = OfflineDigest.of(null, null, ExamPlan(saved, items), at(15, 12, month = 12), almaty)
        assertEquals(date(15, 12), evening.today)
        assertEquals(listOf(0L to listOf("Физика"), 1L to listOf("Право", "История"), 5L to listOf("Химия")),
            evening.examDays.map { day -> day.inDays to day.exams.map { it.subject } })
        // The same saved data a day later is new data: the panel redraws «Сегодня» as it moves.
        val next = OfflineDigest.of(null, null, ExamPlan(saved, items), at(16, 8, month = 12), almaty)
        assertEquals(0L, next.examDays.first().inDays)
        assertFalse(next == evening)
    }

    @Test fun nothingKeptLeavesTheErrorScreenAsItWas() {
        val digest = OfflineDigest.of(null, null, ExamPlan(saved, listOf(exam(1, 9, "Прошло", month = 11))), at(20, 12), almaty)
        assertTrue(digest.empty)
        assertNull(digest.timetableSaved)
        assertTrue(digest.exams.isEmpty())
        assertFalse(OfflineDigest.of(grades, null, null, at(20, 12), almaty).empty)
    }
}
