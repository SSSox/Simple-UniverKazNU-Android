package com.univerflow.kaznu.domain

/**
 * The journal's grades for a printable report: the term, the name in the site header and each course's control-period
 * totals as the page read them. Any script on the site could send the same message, so the checks here are the only
 * trust: a field of the wrong kind, too long or too many voids the whole report, like «Скачать все».
 */
data class GradeReport(val term: Term?, val student: String, val courses: List<Course>) {
    /** [term] 1 is the autumn term, 2 the spring term and 3 the summer term of the academic year starting in [year]. */
    data class Term(val year: Int, val term: Int)
    data class Course(val name: String, val scores: List<Score>)
    /** A control period ("РК1") with its maximum and grade as written ("100", "63", "12,5"); either may be empty. */
    data class Score(val label: String, val max: String, val value: String)

    companion object {
        const val MESSAGE_PREFIX = "univer-report:"
        const val MAX_LENGTH = 64 * 1024
        const val MAX_COURSES = 60
        const val MAX_SCORES = 8
        private val SPACES = Regex("\\s+")
        private val LABEL = Regex("^[\\p{L}\\p{N}*.()]{1,16}$")
        private val AMOUNT = Regex("^(?:-?\\d{1,6}(?:[.,]\\d{1,6})?)?$")

        /**
         * The decoded message (maps, lists, strings and numbers, as JSON gives them) as a report; null unless it is
         * version 1 with 1–[MAX_COURSES] courses of at most [MAX_SCORES] scores each.
         */
        fun of(message: Map<String, Any?>): GradeReport? {
            if ((message["v"] as? Number)?.toInt() != 1) return null
            val term = message["term"].let { if (it == null) null else term(it) ?: return null }
            val student = text(message["student"], 120, allowEmpty = true) ?: return null
            val list = message["courses"] as? List<*> ?: return null
            if (list.isEmpty() || list.size > MAX_COURSES) return null
            val courses = list.map { course(it) ?: return null }
            return GradeReport(term, student, courses)
        }

        private fun term(value: Any): Term? {
            val map = value as? Map<*, *> ?: return null
            val year = whole(map["year"]) ?: return null
            val term = whole(map["term"]) ?: return null
            return if (year in 2000..2100 && term in 1..3) Term(year, term) else null
        }

        private fun course(value: Any?): Course? {
            val map = value as? Map<*, *> ?: return null
            val name = text(map["name"], 200) ?: return null
            val list = map["scores"] as? List<*> ?: return null
            if (list.size > MAX_SCORES) return null
            val scores = list.map { item ->
                val score = item as? Map<*, *> ?: return null
                val label = (score["label"] as? String)?.takeIf(LABEL::matches) ?: return null
                val max = (score["max"] as? String)?.trim()?.takeIf(AMOUNT::matches) ?: return null
                val grade = (score["value"] as? String)?.trim()?.takeIf(AMOUNT::matches) ?: return null
                Score(label, max, grade)
            }
            return Course(name, scores)
        }

        private fun whole(value: Any?): Int? = (value as? Number)?.toDouble()?.takeIf { it % 1.0 == 0.0 && it in -1e6..1e6 }?.toInt()

        /** Collapsed whitespace, no control characters, 1..[limit] characters (or empty when allowed). */
        private fun text(value: Any?, limit: Int, allowEmpty: Boolean = false): String? {
            val raw = value as? String ?: return null
            if (raw.any { it.isISOControl() && it != '\n' && it != '\t' }) return null
            val clean = raw.replace(SPACES, " ").trim()
            return clean.takeIf { it.length <= limit && (allowEmpty || it.isNotEmpty()) }
        }
    }
}
