package com.univerflow.kaznu.web

import com.univerflow.kaznu.grades.JournalPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** The saved password goes into a reauthentication dialog only once the app's own request found the session over. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionCheckTest {
    @Test fun onlyASignedOutAnswerLetsThePasswordIn() {
        assertEquals(true, SessionCheck.of(JournalPage.SignedOut))
        // Still signed in: a dialog now is a lookalike a page script drew, not the site's own.
        assertEquals(false, SessionCheck.of(JournalPage.Found("<html>journal</html>")))
        assertNull(SessionCheck.of(JournalPage.Failed))
    }

    @Test fun overStandsOnlyForTheDialogItWasAskedAbout() {
        assertTrue(SessionCheck.reuse(true, sameDialog = true, at = 1_000, now = 1_000 + SessionCheck.OVER_FOR - 1))
        // An old "over" (the app was minimised meanwhile) is asked again, even for the same dialog.
        assertFalse(SessionCheck.reuse(true, sameDialog = true, at = 1_000, now = 1_000 + SessionCheck.OVER_FOR))
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
        assertTrue(answers.stands("dialog", arrivedAt + SessionCheck.OVER_FOR - 1))
        assertFalse(answers.stands("dialog", arrivedAt + SessionCheck.OVER_FOR))
        assertFalse(answers.stands("next", arrivedAt + 1))
    }

    @Test fun theCheckNeverKeepsACookieFromItsAnswer() = runTest {
        // However late the answer comes (after a sign-out cleared the cookies, another account signed in, or the check
        // was dropped), the fetch is told not to keep the renewed cookie.
        val keeps = mutableListOf<Boolean>()
        val agents = mutableListOf<String>()
        val ask = SessionCheck.journal({ "agent" }) { agent, _, current ->
            agents += agent; keeps += current(); JournalPage.Found("<html>journal</html>")
        }
        assertEquals(false, ask("ASP.NET_SessionId=a"))
        assertEquals(listOf(false), keeps)
        assertEquals(listOf("agent"), agents)
        assertFalse(SessionCheck.KEEP_NO_COOKIE())
    }

    private val key = SessionCheck.Key(1, "ASP.NET_SessionId=a", "doc", "dialog")

    @Test fun aLoopStartedAfterReopeningSharesTheRequestInFlight() = runTest {
        var requests = 0
        val answer = CompletableDeferred<Boolean?>()
        val shared = SessionCheck.Shared(backgroundScope, { 7L }) { requests++; answer.await() }
        // The first loop asks; it does not wait, so it goes on probing the page while the request is out.
        assertNull(shared.poll(key)); runCurrent()
        assertNull(shared.poll(key)); runCurrent()
        // Minimised and reopened on the same page and dialog: the new loop gets the same request, no second one.
        assertNull(shared.poll(key.copy())); runCurrent()
        assertEquals(1, requests)
        answer.complete(true); runCurrent()
        val reply = shared.poll(key)
        assertEquals(true, reply?.over)
        assertEquals(7L, reply?.at)
        assertEquals(1, requests)
        // Each answer is given once: a later question is a new request.
        assertNull(shared.poll(key)); runCurrent()
        assertEquals(2, requests)
    }

    @Test fun aSignedOutAnswerForAnOldDialogNeverReachesANewOne() = runTest {
        var requests = 0
        val answers = mutableListOf(CompletableDeferred<Boolean?>(), CompletableDeferred())
        // The blocking read is not cancellable: it ends only when its answer comes, whatever the coroutine was told.
        val shared = SessionCheck.Shared(backgroundScope, { 0L }) { val i = requests++; kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { answers[i].await() } }
        assertNull(shared.poll(key)); runCurrent()
        // A new page draws a new dialog (perhaps a lookalike, after the session was renewed) before the old request returns.
        val next = SessionCheck.Key(1, "ASP.NET_SessionId=b", "doc2", "dialog2")
        assertNull(shared.poll(next)); runCurrent()
        assertTrue(shared.busy)
        assertEquals("no second request while the first read is still out", 1, requests)
        // The old request returns "signed out": it is discarded, never given to the new dialog.
        answers[0].complete(true); runCurrent()
        assertFalse(shared.busy)
        assertNull(shared.poll(next)); runCurrent()
        assertEquals("the new dialog is asked about afresh", 2, requests)
        answers[1].complete(false); runCurrent()
        assertEquals(false, shared.poll(next)?.over)
    }

    @Test fun anAnswerForAnotherAccountOrSessionIsNeverGiven() = runTest {
        for (other in listOf(key.copy(account = 2), key.copy(session = null), key.copy(document = "doc2"), key.copy(challenge = "x"))) {
            val answer = CompletableDeferred<Boolean?>()
            val shared = SessionCheck.Shared(backgroundScope, { 0L }) { answer.await() }
            assertNull(shared.poll(key)); runCurrent()
            answer.complete(true); runCurrent()
            assertNull("$other", shared.poll(other))
        }
    }

    @Test fun theRequestSendsTheCookieSnapshotOfItsKeyNotTheJarAtFetchTime() = runTest {
        // The jar the fetch would read: signed in when the check is scheduled, briefly signed out when it runs.
        var jar: String? = "ASP.NET_SessionId=a"
        val sent = mutableListOf<String?>()
        // The fetch JournalFetch.get makes: no header means "signed out".
        fun fetch(cookie: String?) = if (cookie == null) JournalPage.SignedOut else JournalPage.Found("<html>journal</html>")
        // The journal check passes the snapshot it is given to the fetch, never what the jar holds when it runs.
        val ask = SessionCheck.journal({ "agent" }) { _, cookie, _ -> sent += cookie; fetch(cookie) }
        jar = null
        assertEquals(false, ask("ASP.NET_SessionId=a"))
        assertEquals(listOf<String?>("ASP.NET_SessionId=a"), sent)
        sent.clear(); jar = "ASP.NET_SessionId=a"
        val shared = SessionCheck.Shared(backgroundScope, { 0L }) { cookie -> sent += cookie; SessionCheck.of(fetch(cookie)) }
        val asked = key.copy(session = SessionCheck.session(jar))
        assertNull(shared.poll(asked))
        jar = null // Changed between scheduling the check and the fetch running.
        runCurrent()
        jar = "ASP.NET_SessionId=a" // And back before the loop polls again.
        assertEquals(listOf<String?>("ASP.NET_SessionId=a"), sent)
        // The answer is about the header that was sent, the signed-in one: not a "signed out" for the live session.
        assertEquals(false, shared.poll(key.copy(session = SessionCheck.session(jar)))?.over)
    }

    @Test fun theSessionIsExactlyTheCookieHeaderTheJournalRequestSends() {
        // Any cookie the request carries is part of it, whatever its name.
        assertEquals("lang=ru; ASP.NET_SessionId=a; _ga=1", SessionCheck.session("lang=ru; ASP.NET_SessionId=a; _ga=1"))
        assertTrue(SessionCheck.session("ASP.NET_SessionId=a; tok=1") != SessionCheck.session("ASP.NET_SessionId=a; tok=2"))
        assertTrue(SessionCheck.session(".ASPXAUTH=x; ASP.NET_SessionId=a") != SessionCheck.session(".ASPXAUTH=y; ASP.NET_SessionId=a"))
        // No header (JournalFetch then sends nothing and reads the session as over).
        assertNull(SessionCheck.session(null))
        assertNull(SessionCheck.session(" "))
    }

    @Test fun aSignedOutAnswerStandsOnlyForTheWholeKeyItWasAskedFor() {
        val answers = SessionCheck.Answers()
        answers.record(key, true, 1_000)
        assertTrue(answers.stands(key.copy(), 2_000))
        // Same dialog still on screen, but the session was renewed, the account changed or the document reloaded.
        assertFalse(answers.stands(key.copy(session = "ASP.NET_SessionId=renewed"), 2_000))
        assertFalse(answers.stands(key.copy(account = 2), 2_000))
        assertFalse(answers.stands(key.copy(document = "doc2"), 2_000))
        // Delayed past the reuse window (the attempt budget, or the app minimised): asked afresh.
        assertFalse(answers.stands(key, 1_000 + SessionCheck.OVER_FOR))
    }
}
