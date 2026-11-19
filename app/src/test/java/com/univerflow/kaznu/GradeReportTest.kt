package com.univerflow.kaznu

import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.domain.Band
import com.univerflow.kaznu.domain.GradeReport
import com.univerflow.kaznu.domain.ScoreBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GradeReportTest {
    private fun standing(value: String, max: String = "100") = ScoreBand.of(value, max)!!.let { Triple(it.band, it.next, it.left) }

    @Test fun aScoreFallsInItsBandByItsShareOfTheMaximumLikeThePage() {
        // The same table as tests/dom/score-bands.test.cjs: the report and the page never disagree.
        assertEquals(Triple(Band.LOW, 50.0, 0.1), standing("49.9"))
        assertEquals(Triple(Band.LOW, 50.0, 50.0), standing("0"))
        assertEquals(Triple(Band.PASS, 70.0, 20.0), standing("50"))
        assertEquals(Triple(Band.PASS, 70.0, 1.0), standing("69"))
        assertEquals(Triple(Band.GOOD, 90.0, 20.0), standing("70"))
        assertEquals(Triple(Band.GOOD, 90.0, 0.5), standing("89,5"))
        assertEquals(Triple(Band.TOP, null, 0.0), standing("90"))
        assertEquals(Triple(Band.TOP, null, 0.0), standing("100"))
        assertEquals(Triple(Band.PASS, 17.5, 5.0), standing("12,5", "25"))
        assertEquals(listOf(12.5, 17.5, 22.5), ScoreBand.of("12,5", "25")!!.marks)
        for ((value, max) in listOf("" to "100", "–" to "100", "5" to "", "5" to "0", "5 баллов" to "100", null to "100"))
            assertNull("$value of $max", ScoreBand.of(value, max))
    }

    @Test fun numbersReadWithTheLanguagesDecimalMark() {
        assertEquals("7", ScoreBand.format(7.0, AppLanguage.RUSSIAN))
        assertEquals("0,5", ScoreBand.format(0.5, AppLanguage.KAZAKH))
        assertEquals("17.5", ScoreBand.format(17.5, AppLanguage.ENGLISH))
    }

    private fun score(label: Any? = "РК1", max: Any? = "100", value: Any? = "63") = mapOf("label" to label, "max" to max, "value" to value)
    private fun course(name: Any? = "Социология", scores: Any? = listOf(score())) = mapOf("name" to name, "scores" to scores)
    private fun message(courses: Any? = listOf(course()), term: Any? = mapOf("year" to 2026, "term" to 1), student: Any? = "Иванова  Айгерим") =
        mapOf("v" to 1, "term" to term, "student" to student, "courses" to courses)

    @Test fun theReportPrintsTheSitesOwnDigitsInTheLanguagesDecimalMark() {
        assertEquals("76,667", ScoreBand.display("76.667", AppLanguage.RUSSIAN))
        assertEquals("76.667", ScoreBand.display(" 76,667 ", AppLanguage.ENGLISH))
        assertEquals("63", ScoreBand.display("63", AppLanguage.KAZAKH))
        assertEquals("—", ScoreBand.display("—", AppLanguage.RUSSIAN))
    }

    @Test fun aWellFormedReportKeepsWhatThePageSent() {
        val report = GradeReport.of(message(listOf(course(), course("Психология", listOf(score(value = ""), score("Экзамен", "100", "12,5"))))))!!
        assertEquals(GradeReport.Term(2026, 1), report.term)
        assertEquals("Иванова Айгерим", report.student)
        assertEquals(listOf("Социология", "Психология"), report.courses.map { it.name })
        assertEquals(GradeReport.Score("РК1", "100", ""), report.courses[1].scores[0])
        assertEquals(GradeReport.Score("Экзамен", "100", "12,5"), report.courses[1].scores[1])
        // The page reads any decimals; so does the report, or one total such as 76,667 would void it.
        assertEquals("76,667", GradeReport.of(message(listOf(course(scores = listOf(score(value = "76,667"))))))!!.courses[0].scores[0].value)
        // A missing term and name are allowed: the report says less, it is not wrong.
        val bare = GradeReport.of(message(term = null, student = ""))!!
        assertNull(bare.term); assertEquals("", bare.student)
    }

    @Test fun anythingOutOfShapeVoidsTheWholeReport() {
        val broken = listOf(
            message() + ("v" to 2),
            message(courses = emptyList<Any>()),
            message(courses = List(GradeReport.MAX_COURSES + 1) { course() }),
            message(courses = listOf(course(name = ""))),
            message(courses = listOf(course(name = "x".repeat(201)))),
            message(courses = listOf(course(name = "a\u0000b"))),
            message(courses = listOf(course(scores = List(GradeReport.MAX_SCORES + 1) { score() }))),
            message(courses = listOf(course(scores = listOf(score(label = "<img src=x>"))))),
            message(courses = listOf(course(scores = listOf(score(value = "63; drop"))))),
            message(courses = listOf(course(scores = listOf(score(max = 100))))),
            message(courses = listOf("Социология")),
            message(term = mapOf("year" to 2026, "term" to 4)),
            message(term = mapOf("year" to 1999, "term" to 1)),
            message(term = "2026/1"),
            message(student = "x".repeat(121)),
            message(student = 42),
            message() + ("goal" to "great"),
            message() + ("goal" to 90),
            message(courses = listOf(course() + ("absences" to -1))),
            message(courses = listOf(course() + ("absences" to 1000))),
            message(courses = listOf(course() + ("absences" to 2.5))),
            message(courses = listOf(course() + ("absences" to "4"))),
        )
        broken.forEachIndexed { index, value -> assertNull("case $index", GradeReport.of(value)) }
    }

    @Test fun aGoalCountsWhatIsLeftToItsOwnMarkLikeThePage() {
        // The same cases as the goal test in tests/dom/score-bands.test.cjs.
        fun aimed(value: String, max: String, goal: Band?) = ScoreBand.of(value, max, goal)!!.let { listOf(it.band, it.next, it.left, it.reached) }
        assertEquals(listOf(Band.PASS, 90.0, 27.0, false), aimed("63", "100", Band.TOP))
        assertEquals(listOf(Band.PASS, 70.0, 7.0, false), aimed("63", "100", Band.GOOD))
        assertEquals(listOf(Band.PASS, null, 0.0, true), aimed("63", "100", Band.PASS))
        assertEquals(listOf(Band.TOP, null, 0.0, true), aimed("92", "100", Band.TOP))
        assertEquals(listOf(Band.LOW, 22.5, 10.5, false), aimed("12", "25", Band.TOP))
        assertEquals(listOf(Band.PASS, 70.0, 7.0, false), aimed("63", "100", null))
    }

    @Test fun theGoalAndEachCoursesAbsencesTravelWithTheReport() {
        val report = GradeReport.of(message(listOf(course() + ("absences" to 4), course("Психология"))) + ("goal" to "top"))!!
        assertEquals(Band.TOP, report.goal)
        assertEquals(listOf(4, 0), report.courses.map { it.absences })
        // A page without a goal (or an older one without the fields) still makes a report.
        val plain = GradeReport.of(message() + ("goal" to null))!!
        assertNull(plain.goal); assertEquals(0, plain.courses[0].absences)
    }
}
