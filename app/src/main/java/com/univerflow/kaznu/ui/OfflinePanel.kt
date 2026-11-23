package com.univerflow.kaznu.ui

import android.content.Context
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import com.univerflow.kaznu.R
import com.univerflow.kaznu.databinding.ItemLastGradeCourseBinding
import com.univerflow.kaznu.databinding.ItemTimetableLessonBinding
import com.univerflow.kaznu.databinding.ViewOfflinePanelBinding
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.domain.ExamItem
import com.univerflow.kaznu.domain.ExamKind
import com.univerflow.kaznu.domain.LastGrades
import com.univerflow.kaznu.domain.OfflineDigest
import com.univerflow.kaznu.domain.Timetable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The offline screen: under a network error the error overlay also shows what the phone kept ([OfflineDigest]): the
 * last grades with the moment they were saved, the day's classes and the exams ahead. Read from the phone only; the main
 * screen fills it only while unlocked and signed in, and empties it when the overlay leaves, on lock and on sign-out.
 * [shownChanged] runs when saved data comes into view or leaves it: meanwhile the window stays out of screenshots.
 */
class OfflinePanel(private val views: ViewOfflinePanelBinding, private val shownChanged: () -> Unit, openWeek: () -> Unit) {
    /** Saved grades, classes or exams are on screen. */
    var shown = false
        private set
    /** The language the panel was drawn in; null while it is empty. */
    var language: AppLanguage? = null
        private set
    private var digest: OfflineDigest? = null
    private val inflater = LayoutInflater.from(views.root.context)

    init {
        views.offlineWeek.setOnClickListener { openWeek() }
        listOf(views.offlineGradesTitle, views.offlineDayTitle, views.offlineExamsTitle).forEach { ViewCompat.setAccessibilityHeading(it, true) }
    }

    /** Draws [digest] in [language]; the same data again changes nothing, so automatic retries neither blink nor scroll. */
    fun show(digest: OfflineDigest, words: Context, language: AppLanguage, now: Long = System.currentTimeMillis(),
             zone: TimeZone = TimeZone.getDefault()) {
        if (views.root.isVisible && digest == this.digest && language == this.language) return
        this.digest = digest
        this.language = language
        val dates = Dates(Locale.forLanguageTag(language.localeCode), zone, now)
        showGrades(digest.grades, words, dates)
        showDay(digest, words, dates)
        showExams(digest.exams, words, dates)
        views.root.isVisible = true
        setShown(!digest.empty)
    }

    fun clear() {
        digest = null
        language = null
        listOf(views.offlineGradesList, views.offlineLessons, views.offlineExamsList).forEach { it.removeAllViews() }
        listOf(views.offlineGradesTitle, views.offlineGradesNote, views.offlineDayTitle, views.offlineDayNote, views.offlineExamsTitle)
            .forEach { it.text = null }
        views.root.isVisible = false
        setShown(false)
    }

    private fun setShown(value: Boolean) {
        if (shown == value) return
        shown = value
        shownChanged()
    }

    /** Always there under a network error: the totals with their date, or how to get them saved. */
    private fun showGrades(grades: LastGrades?, words: Context, dates: Dates) {
        views.offlineGradesTitle.text = grades?.let { words.getString(R.string.offline_grades_as_of, dates.moment(it.saved)) }
            ?: words.getString(R.string.last_grades)
        views.offlineGradesNote.text = words.getString(if (grades == null) R.string.last_grades_none else R.string.last_grades_note)
        views.offlineGradesCard.isVisible = grades != null
        rows(views.offlineGradesList, grades?.byCourse().orEmpty()) { (course, scores) ->
            row(views.offlineGradesList, course, scores.joinToString("  ·  ") { if (it.label.isEmpty()) it.score else "${it.label} ${it.score}" })
        }
    }

