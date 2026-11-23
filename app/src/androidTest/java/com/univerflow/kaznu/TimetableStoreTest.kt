package com.univerflow.kaznu

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.univerflow.kaznu.data.TimetableStore
import com.univerflow.kaznu.domain.Timetable
import com.univerflow.kaznu.domain.TimetableLesson
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The real file in noBackupFilesDir; the account's own saved week is put back after each test. */
@RunWith(AndroidJUnit4::class)
class TimetableStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = File(context.noBackupFilesDir, "timetable.json")
    private var original: ByteArray? = null

    @Before fun keep() { original = file.takeIf { it.exists() }?.readBytes() }

    @After fun restore() {
        original?.let { file.writeBytes(it) } ?: file.delete()
        File(file.path + ".new").delete()
        File(file.path + ".bak").delete()
    }

    private fun week(monday: Long) = Timetable("synthetic", monday, 1_700_000_000_000, listOf(
        TimetableLesson(0, 9 * 60, 9 * 60 + 50, "Subject $monday", "lecture", "309", "FIT", "Teacher", null, null)))

    // A second write used to read the file inside AtomicFile's write, which dropped the new copy.
    @Test fun everyLaterWeekReplacesTheSavedOne() {
        val first = 20_395L // a Monday (epoch day 0 was a Thursday)
        assertTrue(TimetableStore.write(context, week(first), TimetableStore.generation))
        assertEquals(first, TimetableStore.read(context)?.week)
        assertTrue(TimetableStore.write(context, week(first + 7), TimetableStore.generation))
        assertEquals(first + 7, TimetableStore.read(context)?.week)
        assertTrue(TimetableStore.write(context, week(first + 21), TimetableStore.generation))
        assertEquals(first + 21, TimetableStore.read(context)?.week)
    }
}
