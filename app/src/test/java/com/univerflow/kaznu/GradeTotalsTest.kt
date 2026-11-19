package com.univerflow.kaznu

import com.univerflow.kaznu.domain.GradeChange
import com.univerflow.kaznu.domain.GradeReport
import com.univerflow.kaznu.domain.GradeTotals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GradeTotalsTest {
    private fun score(label: String, value: String, max: String = "100") = mapOf("label" to label, "max" to max, "value" to value)
    private fun message(vararg courses: Pair<String, List<Map<String, String>>>, term: Any? = mapOf("year" to 2026, "term" to 1)) =
        mapOf("v" to 1, "term" to term, "lang" to "ru", "courses" to courses.map { (name, scores) -> mapOf("name" to name, "scores" to scores) })

    private fun totals(vararg courses: Pair<String, List<Map<String, String>>>) = GradeTotals.of(message(*courses))!!

    @Test fun onlyTheJournalsShapeIsAccepted() {
        assertNotNull(GradeTotals.of(message("Социология" to listOf(score("РК1", "85")))))
        assertEquals("current", GradeTotals.of(message("Социология" to listOf(score("РК1", "85")), term = null))!!.termKey)
        for (bad in listOf(
            message("Социология" to listOf(score("РК1", "85"))) + ("v" to 2),
            message("Социология" to listOf(score("РК1", "85"))) + ("lang" to "<script>"),
            message("Социология" to listOf(score("РК1", "85"))) + ("courses" to emptyList<Any>()),
            message("Социология" to listOf(score("РК1", "85"))) + ("courses" to listOf("not a course")),
            message("Социология" to listOf(score("РК1", "85")), term = "2026/1"),
            // A journal without a single control period has nothing to watch.
            message("Социология" to emptyList()),
        )) assertNull(bad.toString(), GradeTotals.of(bad))
        val many = (1..GradeReport.MAX_COURSES + 1).map { "Курс $it" to listOf(score("РК1", "50")) }.toTypedArray()
        assertNull(GradeTotals.of(message(*many)))
    }

    @Test fun aNewOrChangedGradeIsNewButNothingIsNewWithoutABase() {
        val before = totals("Социология" to listOf(score("РК1", "85"), score("РК2", "")), "Право" to listOf(score("РК1", "12", "25")))
        val after = totals("Социология" to listOf(score("РК1", "85"), score("РК2", "71")), "Право" to listOf(score("РК1", "14", "25")))
        assertEquals(emptyList<GradeChange>(), after.changes(null))
        assertEquals(emptyList<GradeChange>(), after.changes(after.values()))
        assertEquals(listOf(GradeChange("Социология", "РК2", "71", "100"), GradeChange("Право", "РК1", "14", "25")), after.changes(before.values()))
        // An empty grade is no grade: it is neither kept nor announced.
        assertTrue(before.values().keys.none { it.endsWith("|РК2") })
    }

    @Test fun theCourseNamesCaseAndUnicodeFormDoNotMakeAGradeNew() {
        val composed = totals("Қазақ тілі" to listOf(score("рк1", "90")))
        val decomposed = totals("ҚАЗАҚ ТІЛІ".let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFD) } to listOf(score("РК1", "90")))
        assertEquals(emptyList<GradeChange>(), decomposed.changes(composed.values()))
    }

    @Test fun aCourseNamedTwiceIsAnnouncedOnceAndNotAgain() {
        val twice = totals("Право" to listOf(score("РК1", "60")), "право" to listOf(score("РК1", "70")))
        assertEquals(listOf(GradeChange("право", "РК1", "70", "100")), twice.changes(emptyMap()))
        assertEquals(emptyList<GradeChange>(), twice.changes(twice.values()))
    }

    @Test fun aGradeNotOutOfAHundredSaysItsMaximum() {
        assertEquals("85", GradeChange("Социология", "РК1", "85", "100").score)
        assertEquals("12/25", GradeChange("Право", "РК1", "12", "25").score)
        assertEquals("7", GradeChange("Право", "Экзамен", "7", "").score)
    }
}
