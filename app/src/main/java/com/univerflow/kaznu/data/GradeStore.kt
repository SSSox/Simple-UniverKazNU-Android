package com.univerflow.kaznu.data

import android.content.Context
import android.util.AtomicFile
import com.univerflow.kaznu.domain.GradeTotals
import com.univerflow.kaznu.web.PageJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The grades last seen, for «Следить за оценками в фоне» (noBackupFilesDir/grades.json): for each of the last [TERMS]
 * terms, the site language and every total. The journal page replaces its term whenever the student looks; a
 * background check compares with it, then replaces it. Kept only while tracking is on; erased when it is turned off,
 * on pause and on sign-out.
 *
 * [generation] drops a write that began before an erase; [version] lets a check that began before the page's own write
 * leave that write alone.
 */
class GradeStore(context: Context, name: String = NAME) {
    // A file, not the context: the shared store outlives every screen.
    private val path = File(context.applicationContext.noBackupFilesDir, name)

    private class Entry(val term: String, val language: String, val values: Map<String, String>)

    @Volatile var generation = 0
        private set
    @Volatile var version = 0
        private set

    private fun file() = AtomicFile(path)

    /** What [totals]'s term showed last time, in the same site language; null when there is nothing to compare. */
    @Synchronized fun base(totals: GradeTotals): Map<String, String>? =
        read().firstOrNull { it.term == totals.termKey }?.takeIf { it.language == totals.language }?.values

    /**
     * Keeps [totals] as its term's base. False, keeping nothing, when an erase came after [since] (a [generation]) or
     * another write after [after] (a [version]), or the file could not be written.
     */
    @Synchronized fun keep(totals: GradeTotals, since: Int, after: Int? = null): Boolean {
        if (since != generation || after != null && after != version) return false
        val entries = read().filter { it.term != totals.termKey } + Entry(totals.termKey, totals.language, totals.values())
        val file = file()
        val stream = runCatching { file.startWrite() }.getOrNull() ?: return false
        return try {
            stream.write(json(entries.takeLast(TERMS)).toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
            version++
            true
        } catch (_: Exception) {
            file.failWrite(stream)
            false
        }
    }

    @Synchronized fun erase() {
        generation++
        version++
        runCatching { file().delete() }
    }

    private fun read(): List<Entry> = runCatching {
        val bytes = file().readFully()
        if (bytes.size > MAX_SIZE) return emptyList()
        val map = PageJson.map(bytes.toString(Charsets.UTF_8), "", MAX_SIZE, maxDepth = 3) ?: return emptyList()
        (map["terms"] as? List<*>).orEmpty().mapNotNull { item ->
            val entry = item as? Map<*, *> ?: return@mapNotNull null
            val values = (entry["values"] as? Map<*, *>)?.entries?.mapNotNull { (key, value) ->
                if (key is String && value is String) key to value else null
            }?.toMap() ?: return@mapNotNull null
            Entry(entry["term"] as? String ?: return@mapNotNull null, entry["lang"] as? String ?: return@mapNotNull null, values)
        }
    }.getOrDefault(emptyList())

    private fun json(entries: List<Entry>) = JSONObject().put("v", 1).put("terms", JSONArray(entries.map { entry ->
        JSONObject().put("term", entry.term).put("lang", entry.language).put("values", JSONObject(entry.values))
    }))

    companion object {
        private const val NAME = "grades.json"
        private const val MAX_SIZE = 256 * 1024
        private const val TERMS = 3
        @Volatile private var shared: GradeStore? = null

        /** The one store the page, the background check and sign-out share, so their [generation] and [version] agree. */
        fun of(context: Context): GradeStore = shared ?: synchronized(this) {
            shared ?: GradeStore(context.applicationContext).also { shared = it }
        }
    }
}
