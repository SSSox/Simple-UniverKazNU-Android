package com.univerflow.kaznu

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.domain.Timetable
import com.univerflow.kaznu.domain.TimetableLesson
import com.univerflow.kaznu.domain.TodayPlan
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
 * wallpaper, in the light and dark theme, with a PIN, without a saved week, after the day's classes and once the week has
 * run out. Its list gets the rows TodayWidgetService hands the launcher. [HOME] is the height a 3×2 widget gets on the
 * Pixel emulator, where the list scrolls.
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
        language: AppLanguage = AppLanguage.RUSSIAN, check: (View, List<View>) -> Unit = { _, _ -> }) {
        instrumentation.runOnMainSync {
            val context = themed(dark)
            val density = context.resources.displayMetrics.density
            val width = (300 * density).toInt()
            val height = (heightDp * density).toInt()
            val parent = FrameLayout(context)
            val view = TodayWidget.draw(context, timetable, now, zone, language, 0).apply(context, parent)
            // What the launcher does with TodayWidgetService's rows.
            val rows = TodayWidget.rows(context, timetable?.let { TodayPlan.list(it, now, zone) }, hidden, language, zone).map { it.apply(context, parent) }
            view.findViewById<ListView>(R.id.widget_list).adapter = object : BaseAdapter() {
                override fun getCount() = rows.size
                override fun getItem(position: Int) = rows[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, group: ViewGroup) = rows[position]
            }
            parent.addView(view, FrameLayout.LayoutParams(width, height))
            val margin = (16 * density).toInt()
            parent.setPadding(margin, margin, margin, margin)
            parent.measure(View.MeasureSpec.makeMeasureSpec(width + 2 * margin, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height + 2 * margin, View.MeasureSpec.EXACTLY))
            parent.layout(0, 0, parent.measuredWidth, parent.measuredHeight)
            val bitmap = Bitmap.createBitmap(parent.width, parent.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(if (dark) Color.rgb(48, 52, 58) else Color.rgb(196, 202, 210))
            parent.draw(canvas)
            val folder = File(context.getExternalFilesDir(null), "qa").apply { mkdirs() }
            File(folder, "widget-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            check(view, rows)
        }
    }

    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()
    private fun View.shown(id: Int) = findViewById<View>(id).visibility == View.VISIBLE
    private fun List<View>.subjects() = mapNotNull { it.findViewById<TextView>(R.id.widget_subject)?.text?.toString() }
    private fun words() = AppLanguage.RUSSIAN.context(instrumentation.targetContext)

    @Test
    fun drawsTheDayInBothThemesFromTheClassUnderWay() {
        val now = Timetable.at(wednesday, 10 * 60 + 20, zone)
        listOf(false, true).forEach { dark ->
            render(if (dark) "dark" else "light", week, now, dark = dark) { view, rows ->
                // The two finished classes are gone; the one under way leads and is marked; Thursday follows under its heading.
                assertEquals(listOf("Введение в информационную безопасность и защиту данных", "Алгоритмы и структуры данных", "Английский язык", "Философия"), rows.subjects().take(4))
                assertEquals("a week ahead: next Wednesday ends the list", "Английский язык", rows.subjects().last())
                assertTrue(rows[0].contentDescription.toString().startsWith(words().getString(R.string.timetable_now)))
                assertTrue("the room comes first", rows[0].findViewById<TextView>(R.id.widget_detail).text.startsWith(words().getString(R.string.widget_room, "416а")))
                assertTrue(rows.any { it.findViewById<TextView>(R.id.widget_day)?.text?.startsWith("Четверг") == true })
                assertFalse(view.shown(R.id.widget_note))
            }
        }
        render("kazakh", week, now, language = AppLanguage.KAZAKH)
        render("home", week, now, heightDp = HOME) { view, _ ->
            assertTrue("a 3×2 widget scrolls to the rest", view.findViewById<ListView>(R.id.widget_list).canScrollVertically(1))
        }
    }

    @Test
    fun aPinHidesEveryClassButItsTime() {
        render("pin", week, Timetable.at(wednesday, 10 * 60 + 20, zone), hidden = true) { _, rows ->
            assertTrue(rows.subjects().all { it == words().getString(R.string.widget_class) })
            assertTrue(rows.mapNotNull { it.findViewById<View>(R.id.widget_detail) }.none { it.visibility == View.VISIBLE })
            assertEquals("10:00", rows[0].findViewById<TextView>(R.id.widget_start).text.toString())
        }
    }

    @Test
    fun withoutASavedWeekItSaysWhereTheClassesComeFrom() {
        render("empty", null, Timetable.at(wednesday, 9 * 60, zone), heightDp = HOME) { view, rows ->
            assertEquals(words().getString(R.string.widget_empty), view.text(R.id.widget_note))
            assertTrue(rows.isEmpty())
            assertFalse(view.shown(R.id.widget_list))
            assertEquals("", view.text(R.id.widget_footer))
        }
    }

    @Test
    fun afterTheLastClassItShowsTomorrow() {
        render("tomorrow", week, Timetable.at(wednesday, 18 * 60, zone), heightDp = HOME) { view, rows ->
            assertEquals(words().getString(R.string.widget_tomorrow), view.text(R.id.widget_title))
            assertEquals("Философия", rows.subjects().first())
        }
    }

    @Test
    fun onceTheSavedWeekHasRunOutItKeepsShowingItsClasses() {
        // Three months on: an undated class is kept three weeks, so only the last saved schedule is left to show.
        val later = Timetable.at(wednesday + 91, 9 * 60, zone)
        render("last-known", week, later, heightDp = HOME) { view, rows ->
            assertEquals(words().getString(R.string.widget_last_known), view.text(R.id.widget_note))
            assertTrue(rows.subjects().contains("Политология"))
            assertTrue(view.text(R.id.widget_footer).isNotEmpty())
        }
    }

    private companion object {
        const val HOME = 210
        const val TALL = 290
    }
}
