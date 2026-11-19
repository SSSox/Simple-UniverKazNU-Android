package com.univerflow.kaznu

import android.app.Notification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.univerflow.kaznu.data.AppPreferences
import com.univerflow.kaznu.domain.LessonTime
import com.univerflow.kaznu.domain.Timetable
import com.univerflow.kaznu.domain.TimetableLesson
import com.univerflow.kaznu.today.ClassReminders
import com.univerflow.kaznu.ui.context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.TimeZone

/** What a class reminder says, built from a made-up class (never the saved week) and not posted. */
@RunWith(AndroidJUnit4::class)
class ClassReminderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val words = AppPreferences(context).language.context(context)
    private val zone = TimeZone.getDefault()
    private val now = System.currentTimeMillis()
    private val lesson = TimetableLesson(2, 9 * 60, 9 * 60 + 50, "Культурология", "семинар", "233", "ФИТ", "Сарсембаев Р. М.", null, null)
    private val time = LessonTime(lesson, Timetable.epochDay(now, zone), now + 10 * 60_000L - 5_000L, now + 60 * 60_000L)

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE).toString()
    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    @Test
    fun namesTheClassItsRoomAndTheMinutesLeftButNotOnTheLockScreen() {
        val reminder = ClassReminders.notification(context, time, now, hidden = false)
        assertEquals(words.getString(R.string.reminder_title, 10, "Культурология"), reminder.title())
        assertTrue(reminder.text().contains(words.getString(R.string.widget_room, "233")))
        assertEquals(Notification.VISIBILITY_PRIVATE, reminder.visibility)
        val public = reminder.publicVersion
        assertEquals(words.getString(R.string.reminder_soon), public.title())
        assertFalse("the lock screen does not name the class", public.text().contains("Культурология"))
    }

    @Test
    fun withAPinOnlyTheTimeShows() {
        val reminder = ClassReminders.notification(context, time, now, hidden = true)
        assertEquals(words.getString(R.string.reminder_soon), reminder.title())
        assertEquals(words.getString(R.string.reminder_starts, "09:00"), reminder.text())
        assertFalse(reminder.title().contains("Культурология") || reminder.text().contains("233"))
    }
}
