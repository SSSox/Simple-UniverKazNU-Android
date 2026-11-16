package com.univerflow.kaznu.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeoutException

@OptIn(ExperimentalCoroutinesApi::class)
class CoroutinesTest {
    @Test fun aFailureGivesTheFallbackAndASuccessItsValue() = runTest {
        assertEquals(7, attempt(0) { 7 })
        assertEquals(0, attempt(0) { throw IllegalStateException("storage") })
        assertEquals(emptyList<String>(), attempt<List<String>>(emptyList()) { error("network") })
    }

    @Test fun cancellationIsNeverTurnedIntoTheFallback() = runTest {
        var finished = false
        var fallback: Boolean? = null
        val waiting = CompletableDeferred<Boolean>()
        val job = launch {
            fallback = attempt(false) { waiting.await() }
            finished = true // Must not run: the screen that asked has closed.
        }
        runCurrent()
        job.cancel()
        runCurrent()
        assertTrue(job.isCancelled)
        assertFalse("The caller ends with the cancellation", finished)
        assertNull("No fallback stands in for a cancelled request", fallback)
    }

    @Test fun aStepThatRunsOutOfItsOwnTimeIsReportedAsAFailure() = runTest {
        // The way every screen here catches: cancellation is passed on, anything else is shown to the student.
        var reported: Exception? = null
        val job = launch {
            try { withDeadline(5_000) { CompletableDeferred<Unit>().await() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { reported = failure }
        }
        advanceUntilIdle()
        assertTrue("A step with no answer is a failure, not a closing screen", reported is TimeoutException)
        assertFalse(job.isCancelled)
    }

    @Test fun aStepInTimeGivesItsValueAndItsOwnFailure() = runTest {
        assertEquals(7, withDeadline(5_000) { 7 })
        assertNull(withDeadline<String?>(5_000) { null })
        val thrown = runCatching { withDeadline(5_000) { throw IOException("storage") } }.exceptionOrNull()
        assertTrue(thrown is IOException)
    }

    @Test fun aCallerCancelledDuringAStepStillEndsAsCancelled() = runTest {
        var reported: Exception? = null
        var finished = false
        val job = launch {
            try { withDeadline(60_000) { CompletableDeferred<Unit>().await() }; finished = true }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { reported = failure }
        }
        runCurrent()
        job.cancel()
        runCurrent()
        assertTrue(job.isCancelled)
        assertNull("A closing screen is not a timeout", reported)
        assertFalse(finished)
    }

    @Test fun aThrownCancellationIsRethrownToo() = runTest {
        val thrown = runCatching { attempt(false) { throw CancellationException("newer request") } }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
    }
}
