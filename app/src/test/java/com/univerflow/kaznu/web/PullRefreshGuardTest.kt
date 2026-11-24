package com.univerflow.kaznu.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PullRefreshGuardTest {
    private class Harness(
        var url: String? = "https://univer.kaznu.kz/student/messages/",
        var epoch: Long = 1L,
        var allowed: Boolean = true,
    ) {
        var timeout: Runnable? = null
        val cancelledTimeouts = mutableListOf<Runnable>()
        val evaluations = mutableListOf<Pair<String, (String?) -> Unit>>()
        var reloads = 0
        var stops = 0
        val kept = mutableListOf<PullRefreshGuard.Kept>()

        val guard = PullRefreshGuard(
            scheduleTimeout = { timeout = it },
            cancelTimeout = {
                cancelledTimeouts += it
                if (timeout === it) timeout = null
            },
            evaluate = { script, callback -> evaluations += script to callback },
            allowed = { allowed },
            epoch = { epoch },
            currentUrl = { url },
            onReload = { reloads++ },
            onStop = { stops++ },
            onKept = { kept += it },
        )

        fun fireTimeout() {
            val task = timeout
            assertNotNull("Expected a scheduled guard timeout", task)
            timeout = null
            task!!.run()
        }

        fun answer(index: Int = evaluations.lastIndex, value: String?) {
            evaluations[index].second(value)
        }
    }

    @Test fun reloadsOnlyAfterExplicitOkFromTheCurrentDocument() {
        val h = Harness()
        h.guard.start()
        assertEquals(1, h.evaluations.size)
        assertEquals(PageScripts.REFRESH_GUARD, h.evaluations.single().first)
        val scheduled = h.timeout
        assertNotNull(scheduled)

        h.answer(value = "\"ok\"")

        assertEquals(1, h.reloads)
        assertEquals(0, h.stops)
        assertTrue(h.kept.isEmpty())
        assertNull(h.timeout)
        assertEquals(listOf(scheduled), h.cancelledTimeouts)
    }

    @Test fun draftFieldWithDelayedEditedAnswerStopsOnTimeoutAndNeverReloads() {
        val h = Harness()
        h.guard.start()

        // WebView takes longer than 1.5 s on a page with a typed draft.
        h.fireTimeout()
        assertEquals(0, h.reloads)
        assertEquals(1, h.stops)
        assertTrue(h.kept.isEmpty())

        // The script later answers 'edited' (or even 'ok'): still ignored, never reloads.
        h.answer(value = "\"edited\"")
        assertEquals(0, h.reloads)
        assertEquals(1, h.stops)
        assertTrue(h.kept.isEmpty())
    }

    @Test fun delayedOkAnswerAfterTimeoutDoesNotReload() {
        val h = Harness()
        h.guard.start()
        h.fireTimeout()
        assertEquals(0, h.reloads)
        assertEquals(1, h.stops)

        h.answer(value = "\"ok\"")
        assertEquals(0, h.reloads)
        assertEquals(1, h.stops)
    }

    @Test fun unexpectedAnswersStopIndicatorWithoutReloading() {
        val unexpected = listOf(null, "null", "", "\"\"", "\"unexpected\"", "true", "0", "ok", "\"\"ok\"\"")
        for (raw in unexpected) {
            val h = Harness()
            h.guard.start()
            h.answer(value = raw)
            assertEquals("Unexpected answer $raw must not reload", 0, h.reloads)
            assertEquals("Unexpected answer $raw must stop indicator", 1, h.stops)
            assertTrue("Unexpected answer $raw must not show kept message", h.kept.isEmpty())
            assertNull(h.timeout)
        }
    }

    @Test fun editedAndDialogKeepThePageAndReportReason() {
        val edited = Harness()
        edited.guard.start()
        edited.answer(value = "\"edited\"")
        assertEquals(0, edited.reloads)
        assertEquals(1, edited.stops)
        assertEquals(listOf(PullRefreshGuard.Kept.INPUT), edited.kept)

        val dialog = Harness()
        dialog.guard.start()
        dialog.answer(value = "\"dialog\"")
        assertEquals(0, dialog.reloads)
        assertEquals(1, dialog.stops)
        assertEquals(listOf(PullRefreshGuard.Kept.DIALOG), dialog.kept)
    }

    @Test fun answerFromPreviousDocumentNeverReloadsOrShowsStaleNotice() {
        // Epoch advanced (new navigation started) before "ok" arrived.
        val epochChanged = Harness()
        epochChanged.guard.start()
        epochChanged.epoch++
        epochChanged.answer(value = "\"ok\"")
        assertEquals(0, epochChanged.reloads)
        assertEquals(1, epochChanged.stops)

        // URL changed before "ok" arrived.
        val urlChanged = Harness()
        urlChanged.guard.start()
        urlChanged.url = "https://univer.kaznu.kz/student/myschedule/"
        urlChanged.answer(value = "\"ok\"")
        assertEquals(0, urlChanged.reloads)
        assertEquals(1, urlChanged.stops)

        // Epoch advanced before "edited" arrived: no stale snackbar on the new page.
        val staleEdited = Harness()
        staleEdited.guard.start()
        staleEdited.epoch++
        staleEdited.answer(value = "\"edited\"")
        assertEquals(0, staleEdited.reloads)
        assertEquals(1, staleEdited.stops)
        assertTrue(staleEdited.kept.isEmpty())
    }

    @Test fun disallowedStateWhileWaitingStopsWithoutReloading() {
        val h = Harness()
        h.guard.start()
        h.allowed = false
        h.answer(value = "\"ok\"")
        assertEquals(0, h.reloads)
        assertEquals(1, h.stops)
    }

    @Test fun untrustedOrLoginPageStopsImmediatelyWithoutEvaluatingScript() {
        for (badUrl in listOf(null, "https://example.com/", "https://univer.kaznu.kz/user/login")) {
            val h = Harness(url = badUrl)
            h.guard.start()
            assertEquals(0, h.reloads)
            assertEquals(1, h.stops)
            assertTrue(h.evaluations.isEmpty())
            assertNull(h.timeout)
        }
    }

    @Test fun lateAnswerFromTimedOutPullDoesNotInterfereWithNextPull() {
        val h = Harness()
        h.guard.start()
        h.fireTimeout()
        assertEquals(1, h.stops)

        // Student pulls again; the first pull's late "edited" arrives while the second is in flight.
        h.guard.start()
        h.answer(index = 0, value = "\"edited\"")
        assertEquals(0, h.reloads)
        assertEquals(1, h.stops)
        assertTrue(h.kept.isEmpty())
        assertNotNull("Second pull's timeout must remain armed", h.timeout)

        // Second pull answers "ok" and reloads cleanly.
        h.answer(index = 1, value = "\"ok\"")
        assertEquals(1, h.reloads)
        assertEquals(1, h.stops)
    }

    @Test fun cancelDisarmsTimeoutAndIgnoresLateAnswer() {
        val h = Harness()
        h.guard.start()
        val scheduled = h.timeout
        assertNotNull(scheduled)

        h.guard.cancel()
        assertEquals(listOf(scheduled), h.cancelledTimeouts)
        assertNull(h.timeout)

        h.answer(value = "\"ok\"")
        assertEquals(0, h.reloads)
        assertEquals(0, h.stops)
    }
}
