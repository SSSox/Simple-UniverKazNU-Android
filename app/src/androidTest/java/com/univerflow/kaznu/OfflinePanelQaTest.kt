package com.univerflow.kaznu

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.univerflow.kaznu.databinding.ViewOfflinePanelBinding
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.domain.Band
import com.univerflow.kaznu.domain.ExamItem
import com.univerflow.kaznu.domain.ExamKind
import com.univerflow.kaznu.domain.ExamPlan
import com.univerflow.kaznu.domain.LastGrades
import com.univerflow.kaznu.domain.OfflineDigest
import com.univerflow.kaznu.domain.Timetable
import com.univerflow.kaznu.domain.TimetableLesson
import com.univerflow.kaznu.report.GradeReportDrawing
import com.univerflow.kaznu.ui.OfflinePanel
import com.univerflow.kaznu.ui.PlateLayout
import com.univerflow.kaznu.ui.context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * Draws the offline screen's saved data (synthetic, never the account's files) at the phone's width into
 * qa/offline-*.png, the way the site layer draws the same parts: light and dark, Kazakh and English, nothing saved,
 * long course names on a 320dp phone, and what is left once the app locks.
 */
@RunWith(AndroidJUnit4::class)
class OfflinePanelQaTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val zone: TimeZone = TimeZone.getTimeZone("Asia/Almaty")
    private fun at(day: Int, hour: Int, minute: Int = 0, month: Int = 11) =
        GregorianCalendar(zone).apply { clear(); set(2026, month - 1, day, hour, minute) }.timeInMillis
    private val monday = Timetable.epochDay(at(16, 12), zone)
    private val now = at(16, 11, 20)

    private fun lesson(start: Int, subject: String, type: String, room: String) =
        TimetableLesson(0, start, start + 50, subject, type, room, "ФИТ", "Сарсембаев Р. М.", null, null)

    private val week = Timetable("qa", monday, at(16, 8, 5), listOf(
        lesson(9 * 60, "Политология", "лекция", "309"), lesson(11 * 60, "Культурология", "семинар", "233"),
        lesson(13 * 60, "Алгоритмы и структуры данных", "лабораторное занятие", "416а")))

    private val grades = LastGrades("2026/1", "ru", at(16, 8, 5), listOf(
        LastGrades.Row("Социология", "РК1", "85", "100"), LastGrades.Row("Социология", "РК2", "0", "100"),
        LastGrades.Row("Физика", "РК1", "12", "25"), LastGrades.Row("Физика", "РК2", "20", "25"),
        LastGrades.Row("Английский язык", "РК1", "94", "100"), LastGrades.Row("Английский язык", "РК2", "63", "100"),
        LastGrades.Row("Физическая культура", "Зачёт", "зачтено", "")))

    private val exams = ExamPlan(at(14, 18), listOf(
        ExamItem(ExamKind.MIDTERM, "Физика", Timetable.epochDay(at(20, 12), zone), 10 * 60, "ГУК 210"),
        ExamItem(ExamKind.EXAM, "Социология", Timetable.epochDay(at(15, 12, month = 12), zone), 13 * 60, "ФИТ · Жёлтый зал"),
        ExamItem(ExamKind.EXAM, "История Казахстана", Timetable.epochDay(at(15, 12, month = 12), zone), 9 * 60, "")))

    private class Drawn(val root: LinearLayout, val views: ViewOfflinePanelBinding, val panel: OfflinePanel, val shownChanges: IntArray,
        val weekOpened: BooleanArray)

    /** The app's context with the night mode and, for a narrow phone, the screen width the resources see. */
    private fun themed(dark: Boolean, widthDp: Int? = null): Context {
        val context = instrumentation.targetContext
        val configuration = Configuration(context.resources.configuration)
        configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        widthDp?.let { configuration.screenWidthDp = it }
        return context.createConfigurationContext(configuration)
    }

    private fun snapshot(name: String, root: View, width: Int) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        root.layout(0, 0, width, root.measuredHeight)
        val bitmap = Bitmap.createBitmap(width, maxOf(1, root.measuredHeight), Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "qa").apply { mkdirs() }
        File(folder, "offline-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun draw(name: String, digest: OfflineDigest, dark: Boolean = false, language: AppLanguage = AppLanguage.RUSSIAN,
        widthDp: Int? = null, act: (ViewOfflinePanelBinding) -> Unit = {}): Drawn {
        lateinit var drawn: Drawn
        instrumentation.runOnMainSync {
            val base = themed(dark, widthDp)
            val context = ContextThemeWrapper(base, R.style.Theme_UniverKazNUSimple)
            val density = context.resources.displayMetrics.density
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(ContextCompat.getColor(context, R.color.md_background))
                val side = (16 * density).toInt()
                setPadding(side, 0, side, (32 * density).toInt())
            }
            val views = ViewOfflinePanelBinding.inflate(LayoutInflater.from(context), root, true)
            val changes = IntArray(1)
            val opened = BooleanArray(1)
            val panel = OfflinePanel(views, shownChanged = { changes[0]++ }, openWeek = { opened[0] = true })
            panel.show(digest, language.context(base), language, now, zone)
            act(views)
            snapshot(name, root, widthDp?.let { (it * density).toInt() } ?: context.resources.displayMetrics.widthPixels)
            drawn = Drawn(root, views, panel, changes, opened)
        }
        return drawn
    }

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun children(group: ViewGroup) = (0 until group.childCount).map { group.getChildAt(it) }
    private fun course(views: ViewOfflinePanelBinding, index: Int) = views.offlineGradesList.getChildAt(index) as LinearLayout
    private fun chips(row: View) = children(row.findViewById(R.id.course_scores)).map { it as PlateLayout }
    private fun colour(context: Context, id: Int) = ContextCompat.getColor(context, id)

    @Test fun drawsTheGradesTheDayAndTheExamsLikeTheSite() {
        val digest = OfflineDigest.of(grades, week, exams, now, zone)
        val drawn = draw("light", digest) { it.offlineWeek.performClick() }
        val views = drawn.views
        val light = themed(false)
        val title = views.offlineGradesTitle.text.toString()
        assertTrue(title, title.startsWith("Оценки на 16 ноября") && title.endsWith("08:05"))
        assertEquals(listOf("Социология", "Физика", "Английский язык", "Физическая культура"),
            children(views.offlineGradesList).map { it.findViewById<TextView>(R.id.course_name).text.toString() })

        // 12 of 25 is short of half, 20 of 25 is good: each chip takes its band's ground and a bar as long as the score.
        val physics = chips(course(views, 1))
        assertEquals(listOf("РК1", "12", "/25"), texts(physics[0]))
        assertEquals(colour(light, R.color.band_low_soft), physics[0].fill)
        assertEquals(colour(light, R.color.band_low), physics[0].foot)
        assertEquals(0.48f, physics[0].footShare, 0.001f)
        assertNull("a band chip has no hairline", physics[0].stroke)
        assertEquals(colour(light, R.color.band_good), physics[1].foot)
        assertEquals("Физика: РК1, 12 / 25, Недобор; РК2, 20 / 25, Хорошо", course(views, 1).contentDescription)
        // Out of 100 the maximum is not repeated; a zero is still a score, in the lowest band with no bar to draw.
        val sociology = chips(course(views, 0))
        assertEquals(listOf("РК1", "85", ""), texts(sociology[0]))
        assertEquals(colour(light, R.color.band_low_soft), sociology[1].fill)
        assertEquals(0f, sociology[1].footShare)
        assertEquals(colour(light, R.color.band_top), chips(course(views, 2))[0].foot)
        // A grade without a number or a maximum has no band: the card's ground, a hairline and no bar.
        val credit = chips(course(views, 3)).single()
        assertNull(credit.foot)
        assertEquals(colour(light, R.color.card_surface), credit.fill)
        assertEquals(4, views.offlineBandsList.childCount)
        assertTrue(views.offlineBands.contentDescription.startsWith("Цвета баллов. ниже 50"))

        assertTrue(views.offlineDayTitle.text.startsWith("Сегодня, 16 ноября"))
        assertEquals(3, views.offlineLessons.childCount)
        assertTrue("the 11:00 class is under way", views.offlineLessons.getChildAt(1).findViewById<View>(R.id.lesson_now).isVisible)
        assertFalse(views.offlineLessons.getChildAt(0).findViewById<View>(R.id.lesson_now).isVisible)

        // Exam days as on the exam timetable: Friday 20 November four days on, then 15 December with two exams in order.
        val days = children(views.offlineExamDays)
        assertEquals(2, days.size)
        assertEquals(listOf("20", "ноя", "Пятница", "через 4 дня"), listOf(R.id.exam_date_number, R.id.exam_date_month,
            R.id.exam_weekday, R.id.exam_relative).map { days[0].findViewById<TextView>(it).text.toString() })
        assertEquals("через 29 дней", days[1].findViewById<TextView>(R.id.exam_relative).text.toString())
        val midterm = children(days[0].findViewById(R.id.exam_slots)).single() as PlateLayout
        assertEquals(listOf("10:00", "Рубежный контроль", "Физика", "ГУК 210"), texts(midterm))
        assertNull("a midterm stays quiet", midterm.edge)
        val december = children(days[1].findViewById(R.id.exam_slots)).map { it as PlateLayout }
        assertEquals(listOf("История Казахстана", "Социология"), december.map { it.findViewById<TextView>(R.id.exam_subject).text.toString() })
        assertEquals("an exam carries the brand's edge", colour(light, R.color.md_primary), december[0].edge)
        assertEquals(colour(light, R.color.brand_line), december[0].stroke)
        assertFalse("no room, no pin", december[0].findViewById<View>(R.id.exam_place).isVisible)

        assertTrue("saved data shown", drawn.panel.shown)
        assertEquals(1, drawn.shownChanges[0])
        assertTrue(drawn.weekOpened[0])

        // The dark theme takes the site's dark band tokens from the panel's own context.
        val dark = draw("dark", digest, dark = true)
        assertEquals(0xFF2A2020.toInt(), chips(course(dark.views, 1))[0].fill)
        assertEquals(0xFF558C6C.toInt(), chips(course(dark.views, 1))[1].foot)
    }

    @Test fun lightBandColoursAreTheReportsColours() {
        val light = themed(false)
        val fills = mapOf(Band.LOW to R.color.band_low, Band.PASS to R.color.band_pass, Band.GOOD to R.color.band_good, Band.TOP to R.color.band_top)
        val softs = mapOf(Band.LOW to R.color.band_low_soft, Band.PASS to R.color.band_pass_soft, Band.GOOD to R.color.band_good_soft,
            Band.TOP to R.color.band_top_soft)
        Band.entries.forEach { band ->
            assertEquals(band.name, GradeReportDrawing.colour(band), colour(light, fills.getValue(band)))
            assertEquals(band.name, GradeReportDrawing.soft(band), colour(light, softs.getValue(band)))
        }
    }

    @Test fun theSameDataAgainLeavesTheRowsAloneAndLockingEmptiesEverything() {
        val digest = OfflineDigest.of(grades, week, exams, now, zone)
        val drawn = draw("again", digest)
        var same = false
        instrumentation.runOnMainSync {
            val first = drawn.views.offlineGradesList.getChildAt(0)
            drawn.panel.show(digest, AppLanguage.RUSSIAN.context(themed(false)), AppLanguage.RUSSIAN, now, zone)
            same = first === drawn.views.offlineGradesList.getChildAt(0)
            // What the lock (and sign-out) leaves: the panel is emptied, so nothing saved is drawn under the PIN pad.
            drawn.panel.clear()
            snapshot("locked", drawn.root, drawn.root.width)
        }
        assertTrue("an automatic retry must not redraw", same)
        assertEquals("shown, then cleared", 2, drawn.shownChanges[0])
        assertFalse(drawn.panel.shown)
        assertFalse(drawn.views.root.isVisible)
        val views = drawn.views
        assertEquals(0, listOf(views.offlineGradesList, views.offlineBandsList, views.offlineLessons, views.offlineExamDays).sumOf { it.childCount })
        assertEquals("", views.offlineGradesTitle.text.toString())
        assertEquals("", views.offlineBandsTitle.text.toString())
        assertNull(views.offlineBands.contentDescription)
    }

    @Test fun anExamTodayFillsItsTileAndAnEmptyWeekKeepsOnlyTheHeader() {
        val friday = at(20, 8)
        val drawn = draw("today", OfflineDigest.of(null, week.copy(lessons = emptyList()), exams, friday, zone), dark = true)
        val day = drawn.views.offlineExamDays.getChildAt(0)
        assertEquals("Сегодня", day.findViewById<TextView>(R.id.exam_relative).text.toString())
        assertEquals(colour(themed(true), R.color.brand_fill), day.findViewById<PlateLayout>(R.id.exam_date).fill)
        assertNull(day.findViewById<PlateLayout>(R.id.exam_date).stroke)
        // No class within a week: the card keeps its header with the week button, and no rows.
        assertTrue(drawn.views.offlineDay.isVisible)
        assertEquals(AppLanguage.RUSSIAN.context(instrumentation.targetContext).getString(R.string.widget_none_week),
            drawn.views.offlineDayTitle.text.toString())
        assertFalse(drawn.views.offlineLessons.isVisible)
    }

    @Test fun nothingSavedSaysHowToKeepGradesAndHidesTheRest() {
        val drawn = draw("empty", OfflineDigest.of(null, null, null, now, zone))
        assertFalse("no saved data: the window may be captured as before", drawn.panel.shown)
        assertTrue(drawn.views.root.isVisible)
        assertFalse(drawn.views.offlineGradesList.isVisible)
        assertFalse(drawn.views.offlineBands.isVisible)
        assertEquals(AppLanguage.RUSSIAN.context(instrumentation.targetContext).getString(R.string.last_grades_none),
            drawn.views.offlineGradesEmpty.text.toString())
        assertFalse(drawn.views.offlineDay.isVisible)
        assertFalse(drawn.views.offlineExams.isVisible)
    }

    @Test fun otherLanguagesAndLongNamesFitThePhone() {
        val digest = OfflineDigest.of(grades, week, exams, at(16, 18), zone)
        val kk = draw("kk", digest, language = AppLanguage.KAZAKH)
        assertTrue(kk.views.offlineGradesTitle.text.endsWith("бойынша бағалар"))
        assertEquals("4 күннен кейін", kk.views.offlineExamDays.getChildAt(0).findViewById<TextView>(R.id.exam_relative).text.toString())
        val en = draw("en-dark", digest, dark = true, language = AppLanguage.ENGLISH)
        assertTrue(en.views.offlineGradesTitle.text.startsWith("Grades as of"))
        // Every class of this week is on Monday: after them the next is a week on.
        assertTrue(en.views.offlineDayTitle.text.toString(), en.views.offlineDayTitle.text.startsWith("Monday"))
        assertEquals("in 4 days", en.views.offlineExamDays.getChildAt(0).findViewById<TextView>(R.id.exam_relative).text.toString())
        assertEquals("Score colours", en.views.offlineBandsTitle.text.toString())

        val long = "Теория вероятностей и математическая статистика для специальностей информационной безопасности"
        val many = LastGrades("2026/1", "ru", at(16, 8, 5), (1..8).flatMap { course ->
            listOf(LastGrades.Row("$long $course", "РК1", "85", "100"), LastGrades.Row("$long $course", "РК2", "71", "100"),
                LastGrades.Row("$long $course", "Экзамен", "90", "100"), LastGrades.Row("$long $course", "Итог", "82,5", "100"))
        } + LastGrades.Row("Право", "РК1", "77", "100") + LastGrades.Row("Право", "РК2", "64", "100"))
        val narrow = draw("long-320", OfflineDigest.of(many, week, exams, now, zone), widthDp = 320)
        assertEquals(9, narrow.views.offlineGradesList.childCount)
        children(narrow.views.offlineGradesList).forEach { row ->
            row as LinearLayout
            // On a 320dp phone the name goes over its chips, and the chips wrap inside the card.
            assertEquals(LinearLayout.VERTICAL, row.orientation)
            assertTrue("a long course name wraps", row.findViewById<TextView>(R.id.course_name).lineCount >= 1)
            val group = row.findViewById<ViewGroup>(R.id.course_scores)
            assertTrue(group.right <= row.width - row.paddingEnd)
            chips(row).forEach { chip -> assertTrue("a chip stays inside the card", chip.right <= group.width && chip.left >= 0) }
        }
        val four = chips(course(narrow.views, 0))
        assertTrue("four chips need two lines at 320dp", four.map { it.top }.distinct().size > 1)
        assertTrue(course(narrow.views, 0).findViewById<TextView>(R.id.course_name).lineCount > 1)
        // The week button keeps its full label beside the day.
        assertEquals(1, narrow.views.offlineWeek.lineCount)
        // At the phone's own width two chips sit beside the name, more than two go under it.
        val wide = draw("many", OfflineDigest.of(many, week, exams, now, zone))
        assertEquals(LinearLayout.VERTICAL, course(wide.views, 0).orientation)
        assertEquals(LinearLayout.HORIZONTAL, course(wide.views, 8).orientation)
    }
}
