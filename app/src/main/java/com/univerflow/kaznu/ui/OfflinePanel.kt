package com.univerflow.kaznu.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isNotEmpty
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import com.univerflow.kaznu.R
import com.univerflow.kaznu.databinding.ItemOfflineCourseBinding
import com.univerflow.kaznu.databinding.ItemOfflineExamBinding
import com.univerflow.kaznu.databinding.ItemOfflineExamDayBinding
import com.univerflow.kaznu.databinding.ItemOfflineScoreBinding
import com.univerflow.kaznu.databinding.ItemTimetableLessonBinding
import com.univerflow.kaznu.databinding.ViewOfflinePanelBinding
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.domain.Band
import com.univerflow.kaznu.domain.ExamItem
import com.univerflow.kaznu.domain.ExamKind
import com.univerflow.kaznu.domain.LastGrades
import com.univerflow.kaznu.domain.OfflineDigest
import com.univerflow.kaznu.domain.ScoreBand
import com.univerflow.kaznu.domain.Timetable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * The offline screen: under a network error the error overlay also shows what the phone kept ([OfflineDigest]): the
 * last grades with the moment they were saved, the day's classes and the exams ahead. Read from the phone only; the main
 * screen fills it only while unlocked and signed in, and empties it when the overlay leaves, on lock and on sign-out.
 * [shownChanged] runs when saved data comes into view or leaves it: meanwhile the window stays out of screenshots.
 *
 * It is drawn like the same parts of the site layer (univer-style.js, univer-screens.js): the journal's summary card with
 * score chips in their band's colours and the legend, the classes in a card with a header, the exam timetable's days.
 * Words come from the language context; colours from the panel's own context, which carries the app's day or night.
 */
