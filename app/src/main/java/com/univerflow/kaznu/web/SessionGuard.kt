package com.univerflow.kaznu.web

/**
 * Attempt budgets that survive Activity recreation through the ViewModel (no secrets). [loginTimeout] and
 * [reauthenticationTimeout] bound the wait for a submitted login and for the AJAX /user/ajl answer.
 */
class SessionGuard(val loginTimeout: Long = 25_000, private val reauthenticationTimeout: Long = 25_000) {
    var retryVersion = 0
        private set
    var blocked = false
        private set
    var pendingDocument: String? = null
        private set
    var pendingReauthentication: String? = null
        private set
    /** A challenge whose answer did not arrive in time: its result may still come and is then honoured. */
    var lateReauthentication: String? = null
        private set
    private var lastLogin: Long? = null
    private var lastReauthentication: Long? = null
    private val logins = ArrayDeque<Long>()
    private val reauthenticationAttempts = ArrayDeque<Long>()
    private val completedReauthentications = linkedSetOf<String>()
    private val notices = ConfirmationBudget()
    private val agreements = ConfirmationBudget()
    // Logout and a dialog bound to another account stop until an explicit Retry. A lost renderer only
    // detaches the attempt in flight: the recreated screen continues by itself.
    private var stopped = false
    // A timeout proves nothing about the password: a later document may try again within the budget.
    private var ambiguous = false
    private var blockedDocument: String? = null
    private var blockedAt: Long? = null
    private var reauthenticationDocument: String? = null
    private val recovery = RecoveryBackoff()
    private var loginRefusals = 0

    /**
     * A stop that a fresh page of the site ends by itself: a stalled or refused attempt, not the login form's
     * rejection of the password or an explicit stop. The automation reloads the page for it.
     */
    val recoverable get() = blocked && ambiguous && !stopped

    /** The wait before the next automatic reload of a page whose automation could not finish. */
    fun recoveryDelay(now: Long) = maxOf(recovery.delay(now), if (recoverable) (blockedAt ?: now) + 60_000 - now else 0)
    fun recovering(now: Long) = recovery.spend(now)
    val recoveries get() = recovery.attempts
    /** A signed-in page without pending forms: the next failure is recovered quickly again. */
    fun recovered() = recovery.reset()

    private fun block(document: String?, now: Long, timeout: Boolean) {
        blocked = true
        ambiguous = timeout
        blockedDocument = document
        blockedAt = now
    }

    /**
     * The site refused an AJAX challenge. A stale dialog key is refused even for a correct password, so automation stops
     * but the full-page form may still sign in once; earlier full-page failures and stops stay as they are.
     */
    private fun rejectChallenge(document: String?, now: Long) {
        if (blocked && !ambiguous) return
        if (!blocked || document != blockedDocument) block(document, now, timeout = true)
    }

    fun reauthenticationRejected(document: String, now: Long = lastReauthentication ?: 0) {
        pendingReauthentication = null
        lateReauthentication = null
        rejectChallenge(document, now)
    }

    private fun loginBudget(now: Long): Boolean {
        while (logins.isNotEmpty() && now - logins.first() >= 600_000) logins.removeFirst()
        return logins.size < 3 && (spacingWaived || logins.lastOrNull()?.let { now - it < 60_000 } != true)
    }

    // A renewal the Moodle window asked for (its session ended, or the student's Retry or link there) skips the minute
    // between logins once, because that window gives up after 90 s. The three logins per ten minutes still apply.
    private var spacingWaived = false
    private var waivedLogin: String? = null
    fun renewal() { retry(); spacingWaived = true }

    /** Three full-page logins per ten minutes, a minute apart. A full budget is a wait, not a failure. */
    fun loginBudgetExhausted(now: Long): Boolean {
        while (logins.isNotEmpty() && now - logins.first() >= 600_000) logins.removeFirst()
        return logins.size >= 3
    }

    private fun liftable(document: String, now: Long) = blocked && ambiguous && !stopped &&
        document != blockedDocument && now - (blockedAt ?: now) >= 60_000

