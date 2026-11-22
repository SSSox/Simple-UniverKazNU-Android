package com.univerflow.kaznu.domain

import java.util.TimeZone

enum class ExamKind(val wire: String) {
    EXAM("exam"), MIDTERM("midterm");

    companion object {
        fun of(wire: Any?): ExamKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** One exam or midterm: [date] an epoch day and [minute] of that day, both on the site's clock (the phone's zone). */
data class ExamItem(val kind: ExamKind, val subject: String, val date: Long, val minute: Int, val place: String) {
    fun startAt(zone: TimeZone): Long = Timetable.at(date, minute, zone)
}

/** A reminder due at [at]: the evening before ([evening]) or the lead time before the start. */
data class ExamReminder(val at: Long, val item: ExamItem, val evening: Boolean)

/**
 * The exams and midterms the student last saw on the site's schedule pages, kept for reminders. A page replaces its
 * own kind as a whole, so an exam that was moved or removed stops reminding.
 */
data class ExamPlan(val saved: Long, val items: List<ExamItem>) {
    /** Replaces every item of [kind] with [fresh]. */
    fun replace(kind: ExamKind, fresh: List<ExamItem>, now: Long): ExamPlan =
        ExamPlan(now, items.filter { it.kind != kind } + fresh)

    /**
     * The first reminder after [now] and after [after] (the last one shown: an alarm that fires early must not bring it
     * up again) for an exam that has not started. Each exam has two: at [EVENING_MINUTE] the evening before, and [lead]
     * before its start.
     */
    fun next(now: Long, zone: TimeZone, lead: Long, after: Long = Long.MIN_VALUE): ExamReminder? = items.flatMap { item ->
        val start = item.startAt(zone)
        if (start <= now) emptyList() else listOf(
            ExamReminder(Timetable.at(item.date - 1, EVENING_MINUTE, zone), item, true), ExamReminder(start - lead, item, false))
    }.filter { it.at > now && it.at > after }.minByOrNull { it.at }

    companion object {
        const val MESSAGE_PREFIX = "univer-exams:"
        const val MAX_LENGTH = 32768
        const val MAX_ITEMS = 60
        const val EVENING_MINUTE = 19 * 60
        private const val DAY = 86_400_000L
        private val START = Regex("^(\\d{4}-\\d{2}-\\d{2})T(\\d{2}):(\\d{2})$")

        /** The saved copy: the items with [saved]; out of shape is null. */
        fun restore(map: Map<String, Any?>): ExamPlan? {
            if (map["v"] != 1) return null
            val saved = (map["saved"] as? Number)?.toLong()?.takeIf { it > 0 } ?: return null
            val list = map["items"] as? List<*> ?: return null
            if (list.size > 2 * MAX_ITEMS) return null
            return ExamPlan(saved, list.map { item(it as? Map<*, *> ?: return null) ?: return null })
        }

        /**
         * The page's message: {"v":1,"kind":"exam","items":[{"kind":"exam","subject","start":"2026-12-15T13:00","place"}]}.
         * Anything out of shape is null; items that already began, or are over a year away, are dropped.
         */
        fun batch(map: Map<String, Any?>, now: Long, zone: TimeZone = TimeZone.getDefault()): Pair<ExamKind, List<ExamItem>>? {
            if (map["v"] != 1) return null
            val kind = ExamKind.of(map["kind"]) ?: return null
            val list = map["items"] as? List<*> ?: return null
            if (list.size > MAX_ITEMS) return null
            val items = list.map { raw ->
                val item = item(raw as? Map<*, *> ?: return null) ?: return null
                if (item.kind != kind) return null
                item
            }
            return kind to items.filter { it.startAt(zone) > now && it.startAt(zone) < now + 366 * DAY }
        }

        private fun item(map: Map<*, *>): ExamItem? {
            val kind = ExamKind.of(map["kind"]) ?: return null
            val subject = text(map["subject"], 200)?.takeIf { it.isNotEmpty() } ?: return null
            val parts = START.matchEntire(map["start"] as? String ?: return null)?.groupValues ?: return null
            val date = Timetable.date(parts[1]) ?: return null
            val hour = parts[2].toInt(); val minute = parts[3].toInt()
            if (hour > 23 || minute > 59) return null
            return ExamItem(kind, subject, date, hour * 60 + minute, text(map["place"], 200) ?: return null)
        }

        /** Absent is empty; text is trimmed with its spaces folded, within [max]. */
        private fun text(value: Any?, max: Int): String? = when (value) {
            null -> ""
            is String -> value.replace(Regex("\\s+"), " ").trim().takeIf { it.length <= max }
            else -> null
        }
    }
}
