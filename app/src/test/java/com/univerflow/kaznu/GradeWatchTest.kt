package com.univerflow.kaznu

import com.univerflow.kaznu.data.GradeStore
import com.univerflow.kaznu.domain.GradeChange
import com.univerflow.kaznu.domain.GradeReport
import com.univerflow.kaznu.domain.GradeTotals
import com.univerflow.kaznu.grades.GradeCheck.Outcome
import com.univerflow.kaznu.grades.GradeRetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Retry schedule for failed grade checks and backwards-compatible GradeStore behaviour. */
class GradeWatchTest {
    @get:Rule val folder = TemporaryFolder()

    private fun course(name: String, vararg scores: Pair<String, String>) =
        GradeReport.Course(name, scores.map { GradeReport.Score(it.first, "100", it.second) })

    private fun totals(term: GradeReport.Term?, vararg courses: GradeReport.Course) =
        GradeTotals(term, "ru", courses.toList())

    @Test fun failedReadsRetryAfterOneMinuteThenFiveMinutesThenReturnToNormalSchedule() {
        val first = GradeRetry.next(0, Outcome.FAILED, allowed = true)
        assertEquals(GradeRetry.Decision(1, 60_000L), first)

        val second = GradeRetry.next(first.failures, Outcome.FAILED, allowed = true)
        assertEquals(GradeRetry.Decision(2, 300_000L), second)

        var failures = second.failures
        repeat(5) {
            val later = GradeRetry.next(failures, Outcome.FAILED, allowed = true)
            assertNull("after two short retries, fall back to the normal periodic schedule", later.retryAfterMs)
            assertEquals(3, later.failures)
            failures = later.failures
        }

        // A successful read resets failures and never chains another check.
        val recovered = GradeRetry.next(failures, Outcome.UNCHANGED, allowed = true)
        assertEquals(GradeRetry.Decision(0, null), recovered)

        // The next failure starts the 1 min -> 5 min cycle again.
        assertEquals(GradeRetry.Decision(1, 60_000L), GradeRetry.next(recovered.failures, Outcome.FAILED, allowed = true))
    }

    @Test fun nonFailureOutcomesAndSignedOutOrDisallowedStatesNeverRetry() {
        for (success in listOf(Outcome.BASED, Outcome.UNCHANGED, Outcome.NOTIFIED)) {
            assertEquals(GradeRetry.Decision(0, null), GradeRetry.next(2, success, allowed = true))
        }
        // Signed out never retries and clears failure count.
        assertEquals(GradeRetry.Decision(0, null), GradeRetry.next(1, Outcome.SIGNED_OUT, allowed = true))
        assertEquals(GradeRetry.Decision(0, null), GradeRetry.next(0, Outcome.SIGNED_OUT, allowed = false))
        // A failure when disallowed (signed out, foreground, paused or off) never retries.
        assertEquals(GradeRetry.Decision(0, null), GradeRetry.next(0, Outcome.FAILED, allowed = false))
        assertEquals(GradeRetry.Decision(0, null), GradeRetry.next(1, Outcome.FAILED, allowed = false))
        // Skipped never schedules a retry.
        assertNull(GradeRetry.next(0, Outcome.SKIPPED, allowed = true).retryAfterMs)
        assertNull(GradeRetry.next(1, Outcome.SKIPPED, allowed = false).retryAfterMs)
    }

    @Test fun gradeStoreStillReadsExistingStoredFilesWithoutCoursesField() {
        val file = folder.newFile("grades.json")
        // Legacy format written before course names were also recorded.
        file.writeText("""{"v":1,"terms":[{"term":"2026/1","lang":"ru","values":{"социология|РК1":"85","право|РК1":"60"}}]}""")
        val store = GradeStore(file, useAtomicFile = false)

        val sameTerm = totals(GradeReport.Term(2026, 1),
            course("Социология", "РК1" to "85", "РК2" to "71"),
            course("Право", "РК1" to "60"))
        val baseMap = store.base(sameTerm)
        assertNotNull(baseMap)
        assertEquals("85", baseMap!![GradeTotals.key("Социология", "РК1")])
        assertEquals(listOf(GradeChange("Социология", "РК2", "71", "100")), sameTerm.changesFrom(store.baseSnapshot(sameTerm)))

        // A new term with completely different courses under the same legacy file is silent.
        val newCourses = totals(GradeReport.Term(2026, 1), course("Химия", "РК1" to "90"))
        assertEquals(emptyList<GradeChange>(), newCourses.changesFrom(store.baseSnapshot(newCourses)))
    }

    @Test fun gradeStoreTracksEmptyCourseNamesAcrossTermsAndRespectsGenerationAndVersion() {
        val file = folder.newFile("grades-new.json")
        val store = GradeStore(file, useAtomicFile = false)

        val emptyTerm = totals(null,
            course("Физика", "РК1" to ""),
            course("История", "РК1" to ""),
            course("Литература", "РК1" to ""))
        val gen = store.generation
        val ver = store.version
        assertTrue(store.keep(emptyTerm, gen, after = ver))

        // First real grade in the same term IS announced.
        val firstGrade = totals(null,
            course("Физика", "РК1" to "88"),
            course("История", "РК1" to ""),
            course("Литература", "РК1" to ""))
        assertEquals(listOf(GradeChange("Физика", "РК1", "88", "100")), firstGrade.changesFrom(store.baseSnapshot(firstGrade)))

        // Different term with no overlapping courses is silent even though the previous term had only empty scores.
        val otherTerm = totals(null, course("Химия", "РК1" to "92"), course("Биология", "РК1" to "80"))
        assertEquals(emptyList<GradeChange>(), otherTerm.changesFrom(store.baseSnapshot(otherTerm)))

        // Stale version or erased generation is refused.
        assertFalse(store.keep(firstGrade, gen, after = ver))
        store.erase()
        assertFalse(store.keep(firstGrade, gen))
        assertNull(store.base(firstGrade))
    }
}
