package com.univerflow.kaznu

import com.univerflow.kaznu.domain.ExamItem
import com.univerflow.kaznu.domain.ExamKind
import com.univerflow.kaznu.domain.ExamPlan
import com.univerflow.kaznu.domain.Timetable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.GregorianCalendar
import java.util.TimeZone

class ExamsTest {
    private val zone: TimeZone = TimeZone.getDefault()
    private fun at(month: Int, day: Int, hour: Int, minute: Int = 0) =
        GregorianCalendar(zone).apply { clear(); set(2026, month - 1, day, hour, minute) }.timeInMillis
    private val now = at(11, 23, 12)
    private val minute = 60_000L

    private fun item(start: String, subject: String = "Алгебра", kind: String = "exam", place: String = "ФИТ · 309") =
        mapOf("kind" to kind, "subject" to subject, "start" to start, "place" to place)

    @Test fun aValidMessageGivesItsComingItemsAndDropsPastOnes() {
        val parsed = ExamPlan.batch(mapOf("v" to 1, "kind" to "exam", "items" to listOf(
            item("2026-12-15T13:00"), item("2026-11-20T09:00", "Прошедший"), item("2026-11-23T11:59", "Уже идёт"))), now, zone)
        assertNotNull(parsed)
        assertEquals(ExamKind.EXAM, parsed!!.first)
        assertEquals(listOf("Алгебра"), parsed.second.map { it.subject })
        assertEquals(at(12, 15, 13), parsed.second.single().startAt(zone))
    }

    @Test fun anythingOutsideTheShapeIsRefused() {
        fun batch(vararg items: Map<String, Any?>, kind: Any? = "exam", v: Any? = 1, list: Any? = items.toList()) =
            ExamPlan.batch(mapOf("v" to v, "kind" to kind, "items" to list), now, zone)
        assertNotNull(batch(item("2026-12-15T13:00")))
        assertNull(batch(item("2026-12-15T13:00"), v = 2))
        assertNull(batch(item("2026-12-15T13:00"), kind = "consult"))
        assertNull(batch(item("2026-12-15T13:00", kind = "midterm")))
        assertNull(batch(item("2026-12-15 13:00")))
        assertNull(batch(item("2026-12-15T25:00")))
        assertNull(batch(item("2026-02-30T10:00")))
        assertNull(batch(item("2026-12-15T13:00", subject = "")))
        assertNull(batch(item("2026-12-15T13:00", subject = "x".repeat(201))))
        assertNull(batch(item("2026-12-15T13:00", place = "x".repeat(201))))
        assertNull(batch(list = "none"))
        assertNull(batch(list = List(ExamPlan.MAX_ITEMS + 1) { item("2026-12-15T13:00") }))
        assertNull(batch(list = listOf("text")))
    }

    @Test fun aBatchReplacesOnlyItsOwnKind() {
        val exam = ExamItem(ExamKind.EXAM, "Алгебра", 0, 600, "")
        val midterm = ExamItem(ExamKind.MIDTERM, "Физика", 0, 600, "")
        val plan = ExamPlan(1, listOf(exam, midterm)).replace(ExamKind.EXAM, emptyList(), 2)
        assertEquals(listOf(midterm), plan.items)
        assertEquals(2, plan.saved)
        assertEquals(listOf(midterm, exam.copy(subject = "Геометрия")), plan.replace(ExamKind.EXAM, listOf(exam.copy(subject = "Геометрия")), 3).items)
    }

    @Test fun theSavedCopyRoundTripsAndOutOfShapeIsRefused() {
        val map = mapOf("v" to 1, "saved" to 5L, "items" to listOf(item("2026-12-15T13:00"), item("2026-12-16T09:30", kind = "midterm")))
        val plan = ExamPlan.restore(map)!!
        assertEquals(listOf(ExamKind.EXAM, ExamKind.MIDTERM), plan.items.map { it.kind })
        assertEquals(9 * 60 + 30, plan.items[1].minute)
        assertEquals("2026-12-16", Timetable.iso(plan.items[1].date))
        assertNull(ExamPlan.restore(map + ("v" to 2)))
        assertNull(ExamPlan.restore(map + ("saved" to 0L)))
        assertNull(ExamPlan.restore(map + ("items" to listOf(item("bad")))))
    }

    private fun plan(vararg starts: Pair<String, String>) = ExamPlan.restore(mapOf("v" to 1, "saved" to 1L,
        "items" to starts.map { item(it.first, it.second) }))!!

    @Test fun anExamHasAnEveningBeforeReminderAt19AndALeadReminder() {
        val plan = plan("2026-12-15T13:00" to "Алгебра")
        val lead = 30 * minute
        val first = plan.next(now, zone, lead)!!
        assertTrue(first.evening)
        assertEquals(at(12, 14, 19), first.at)
        val second = plan.next(now, zone, lead, after = first.at)!!
        assertFalse(second.evening)
        assertEquals(at(12, 15, 12, 30), second.at)
        assertNull(plan.next(now, zone, lead, after = second.at))
    }

    @Test fun anEarlyExamIsRemindedTheEveningBeforeNotThatMorning() {
        val plan = plan("2026-12-15T08:30" to "Алгебра")
        assertEquals(at(12, 14, 19), plan.next(now, zone, 15 * minute)!!.at)
    }

    @Test fun pastRemindersAndStartedExamsAreSkipped() {
        val plan = plan("2026-11-24T09:00" to "Завтрашний", "2026-11-23T11:00" to "Начался")
        // It is 19:30 the evening before: the evening reminder has gone, the lead one is still ahead.
        val late = at(11, 23, 19, 30)
        val next = plan.next(late, zone, 20 * minute)!!
        assertFalse(next.evening)
        assertEquals("Завтрашний", next.item.subject)
        assertEquals(at(11, 24, 8, 40), next.at)
        // After the lead moment only the exam's start remains: nothing is left to say.
        assertNull(plan.next(at(11, 24, 8, 45), zone, 20 * minute))
        assertNull(plan.next(at(11, 24, 9, 1), zone, 20 * minute))
    }

    @Test fun theSoonestReminderOfSeveralExamsComesFirst() {
        val plan = plan("2026-12-20T10:00" to "Поздний", "2026-12-15T13:00" to "Ранний")
        assertEquals("Ранний", plan.next(now, zone, 10 * minute)!!.item.subject)
    }
}