class OfflinePanel(private val views: ViewOfflinePanelBinding, private val shownChanged: () -> Unit, openWeek: () -> Unit) {
    /** Saved grades, classes or exams are on screen. */
    var shown = false
        private set
    /** The language the panel was drawn in; null while it is empty. */
    var language: AppLanguage? = null
        private set
    private var digest: OfflineDigest? = null
    private val context = views.root.context
    private val inflater = LayoutInflater.from(context)
    private val density = context.resources.displayMetrics.density

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
        showGrades(digest.grades, words, language, dates)
        showDay(digest, words, dates)
        showExams(digest, words, dates)
        views.root.isVisible = true
        setShown(!digest.empty)
    }

    fun clear() {
        digest = null
        language = null
        listOf(views.offlineGradesList, views.offlineBandsList, views.offlineLessons, views.offlineExamDays).forEach { it.removeAllViews() }
        listOf(views.offlineGradesTitle, views.offlineGradesEmpty, views.offlineGradesNote, views.offlineBandsTitle, views.offlineDayTitle,
            views.offlineDayNote, views.offlineExamsTitle).forEach { it.text = null }
        views.offlineBands.contentDescription = null
        views.root.isVisible = false
        setShown(false)
    }

    private fun setShown(value: Boolean) {
        if (shown == value) return
        shown = value
        shownChanged()
    }

    /** Always there under a network error: the totals with their date and the colour legend, or how to get them saved. */
    private fun showGrades(grades: LastGrades?, words: Context, language: AppLanguage, dates: Dates) {
        views.offlineGradesTitle.text = grades?.let { words.getString(R.string.offline_grades_as_of, dates.moment(it.saved)) }
            ?: words.getString(R.string.last_grades)
        views.offlineGradesEmpty.text = if (grades == null) words.getString(R.string.last_grades_none) else null
        views.offlineGradesNote.text = if (grades == null) null else words.getString(R.string.last_grades_note)
        views.offlineGradesEmpty.isVisible = grades == null
        listOf(views.offlineGradesList, views.offlineBands, views.offlineGradesNote).forEach { it.isVisible = grades != null }
        rows(views.offlineGradesList, grades?.byCourse().orEmpty()) { (course, scores) -> course(course, scores, words, language) }
        showBands(words)
    }

    /** A course beside its chips, or over them with more than two chips or on a narrow phone, as the site's list wraps. */
    private fun course(name: String, scores: List<LastGrades.Row>, words: Context, language: AppLanguage): View =
        ItemOfflineCourseBinding.inflate(inflater, views.offlineGradesList, false).apply {
            courseName.text = name
            if (scores.size > 2 || context.resources.configuration.screenWidthDp < NARROW_DP) {
                root.orientation = LinearLayout.VERTICAL
                courseName.updateLayoutParams<LinearLayout.LayoutParams> { width = ViewGroup.LayoutParams.MATCH_PARENT; weight = 0f }
                courseScores.updateLayoutParams<LinearLayout.LayoutParams> {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    marginStart = 0
                    topMargin = dp(8)
                }
            }
            val spoken = scores.map { chip(courseScores, it, words, language) }
            // One stop for a screen reader: the course and every grade with its band in words, since colour is not read.
            root.contentDescription = "$name: ${spoken.joinToString("; ")}"
            courseName.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }.root

    /** Adds one score chip to [group]; returns what a screen reader says for it. */
    private fun chip(group: ViewGroup, score: LastGrades.Row, words: Context, language: AppLanguage): String {
        val standing = ScoreBand.of(score.value, score.max)
        val value = ScoreBand.display(score.value, language)
        val max = score.max.takeIf { it.isNotEmpty() }?.let { ScoreBand.display(it, language) }
        ItemOfflineScoreBinding.inflate(inflater, group, false).apply {
            scoreLabel.text = score.label
            scoreLabel.isVisible = score.label.isNotEmpty()
            scoreValue.text = value
            // The maximum is shown only when it is not 100, so 12 of 25 is not read as 12 of 100.
            scoreMax.text = max?.takeIf { score.max != "100" }?.let { "/$it" }
            scoreMax.isVisible = scoreMax.text.isNotEmpty()
            if (ScoreBand.number(score.value) == 0.0) {
                // A zero is the site's quiet zero: muted, not bold.
                scoreValue.setTextColor(scoreLabel.currentTextColor)
                scoreValue.typeface = Typeface.DEFAULT
            }
            // Without a maximum or a number there is no band: the chip keeps the card's ground and hairline.
            standing?.let {
                root.fill = colour(soft(it.band))
                root.stroke = null
                root.foot = colour(fill(it.band))
                root.footShare = (it.percent / 100).toFloat()
            }
            group.addView(root)
        }
        return listOfNotNull(score.label.ifEmpty { null }, if (max == null) value else "$value / $max",
            standing?.let { words.getString(name(it.band)) }).joinToString(", ")
    }

    /** «Цвета баллов» with a square of each band, the same words the report's legend uses. */
    private fun showBands(words: Context) {
        val title = words.getString(R.string.offline_bands)
        views.offlineBandsTitle.text = title
        views.offlineBandsList.removeAllViews()
        val size = dp(10)
        val items = Band.entries.map { band ->
            val text = words.getString(legend(band))
            views.offlineBandsList.addView(TextView(context).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(colour(R.color.md_on_surface_variant))
                compoundDrawablePadding = dp(6)
                // Left, not start: a relative drawable waits for an attached window, and the app's languages run left to right.
                setCompoundDrawables(plate(fill(band), 3).apply { setBounds(0, 0, size, size) }, null, null, null)
            })
            text
        }
        views.offlineBands.contentDescription = (listOf(title) + items).joinToString(". ")
    }

    /** Today's classes, or the next day's, in a card headed by the day; hidden when no week is saved. */
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
        views.offlineLessons.isVisible = views.offlineLessons.isNotEmpty()
    }

    /** The exams ahead by day, like the site's exam timetable: a date tile, the weekday and how far, then each exam. */
    private fun showExams(digest: OfflineDigest, words: Context, dates: Dates) {
        views.offlineExams.isVisible = digest.exams.isNotEmpty()
        views.offlineExamsTitle.text = words.getString(R.string.offline_exams)
        views.offlineExamDays.removeAllViews()
        val months = words.resources.getStringArray(R.array.offline_months_short)
        digest.examDays.forEachIndexed { index, day ->
            val item = ItemOfflineExamDayBinding.inflate(inflater, views.offlineExamDays, false)
            item.examDateNumber.text = dates.dayOfMonth(day.date)
            item.examDateMonth.text = months[dates.month(day.date)]
            item.examWeekday.text = dates.weekdayName(day.date)
            item.examWeekday.contentDescription = "${item.examWeekday.text}, ${dates.day(day.date)}"
            ViewCompat.setAccessibilityHeading(item.examWeekday, true)
            item.examRelative.text = when (day.inDays) {
                0L -> words.getString(R.string.widget_today)
                1L -> words.getString(R.string.widget_tomorrow)
                else -> words.resources.getQuantityString(R.plurals.offline_in_days, day.inDays.toInt(), day.inDays.toInt())
            }
            if (day.inDays == 0L) {
                // Today's tile is filled, as on the site; the rest stay quiet.
                item.examDate.fill = colour(R.color.brand_fill)
                item.examDate.stroke = null
                item.examDateNumber.setTextColor(Color.WHITE)
                item.examDateMonth.setTextColor(Color.WHITE)
                item.examRelative.setTextColor(colour(R.color.brand_strong))
            }
            day.exams.forEachIndexed { slot, exam -> item.examSlots.addView(exam(item.examSlots, exam, words, dates, slot > 0)) }
            if (index > 0) item.root.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = dp(20) }
            views.offlineExamDays.addView(item.root)
        }
    }

    private fun exam(list: ViewGroup, exam: ExamItem, words: Context, dates: Dates, below: Boolean) =
        ItemOfflineExamBinding.inflate(inflater, list, false).apply {
            val main = exam.kind == ExamKind.EXAM
            examTime.text = dates.time(exam.startAt(dates.zone))
            examKind.text = words.getString(if (main) R.string.exam_kind_exam else R.string.exam_kind_midterm)
                .replaceFirstChar { it.titlecase(dates.locale) }
            examKind.background = plate(if (main) R.color.brand_soft else R.color.card_header, 6)
            examKind.setTextColor(colour(if (main) R.color.brand_strong else R.color.md_on_surface_variant))
            examSubject.text = exam.subject
            // A room keeps its number: «ГУК 210» does not break before 210; a long place still wraps between words.
            examPlace.text = exam.place.replace(ROOM_NUMBER, Typography.nbsp.toString())
            examPlace.isVisible = exam.place.isNotEmpty()
            val pin = ContextCompat.getDrawable(context, R.drawable.ic_place)?.mutate()?.apply {
                setTint(colour(R.color.md_on_surface_variant))
                setBounds(0, 0, dp(16), dp(16))
            }
            examPlace.setCompoundDrawables(pin, null, null, null)
            if (main) {
                root.stroke = colour(R.color.brand_line)
                root.edge = colour(R.color.md_primary)
            }
            if (below) root.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = dp(8) }
        }.root

    private fun <T> rows(list: LinearLayout, items: List<T>, view: (T) -> View) {
        list.removeAllViews()
        items.forEachIndexed { index, item ->
            val row = view(item)
            if (index > 0) row.setBackgroundResource(R.drawable.timetable_divider)
            list.addView(row)
        }
    }

    private fun colour(@ColorRes id: Int) = ContextCompat.getColor(context, id)
    private fun dp(value: Int) = (value * density).roundToInt()
    private fun plate(@ColorRes id: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(colour(id))
        cornerRadius = radiusDp * density
    }

    private companion object {
        val ROOM_NUMBER = Regex(" (?=[0-9])")
        /** Below this width the site's course list puts the chips under the name. */
        const val NARROW_DP = 360

        @ColorRes fun fill(band: Band) = when (band) {
            Band.LOW -> R.color.band_low; Band.PASS -> R.color.band_pass; Band.GOOD -> R.color.band_good; Band.TOP -> R.color.band_top
        }
        @ColorRes fun soft(band: Band) = when (band) {
            Band.LOW -> R.color.band_low_soft; Band.PASS -> R.color.band_pass_soft
            Band.GOOD -> R.color.band_good_soft; Band.TOP -> R.color.band_top_soft
        }
        fun name(band: Band) = when (band) {
            Band.LOW -> R.string.report_band_low; Band.PASS -> R.string.report_band_pass
            Band.GOOD -> R.string.report_band_good; Band.TOP -> R.string.report_band_top
        }
        fun legend(band: Band) = when (band) {
            Band.LOW -> R.string.report_legend_low; Band.PASS -> R.string.report_legend_pass
            Band.GOOD -> R.string.report_legend_good; Band.TOP -> R.string.report_legend_top
        }
    }

    /** Dates in the app's language, on the phone's clock; times on the 24-hour clock, like the classes. */
    private class Dates(val locale: Locale, val zone: TimeZone, private val now: Long) {
        /** "23 ноября, 14:05", with the year when it is not this one. */
        fun moment(at: Long) = format(if (year(at) == year(now)) "dMMMMHHmm" else "dMMMMyHHmm", at)
        fun date(at: Long) = format(if (year(at) == year(now)) "dMMMM" else "dMMMMy", at)
        fun day(date: Long) = format("dMMMM", noon(date))
        fun weekday(date: Long) = format("EEEEdMMMM", noon(date)).replaceFirstChar { it.titlecase(locale) }
        fun dayOfMonth(date: Long) = format("d", noon(date))
        fun month(date: Long) = calendar(noon(date)).get(Calendar.MONTH)
        /** The weekday on its own ("Понедельник"), as an exam day's heading. */
        fun weekdayName(date: Long): String = SimpleDateFormat("cccc", locale).apply { timeZone = zone }.format(Date(noon(date)))
            .replaceFirstChar { it.titlecase(locale) }
        fun time(at: Long) = format("HHmm", at)

        private fun noon(date: Long) = Timetable.at(date, 12 * 60, zone)
        private fun calendar(at: Long) = Calendar.getInstance(zone).apply { timeInMillis = at }
        private fun year(at: Long) = calendar(at).get(Calendar.YEAR)
        private fun format(skeleton: String, at: Long): String =
            SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).apply { timeZone = zone }.format(Date(at))
    }
}
