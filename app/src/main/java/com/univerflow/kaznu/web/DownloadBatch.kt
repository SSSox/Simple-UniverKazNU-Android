package com.univerflow.kaznu.web

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * «Скачать все» in a section of a course's UMKD page: the section's heading, the [tap] the keyed channel noted (the
 * heading's number among the page's headings, so two sections with the same heading stay apart, how many headings there
 * were and the number naming the tapped row) and its files' addresses, as the page sends them. Any script on the site could send the same message, so only UMKD file addresses on the exact origin pass, each
 * file once (by its path, whatever the query, case or leading zeros) and at most [LIMIT] of them; anything else makes the
 * whole message void. Shared rules with Windows.
 */
class DownloadBatch private constructor(val section: String, val tap: Tap, val urls: List<String>) {
    /** Where the open page lists a file: its course and the heading over it, as the page shows them. */
    data class Place(val course: String, val section: String)

    /**
     * The tapped heading as the keyed channel noted it (univer-channel.js mark): its number [head] among the [count]
     * heading rows of the page, and [mark], the number under which the channel keeps that very row for the lookup.
     */
    data class Tap(val head: Int, val count: Int, val mark: Int) {
        val valid get() = count in 1..MAX_HEAD + 1 && head in 0 until count && mark in 1..MAX_MARK
    }

    companion object {
        const val MESSAGE_PREFIX = "univer-download-all:"
        const val LIMIT = 200
        private const val MAX_LENGTH = 64 * 1024

        fun parse(message: String?): DownloadBatch? {
            if (message == null || message.length > MAX_LENGTH + MESSAGE_PREFIX.length || !message.startsWith(MESSAGE_PREFIX)) return null
            val json = runCatching { JSONTokener(message.removePrefix(MESSAGE_PREFIX)).nextValue() as? JSONObject }.getOrNull() ?: return null
            val array = json.opt("urls") as? JSONArray ?: return null
            return of(json.opt("section"), (0 until array.length()).map { array.opt(it) }, json.opt("head"), json.opt("count"), json.opt("mark"))
        }

        /**
         * The checks on the decoded message: a heading of 1–200 characters, its number among [count] headings and the
         * channel's [mark] of it, and UMKD file addresses only.
         */
        fun of(section: Any?, urls: List<Any?>, head: Any? = 0, count: Any? = 1, mark: Any? = 1): DownloadBatch? {
            val name = (section as? String)?.trim() ?: return null
            if (name.isEmpty() || name.length > 200) return null
            val tap = Tap(head as? Int ?: return null, count as? Int ?: return null, mark as? Int ?: return null).takeIf { it.valid } ?: return null
            val unique = LinkedHashMap<String, String>()
            for (item in urls) {
                val url = item as? String ?: return null
                if (!SitePolicy.materialFile(url)) return null
                unique.getOrPut(fileKey(url)) { url }
                if (unique.size > LIMIT) return null
            }
            return if (unique.isEmpty()) null else DownloadBatch(name, tap, unique.values.toList())
        }

        /**
         * The places of [urls], all of which the open page must list under the very heading row the [tap] marked, with
         * the heading text [section] (as the tap named it). [raw] is what [PageScripts.sectionPlaces] answered: JSON text
         * (itself inside a JSON string) made by the keyed channel's document-start lookup with built-ins a site script
         * cannot swap (univer-channel.js). A script on the page could have changed the list or the headings between the
         * tap and the lookup: when the page now has another number of headings than at the tap, or any file is under a
         * heading row other than the tapped one (even one with the same text that took its number after it was
         * removed), under none or not on the page, or the heading's number or text differ, the whole batch is refused
         * (an empty map) rather than any file being fetched into a section the student did not tap.
         * Null when the page gave no answer or one that is not exactly the lookup's shape: {count, files} with a whole
         * count from 0 and files keyed by asked addresses only, each {course, section, head, tapped} with two strings, a
         * whole number from -1 and a boolean.
         */
        fun places(raw: String?, urls: List<String>, tap: Tap, section: String): Map<String, Place>? {
            if (raw == null || !tap.valid || urls.isEmpty()) return null
            val text = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: return null
            if (text.length > MAX_ANSWER) return null
            val json = runCatching { JSONTokener(text).let { reader -> (reader.nextValue() as? JSONObject)?.takeIf { reader.nextClean() == 0.toChar() } } }
                .getOrNull() ?: return null
            if (json.length() != 2) return null
            val count = json.opt("count") as? Int ?: return null
            val files = json.opt("files") as? JSONObject ?: return null
            if (count !in 0..MAX_HEAD + 1) return null
            val asked = urls.toSet()
            val answer = HashMap<String, Located>()
            for (key in files.keys()) {
                if (key !in asked) return null
                val place = files.opt(key) as? JSONObject ?: return null
                if (place.length() != 4) return null
                val course = place.opt("course") as? String ?: return null
                val name = place.opt("section") as? String ?: return null
                val number = place.opt("head") as? Int ?: return null
                val tapped = place.opt("tapped") as? Boolean ?: return null
                if (number !in -1..MAX_HEAD || course.length > MAX_TEXT || name.length > MAX_TEXT) return null
                answer[key] = Located(Place(course, name), number, tapped)
            }
            if (count != tap.count) return emptyMap()
            val found = LinkedHashMap<String, Place>()
            for (url in urls) {
                val located = answer[url] ?: return emptyMap()
                if (!located.tapped || located.head != tap.head || heading(located.place.section) != section) return emptyMap()
                found[url] = located.place
            }
            return found
        }

        private class Located(val place: Place, val head: Int, val tapped: Boolean)

        /**
         * Whether [url] is a course's UMKD page, where «Скачать все» asks the keyed channel for its files' places: the exact
         * origin, /student/umkd/{course}/{year}/{term} (digits only, a four-digit year, any term number), an optional
         * trailing slash, query and fragment. The same shape as COURSE_PAGE in univer-channel.js, which answers nothing
         * for any other page.
         */
        fun coursePage(url: String?) = url != null && COURSE_PAGE.matches(url)

        private val COURSE_PAGE = Regex("^" + Regex.escape(SitePolicy.ORIGIN) + """/student/umkd/\d+/\d{4}/\d+/?(?:\?[^#]*)?(?:#.*)?$""")

        /** The heading text as the fold module names it (univer-dom.js clean, then at most 200 characters). */
        fun heading(text: String) = WHITESPACE.replace(text, " ").trim().take(200).trim()

        private val WHITESPACE = Regex("""[\s\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]+""")
        private const val MAX_TEXT = 4_096
        private const val MAX_ANSWER = 4 * 1024 * 1024

        private const val MAX_HEAD = 9_999
        private const val MAX_MARK = 1_000_000_000

        private val LEADING_ZEROS = Regex("(?<=/)0+(?=\\d)")
        private fun fileKey(url: String) = LEADING_ZEROS.replace(java.net.URI(url).path.trimEnd('/').lowercase(java.util.Locale.ROOT), "")
    }
}
