package com.univerflow.kaznu.domain

/**
 * The totals the student last saw, kept for the error screen's «Последние оценки»: one term, the language they were read
 * in, the moment they were saved and each non-empty grade with its course's name. Pure Kotlin; the file is
 * [com.univerflow.kaznu.data.LastGradesStore].
 */
data class LastGrades(val termKey: String, val language: String, val saved: Long, val rows: List<Row>) {
    /** One grade: "Социология", "РК2", "71" of "100". */
    data class Row(val course: String, val label: String, val value: String, val max: String) {
        /** "85", or "12/25" for a grade not out of 100. */
        val score: String get() = if (max.isEmpty() || max == "100") value else "$value/$max"
    }

    /** The rows by course in their order, for display. */
    fun byCourse(): List<Pair<String, List<Row>>> = rows.groupBy { it.course }.toList()

    fun toMap(): Map<String, Any?> = mapOf("v" to 1, "term" to termKey, "lang" to language, "saved" to saved,
        "rows" to rows.map { mapOf("course" to it.course, "label" to it.label, "value" to it.value, "max" to it.max) })

    companion object {
        const val MAX_ROWS = 480
        const val MAX_TEXT = 200

        /** [totals] without its empty grades; null when none is left. */
        fun of(totals: GradeTotals, saved: Long): LastGrades? {
            val rows = totals.courses.flatMap { course ->
                course.scores.filter { it.value.isNotEmpty() }
                    .map { Row(course.name.take(MAX_TEXT), it.label.take(MAX_TEXT), it.value.take(MAX_TEXT), it.max.take(MAX_TEXT)) }
            }.take(MAX_ROWS)
            return if (rows.isEmpty() || saved <= 0) null else LastGrades(totals.termKey.take(MAX_TEXT), totals.language, saved, rows)
        }

        /** A saved copy; anything out of shape, empty or over the bounds is null. */
        fun restore(map: Map<String, Any?>): LastGrades? {
            if ((map["v"] as? Number)?.toInt() != 1) return null
            val term = (map["term"] as? String)?.takeIf { it.length <= MAX_TEXT } ?: return null
            val language = (map["lang"] as? String)?.takeIf { it.length <= 5 } ?: return null
            val saved = (map["saved"] as? Number)?.toLong()?.takeIf { it > 0 } ?: return null
            val list = map["rows"] as? List<*> ?: return null
            if (list.isEmpty() || list.size > MAX_ROWS) return null
            val rows = list.map { item ->
                val row = item as? Map<*, *> ?: return null
                fun text(key: String) = (row[key] as? String)?.takeIf { it.length <= MAX_TEXT }
                val course = text("course")?.ifEmpty { null } ?: return null
                val value = text("value")?.ifEmpty { null } ?: return null
                Row(course, text("label") ?: return null, value, text("max") ?: return null)
            }
            return LastGrades(term, language, saved, rows)
        }
    }
}
