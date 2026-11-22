package com.univerflow.kaznu

import com.univerflow.kaznu.data.LastGradesStore
import com.univerflow.kaznu.domain.GradeReport
import com.univerflow.kaznu.domain.GradeTotals
import com.univerflow.kaznu.domain.LastGrades
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LastGradesTest {
    @get:Rule val folder = TemporaryFolder()

    private fun totals(vararg courses: GradeReport.Course) =
        GradeTotals(GradeReport.Term(2026, 1), "ru", courses.toList())

    private fun course(name: String, vararg scores: Triple<String, String, String>) =
        GradeReport.Course(name, scores.map { GradeReport.Score(it.first, it.third, it.second) })

    private val sample = totals(
        course("Социология", Triple("РК1", "85", "100"), Triple("РК2", "", "100")),
        course("Физика", Triple("РК1", "12", "25")),
    )

    @Test fun keepsOnlyFilledGradesWithCourseNames() {
        val saved = LastGrades.of(sample, 1000)!!
        assertEquals("2026/1", saved.termKey)
        assertEquals(listOf("Социология" to "85", "Физика" to "12/25"), saved.rows.map { it.course to it.score })
        assertEquals(listOf("Социология", "Физика"), saved.byCourse().map { it.first })
    }

    @Test fun nothingToKeepWithoutAGrade() {
        assertNull(LastGrades.of(totals(course("Социология", Triple("РК1", "", "100"))), 1000))
    }

    @Test fun mapRoundTrips() {
        val saved = LastGrades.of(sample, 1000)!!
        assertEquals(saved, LastGrades.restore(saved.toMap()))
    }

    @Test fun outOfShapeCopiesAreRefused() {
        val good = LastGrades.of(sample, 1000)!!.toMap()
        for (bad in listOf(
            good + ("v" to 2), good + ("saved" to 0), good + ("lang" to "toolong"), good + ("rows" to emptyList<Any>()),
            good + ("rows" to listOf("x")), good + ("rows" to listOf(mapOf("course" to "", "label" to "A", "value" to "1", "max" to ""))),
            good + ("rows" to List(LastGrades.MAX_ROWS + 1) { mapOf("course" to "c", "label" to "A", "value" to "1", "max" to "") }),
            good + ("term" to "x".repeat(LastGrades.MAX_TEXT + 1)),
        )) assertNull(bad.toString().take(80), LastGrades.restore(bad))
    }

    @Test fun storeWritesReadsAndErases() {
        val store = LastGradesStore(folder.newFile("last-grades.json"))
        val saved = LastGrades.of(sample, 1000)!!
        assertTrue(store.write(saved, store.generation))
        assertEquals(saved, store.read())
        store.erase()
        assertNull(store.read())
    }

    @Test fun aWriteThatBeganBeforeTheEraseIsDropped() {
        val store = LastGradesStore(folder.newFile("last-grades.json"))
        val since = store.generation
        store.erase()
        assertFalse(store.write(LastGrades.of(sample, 1000)!!, since))
        assertNull(store.read())
    }

    @Test fun aNewWriteReplacesTheOldOne() {
        val store = LastGradesStore(folder.newFile("last-grades.json"))
        store.write(LastGrades.of(sample, 1000)!!, store.generation)
        val newer = LastGrades.of(totals(course("Химия", Triple("РК1", "70", "100"))), 2000)!!
        store.write(newer, store.generation)
        assertEquals(newer, store.read())
    }

    @Test fun malformedOrMissingFilesReadAsNothing() {
        val file = folder.newFile("last-grades.json")
        val store = LastGradesStore(file)
        file.writeText("{not json")
        assertNull(store.read())
        file.writeText("""{"v":1,"term":"2026/1","lang":"ru","saved":5,"rows":"nope"}""")
        assertNull(store.read())
        file.delete()
        assertNull(store.read())
    }

    @Test fun anOversizedFileIsNotRead() {
        val file = folder.newFile("last-grades.json")
        file.writeText(" ".repeat(LastGradesStore.MAX_SIZE + 1))
        assertNull(LastGradesStore(file).read())
    }

    @Test fun rowsAreBounded() {
        val many = totals(*Array(GradeReport.MAX_COURSES) { i ->
            course("Курс $i" + "я".repeat(300), *Array(GradeReport.MAX_SCORES) { Triple("РК$it", "50", "100") })
        })
        val saved = LastGrades.of(many, 1000)!!
        assertEquals(LastGrades.MAX_ROWS, saved.rows.size)
        assertTrue(saved.rows.all { it.course.length <= LastGrades.MAX_TEXT })
        val store = LastGradesStore(folder.newFile("big.json"))
        assertTrue(store.write(saved, store.generation))
        assertNotNull(store.read())
    }
}
