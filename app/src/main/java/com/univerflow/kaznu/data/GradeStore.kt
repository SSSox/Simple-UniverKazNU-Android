package com.univerflow.kaznu.data

import android.content.Context
import android.util.AtomicFile
import com.univerflow.kaznu.domain.GradeReport
import com.univerflow.kaznu.domain.GradeTotals
import com.univerflow.kaznu.web.PageJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The grades last seen, for «Следить за оценками в фоне» (noBackupFilesDir/grades.json): for each of the last [TERMS]
 * terms, the site language, the course names and every total. The journal page replaces its term whenever the student
 * looks; a background check compares with it, then replaces it. Kept only while tracking is on; erased when it is
 * turned off, on pause and on sign-out.
 *
 * [generation] drops a write that began before an erase; [version] lets a check that began before the page's own write
 * leave that write alone.
 */
class GradeStore internal constructor(private val path: File, private val useAtomicFile: Boolean) {
    // A file, not the context: the shared store outlives every screen.
    constructor(context: Context, name: String = NAME) :
        this(File(context.applicationContext.noBackupFilesDir, name), useAtomicFile = true)

    private class Entry(val term: String, val language: String, val values: Map<String, String>, val courses: Set<String>?)

    @Volatile var generation = 0
        private set
    @Volatile var version = 0
        private set

    private fun file() = AtomicFile(path)

    /** What [totals]'s term showed last time, in the same site language; null when there is nothing to compare. */
    @Synchronized fun base(totals: GradeTotals): Map<String, String>? = baseSnapshot(totals)?.values

    /** Same baseline with its stored course names (falling back to the keys in older stored files). */
    @Synchronized internal fun baseSnapshot(totals: GradeTotals): GradeTotals.Base? =
        read().firstOrNull { it.term == totals.termKey }?.takeIf { it.language == totals.language }?.let { entry ->
            if (entry.courses != null) GradeTotals.Base(entry.values, entry.courses) else GradeTotals.Base(entry.values)
        }

    /**
     * Keeps [totals] as its term's base. False, keeping nothing, when an erase came after [since] (a [generation]) or
     * another write after [after] (a [version]), or the file could not be written.
     */
    @Synchronized fun keep(totals: GradeTotals, since: Int, after: Int? = null): Boolean {
        if (since != generation || after != null && after != version) return false
        val entries = read().filter { it.term != totals.termKey } +
            Entry(totals.termKey, totals.language, totals.values(), totals.courseNames())
        val payload = json(entries.takeLast(TERMS)).toString().toByteArray(Charsets.UTF_8)
        if (!writeBytes(payload)) return false
        version++
        return true
    }

    @Synchronized fun erase() {
        generation++
        version++
        if (useAtomicFile) runCatching { file().delete() } else runCatching { path.delete() }
    }

    private fun readBytes(): ByteArray? = if (useAtomicFile) {
        file().readFully()
    } else {
        if (!path.isFile || path.length() > MAX_SIZE) null else path.readBytes()
    }

    private fun writeBytes(bytes: ByteArray): Boolean = if (useAtomicFile) {
        val file = file()
        val stream = runCatching { file.startWrite() }.getOrNull() ?: return false
        try {
            stream.write(bytes)
            file.finishWrite(stream)
            true
        } catch (_: Exception) {
            file.failWrite(stream)
            false
        }
    } else {
        runCatching { path.writeBytes(bytes); true }.getOrDefault(false)
    }

    private fun read(): List<Entry> = runCatching {
        val bytes = readBytes() ?: return emptyList()
        if (bytes.size > MAX_SIZE) return emptyList()
        val map = PageJson.map(bytes.toString(Charsets.UTF_8), "", MAX_SIZE, maxDepth = 3) ?: return emptyList()
        (map["terms"] as? List<*>).orEmpty().mapNotNull { item ->
            val entry = item as? Map<*, *> ?: return@mapNotNull null
            val values = (entry["values"] as? Map<*, *>)?.entries?.mapNotNull { (key, value) ->
                if (key is String && value is String) key to value else null
            }?.toMap() ?: return@mapNotNull null
            val courses = (entry["courses"] as? List<*>)?.asSequence()?.filterIsInstance<String>()
                ?.filter { it.isNotEmpty() }?.take(GradeReport.MAX_COURSES)?.toCollection(LinkedHashSet())
            Entry(entry["term"] as? String ?: return@mapNotNull null, entry["lang"] as? String ?: return@mapNotNull null, values, courses)
        }
    }.getOrDefault(emptyList())

    private fun json(entries: List<Entry>) = JSONObject().put("v", 1).put("terms", JSONArray(entries.map { entry ->
        JSONObject().put("term", entry.term).put("lang", entry.language).put("values", JSONObject(entry.values)).apply {
            entry.courses?.let { put("courses", JSONArray(it.toList())) }
        }
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