    /** Today's classes, or the next day's; hidden when no week is saved. */
    private fun showDay(digest: OfflineDigest, words: Context, dates: Dates) {
        val saved = digest.timetableSaved
        views.offlineDay.isVisible = saved != null
        if (saved == null) { views.offlineLessons.removeAllViews(); return }
        val plan = digest.plan
        views.offlineDayTitle.text = when {
            plan == null -> words.getString(R.string.widget_none_week)
            digest.day == OfflineDigest.Day.TODAY -> "${words.getString(R.string.widget_today)}, ${dates.day(plan.date)}"
            digest.day == OfflineDigest.Day.TOMORROW -> "${words.getString(R.string.widget_tomorrow)}, ${dates.day(plan.date)}"
            else -> dates.weekday(plan.date)
        }
        views.offlineWeek.text = words.getString(R.string.offline_week)
        views.offlineDayCard.isVisible = plan != null
        views.offlineDayNote.text = listOfNotNull(
            when {
                plan?.free == true -> words.getString(R.string.widget_free)
                plan?.finished == true -> words.getString(R.string.widget_finished)
                else -> null
            },
            words.getString(R.string.widget_from, dates.date(saved)),
        ).joinToString(" · ")
        rows(views.offlineLessons, plan?.lessons.orEmpty()) { time ->
            ItemTimetableLessonBinding.inflate(inflater, views.offlineLessons, false).apply { show(time, words, time == plan?.current) }.root
        }
    }

    private fun showExams(exams: List<ExamItem>, words: Context, dates: Dates) {
        views.offlineExams.isVisible = exams.isNotEmpty()
        views.offlineExamsTitle.text = words.getString(R.string.offline_exams)
        rows(views.offlineExamsList, exams) { exam ->
            val kind = words.getString(if (exam.kind == ExamKind.EXAM) R.string.exam_kind_exam else R.string.exam_kind_midterm)
            // A room keeps its number: «ГУК 210» does not break before 210; a long place still wraps between words.
            row(views.offlineExamsList, exam.subject, listOf(dates.start(exam.startAt(dates.zone)), kind, exam.place.replace(ROOM_NUMBER, Typography.nbsp.toString()))
                .filter { it.isNotEmpty() }.joinToString(" · "))
        }
    }

    private fun <T> rows(list: LinearLayout, items: List<T>, view: (T) -> android.view.View) {
        list.removeAllViews()
        items.forEachIndexed { index, item ->
            val row = view(item)
            if (index > 0) row.setBackgroundResource(R.drawable.timetable_divider)
            list.addView(row)
        }
    }

    /** A two-line row: a course or subject over its grades or its date and place. */
    private fun row(list: LinearLayout, title: String, detail: String) =
        ItemLastGradeCourseBinding.inflate(inflater, list, false).apply {
            gradeCourse.text = title
            gradeScores.text = detail
            gradeScores.isVisible = detail.isNotEmpty()
        }.root

    private companion object {
        val ROOM_NUMBER = Regex(" (?=[0-9])")
    }

    /** Dates in the app's language, on the phone's clock; times on the 24-hour clock, like the classes. */
    private class Dates(private val locale: Locale, val zone: TimeZone, private val now: Long) {
        /** "23 ноября, 14:05", with the year when it is not this one. */
        fun moment(at: Long) = format(if (year(at) == year(now)) "dMMMMHHmm" else "dMMMMyHHmm", at)
        fun date(at: Long) = format(if (year(at) == year(now)) "dMMMM" else "dMMMMy", at)
        fun day(date: Long) = format("dMMMM", noon(date))
        fun weekday(date: Long) = format("EEEEdMMMM", noon(date)).replaceFirstChar { it.titlecase(locale) }
        fun start(at: Long) = format("EEEdMMMMHHmm", at)

        private fun noon(date: Long) = Timetable.at(date, 12 * 60, zone)
        private fun year(at: Long) = Calendar.getInstance(zone).apply { timeInMillis = at }.get(Calendar.YEAR)
        private fun format(skeleton: String, at: Long): String =
            SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).apply { timeZone = zone }.format(Date(at))
    }
}
