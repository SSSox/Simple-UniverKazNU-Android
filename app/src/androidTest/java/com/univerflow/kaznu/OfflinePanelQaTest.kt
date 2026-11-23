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
import com.univerflow.kaznu.domain.ExamItem
import com.univerflow.kaznu.domain.ExamKind
import com.univerflow.kaznu.domain.ExamPlan
import com.univerflow.kaznu.domain.LastGrades
import com.univerflow.kaznu.domain.OfflineDigest
import com.univerflow.kaznu.domain.Timetable
import com.univerflow.kaznu.domain.TimetableLesson
import com.univerflow.kaznu.ui.OfflinePanel
import com.univerflow.kaznu.ui.context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * Draws the offline screen's saved data (synthetic, never the account's files) at the phone's width into
 * qa/offline-*.png: light and dark, Kazakh and English, nothing saved, and long course names.
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
        LastGrades.Row("Социология", "РК1", "85", "100"), LastGrades.Row("Социология", "РК2", "71", "100"),
        LastGrades.Row("Физика", "РК1", "12", "25"), LastGrades.Row("Физика", "РК2", "20", "25"),
        LastGrades.Row("Английский язык", "РК1", "94", "100")))

    private val exams = ExamPlan(at(14, 18), listOf(
        ExamItem(ExamKind.MIDTERM, "Физика", Timetable.epochDay(at(20, 12), zone), 10 * 60, "ГУК 210"),
        ExamItem(ExamKind.EXAM, "Социология", Timetable.epochDay(at(15, 12, month = 12), zone), 13 * 60, "")))

    private class Drawn(val bitmap: Bitmap, val views: ViewOfflinePanelBinding, val panel: OfflinePanel, val shownChanges: IntArray, val weekOpened: BooleanArray)

    private fun themed(dark: Boolean): Context {
        val context = instrumentation.targetContext
        val configuration = Configuration(context.resources.configuration)
        configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        return context.createConfigurationContext(configuration)
    }

    private fun draw(name: String, digest: OfflineDigest, dark: Boolean = false, language: AppLanguage = AppLanguage.RUSSIAN,
        widthDp: Int? = null, act: (ViewOfflinePanelBinding) -> Unit = {}): Drawn {
        lateinit var drawn: Drawn
        instrumentation.runOnMainSync {
            val base = themed(dark)
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
            val width = widthDp?.let { (it * density).toInt() } ?: context.resources.displayMetrics.widthPixels
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            root.layout(0, 0, width, root.measuredHeight)
            val bitmap = Bitmap.createBitmap(width, root.measuredHeight, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "qa").apply { mkdirs() }
            File(folder, "offline-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            drawn = Drawn(bitmap, views, panel, changes, opened)
        }
        return drawn
    }

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun showsTheGradesWithTheirDateTheDayAndTheExamsAhead() {
        val digest = OfflineDigest.of(grades, week, exams, now, zone)
        val drawn = draw("light", digest) { it.offlineWeek.performClick() }
        val views = drawn.views
        val title = views.offlineGradesTitle.text.toString()
        assertTrue(title, title.startsWith("Оценки на 16 ноября") && title.endsWith("08:05"))
        assertEquals(listOf("Социология", "Физика", "Английский язык"), (0 until views.offlineGradesList.childCount)
            .map { (views.offlineGradesList.getChildAt(it).findViewById<TextView>(R.id.grade_course)).text.toString() })
        assertTrue(texts(views.offlineGradesList).contains("РК1 12/25  ·  РК2 20/25"))
        assertTrue(views.offlineDayTitle.text.startsWith("Сегодня, 16 ноября"))
        assertEquals(3, views.offlineLessons.childCount)
        val current = views.offlineLessons.getChildAt(1)
        assertTrue("the 11:00 class is under way", current.findViewById<View>(R.id.lesson_now).isVisible)
        assertFalse(views.offlineLessons.getChildAt(0).findViewById<View>(R.id.lesson_now).isVisible)
        assertEquals(2, views.offlineExamsList.childCount)
        assertTrue(texts(views.offlineExamsList).any { it.contains("рубежный контроль") && it.contains("ГУК 210") })
        assertTrue("saved data shown", drawn.panel.shown)
        assertEquals(1, drawn.shownChanges[0])
        assertTrue(drawn.weekOpened[0])
        draw("dark", digest, dark = true)
    }

    @Test fun theSameDataAgainLeavesTheRowsAloneAndClearingEmptiesEverything() {
        val digest = OfflineDigest.of(grades, week, exams, now, zone)
        val drawn = draw("again", digest)
        var same = false
        instrumentation.runOnMainSync {
            val first = drawn.views.offlineGradesList.getChildAt(0)
            drawn.panel.show(digest, AppLanguage.RUSSIAN.context(themed(false)), AppLanguage.RUSSIAN, now, zone)
            same = first === drawn.views.offlineGradesList.getChildAt(0)
            drawn.panel.clear()
        }
        assertTrue("an automatic retry must not redraw", same)
        assertEquals("shown, then cleared", 2, drawn.shownChanges[0])
        assertFalse(drawn.panel.shown)
        assertFalse(drawn.views.root.isVisible)
        assertEquals(0, drawn.views.offlineGradesList.childCount + drawn.views.offlineLessons.childCount + drawn.views.offlineExamsList.childCount)
        assertEquals("", drawn.views.offlineGradesTitle.text.toString())
    }

    @Test fun nothingSavedSaysHowToKeepGradesAndHidesTheRest() {
        val drawn = draw("empty", OfflineDigest.of(null, null, null, now, zone))
        assertFalse("no saved data: the window may be captured as before", drawn.panel.shown)
        assertTrue(drawn.views.root.isVisible)
        assertFalse(drawn.views.offlineGradesCard.isVisible)
        assertEquals(AppLanguage.RUSSIAN.context(instrumentation.targetContext).getString(R.string.last_grades_none),
            drawn.views.offlineGradesNote.text.toString())
        assertFalse(drawn.views.offlineDay.isVisible)
        assertFalse(drawn.views.offlineExams.isVisible)
    }

    @Test fun otherLanguagesAndLongNamesFitThePhone() {
        val digest = OfflineDigest.of(grades, week, exams, at(16, 18), zone)
        val kk = draw("kk", digest, language = AppLanguage.KAZAKH)
        assertTrue(kk.views.offlineGradesTitle.text.endsWith("бойынша бағалар"))
        val en = draw("en-dark", digest, dark = true, language = AppLanguage.ENGLISH)
        assertTrue(en.views.offlineGradesTitle.text.startsWith("Grades as of"))
        // Every class of this week is on Monday: after them the next is a week on.
        assertTrue(en.views.offlineDayTitle.text.toString(), en.views.offlineDayTitle.text.startsWith("Monday"))

        val long = "Теория вероятностей и математическая статистика для специальностей информационной безопасности"
        val many = LastGrades("2026/1", "ru", at(16, 8, 5), (1..8).flatMap { course ->
            listOf(LastGrades.Row("$long $course", "РК1", "85", "100"), LastGrades.Row("$long $course", "РК2", "71", "100"),
                LastGrades.Row("$long $course", "Экзамен", "90", "100"))
        })
        val narrow = draw("long-320", OfflineDigest.of(many, week, exams, now, zone), widthDp = 320)
        val title = narrow.views.offlineGradesList.getChildAt(0).findViewById<TextView>(R.id.grade_course)
        assertTrue("a long course name wraps", title.lineCount > 1)
        assertEquals(8, narrow.views.offlineGradesList.childCount)
        // The week button keeps its full label beside the day.
        assertEquals(1, narrow.views.offlineWeek.lineCount)
    }
}