    fun continueOnNewDocument(document: String, now: Long) {
        if (liftable(document, now)) { blocked = false; ambiguous = false; blockedDocument = null; blockedAt = null }
    }

    fun tryLogin(document: String, now: Long): Boolean {
        if (loginBudget(now)) continueOnNewDocument(document, now)
        if (blocked || pendingDocument != null || pendingReauthentication != null) return false
        // A session that ends again soon after a successful login waits for the budget instead of
        // stopping for good: only a confirmed failure (below) needs the user's Retry.
        if (!loginBudget(now)) return false
        pendingDocument = document
        lastLogin = now
        logins.addLast(now)
        waivedLogin = document.takeIf { spacingWaived }; spacingWaived = false
        // A notice or agreement sent in the ended session (its POST redirected to this form) did not count:
        // shown again after this login, it is a new appearance. Their attempt budgets stay.
        notices.release(); agreements.release()
        return true
    }

    fun loginStillVisible(document: String, now: Long): Boolean {
        if (pendingDocument != null) {
            // A new login document after the submit is the site's rejection; the same one is a stall.
            val submitted = pendingDocument
            if (submitted != document) {
                pendingDocument = null
                if (++loginRefusals >= 2) stop() else block(submitted, now, timeout = true)
            }
            else if (now - (lastLogin ?: now) > loginTimeout) { block(document, now, timeout = true); pendingDocument = null }
        }
        return blocked && !liftable(document, now)
    }

    fun authenticated() {
        loginRefusals = 0
        pendingDocument = null
        spacingWaived = false // A renewal that needed no login does not carry its waiver to a later one.
    }

    /** The script verified that the form was no longer ready and no click took place. */
    fun releaseLogin(document: String) {
        if (pendingDocument != document) return
        pendingDocument = null; lastLogin = null
        if (logins.isNotEmpty()) logins.removeLast()
        if (waivedLogin == document) spacingWaived = true
        waivedLogin = null
    }

    fun releaseReauthentication(challenge: String) {
        if (pendingReauthentication != challenge) return
        pendingReauthentication = null; lastReauthentication = null
        if (reauthenticationAttempts.isNotEmpty()) reauthenticationAttempts.removeLast()
    }

    /**
     * A newly loaded page of a signed-in account proves the session works again, e.g. after a slow
     * submit that finished late or a manual login. Lifts every block except an explicit stop.
     */
    fun authenticatedPage(document: String): Boolean {
        if (!blocked || stopped || document == blockedDocument) return false
        blocked = false; ambiguous = false; blockedDocument = null; blockedAt = null; loginRefusals = 0
        return true
    }

    fun tryReauthenticate(challenge: String, now: Long, document: String = challenge): Boolean {
        continueOnNewDocument(document, now)
        if (blocked || pendingReauthentication != null) return false
        if (challenge in completedReauthentications) return false
        while (reauthenticationAttempts.isNotEmpty() && now - reauthenticationAttempts.first() >= 60_000)
            reauthenticationAttempts.removeFirst()
        // Verified successes may be followed by another real challenge. Bound request storms
        // without turning a temporary rate limit into a demand to enter credentials again.
        if (reauthenticationAttempts.size >= 3) return false
        pendingReauthentication = challenge
        lateReauthentication = null
        reauthenticationDocument = document
        lastReauthentication = now
        reauthenticationAttempts.addLast(now)
        return true
    }

    /** True when this result lifted a stop that an unanswered (late) request had caused. */
    fun reauthenticationResult(challenge: String, success: Boolean, now: Long = lastReauthentication ?: 0): Boolean {
        val late = lateReauthentication == challenge
        if (pendingReauthentication != challenge && !late) return false
        pendingReauthentication = null
        lateReauthentication = null
        if (!success) { rejectChallenge(reauthenticationDocument, now); return false }
        completedReauthentications.add(challenge)
        if (completedReauthentications.size > 64) completedReauthentications.remove(completedReauthentications.first())
        // A slow but verified answer proves the password: only the timeout's own stop is lifted.
        if (late && blocked && ambiguous && !stopped) {
            blocked = false; ambiguous = false; blockedDocument = null; blockedAt = null
            return true
        }
        return false
    }

