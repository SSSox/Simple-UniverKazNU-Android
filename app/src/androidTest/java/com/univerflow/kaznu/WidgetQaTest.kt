package com.univerflow.kaznu

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.domain.Timetable
import com.univerflow.kaznu.domain.TimetableLesson
import com.univerflow.kaznu.today.TodayWidget
import com.univerflow.kaznu.ui.context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.TimeZone

/**
 * The «Сегодня» widget drawn from a made-up week (never the saved one) into qa/widget-*.png, on a grey stand-in for the
 * wallpaper, in the light and dark theme, with a PIN, without a saved week and after the day's classes. [HOME] is the
 * height a 3×2 widget gets on the Pixel emulator; every row drawn must fit in it whole.
 */
@RunWith(AndroidJUnit4::class)
class WidgetQaTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val zone = TimeZone.getTimeZone("Asia/Almaty")
    // Wednesday 18 November 2026.
    private val wednesday = 20_775L
    private val monday = wednesday - 2

    private fun lesson(day: Int, start: String, end: String, subject: String, type: String, room: String) = TimetableLesson(day,
        start.minutes(), end.minutes(), subject, type, room, "ФИТ", "Сарсембаев Р. М.", null, null)

    private fun String.minutes() = split(':').let { it[0].toInt() * 60 + it[1].toInt() }

    private val week = Timetable("qa", monday, Timetable.at(monday, 9 * 60, zone), listOf(
        lesson(2, "08:00", "08:50", "Политология", "лекция", "309"),
        lesson(2, "09:00", "09:50", "Культурология", "семинар", "233"),
        lesson(2, "10:00", "10:50", "Введение в информационную безопасность и защиту данных", "лабораторное занятие", "416а"),
        lesson(2, "11:00", "11:50", "Алгоритмы и структуры данных", "лекция", "Б-1"),
        lesson(2, "12:00", "12:50", "Английский язык", "практическое занятие", "120"),
        lesson(3, "08:00", "08:50", "Философия", "лекция", "309")))

    private fun themed(dark: Boolean): Context {
        val context = instrumentation.targetContext
        val configuration = Configuration(context.resources.configuration)
        configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        return context.createConfigurationContext(configuration)
    }

    private fun render(name: String, timetable: Timetable?, now: Long, dark: Boolean = false, hidden: Boolean = false, heightDp: Int = TALL,
        language: AppLanguage = AppLanguage.RUSSIAN, check: (View) -> Unit = {}) {
        instrumentation.runOnMainSync {
            val context = themed(dark)
            val density = context.resources.displayMetrics.density
            val width = (300 * density).toInt()
            val height = (heightDp * density).toInt()
            val parent = FrameLayout(context)
            val view = TodayWidget.draw(context, timetable, now, zone, language, hidden, heightDp).apply(context, parent)
            parent.addView(view, FrameLayout.LayoutParams(width, height))
            val margin = (16 * density).toInt()
            parent.measure(View.MeasureSpec.makeMeasureSpec(width + 2 * margin, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height + 2 * margin, View.MeasureSpec.EXACTLY))
            parent.setPadding(margin, margin, margin, margin)
            parent.measure(View.MeasureSpec.makeMeasureSpec(width + 2 * margin, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height + 2 * margin, View.MeasureSpec.EXACTLY))
            parent.layout(0, 0, parent.measuredWidth, parent.measuredHeight)
            val bitmap = Bitmap.createBitmap(parent.width, parent.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(if (dark) Color.rgb(48, 52, 58) else Color.rgb(196, 202, 210))
            parent.draw(canvas)
            // No row is cut by the widget's bottom edge.
            val bottom = view.findViewById<View>(R.id.widget_footer).top
            listOf(R.id.widget_row_1, R.id.widget_row_2, R.id.widget_row_3, R.id.widget_row_4).map { view.findViewById<View>(it) }
                .filter { it.visibility == View.VISIBLE }.forEach { row ->
                    val rowBottom = (row.parent as View).top + row.bottom
                    assertTrue("$name: a row ends at $rowBottom below the footer at $bottom", rowBottom <= bottom)
                }
            val folder = File(context.getExternalFilesDir(null), "qa").apply { mkdirs() }
            File(folder, "widget-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            check(view)
        }
    }

    private fun View.rows() = listOf(R.id.widget_row_1, R.id.widget_row_2, R.id.widget_row_3, R.id.widget_row_4).count { shown(it) }
    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()
    private fun View.shown(id: Int) = findViewById<View>(id).visibility == View.VISIBLE
    private fun words() = AppLanguage.RUSSIAN.context(instrumentation.targetContext)

    @Test
    fun drawsTheDayInBothThemesWithTheClassUnderWayMarked() {
        val now = Timetable.at(wednesday, 10 * 60 + 20, zone)
        listOf(false, true).forEach { dark ->
            render(if (dark) "dark" else "light", week, now, dark = dark) { view ->
                // Five classes, four rows: the finished first one gives way.
                assertEquals("Культурология", view.text(R.id.widget_subject_1))
                assertTrue(view.findViewById<View>(R.id.widget_row_2).contentDescription.toString().startsWith(words().getString(R.string.timetable_now)))
                assertTrue("the room comes first", view.text(R.id.widget_detail_2).startsWith(words().getString(R.string.widget_room, "416а")))
                assertFalse(view.shown(R.id.widget_note))
            }
        }
        render("kazakh", week, now, language = AppLanguage.KAZAKH)
        render("home", week, now, heightDp = HOME) { view -> assertEquals("a 3×2 widget holds three classes", 3, view.rows()) }
    }

    @Test
    fun aPinHidesEveryClassButItsTime() {
        render("pin", week, Timetable.at(wednesday, 10 * 60 + 20, zone), hidden = true) { view ->
            listOf(R.id.widget_subject_1, R.id.widget_subject_2, R.id.widget_subject_3, R.id.widget_subject_4).forEach {
                assertEquals(words().getString(R.string.widget_class), view.text(it))
            }
            listOf(R.id.widget_detail_1, R.id.widget_detail_2, R.id.widget_detail_3, R.id.widget_detail_4).forEach { assertFalse(view.shown(it)) }
            assertEquals("09:00", view.text(R.id.widget_start_1))
        }
    }

    @Test
    fun withoutASavedWeekItSaysWhereTheClassesComeFrom() {
        render("empty", null, Timetable.at(wednesday, 9 * 60, zone), heightDp = HOME) { view ->
            assertEquals(words().getString(R.string.widget_empty), view.text(R.id.widget_note))
            assertFalse(view.shown(R.id.widget_row_1))
            assertEquals("", view.text(R.id.widget_footer))
        }
    }

    @Test
    fun afterTheLastClassItShowsTomorrow() {
        render("tomorrow", week, Timetable.at(wednesday, 18 * 60, zone), heightDp = HOME) { view ->
            assertEquals(words().getString(R.string.widget_tomorrow), view.text(R.id.widget_title))
            assertEquals("Философия", view.text(R.id.widget_subject_1))
            assertFalse(view.shown(R.id.widget_row_2))
        }
        // Thursday has four classes here; with the note line two whole rows fit in a 3×2 widget.
        val long = week.copy(lessons = week.lessons + (1..3).map { lesson(3, "0${8 + it}:00", "0${8 + it}:50", "Пара $it", "лекция", "10$it") })
        render("tomorrow-full", long, Timetable.at(wednesday, 18 * 60, zone), heightDp = HOME) { view -> assertEquals(2, view.rows()) }
    }

    @Test
    fun aLargerTextSizeShowsFewerRows() {
        assertEquals(3, TodayWidget.rowsFor(HOME, note = false, fontScale = 1f))
        assertEquals(2, TodayWidget.rowsFor(HOME, note = true, fontScale = 1f))
        assertEquals(1, TodayWidget.rowsFor(HOME, note = true, fontScale = 2f))
        assertEquals(4, TodayWidget.rowsFor(null, note = true, fontScale = 1f))
    }

    private companion object {
        const val HOME = 210
        const val TALL = 290
    }
}
