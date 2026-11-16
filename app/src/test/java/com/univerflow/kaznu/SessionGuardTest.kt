package com.univerflow.kaznu

import com.univerflow.kaznu.web.RecoveryBackoff
import com.univerflow.kaznu.web.SessionGuard
import com.univerflow.kaznu.web.SitePolicy
import org.junit.Assert.*
import org.junit.Test

class SessionGuardTest {
    @Test fun anExplicitNoClickReturnsTheReservationButUnknownResultsKeepIt() {
        val guard = SessionGuard()
        assertTrue(guard.tryLogin("page", 0))
        guard.releaseLogin("old-page")
        assertEquals("page", guard.pendingDocument)
        guard.releaseLogin("page")
        assertFalse(guard.loginStillVisible("page", 70_000))
        assertTrue("No request was sent, so its time budget is returned", guard.tryLogin("page", 1_000))
        guard.authenticated()
        assertTrue(guard.tryReauthenticate("a", 2_000, "page"))
        guard.releaseReauthentication("old")
        assertEquals("a", guard.pendingReauthentication)
        guard.releaseReauthentication("a")
        repeat(3) { index ->
            assertTrue(guard.tryReauthenticate("next-$index", 3_000L + index))
            guard.reauthenticationResult("next-$index", true)
        }
        assertFalse("Real submissions still spend the budget", guard.tryReauthenticate("fourth", 4_000))
    }
    @Test fun firstFullPageRefusalWaitsOneMinuteAndSecondRefusalStops() {
        val guard = SessionGuard()
        assertTrue(guard.tryLogin("first-document", 1_000))
        assertFalse(guard.tryLogin("first-document", 2_000))
        assertTrue(guard.loginStillVisible("new-login-document", 3_000))
        assertTrue(guard.recoverable)
        assertFalse(guard.tryLogin("new-login-document", 62_999))
        assertTrue(guard.tryLogin("new-login-document", 63_000))
        assertTrue(guard.loginStillVisible("second-refusal", 64_000))
        assertFalse(guard.recoverable)
        assertFalse(guard.tryLogin("another-form", 1_000_000))
        guard.retry()
        assertTrue(guard.tryLogin("new-login-document", 100_000))
    }
    @Test fun retryWithAWrongPasswordNeverSendsMoreThanThreeLoginsInTenMinutes() {
        val guard = SessionGuard()
        assertTrue(guard.tryLogin("a", 0)); assertTrue(guard.loginStillVisible("b", 1_000))
        assertTrue(guard.tryLogin("b", 61_000)); assertTrue(guard.loginStillVisible("c", 62_000))
        assertFalse(guard.recoverable)
        guard.retry()
        assertFalse("Retry keeps a minute between logins", guard.tryLogin("c", 63_000))
        assertTrue("Retry lifts the stop for one more try", guard.tryLogin("c", 121_000))
        assertTrue(guard.loginStillVisible("d", 122_000))
        assertFalse("After two refusals the next one stops at once", guard.recoverable)
        var sent = 0
        repeat(10) { guard.retry(); if (guard.tryLogin("e$it", 125_000L + it)) sent++ }
        assertEquals(0, sent)
        assertTrue("The budget refills as old attempts leave the window", guard.tryLogin("f", 600_001))
        guard.reset()
        assertTrue("New credentials start afresh", guard.tryLogin("g", 600_002))
    }
    @Test fun stalledSubmissionStopsAndSuccessfulSessionCanExpireLater() {
        val guard = SessionGuard()
        guard.tryLogin("one", 0)
        assertFalse(guard.loginStillVisible("one", 24_000))
        assertTrue(guard.loginStillVisible("one", 26_000))
        guard.retry()
        // Retry keeps the login window (review L5): the next login still waits a minute after the first.
        assertFalse(guard.tryLogin("two", 30_000))
        assertTrue(guard.tryLogin("two", 60_000))
        guard.authenticated()
        assertTrue(guard.tryLogin("three", 120_000))
    }
    @Test fun sessionEndingSoonAfterSuccessfulLoginWaitsInsteadOfStopping() {
        val guard = SessionGuard()
        assertTrue(guard.tryLogin("one", 0))
        guard.authenticated()
        assertFalse("A minute between logins", guard.tryLogin("two", 5_000))
        assertFalse("Waiting is not a failure", guard.blocked)
        assertTrue("Continues by itself once the minute passes", guard.tryLogin("two", 60_000))
        guard.authenticated()
        assertTrue(guard.tryLogin("three", 120_000))
        guard.authenticated()
        assertFalse("Three logins in ten minutes", guard.tryLogin("four", 180_000))
        assertTrue(guard.loginBudgetExhausted(180_000))
        assertFalse(guard.blocked)
        assertTrue(guard.tryLogin("four", 600_000))
    }
    @Test fun moodleEntryWaitsLongerForTheSlowHandOffPost() {
        val guard = SessionGuard(loginTimeout = 80_000)
        assertTrue(guard.tryLogin("entry", 0))
        assertFalse("A 30 s answer is normal for the Moodle entry", guard.loginStillVisible("entry", 30_000))
        assertTrue(guard.loginStillVisible("entry", 81_000))
    }
    @Test fun lateSuccessAfterStalledSubmitRestoresAutomaticLogin() {
        val guard = SessionGuard()
        assertTrue(guard.tryLogin("login", 0))
        assertTrue(guard.loginStillVisible("login", 26_000))
        assertFalse("The stalled document itself never lifts the stop", guard.authenticatedPage("login"))
        assertTrue("The slow POST finished on a signed-in page", guard.authenticatedPage("news"))
        assertFalse(guard.blocked)
        assertTrue("A later expiry signs in again", guard.tryLogin("later-login", 3_600_000))
    }
    @Test fun stalledSubmitAllowsANewLoginDocumentWithinBudget() {
        val guard = SessionGuard()
        assertTrue(guard.tryLogin("first", 0))
        assertTrue(guard.loginStillVisible("first", 26_000))
        assertTrue("Same stalled form stays stopped", guard.loginStillVisible("first", 70_000))
        assertTrue("A fresh document still waits a minute after the timeout", guard.loginStillVisible("second", 70_000))
        assertFalse(guard.loginStillVisible("second", 86_000))
        assertTrue(guard.tryLogin("second", 86_000))
        assertTrue(guard.loginStillVisible("third", 88_000))
        assertTrue("A timeout did not count as a rejected password", guard.tryLogin("third", 148_000))
        assertTrue(guard.loginStillVisible("fourth", 149_000))
        assertFalse(guard.tryLogin("fifth", 800_000))
    }
    @Test fun aProvenSignInResetsTheFullPageRefusalCount() {
        val guard = SessionGuard()
        assertTrue(guard.tryLogin("first", 0))
        assertTrue(guard.loginStillVisible("second", 1_000))
        assertTrue("The user signed in by hand", guard.authenticatedPage("profile"))
        assertTrue(guard.tryLogin("next-expiry", 200_000))
        assertTrue(guard.loginStillVisible("new-refusal", 201_000))
        assertTrue("The previous refusal no longer counts", guard.recoverable)
    }
    @Test fun explicitStopIsNeverLiftedAutomatically() {
        val guard = SessionGuard()
        guard.stop()
        assertFalse(guard.authenticatedPage("news"))
        assertFalse(guard.tryLogin("login", 0))
        guard.retry()
        assertTrue(guard.tryLogin("login", 0))
    }
    @Test fun reauthenticationTimeoutLiftsOnANewSignedInPageButNotOnItsOwnPage() {
        val guard = SessionGuard()
        assertTrue(guard.tryReauthenticate("challenge", 0, "page-a"))
        assertTrue(guard.reauthenticationTimedOut(26_000))
        assertFalse(guard.authenticatedPage("page-a"))
        assertTrue(guard.loginStillVisible("login-b", 30_000))
        assertFalse(guard.tryLogin("login-b", 85_999))
        assertTrue("An ended session may use the full-page form after the wait", guard.tryLogin("login-b", 86_000))
    }
    @Test fun recreatingTheWebViewDuringASlowLoginIsNotTheSitesRejection() {
        val guard = SessionGuard(loginTimeout = 60_000)
        assertTrue(guard.tryLogin("old-document", 0))
        guard.detach() // Rotation: the old WebView and its POST are gone.
        assertFalse("A fresh form after recreation is not a rejected password", guard.loginStillVisible("new-document", 10_000))
        assertFalse(guard.blocked)
        assertFalse("The attempt budget survives: a minute between submissions", guard.tryLogin("new-document", 10_000))
        assertTrue("and the login continues by itself", guard.tryLogin("new-document", 60_000))
    }
    @Test fun lateVerifiedAjaxAnswerLiftsOnlyTheTimeoutStop() {
        val guard = SessionGuard(reauthenticationTimeout = 25_000)
        assertTrue(guard.tryReauthenticate("challenge", 0, "page"))
        assertTrue(guard.reauthenticationTimedOut(26_000))
        assertEquals("challenge", guard.lateReauthentication)
        assertTrue("The slow answer proved the password", guard.reauthenticationResult("challenge", true))
        assertFalse(guard.blocked)
        assertNull(guard.lateReauthentication)
        assertFalse("A completed challenge is never resubmitted", guard.tryReauthenticate("challenge", 40_000, "page"))
    }
    @Test fun lateAjaxRejectionStaysAFailureAndAStopIsNeverLifted() {
        val guard = SessionGuard(reauthenticationTimeout = 25_000)
        guard.tryReauthenticate("one", 0, "page")
        guard.reauthenticationTimedOut(26_000)
        assertFalse(guard.reauthenticationResult("one", false))
        assertTrue(guard.blocked)
        assertFalse("A refused dialog does not forbid the full-page form after its wait", guard.loginStillVisible("another-form", 90_000))
        guard.retry()
        guard.tryReauthenticate("two", 40_000, "page")
        guard.reauthenticationTimedOut(70_000)
        guard.stop()
        assertFalse("Logout stays a stop", guard.reauthenticationResult("two", true))
        assertTrue(guard.blocked)
    }
    @Test fun noticeReloadDoesNotResubmitButExplicitRetryAllowsIt() {
        val guard = SessionGuard()
        assertTrue(guard.canSubmitNotice("semester-notice"))
        guard.noticeSubmitted("semester-notice")
        assertFalse(guard.canSubmitNotice("semester-notice"))
        assertTrue(guard.canSubmitNotice("new-notice"))
        guard.retry()
        assertTrue(guard.canSubmitNotice("semester-notice"))
    }
    @Test fun laterNoticeIsAllowedOnlyAfterStableNormalPageAndRapidCyclesAreBounded() {
        val guard = SessionGuard()
        guard.noticeSubmitted("same", 0)
        guard.observeNotices(false, 100)
        assertFalse(guard.canSubmitNotice("same", 1_000))
        guard.observeNotices(true, 1_100) // A transient disappearance must not release the reservation.
        assertFalse(guard.canSubmitNotice("same", 2_000))
        repeat(2) { index ->
            val now = 3_000L + index * 4_000
            guard.observeNotices(false, now)
            guard.observeNotices(false, now + 2_000)
            assertTrue(guard.canSubmitNotice("same", now + 2_000))
            guard.noticeSubmitted("same", now + 2_000)
        }
        guard.observeNotices(false, 12_000)
        guard.observeNotices(false, 14_000)
        assertFalse(guard.canSubmitNotice("same", 14_000))
        assertTrue(guard.canSubmitNotice("same", 65_000))
    }
    @Test fun ajaxReauthenticationRequiresMatchingSuccessAndAllowsLaterExpiredSession() {
        val guard = SessionGuard()
        assertTrue(guard.tryReauthenticate("first", 0))
        guard.reauthenticationResult("unrelated", true)
        assertEquals("first", guard.pendingReauthentication)
        assertFalse(guard.tryReauthenticate("second", 5_000))
        guard.reauthenticationResult("first", true)
        assertNull(guard.pendingReauthentication)
        assertFalse(guard.blocked)
        assertFalse("A completed challenge must never be resubmitted", guard.tryReauthenticate("first", 1_000))
        assertTrue("A new verified session expiry within a minute still uses saved credentials", guard.tryReauthenticate("second", 5_000))
        guard.reauthenticationResult("second", false)
        assertTrue(guard.blocked)
        assertTrue("A newer document can reauthenticate after the wait", guard.tryReauthenticate("third", 602_000))
    }
    @Test fun disappearingDialogWithoutAjaxResultTimesOutButVerifiedSuccessAllowsNewChallenge() {
        val guard = SessionGuard()
        guard.tryReauthenticate("one", 0)
        assertFalse(guard.reauthenticationTimedOut(24_000))
        assertTrue(guard.reauthenticationTimedOut(26_000))
        guard.retry()
        assertTrue(guard.tryReauthenticate("two", 30_000))
        guard.reauthenticationResult("two", true)
        assertTrue(guard.tryReauthenticate("three", 31_000))
        assertFalse(guard.blocked)
    }
    @Test fun successfulReauthenticationStormWaitsForBudgetWithoutBlockingSavedAccount() {
        val guard = SessionGuard()
        repeat(3) { index ->
            assertTrue(guard.tryReauthenticate("challenge-$index", index * 1_000L))
            guard.reauthenticationResult("challenge-$index", true)
        }
        assertFalse(guard.tryReauthenticate("fourth", 3_000))
        assertFalse(guard.blocked)
        assertNull(guard.pendingReauthentication)
        assertTrue(guard.tryReauthenticate("fourth", 60_000))
    }
    @Test fun refusedReauthenticationContinuesOnlyOnANewerDocumentAfterOneMinute() {
        val guard = SessionGuard()
        assertTrue(guard.tryReauthenticate("first", 0, "page-a"))
        guard.reauthenticationResult("first", false, 5_000)
        assertFalse(guard.tryReauthenticate("second", 64_999, "page-b"))
        assertFalse(guard.tryReauthenticate("second", 90_000, "page-a"))
        assertTrue(guard.blocked)
        assertTrue(guard.tryReauthenticate("second", 90_000, "page-b"))
    }
    @Test fun refusedDialogAfterALongBreakStillAllowsOneFullPageLogin() {
        val guard = SessionGuard()
        assertTrue(guard.tryReauthenticate("stale-key-dialog", 0, "page"))
        assertFalse(guard.reauthenticationResult("stale-key-dialog", false))
        assertTrue(guard.blocked)
        assertFalse("The same stuck page stays blocked", guard.tryLogin("page", 1_000))
        assertFalse("The site's login form is allowed after the recovery wait", guard.loginStillVisible("login-form", 60_000))
        assertTrue(guard.tryLogin("login-form", 60_000))
        assertFalse(guard.blocked)
        assertTrue(guard.loginStillVisible("login-form-after-rejection", 63_000))
        assertTrue("AJAX refusals do not count as full-page refusals", guard.recoverable)
        assertFalse(guard.tryLogin("another-form", 70_000))
        guard.retry()
        assertTrue("Retry lifts the block; the minute after the last login still applies", guard.tryLogin("another-form", 120_000))
    }
    @Test fun secondDialogAttemptMarkerIsNotAnExplicitStop() {
        val guard = SessionGuard()
        guard.reauthenticationRejected("page")
        assertTrue(guard.blocked)
        assertTrue(guard.loginStillVisible("login-form", 1_000))
        assertFalse(guard.loginStillVisible("login-form", 60_000))
        assertTrue(guard.tryLogin("login-form", 60_000))
    }
    @Test fun refusedDialogNeverWeakensAConfirmedFailureOrAStop() {
        val failed = SessionGuard()
        assertTrue(failed.tryLogin("form", 0))
        assertTrue(failed.loginStillVisible("next-form", 3_000))
        assertTrue(failed.tryLogin("next-form", 63_000))
        assertTrue(failed.loginStillVisible("second-refusal", 64_000))
        failed.reauthenticationRejected("page")
        assertTrue("A confirmed full-page rejection stays final", failed.loginStillVisible("third-form", 70_000))
        val stopped = SessionGuard()
        stopped.stop()
        stopped.reauthenticationRejected("page")
        assertTrue("Logout stays a stop", stopped.loginStillVisible("form", 5_000))
        assertFalse(stopped.tryLogin("form", 5_000))
    }
    @Test fun agreementAndNoticeHaveIndependentReservationBudgets() {
        val guard = SessionGuard()
        guard.noticeSubmitted("same", 0)
        assertTrue(guard.canSubmitAgreement("same", 0))
        guard.agreementSubmitted("same", 0)
        assertFalse(guard.canSubmitAgreement("same", 1_000))
        guard.observeNotices(false, 1_000)
        guard.observeNotices(false, 3_000)
        assertTrue(guard.canSubmitNotice("same", 3_000))
        assertFalse(guard.canSubmitAgreement("same", 3_000))
    }
    @Test fun stalledOrRefusedAttemptsAreRecoverableButTwoFullPageRejectionsAndStopsAreNot() {
        val stalled = SessionGuard()
        assertTrue(stalled.tryLogin("form", 0))
        assertTrue(stalled.loginStillVisible("form", 26_000))
        assertTrue("A stalled submit is reloaded by itself", stalled.recoverable)
        val refused = SessionGuard()
        assertTrue(refused.tryReauthenticate("dialog", 0, "page"))
        refused.reauthenticationResult("dialog", false)
        assertTrue("A refused dialog is reloaded to the full-page form", refused.recoverable)
        val rejected = SessionGuard()
        assertTrue(rejected.tryLogin("form", 0))
        assertTrue(rejected.loginStillVisible("next-form", 3_000))
        assertTrue(rejected.tryLogin("next-form", 63_000))
        assertTrue(rejected.loginStillVisible("second-refusal", 64_000))
        assertFalse("A rejected password is never retried by itself", rejected.recoverable)
        val stopped = SessionGuard()
        stopped.stop()
        assertFalse(stopped.recoverable)
    }
    @Test fun repeatedAjaxRefusalsRemainRecoverable() {
        val guard = SessionGuard()
        assertTrue(guard.tryReauthenticate("first", 0, "page-a"))
        guard.reauthenticationResult("first", false)
        assertTrue("The page itself is still signed in", guard.authenticatedPage("page-b"))
        assertTrue(guard.tryReauthenticate("second", 70_000, "page-b"))
        guard.reauthenticationResult("second", false)
        assertTrue(guard.blocked)
        assertTrue("A stale AJAX key never proves a wrong password", guard.recoverable)
        assertTrue(guard.loginStillVisible("login-form", 80_000))
        assertFalse(guard.tryLogin("login-form", 80_000))
        assertTrue(guard.tryLogin("login-form", 130_000))
    }
    @Test fun refusalAfterAProvingFullPageSignInIsAmbiguousAgain() {
        val guard = SessionGuard()
        assertTrue(guard.tryReauthenticate("stale", 0, "page"))
        guard.reauthenticationResult("stale", false)
        assertTrue(guard.tryLogin("login-form", 60_000))
        guard.authenticated()
        assertTrue(guard.tryReauthenticate("later", 3_600_000, "news"))
        guard.reauthenticationResult("later", false)
        assertTrue("Proven password: a stale key again, not a rejection", guard.recoverable)
    }
    @Test fun errorPageDuringSubmitDoesNotMakeTheNextFormARejection() {
        val guard = SessionGuard()
        assertTrue(guard.tryLogin("form", 0))
        guard.detach() // The POST ended in a network or server error page.
        assertFalse(guard.loginStillVisible("fresh-form", 5_000))
        assertFalse(guard.blocked)
        assertTrue("A minute after the lost submit", guard.tryLogin("fresh-form", 60_000))
    }
    @Test fun confirmationStillShownAfterItsSubmitIsSentAgainAtMostThreeTimesPerTenMinutes() {
        val guard = SessionGuard()
        guard.noticeSubmitted("notice", 0)
        assertFalse("Its answer may still come", guard.canSubmitNotice("notice", 19_000))
        assertTrue("Still shown: the POST was lost", guard.canSubmitNotice("notice", 20_000))
        guard.noticeSubmitted("notice", 20_000)
        assertTrue(guard.canSubmitNotice("notice", 80_000))
        guard.noticeSubmitted("notice", 80_000)
        assertFalse("Three sends of one appearance", guard.canSubmitNotice("notice", 200_000))
        assertTrue("A new ten-minute window", guard.canSubmitNotice("notice", 600_000))
        guard.agreementSubmitted("agreement", 0)
        assertFalse(guard.canSubmitAgreement("agreement", 10_000))
        assertTrue(guard.canSubmitAgreement("agreement", 30_000))
    }
    @Test fun confirmationSentInTheEndedSessionIsSentAgainAfterTheNextLogin() {
        val guard = SessionGuard()
        guard.noticeSubmitted("notice", 0) // Its POST was redirected to the login form.
        guard.agreementSubmitted("agreement", 0)
        assertFalse(guard.canSubmitNotice("notice", 5_000))
        assertTrue(guard.tryLogin("login", 5_000))
        guard.authenticated()
        assertTrue("A new session: a new appearance", guard.canSubmitNotice("notice", 8_000))
        assertTrue(guard.canSubmitAgreement("agreement", 8_000))
    }
    @Test fun recoveryReloadsSlowDownUntilAWorkingPageOrExplicitRetry() {
        val guard = SessionGuard()
        assertEquals(3_000, guard.recoveryDelay(0))
        guard.recovering(0)
        assertEquals(10_000, guard.recoveryDelay(1_000))
        repeat(10) { guard.recovering(2_000L + it) }
        assertEquals("Capped, never given up", 300_000, guard.recoveryDelay(3_000))
        guard.recovered()
        assertEquals(3_000, guard.recoveryDelay(4_000))
        guard.recovering(4_000)
        guard.retry()
        assertEquals(0, guard.recoveries)
    }
    @Test fun recoveryBackoffStartsAgainAfterAQuietPeriod() {
        val backoff = RecoveryBackoff(longArrayOf(1, 2, 3), quietReset = 100)
        backoff.spend(0); backoff.spend(10)
        assertEquals(3, backoff.delay(20))
        assertEquals(2, backoff.attempts)
        assertEquals("Ten quiet units later the first wait applies again", 1, backoff.delay(110))
        backoff.spend(110)
        assertEquals(1, backoff.attempts)
    }
    @Test fun onlyExactHttpsOriginIsTrusted() {
        assertTrue(SitePolicy.trusted("https://univer.kaznu.kz/user/login?ReturnUrl=/"))
        assertTrue(SitePolicy.trusted("https://univer.kaznu.kz:443/"))
        listOf("http://univer.kaznu.kz/", "https://univer.kaznu.kz.evil.test/", "https://evil.test/?univer.kaznu.kz",
            "https://evil@univer.kaznu.kz/", "https://univer.kaznu.kz:444/", "file:///user/login", "javascript:alert(1)")
            .forEach { assertFalse(it, SitePolicy.trusted(it)) }
    }
}