    fun reauthenticationTimedOut(now: Long): Boolean {
        if (pendingReauthentication != null && now - (lastReauthentication ?: now) > reauthenticationTimeout) {
            lateReauthentication = pendingReauthentication
            pendingReauthentication = null
            block(reauthenticationDocument, now, timeout = true)
        }
        return blocked
    }

    /**
     * The WebView of an unfinished attempt is gone (rotation, closed window): only the in-flight state is dropped and
     * the budget stays, since a fresh form in its replacement is not a rejection of the password.
     */
    fun detach() {
        pendingDocument = null
        pendingReauthentication = null
        lateReauthentication = null
    }


    fun canSubmitNotice(key: String, now: Long = 0) = notices.canSubmit(key, now)
    fun noticeSubmitted(key: String, now: Long = 0) = notices.submitted(key, now)
    fun observeNotices(present: Boolean, now: Long) = notices.observe(present, now)
    fun canSubmitAgreement(key: String, now: Long) = agreements.canSubmit(key, now)
    fun agreementSubmitted(key: String, now: Long) = agreements.submitted(key, now)
    fun observeAgreements(present: Boolean, now: Long) = agreements.observe(present, now)

    /**
     * The student's Retry or Sign in: a block or a stop is lifted, but the login and reauthentication windows and the
     * refusal count stay, so pressing it again and again with a wrong saved password never sends more than three logins
     * in ten minutes, and after two refusals the next refusal stops at once. Other credentials start afresh ([reset]).
     */
    fun retry() {
        retryVersion++
        blocked = false; pendingDocument = null; pendingReauthentication = null; lateReauthentication = null
        lastLogin = null; lastReauthentication = null
        stopped = false; ambiguous = false; blockedDocument = null; blockedAt = null; reauthenticationDocument = null
        completedReauthentications.clear()
        notices.reset(); agreements.reset(); recovery.reset()
    }
    /** Another login or password was saved: nothing the old credentials did counts against the new ones. */
    fun reset() {
        retry()
        loginRefusals = 0; logins.clear(); reauthenticationAttempts.clear(); spacingWaived = false; waivedLogin = null
    }
    fun stop() { blocked = true; stopped = true; pendingDocument = null; pendingReauthentication = null; lateReauthentication = null; spacingWaived = false }

    /**
     * One submission per appearance; one still shown 20 s after sending is resent, at most three times per ten minutes.
     * A stable absence releases it for a later, genuine appearance.
     */
    private class ConfirmationBudget {
        private class Sent(val first: Long, var last: Long, var count: Int)
        private val reserved = linkedMapOf<String, Sent>()
        private val attempts = ArrayDeque<Long>()
        private var absentSince: Long? = null
        fun canSubmit(key: String, now: Long): Boolean {
            while (attempts.isNotEmpty() && now - attempts.first() >= 60_000) attempts.removeFirst()
            if (attempts.size >= 3) return false
            val sent = reserved[key] ?: return reserved.size < 20
            return now - sent.last >= 20_000 && (sent.count < 3 || now - sent.first >= 600_000)
        }
        fun submitted(key: String, now: Long) {
            val sent = reserved[key]
            if (sent == null || now - sent.first >= 600_000) reserved[key] = Sent(now, now, 1)
            else { sent.last = now; sent.count++ }
            attempts.addLast(now); absentSince = null
        }
        fun observe(present: Boolean, now: Long) {
            if (present) absentSince = null
            else {
                if (absentSince == null) absentSince = now
                // A normal page must remain visible, not just a transient AJAX loading frame.
                if (now - (absentSince ?: now) >= 2_000) reserved.clear()
            }
        }
        fun release() { reserved.clear(); absentSince = null }
        fun reset() { reserved.clear(); attempts.clear(); absentSince = null }
    }
}
