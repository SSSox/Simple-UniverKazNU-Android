package com.univerflow.kaznu.web

import com.univerflow.kaznu.grades.JournalPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The saved password goes into a reauthentication dialog only once the app's own request found the session over. */
class SessionCheckTest {
    @Test fun onlyASignedOutAnswerLetsThePasswordIn() {
        assertEquals(true, SessionCheck.of(JournalPage.SignedOut))
        // Still signed in: a dialog now is a lookalike a page script drew, not the site's own.
        assertEquals(false, SessionCheck.of(JournalPage.Found("<html>journal</html>")))
        assertNull(SessionCheck.of(JournalPage.Failed))
    }

    @Test fun overStandsOnlyForTheDialogItWasAskedAbout() {
        assertTrue(SessionCheck.reuse(true, sameDialog = true, at = 1_000, now = 1_000 + 3_600_000))
        // After the student is back in, a new dialog is asked about afresh, however soon.
        assertFalse(SessionCheck.reuse(true, sameDialog = false, at = 1_000, now = 1_001))
    }

    @Test fun otherAnswersStandForAWhileForAnyDialog() {
        // A page drawing one dialog after another gets no new request each time.
        assertTrue(SessionCheck.reuse(false, sameDialog = false, at = 1_000, now = 1_000 + SessionCheck.LIVE_FOR - 1))
        assertFalse(SessionCheck.reuse(false, sameDialog = true, at = 1_000, now = 1_000 + SessionCheck.LIVE_FOR))
        assertTrue(SessionCheck.reuse(null, sameDialog = false, at = 1_000, now = 1_000 + SessionCheck.UNKNOWN_FOR - 1))
        assertFalse(SessionCheck.reuse(null, sameDialog = true, at = 1_000, now = 1_000 + SessionCheck.UNKNOWN_FOR))
        // Never asked, or a clock that went back: ask.
        assertFalse(SessionCheck.reuse(null, sameDialog = true, at = null, now = 5_000))
        assertFalse(SessionCheck.reuse(false, sameDialog = true, at = 5_000, now = 4_000))
    }
    @Test fun withoutNetworkTheQuestionIsAskedLessAndLessOften() {
        assertEquals(15_000L, SessionCheck.unknownFor(1))
        assertEquals(30_000L, SessionCheck.unknownFor(2))
        assertEquals(60_000L, SessionCheck.unknownFor(3))
        assertEquals(SessionCheck.UNKNOWN_MAX, SessionCheck.unknownFor(4))
        assertEquals(SessionCheck.UNKNOWN_MAX, SessionCheck.unknownFor(50))
        val answers = SessionCheck.Answers()
        var now = 0L
        var asked = 0
        // Five minutes with the network away and the dialog on screen, looked at every 600 ms as the observation does.
        while (now < 300_000) {
            if (!answers.stands("dialog", now)) { asked++; now += 2_000; answers.record("dialog", null, now) } // Each request takes 2 s.
            now += 600
        }
        assertTrue("no request every 15 s: $asked", asked <= 6)
        assertEquals(asked, answers.misses)
        // The network is back: an answer ends the back-off.
        answers.record("dialog", true, now)
        assertEquals(0, answers.misses)
        answers.record("dialog", null, now + 1)
        assertFalse(answers.stands("dialog", now + 1 + SessionCheck.UNKNOWN_FOR))
    }

    @Test fun anAnswerStandsFromWhenItArrivedNotFromWhenItWasAsked() {
        val answers = SessionCheck.Answers()
        val askedAt = 10_000L
        val arrivedAt = askedAt + 25_000 // A slow journal request.
        answers.record("dialog", false, arrivedAt)
        assertTrue(answers.stands("other", askedAt + SessionCheck.LIVE_FOR + 1_000))
        assertTrue(answers.stands("other", arrivedAt + SessionCheck.LIVE_FOR - 1))
        assertFalse(answers.stands("other", arrivedAt + SessionCheck.LIVE_FOR))
        // "Over" holds for its own dialog only.
        answers.record("dialog", true, arrivedAt)
        assertTrue(answers.stands("dialog", arrivedAt + 3_600_000))
        assertFalse(answers.stands("next", arrivedAt + 1))
    }
}
