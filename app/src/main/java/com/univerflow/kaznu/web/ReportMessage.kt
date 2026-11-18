package com.univerflow.kaznu.web

import com.univerflow.kaznu.domain.GradeReport
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** The journal's «Поделиться успеваемостью» message: JSON after [GradeReport.MESSAGE_PREFIX], checked by [GradeReport.of]. */
object ReportMessage {
    private const val MAX_DEPTH = 4

    fun parse(message: String?): GradeReport? {
        if (message == null || message.length > GradeReport.MAX_LENGTH || !message.startsWith(GradeReport.MESSAGE_PREFIX)) return null
        val json = runCatching { JSONTokener(message.removePrefix(GradeReport.MESSAGE_PREFIX)).nextValue() as? JSONObject }.getOrNull() ?: return null
        @Suppress("UNCHECKED_CAST")
        val map = runCatching { plain(json, 0) }.getOrNull() as? Map<String, Any?> ?: return null
        return GradeReport.of(map)
    }

    private class TooDeep : RuntimeException()

    /** Objects and arrays as maps and lists, JSON null as null; nesting deeper than the report's own shape voids it. */
    private fun plain(value: Any?, depth: Int): Any? = when (value) {
        JSONObject.NULL, null -> null
        is JSONObject -> if (depth > MAX_DEPTH) throw TooDeep() else value.keys().asSequence().associateWith { plain(value.opt(it), depth + 1) }
        is JSONArray -> if (depth > MAX_DEPTH) throw TooDeep() else (0 until value.length()).map { plain(value.opt(it), depth + 1) }
        else -> value
    }
}
