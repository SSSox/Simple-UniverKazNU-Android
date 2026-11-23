package com.univerflow.kaznu.domain

import java.util.TimeZone

/**
 * What the offline screen shows under a network error, from the copies the phone kept while it had the network: the
 * last grade totals, the classes of today (or of the next day with classes) from the saved week, and the exams still
 * ahead. Pure Kotlin; the screen is [com.univerflow.kaznu.ui.OfflinePanel].
 */
data class OfflineDigest(
    val grades: LastGrades?,
    /** When the week was saved; null when none is. */
    val timetableSaved: Long?,
    /** The day to show; null when no week is saved or no class is left within a week. */
    val plan: TodayPlan?,
    /** Which day [plan] is, said in words. */
    val day: Day?,
    /** Exams and midterms that have not started, soonest first. */
    val exams: List<ExamItem>,
    /** The epoch day on the phone's clock: how far each exam day is follows it, so after midnight the digest differs. */
    val today: Long,
) {
    enum class Day { TODAY, TOMORROW, LATER }

    /** The exams of one day, as the site's exam timetable groups them; [inDays] 0 is today. */
    data class ExamDay(val date: Long, val inDays: Long, val exams: List<ExamItem>)

    /** Nothing kept at all: the error screen stays as it was. */
    val empty: Boolean get() = grades == null && timetableSaved == null && exams.isEmpty()

    /** [exams] by day, soonest first. */
    val examDays: List<ExamDay> get() = exams.groupBy { it.date }.map { (date, items) -> ExamDay(date, date - today, items) }

    companion object {
        /** A whole day of classes: the offline screen has room for all of them. */
        const val LESSON_ROWS = 12
        const val EXAM_ROWS = 5

        fun of(grades: LastGrades?, timetable: Timetable?, exams: ExamPlan?, now: Long, zone: TimeZone): OfflineDigest {
            val plan = timetable?.let { TodayPlan.of(it, now, zone, LESSON_ROWS) }
            val today = Timetable.epochDay(now, zone)
            val day = plan?.let {
                when (it.date) {
                    today -> Day.TODAY
                    today + 1 -> Day.TOMORROW
                    else -> Day.LATER
                }
            }
            val ahead = exams?.items.orEmpty().map { it to it.startAt(zone) }.filter { it.second > now }
                .sortedBy { it.second }.take(EXAM_ROWS).map { it.first }
            return OfflineDigest(grades, timetable?.saved, plan, day, ahead, today)
        }
    }
}
