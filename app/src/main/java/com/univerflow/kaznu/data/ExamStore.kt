package com.univerflow.kaznu.data

import android.content.Context
import android.util.AtomicFile
import com.univerflow.kaznu.domain.ExamItem
import com.univerflow.kaznu.domain.ExamKind
import com.univerflow.kaznu.domain.ExamPlan
import com.univerflow.kaznu.domain.Timetable
import com.univerflow.kaznu.web.PageJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The saved exams and midterms (noBackupFilesDir/exams.json), read by the exam reminders. Sign-out erases it; a
 * list handed over before the erase but written after it is dropped by [generation].
 */
object ExamStore {
    private const val NAME = "exams.json"
    private const val MAX_SIZE = 64 * 1024

    /** Grows with every [erase]: a write that began before it is not kept. */
    @Volatile var generation = 0
        private set

    private fun file(context: Context) = AtomicFile(File(context.applicationContext.noBackupFilesDir, NAME))

    @Synchronized fun read(context: Context): ExamPlan? = runCatching {
        val bytes = file(context).readFully()
        if (bytes.size > MAX_SIZE) return null
        PageJson.map(bytes.toString(Charsets.UTF_8), "", MAX_SIZE, maxDepth = 3)?.let(ExamPlan::restore)
    }.getOrNull()

    /** Replaces [kind]'s items with [items]; false when it came before a later [erase], or the file could not be written. */
    @Synchronized fun replace(context: Context, kind: ExamKind, items: List<ExamItem>, since: Int, now: Long = System.currentTimeMillis()): Boolean {
        if (since != generation) return false
        val plan = (read(context) ?: ExamPlan(now, emptyList())).replace(kind, items, now)
        val file = file(context)
        val stream = runCatching { file.startWrite() }.getOrNull() ?: return false
        return try {
            stream.write(json(plan).toString().toByteArray(Charsets.UTF_8))
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

    private fun json(plan: ExamPlan) = JSONObject().put("v", 1).put("saved", plan.saved)
        .put("items", JSONArray(plan.items.map { item ->
            JSONObject().put("kind", item.kind.wire).put("subject", item.subject)
                .put("start", Timetable.iso(item.date) + "T" + Timetable.clock(item.minute)).put("place", item.place)
        }))
}
