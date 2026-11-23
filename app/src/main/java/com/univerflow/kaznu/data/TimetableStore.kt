package com.univerflow.kaznu.data

import android.content.Context
import android.util.AtomicFile
import com.univerflow.kaznu.domain.Timetable
import com.univerflow.kaznu.domain.TimetableLesson
import com.univerflow.kaznu.web.PageJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The saved week (noBackupFilesDir/timetable.json), read by the widget, class reminders and the offline schedule.
 * Sign-out erases it; a week handed over before the erase but written after it is dropped by [generation].
 */
object TimetableStore {
    private const val NAME = "timetable.json"
    private const val MAX_SIZE = 192 * 1024

    /** Grows with every [erase]: a write that began before it is not kept. */
    @Volatile var generation = 0
        private set

    private fun file(context: Context) = AtomicFile(File(context.applicationContext.noBackupFilesDir, NAME))

    @Synchronized fun read(context: Context): Timetable? = runCatching {
        val bytes = file(context).readFully()
        if (bytes.size > MAX_SIZE) return null
        PageJson.map(bytes.toString(Charsets.UTF_8), "", MAX_SIZE, maxDepth = 3)?.let(Timetable::restore)
    }.getOrNull()

    /** False when [timetable] came before a later [erase], or the file could not be written. */
    @Synchronized fun write(context: Context, timetable: Timetable, since: Int): Boolean {
        if (since != generation) return false
        val file = file(context)
        val stream = runCatching { file.startWrite() }.getOrNull() ?: return false
        return try {
            stream.write(json(timetable).toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
            true
        } catch (_: Exception) {
            file.failWrite(stream)
            false
        }
    }

    @Synchronized fun erase(context: Context) {
        generation++
        runCatching { file(context).delete() }
    }

    private fun json(timetable: Timetable) = JSONObject().put("v", 1).put("week", Timetable.iso(timetable.week))
        .put("saved", timetable.saved).put("owner", timetable.owner)
        .put("lessons", lessons(timetable.lessons))
        // Absent in a copy with no neighbouring week, as in files written before the odd and even weeks were kept.
        .apply { timetable.alternate?.let { put("second", JSONObject().put("week", Timetable.iso(it.week)).put("lessons", lessons(it.lessons))) } }

    private fun lessons(list: List<TimetableLesson>) = JSONArray(list.map { lesson ->
        JSONObject().put("day", lesson.day).put("start", Timetable.clock(lesson.start)).put("end", Timetable.clock(lesson.end))
            .put("subject", lesson.subject).put("type", lesson.type).put("room", lesson.room).put("faculty", lesson.faculty)
            .put("teacher", lesson.teacher).put("from", lesson.from?.let(Timetable::iso) ?: JSONObject.NULL)
            .put("until", lesson.until?.let(Timetable::iso) ?: JSONObject.NULL)
    })
}
