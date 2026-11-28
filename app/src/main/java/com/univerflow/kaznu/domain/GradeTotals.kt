package com.univerflow.kaznu.domain

import java.text.Normalizer
import java.util.Locale

/**
 * A journal's control-period totals for one term in the site's language, as the journal page sends them
 * (`univer-grades:`) or as a background check read them from the raw page, both with the journal's own reader. Any
 * script on the site could send the message, so these checks (the report's own) are the only trust.
 */
data class GradeTotals(val term: GradeReport.Term?, val language: String, val courses: List<GradeReport.Course>) {
    /** A stored baseline for one term: the non-empty cell values and the normalized course names that were present. */
    data class Base(
        val values: Map<String, String>,
        val courses: Set<String> = values.keys.mapTo(LinkedHashSet()) { it.substringBefore('|') },
    )

    /** "2026/1", or "current" when the page does not say which term it shows. */
    val termKey: String get() = term?.let { "${it.year}/${it.term}" } ?: "current"

    /** Normalized course names in this reading, used to detect a new term even when scores start empty. */
    fun courseNames(): Set<String> = courses.mapTo(LinkedHashSet()) { courseKey(it.name) }

    /** Each grade by course and period, without empty ones; a course named twice counts once, its last reading wins. */
    private fun grades(): Map<String, GradeChange> = courses.flatMap { course ->
        course.scores.filter { it.value.isNotEmpty() }.map { key(course.name, it.label) to GradeChange(course.name, it.label, it.value, it.max) }
    }.toMap()

    /** Course and period to its grade; case and Unicode form of the course name do not count. */
    fun values(): Map<String, String> = grades().mapValues { it.value.value }

    /** The grades new or changed since [base]; null means nothing to compare against, so nothing is new. */
    fun changes(base: Map<String, String>?): List<GradeChange> = changesFrom(base?.let(::Base))

    /**
     * The grades new or changed since [base]. A new term (fewer than half of the previous courses still present), a
     * mass reset (two or more previously non-zero scores back to empty or zero), and any total that is empty or just
     * zero are silent.
     */
    fun changesFrom(base: Base?): List<GradeChange> {
        if (base == null) return emptyList()
        val previousCourses = base.courses
        if (previousCourses.isNotEmpty()) {
            val currentCourses = courseNames()
            val shared = previousCourses.count { it in currentCourses }
            if (shared == 0 || shared * 2 < previousCourses.size) return emptyList()
        }
        val currentCells = courses.flatMap { course ->
            course.scores.map { key(course.name, it.label) to it.value }
        }.toMap()
        val resets = base.values.count { (cellKey, previous) ->
            if (previous.isEmpty() || isZero(previous)) return@count false
            val current = currentCells[cellKey] ?: return@count false
            current.isEmpty() || isZero(current)
        }
        if (resets >= 2) return emptyList()
        return grades().filter { (cellKey, grade) -> !isZero(grade.value) && base.values[cellKey] != grade.value }.values.toList()
    }

    companion object {
        const val MESSAGE_PREFIX = "univer-grades:"
        const val MAX_LENGTH = 64 * 1024
        private val LANGUAGE = Regex("^[a-z]{0,5}$")
        private val ZERO_SCORE = Regex("^0+(?:[.,]0+)?\\*?$")

        fun isZero(value: String): Boolean = ZERO_SCORE.matches(value.trim())

        fun courseKey(course: String) =
            Normalizer.normalize(course, Normalizer.Form.NFC).lowercase(Locale.ROOT)

        fun key(course: String, label: String) =
            courseKey(course) + "|" + label.uppercase(Locale.ROOT)

        /** Null for anything but the journal's shape, and for a journal without a single control period. */
        fun of(message: Map<String, Any?>): GradeTotals? {
            if ((message["v"] as? Number)?.toInt() != 1) return null
            val term = message["term"].let { if (it == null) null else GradeReport.term(it) ?: return null }
            val language = (message["lang"] as? String)?.lowercase(Locale.ROOT)?.takeIf(LANGUAGE::matches) ?: return null
            val list = message["courses"] as? List<*> ?: return null
            if (list.isEmpty() || list.size > GradeReport.MAX_COURSES) return null
            val courses = list.map { GradeReport.course(it) ?: return null }
            return GradeTotals(term, language, courses).takeIf { totals -> totals.courses.any { it.scores.isNotEmpty() } }
        }
    }
}

/** A total new or changed since the last look: "Социология", "РК2", "71" of "100". */
data class GradeChange(val course: String, val label: String, val value: String, val max: String) {
    /** "85", or "12/25" for a grade not out of 100. */
    val score: String get() = if (max.isEmpty() || max == "100") value else "$value/$max"
}
