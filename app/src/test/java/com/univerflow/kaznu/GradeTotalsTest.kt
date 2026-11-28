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

    @Test fun aTotalThatIsZeroOrEmptyIsNotNews() {
        val before = totals("Социология" to listOf(score("РК1", "85"), score("РК2", "")))
        for (zero in listOf("0", "0.0", "0,00", "00")) {
            val withZero = totals("Социология" to listOf(score("РК1", "85"), score("РК2", zero)),
                "История" to listOf(score("РК1", zero), score("РК2", "")))
            assertEquals("zero=$zero", emptyList<GradeChange>(), withZero.changes(before.values()))
        }
        assertTrue(GradeTotals.isZero("0*"))
        // Moving from zero to a real score IS news.
        val zeroBase = totals("Социология" to listOf(score("РК1", "85"), score("РК2", "0")))
        val realGrade = totals("Социология" to listOf(score("РК1", "85"), score("РК2", "71")))
        assertEquals(listOf(GradeChange("Социология", "РК2", "71", "100")), realGrade.changes(zeroBase.values()))
        // A single score going back to empty or zero is not news, and does not hide a real change elsewhere.
        val mixedBefore = totals("Социология" to listOf(score("РК1", "85")), "Право" to listOf(score("РК1", "60")))
        val mixedAfter = totals("Социология" to listOf(score("РК1", "0")), "Право" to listOf(score("РК1", "75")))
        assertEquals(listOf(GradeChange("Право", "РК1", "75", "100")), mixedAfter.changes(mixedBefore.values()))
    }

    @Test fun aNewTermWithDisjointOrMostlyDifferentCoursesIsSilent() {
        val before = totals(
            "Физика" to listOf(score("РК1", "90"), score("РК2", "80")),
            "История" to listOf(score("РК1", "82")),
            "Литература" to listOf(score("РК1", "70")),
        )
        val disjoint = totals("Математика" to listOf(score("РК1", "95")))
        assertEquals(emptyList<GradeChange>(), disjoint.changes(before.values()))
        // Only 1 of 3 previous courses kept: still a new term, silent.
        val oneShared = totals(
            "Физика" to listOf(score("РК1", "90")),
            "Математика" to listOf(score("РК1", "85")),
            "География" to listOf(score("РК1", "77")),
        )
        assertEquals(emptyList<GradeChange>(), oneShared.changes(before.values()))
    }

    @Test fun aMassResetOfPublishedScoresIsSilentEvenWhenCourseNamesStay() {
        val before = totals(
            "Иностранный язык" to listOf(score("РК1", "90"), score("РК2", "80")),
            "Спорт" to listOf(score("РК1", "70")),
        )
        val resetToZero = totals(
            "Иностранный язык" to listOf(score("РК1", "0"), score("РК2", "0")),
            "Спорт" to listOf(score("РК1", "0")),
            "Новая дисциплина" to listOf(score("РК1", "88")),
        )
        assertEquals(emptyList<GradeChange>(), resetToZero.changes(before.values()))
        val resetToEmpty = totals(
            "Иностранный язык" to listOf(score("РК1", ""), score("РК2", "")),
            "Спорт" to listOf(score("РК1", "70")),
            "Новая дисциплина" to listOf(score("РК1", "88")),
        )
        assertEquals(emptyList<GradeChange>(), resetToEmpty.changes(before.values()))
    }

    @Test fun coursesAndColumnsStillMatchByNameWhenReorderedOrAdded() {
        val before = totals(
            "Математика" to listOf(score("РК1", "70"), score("Экзамен", "")),
            "История" to listOf(score("РК1", "82")),
            "Физика" to listOf(score("РК1", "90")),
        )
        val after = totals(
            "Физика" to listOf(score("РК1", "90")),
            "Искусство" to listOf(score("РК2", "80"), score("РК1", "0")),
            "Математика" to listOf(score("РК2", "75"), score("РК1", "72")),
        )
        assertEquals(
            listOf(
                GradeChange("Искусство", "РК2", "80", "100"),
                GradeChange("Математика", "РК2", "75", "100"),
                GradeChange("Математика", "РК1", "72", "100"),
            ),
            after.changes(before.values()),
        )
    }
}
