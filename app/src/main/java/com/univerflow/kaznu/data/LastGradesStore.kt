package com.univerflow.kaznu.data

import android.content.Context
import com.univerflow.kaznu.domain.LastGrades
import com.univerflow.kaznu.web.PageJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The grade totals last seen (noBackupFilesDir/last-grades.json), shown by «Последние оценки» under a network error.
 * Unlike [GradeStore] it is written whenever the journal or Кабинет shows totals, with or without background alerts, and
 * keeps course names. One term at a time. Sign-out erases it; a write that began before the erase is dropped by
 * [generation].
 */
class LastGradesStore(private val path: File) {
    @Volatile var generation = 0
        private set

    private val temp = File(path.path + ".tmp")

    /** Null when nothing is saved, or the file is too large, torn or out of shape. */
    @Synchronized fun read(): LastGrades? = runCatching {
        if (path.length() > MAX_SIZE) return null
        val bytes = path.readBytes()
        PageJson.map(bytes.toString(Charsets.UTF_8), "", MAX_SIZE, maxDepth = 3)?.let(LastGrades::restore)
    }.getOrNull()

    /** False when [grades] came before a later [erase], or the file could not be written. */
    @Synchronized fun write(grades: LastGrades, since: Int): Boolean {
        if (since != generation) return false
        val bytes = json(grades).toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_SIZE) return false
        return try {
            temp.writeBytes(bytes)
            // rename(2) replaces the old file in one step.
            temp.renameTo(path)
        } catch (_: Exception) {
            runCatching { temp.delete() }
            false
        }
    }

    @Synchronized fun erase() {
        generation++
        runCatching { path.delete() }
        runCatching { temp.delete() }
    }

    private fun json(grades: LastGrades) = JSONObject().put("v", 1).put("term", grades.termKey).put("lang", grades.language)
        .put("saved", grades.saved).put("rows", JSONArray(grades.rows.map {
            JSONObject().put("course", it.course).put("label", it.label).put("value", it.value).put("max", it.max)
        }))

    companion object {
        private const val NAME = "last-grades.json"
        const val MAX_SIZE = 256 * 1024

        @Volatile private var shared: LastGradesStore? = null

        /** One store for the process, so [generation] holds across screens. */
        fun of(context: Context): LastGradesStore = shared ?: synchronized(this) {
            shared ?: LastGradesStore(File(context.applicationContext.noBackupFilesDir, NAME)).also { shared = it }
        }
    }
}
