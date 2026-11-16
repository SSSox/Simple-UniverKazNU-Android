package com.univerflow.kaznu.web

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * «Скачать все» in a section of a course's UMKD page: the section's heading and its files' addresses, as the page sends
 * them. Any script on the site could send the same message, so only UMKD file addresses on the exact origin pass, each
 * file once (by its path, whatever the query, case or leading zeros) and at most [LIMIT] of them; anything else makes the
 * whole message void. Shared rules with Windows.
 */
class DownloadBatch private constructor(val section: String, val urls: List<String>) {
    companion object {
        const val MESSAGE_PREFIX = "univer-download-all:"
        const val LIMIT = 200
        private const val MAX_LENGTH = 64 * 1024

        fun parse(message: String?): DownloadBatch? {
            if (message == null || message.length > MAX_LENGTH + MESSAGE_PREFIX.length || !message.startsWith(MESSAGE_PREFIX)) return null
            val json = runCatching { JSONTokener(message.removePrefix(MESSAGE_PREFIX)).nextValue() as? JSONObject }.getOrNull() ?: return null
            val array = json.opt("urls") as? JSONArray ?: return null
            return of(json.opt("section"), (0 until array.length()).map { array.opt(it) })
        }

        /** The checks on the decoded message: a heading of 1–200 characters and UMKD file addresses only. */
        fun of(section: Any?, urls: List<Any?>): DownloadBatch? {
            val name = (section as? String)?.trim() ?: return null
            if (name.isEmpty() || name.length > 200) return null
            val unique = LinkedHashMap<String, String>()
            for (item in urls) {
                val url = item as? String ?: return null
                if (!SitePolicy.materialFile(url)) return null
                unique.getOrPut(fileKey(url)) { url }
                if (unique.size > LIMIT) return null
            }
            return if (unique.isEmpty()) null else DownloadBatch(name, unique.values.toList())
        }

        private val LEADING_ZEROS = Regex("(?<=/)0+(?=\\d)")
        private fun fileKey(url: String) = LEADING_ZEROS.replace(java.net.URI(url).path.trimEnd('/').lowercase(java.util.Locale.ROOT), "")
    }
}
