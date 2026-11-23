package com.univerflow.kaznu

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.domain.ExportWeek
import com.univerflow.kaznu.domain.SchedulePair
import com.univerflow.kaznu.domain.Timetable
import com.univerflow.kaznu.domain.TimetableLesson
import com.univerflow.kaznu.report.ScheduleDrawing
import com.univerflow.kaznu.ui.context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The schedule picture of two made-up weeks, drawn into qa/schedule-*.png to look at: two columns, one per week, an unread week marked. */
@RunWith(AndroidJUnit4::class)
class ScheduleQaTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val qa get() = File(context.getExternalFilesDir(null), "qa").apply { mkdirs() }
    private fun day(iso: String) = Timetable.date(iso)!!
    private fun lesson(day: Int, start: Int, subject: String, room: String = "309") =
        TimetableLesson(day, start, start + 50, subject, "лекция", room, "ФИТ", "Учителева А. П.", null, null)
    private val first = ExportWeek(12, true, day("2026-11-16"), day("2026-11-16"), day("2026-11-22"),
        listOf(lesson(0, 9 * 60, "Политология"), lesson(0, 11 * 60, "Иностранный язык", "101"), lesson(2, 10 * 60, "Культурология")))
    private val second = ExportWeek(13, true, day("2026-11-23"), day("2026-11-23"), day("2026-11-29"), listOf(lesson(1, 9 * 60, "Физика")))

    @Test fun twoWeeksAreTwoColumnsAndAnUnreadWeekIsMarked() {
        for (language in AppLanguage.entries) {
            val both = File(qa, "schedule-" + language.siteCode + ".png")
            ScheduleDrawing(language.context(context), language, SchedulePair("2026/1", day("2026-12-20"), listOf(first, second), emptyList())).writePng(both)
            val image = BitmapFactory.decodeFile(both.path)
            assertEquals("two columns of 360 units and three margins of 20, twice", 2 * (2 * 360 + 3 * 20), image.width)
            assertTrue("the picture is not blank", (0 until image.height step 4).any { y -> (0 until image.width step 4).any { x -> image.getPixel(x, y) != android.graphics.Color.WHITE } })
            image.recycle()
            val part = File(qa, "schedule-part-" + language.siteCode + ".png")
            ScheduleDrawing(language.context(context), language, SchedulePair("2026/1", day("2026-12-20"), listOf(first), listOf(13))).writePng(part)
            assertEquals("an unread week is still a column", 2 * (2 * 360 + 3 * 20), BitmapFactory.decodeFile(part.path).width)
        }
    }
}
