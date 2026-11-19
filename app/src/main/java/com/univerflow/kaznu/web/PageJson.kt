package com.univerflow.kaznu.web

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * A keyed page message as plain Kotlin values: the JSON object after [prefix], with objects as maps, arrays as lists
 * and JSON null as null. A message over [maxLength], not an object, or nested deeper than [maxDepth] gives null.
 */
object PageJson {
    fun map(message: String?, prefix: String, maxLength: Int, maxDepth: Int = 4): Map<String, Any?>? {
        if (message == null || message.length > maxLength || !message.startsWith(prefix)) return null
        val json = runCatching { JSONTokener(message.removePrefix(prefix)).nextValue() as? JSONObject }.getOrNull() ?: return null
        @Suppress("UNCHECKED_CAST")
        return runCatching { plain(json, 0, maxDepth) }.getOrNull() as? Map<String, Any?>
    }

    private class TooDeep : RuntimeException()

    private fun plain(value: Any?, depth: Int, maxDepth: Int): Any? = when (value) {
        JSONObject.NULL, null -> null
        is JSONObject -> if (depth > maxDepth) throw TooDeep() else value.keys().asSequence().associateWith { plain(value.opt(it), depth + 1, maxDepth) }
        is JSONArray -> if (depth > maxDepth) throw TooDeep() else (0 until value.length()).map { plain(value.opt(it), depth + 1, maxDepth) }
        else -> value
    }
}
