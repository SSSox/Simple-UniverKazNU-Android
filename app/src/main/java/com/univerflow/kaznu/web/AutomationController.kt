package com.univerflow.kaznu.web

import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebView
import com.univerflow.kaznu.UniverViewModel
import com.univerflow.kaznu.domain.AppLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener

/** The page the site redirected to its login form; kept by the ViewModel so a rotation cannot lose it. */
class ResumeTarget { var url: String? = null; var pending = false }

enum class AutomationStatus { IDLE, SIGNING_IN, NOTICE_CONFIRMING, LOGIN_FAILED, NOTICE_FAILED, AGREEMENT_FAILED, LANGUAGE_FAILED, ELEMENTS_MISSING }

/** No JavascriptInterface: the only data coming back from the DOM is a non-secret status object. */
class AutomationController(private val web: WebView, private val model: UniverViewModel,
    private val scope: CoroutineScope, private val guard: SessionGuard = model.guard,
    private val synchronizeLanguage: Boolean = true,
    /** Called once a login performed here is confirmed by a signed-in page. */
    private val signedIn: () -> Unit = {},
    private val resumeTarget: ResumeTarget = ResumeTarget(),
    /**
     * A GET of a trusted page that replaces one whose automation could not finish; null leaves such a page to the
     * window's own retry. Never a POST: a credential or notice form is only ever sent by the site's own handler.
     */
    private val recover: ((String) -> Unit)? = null,
    private val status: (AutomationStatus) -> Unit) {
    private val library = web.context.assets.open("univer-automation.js").bufferedReader().use { it.readText() }
    private var watch: Job? = null
    private var generation = 0
    private var switchingUntil = 0L
    private val switches = ArrayDeque<Long>()
    private var running = false
    private var pendingAgreement: String? = null
    private var agreementSentAt = 0L
    private var agreementFailed = false
    private var failedAgreementEpisode: String? = null
    private var retryVersion = guard.retryVersion
    private var failedNoticeEpisode: String? = null
    // The cover outlives the longest accepted wait for the server's answer, so a slow sign-in never
    // exposes the login form (or a failure) before the automation has finished.
    private val presentation = SessionPresentation(web, (guard.loginTimeout + 5_000).coerceIn(25_000, SessionPresentation.MAX_TIMEOUT))
    // The page whose request the site redirected to its login form; reopened once after that login.
    private var resumeUrl: String? by resumeTarget::url
    private var resumePending: Boolean by resumeTarget::pending
    private var freshFormAt = Long.MIN_VALUE / 2

    // An agreement failure belongs to the document it happened in: a new navigation (for example the login
    // form after the session ended) must not stay blocked by it.
    fun pageStarted() { generation++; watch?.cancel(); agreementFailed = false; pendingAgreement = null; presentation.apply() }
    fun retry() { guard.retry(); resume(replacing = true) }
    /** The Moodle window signs in again through Univer: like [retry], without the minute between logins. */
    fun renew() { guard.renewal(); resume(replacing = true) }
    fun handOffCompleted() { guard.authenticated() }
    /** [url] was replaced by the login form; null forgets it (the user went somewhere else). */
    fun resumeAfterLogin(url: String?) { resumeUrl = url; resumePending = false }
    /**
     * [replacing]: the caller is loading another document, so the next page is observed and this one left alone:
     * a login sent from it would be lost with it, and the fresh form would then read as a rejection. So the attempt in
     * flight is dropped as well; its budget stays.
     */
    fun resume(replacing: Boolean = false) {
        running = true
        presentation.setEnabled(canRecover(), retry = canRecover())
        if (replacing) { generation++; watch?.cancel(); guard.detach() } else observe()
    }
    fun pause() { running = false; generation++; watch?.cancel(); presentation.setEnabled(false) }
    fun close() {
        running = false; generation++; watch?.cancel(); presentation.close()
        // An attempt in flight in this WebView is abandoned with it; a replacement must not read its
        // fresh form as the site's rejection.
        guard.detach()
    }
    fun pageFinished() { presentation.apply(); if (running && watch?.isActive != true) observe() }

    private fun canRecover() = running && model.state.value.hasCredentials && !model.state.value.paused && !guard.blocked

    private fun report(value: AutomationStatus) {
        presentation.setEnabled(canRecover() && value in setOf(
            AutomationStatus.IDLE, AutomationStatus.SIGNING_IN, AutomationStatus.NOTICE_CONFIRMING))
        status(value)
    }

    fun websiteLanguageSelected() {
        switches.clear()
        switchingUntil = SystemClock.elapsedRealtime() + 5_000
        pageStarted()
    }

    /** The start page, in the app's language from the first frame (see [SitePolicy.startUrl]). */
    fun open() {
        web.loadUrl(SitePolicy.startUrl(CookieManager.getInstance().getCookie(SitePolicy.ORIGIN), model.state.value.language, model.state.value.startPage))
    }

    fun applyLanguage(language: AppLanguage) {
        switches.clear()
        // A flag click now would abort a login, dialog or agreement still waiting for the site's answer, and the fresh
        // login form would read as a rejection. The language is saved: the observation switches once that has settled.
        if (guard.pendingDocument != null || guard.pendingReauthentication != null || pendingAgreement != null) return
        switchingUntil = SystemClock.elapsedRealtime() + 2_000
        pageStarted()
        val epoch = generation
        scope.launch {
            if (SitePolicy.trusted(web.url)) {
                val result = evaluate("$library; window.__univerAutomationV1.switchLanguage(${quote(language.siteCode)})")
                if (result == "clicked" || epoch != generation) return@launch
            }
            val headers = web.url?.takeIf(SitePolicy::trusted)?.let { mapOf("Referer" to it) } ?: emptyMap()
            web.loadUrl("${SitePolicy.ORIGIN}/lang/change/${language.siteCode}/", headers)
        }
    }

    /** What a step of one probe decided for the polling loop. */
    private enum class Step {
        /** Go on with the later steps of this probe. */
        NEXT,
        /** Skip the rest of this probe and look again shortly. */
        WAIT,
        /** End this observation (the page is being replaced or the observation is stale). */
        STOP,
    }

    /** What one observation of a document remembers between probes. */
    private class Round(val epoch: Int) {
        val began = SystemClock.elapsedRealtime()
        var noticeBegan: Long? = null
        var noticeSentAt: Long? = null
        var agreementBegan: Long? = null
        var warnedMissing = false
        var confirmingNotice = false
        /** When this document is reloaded because its automation could not finish. */
        var recoverAt: Long? = null
    }

    private fun observe() {
        watch?.cancel()
        if (!SitePolicy.trusted(web.url) || !model.state.value.ready) return
        val epoch = ++generation
        watch = scope.launch {
            evaluate(library)
            presentation.apply()
            val round = Round(epoch)
            while (isActive && epoch == generation && SitePolicy.trusted(web.url)) {
                round.confirmingNotice = false
                val probe = probe()
                if (epoch != generation) break
                if (probe != null) reportUsableContent(probe)
                val step = if (probe != null && probe.optBoolean("trusted") && probe.optBoolean("ready")) handle(probe, round) else Step.NEXT
                if (step == Step.STOP) break
                if (step == Step.WAIT) { delay(600); continue }
                // DOM observation continues while foregrounded, even for a very late AJAX/session redirect.
                // Click attempts are bounded independently by document flags and SessionGuard.
                delay(if (round.confirmingNotice) 100 else if (SystemClock.elapsedRealtime() - round.began < 90_000) 600 else 2_000)
            }
        }
    }

    private suspend fun probe(): JSONObject? = try {
        evaluate("JSON.stringify(window.__univerAutomationV1 && window.__univerAutomationV1.probe())")?.let { JSONObject(it) }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    // News is usable at DOMContentLoaded even when an image/frame is still loading.
    // Form automation below deliberately waits for complete, retaining onload handlers.
    private fun reportUsableContent(probe: JSONObject) {
        if (probe.optBoolean("trusted") && probe.optBoolean("domReady") &&
            !probe.optBoolean("ready") && probe.optBoolean("hasContent") &&
            !probe.optBoolean("hasPendingForm") && !guard.blocked &&
            guard.pendingDocument == null && guard.pendingReauthentication == null &&
            pendingAgreement == null) report(AutomationStatus.IDLE)
    }

    /** One probe of a complete document: pending results first, then the language, then the page's own forms. */
    private suspend fun handle(probe: JSONObject, round: Round): Step {
        val now = SystemClock.elapsedRealtime()
        val state = model.state.value
        val document = probe.getString("document")
        val automated = state.hasCredentials && !state.paused
        if (automated) {
            val step = settlePending(probe, document, now, round)
            if (step != Step.NEXT) return step
        }
        // A sent login waits for its answer first (see applyLanguage); a pending dialog or agreement already waited above.
        val language = if (automated && guard.pendingDocument != null) LanguageSync.MATCHED
            else syncLanguage(probe, state.language, now)
        if (language == LanguageSync.SWITCHED) return Step.WAIT
        if (!automated) return Step.NEXT
        if (probe.optBoolean("loginPage")) return signIn(probe, document, now, round)
        return signedInPage(probe, document, now, round, language == LanguageSync.FAILED)
    }

    /**
     * Reloads a document whose automation could not finish, after a wait that grows with each reload in a row. Always a
     * GET (the login form, the page, then the start page); [SessionGuard] budgets still bound every submission.
     */
    private fun recoverLater(round: Round, now: Long, otherwise: Step, failed: Boolean = guard.recoverable): Step {
        val recover = recover ?: return otherwise
        val state = model.state.value
        if (!failed || !running || !state.hasCredentials || state.paused) { round.recoverAt = null; return otherwise }
        val at = round.recoverAt ?: (now + guard.recoveryDelay(now)).also { round.recoverAt = it }
        if (now < at) return otherwise
        val url = web.url
        val target = when {
            SitePolicy.loginPage(url) -> url!!
            guard.recoveries == 0 && SitePolicy.resumable(url) -> url!!
            else -> SitePolicy.HOME
        }
        guard.recovering(now)
        recover(target)
        return Step.STOP
    }

    /**
     * A retry, an AJAX reauthentication, a stopped sign-in and a sent agreement are settled before anything else:
     * while one of them is unresolved, nothing else on the page is automated.
     */
    private suspend fun settlePending(probe: JSONObject, document: String, now: Long, round: Round): Step {
        if (retryVersion != guard.retryVersion) {
            retryVersion = guard.retryVersion
            presentation.setEnabled(canRecover(), retry = canRecover())
            pendingAgreement = null; agreementFailed = false
            failedNoticeEpisode = null
        }
        // The site closes auth_modal BEFORE /user/ajl completes. Wait for its result.
        // A late answer (after the timeout) still counts: it proves the password.
        val pending = guard.pendingReauthentication ?: guard.lateReauthentication
        if (pending != null) {
            val outcome = probe.optString("reauthOutcome")
            if (probe.optString("reauthResultChallenge") == pending && outcome in listOf("success", "failure") &&
                guard.reauthenticationResult(pending, outcome == "success", now)) report(AutomationStatus.IDLE)
            else if (guard.reauthenticationTimedOut(now)) report(AutomationStatus.LOGIN_FAILED)
            else if (guard.pendingReauthentication != null) report(AutomationStatus.SIGNING_IN)
        }
        guard.continueOnNewDocument(document, now)
        // A refused dialog can carry a stale key. A newer document continues after the recovery wait.
        if (probe.optBoolean("reauth")) { reauthenticate(probe, document, now); return recoverLater(round, now, Step.WAIT) }
        // A newly loaded page of the signed-in account ends a stall/failure stop.
        if (probe.optBoolean("authenticated") && guard.authenticatedPage(document)) report(AutomationStatus.IDLE)
        if (guard.pendingReauthentication != null || (guard.blocked && !probe.optBoolean("loginPage"))) {
            if (guard.blocked) report(AutomationStatus.LOGIN_FAILED)
            return recoverLater(round, now, Step.WAIT)
        }
        if (pendingAgreement != null) {
            val outcome = probe.optString("agreementOutcome")
            if (probe.optString("agreementResultEpisode") == pendingAgreement && outcome == "success") pendingAgreement = null
            // The site answers its AJAX endpoints in 20–30 s; only an error or a minute of silence is a failure.
            else if (outcome == "failure" || now - agreementSentAt > 60_000) {
                failedAgreementEpisode = pendingAgreement
                pendingAgreement = null; agreementFailed = true
            }
            if (pendingAgreement != null) return Step.WAIT
        }
        // A request that answered after the timeout still proves the agreement was accepted.
        if (agreementFailed && failedAgreementEpisode != null && probe.optString("agreementOutcome") == "success" &&
            probe.optString("agreementResultEpisode") == failedAgreementEpisode) {
            agreementFailed = false; failedAgreementEpisode = null
            report(AutomationStatus.IDLE)
        }
        if (agreementFailed) { report(AutomationStatus.AGREEMENT_FAILED); return recoverLater(round, now, Step.WAIT, failed = true) }
        return Step.NEXT
    }

    /** The site's own reauthentication dialog (#auth_modal): one submission per new challenge, within the budget. */
    private suspend fun reauthenticate(probe: JSONObject, document: String, now: Long) {
        when {
            probe.optInt("reauthAttempt") > 1 -> {
                guard.reauthenticationRejected(document, now); report(AutomationStatus.LOGIN_FAILED)
            }
            guard.blocked -> report(AutomationStatus.LOGIN_FAILED)
            guard.pendingReauthentication != null -> report(AutomationStatus.SIGNING_IN)
            probe.optBoolean("reauthReady") -> {
                val credentials = model.credentials
                val challenge = probe.getString("reauthChallenge")
                if (credentials != null && guard.tryReauthenticate(challenge, now, document)) {
                    report(AutomationStatus.SIGNING_IN)
                    val result = evaluate(onSite("window.__univerAutomationV1.reauthenticate(${quote(document)},${quote(challenge)},${quote(credentials.login)},${quote(credentials.password)})"))
                    if (result == "wait") guard.releaseReauthentication(challenge)
                    // The dialog's readonly login belongs to another account: never replace it.
                    if (result?.contains("account-mismatch") == true) { guard.stop(); report(AutomationStatus.LOGIN_FAILED) }
                } else if (guard.blocked) report(AutomationStatus.LOGIN_FAILED)
                else report(AutomationStatus.SIGNING_IN) // Temporary request budget; no credential entry required.
            }
        }
    }

    private enum class LanguageSync { MATCHED, SWITCHED, FAILED }

    /** Switches the site to the app language on a real mismatch, at most three times a minute. */
    private suspend fun syncLanguage(probe: JSONObject, language: AppLanguage, now: Long): LanguageSync {
        val actualLanguage = probe.optString("language")
        if (!synchronizeLanguage || actualLanguage.isEmpty() || actualLanguage == language.siteCode || now <= switchingUntil) return LanguageSync.MATCHED
        while (switches.isNotEmpty() && now - switches.first() > 60_000) switches.removeFirst()
        if (switches.size >= 3) { report(AutomationStatus.LANGUAGE_FAILED); return LanguageSync.FAILED }
        switches.addLast(now)
        switchingUntil = now + 5_000
        evaluate("window.__univerAutomationV1.switchLanguage(${quote(language.siteCode)})")
        return LanguageSync.SWITCHED
    }

    /** The full-page login form: one bounded submission through the site's own handler, then wait for its answer. */
    private suspend fun signIn(probe: JSONObject, document: String, now: Long, round: Round): Step {
        if (guard.loginStillVisible(document, now)) {
            report(AutomationStatus.LOGIN_FAILED)
            // A stalled submit: a fresh form may try again within the budget. A rejected password stays stopped.
            return recoverLater(round, now, Step.NEXT)
        }
        if (probe.optBoolean("loginReady") && guard.pendingDocument == null) {
            val credentials = model.credentials
            // A form left open for long (behind other apps, or waiting for the budget) carries an expired key that the
            // site refuses even with the right password, which would read as a rejection: load a fresh one first.
            val url = web.url
            if (credentials != null && recover != null && probe.optLong("age") > STALE_FORM &&
                now - freshFormAt >= STALE_FORM && SitePolicy.loginPage(url)) {
                freshFormAt = now
                recover(url!!)
                return Step.STOP
            }
            if (credentials != null && guard.tryLogin(document, now)) {
                report(AutomationStatus.SIGNING_IN)
                val result = evaluate(onSite("window.__univerAutomationV1.login(${quote(document)},${quote(credentials.login)},${quote(credentials.password)})"))
                if (result == "wait") guard.releaseLogin(document)
            } else if (guard.blocked || guard.loginBudgetExhausted(now)) report(AutomationStatus.LOGIN_FAILED)
            // The next login waits a minute after the previous one, then continues by itself.
            else if (credentials != null) report(AutomationStatus.SIGNING_IN)
        } else if (!probe.optBoolean("loginReady") && guard.pendingDocument == null && now - round.began > 45_000) {
            if (!round.warnedMissing) { round.warnedMissing = true; report(AutomationStatus.ELEMENTS_MISSING) }
            return recoverLater(round, now, Step.NEXT, failed = true)
        }
        return Step.NEXT
    }

    /** A page of the signed-in account: confirm a login, then the known notice and agreement, then the asked-for page. */
    private suspend fun signedInPage(probe: JSONObject, document: String, now: Long, round: Round, languageFailed: Boolean): Step {
        if (guard.pendingDocument != null && probe.optBoolean("hasContent")) {
            guard.authenticated()
            web.clearHistory()
            report(AutomationStatus.IDLE)
            resumePending = resumeUrl != null
            signedIn()
        }
        if (confirmNotice(probe, document, now, round) == Step.STOP) return Step.STOP
        if (acceptAgreement(probe, document, now, round) == Step.STOP) return Step.STOP
        if (!probe.optBoolean("notice") && !probe.optBoolean("agreement") && !languageFailed) {
            report(AutomationStatus.IDLE)
            if (probe.optBoolean("authenticated")) guard.recovered()
            // The read notice and agreement come first; then the page that was asked for.
            val resume = resumeUrl
            if (resumePending && resume != null && probe.optBoolean("hasContent")) {
                resumeUrl = null; resumePending = false
                if (resume != web.url && SitePolicy.resumable(resume)) { web.loadUrl(resume); return Step.STOP }
            }
        }
        return Step.NEXT
    }

    /** The supplied read notice: tick its checkbox (the site's calc()), then submit it once per appearance. */
    private suspend fun confirmNotice(probe: JSONObject, document: String, now: Long, round: Round): Step {
        if (!probe.optBoolean("notice")) {
            round.noticeBegan = null; round.noticeSentAt = null
            if (probe.optBoolean("hasContent")) guard.observeNotices(false, now)
            return Step.NEXT
        }
        round.confirmingNotice = true
        guard.observeNotices(true, now)
        val key = probe.getString("noticeKey")
        val appearance = probe.getString("noticeEpisode")
        val began = round.noticeBegan ?: now.also { round.noticeBegan = it }
        // Not ready to send within 25 s, or sent and still shown a minute later: the site answers a POST in
        // 20–30 s, and its page stays until then. A reload before that would abort the confirmation.
        val stuck = round.noticeSentAt?.let { now - it > 60_000 } ?: (now - began > 25_000)
        if (failedNoticeEpisode == appearance || stuck) {
            round.confirmingNotice = false
            failedNoticeEpisode = appearance
            report(AutomationStatus.NOTICE_FAILED)
            return recoverLater(round, now, Step.NEXT, failed = true)
        }
        report(AutomationStatus.NOTICE_CONFIRMING)
        if (!guard.canSubmitNotice(key, now)) return Step.NEXT
        val ready = if (probe.optBoolean("checked")) probe.optBoolean("submitEnabled")
            // A plain string result: evaluate() already removes the one JSON layer evaluateJavascript adds.
            else evaluate("window.__univerAutomationV1.prepareNotice(${quote(document)},${quote(appearance)})") == "ready"
        if (round.epoch != generation || !currentCoroutineContext().isActive) return Step.STOP
        if (ready && !model.state.value.paused && !guard.blocked) {
            // Reserve BEFORE the click: a navigation can discard the JS callback.
            guard.noticeSubmitted(key, now)
            round.noticeSentAt = now
            evaluate("JSON.stringify(window.__univerAutomationV1.submitNotice(${quote(document)},${quote(appearance)}))")
        }
        return Step.NEXT
    }

    /** The user-authorized additional agreement: tick #iconfirm, then send it once through the site's agree(). */
    private suspend fun acceptAgreement(probe: JSONObject, document: String, now: Long, round: Round): Step {
        if (!probe.optBoolean("agreement")) {
            round.agreementBegan = null
            if (probe.optBoolean("hasContent")) guard.observeAgreements(false, now)
            return Step.NEXT
        }
        guard.observeAgreements(true, now)
        val key = probe.getString("agreementKey")
        val appearance = probe.getString("agreementEpisode")
        if (round.agreementBegan == null) round.agreementBegan = now
        if (guard.canSubmitAgreement(key, now)) {
            if (!probe.optBoolean("agreementChecked"))
                evaluate("JSON.stringify(window.__univerAutomationV1.checkAgreement(${quote(document)},${quote(appearance)}))")
            else if (probe.optBoolean("agreementEnabled")) {
                guard.agreementSubmitted(key, now)
                pendingAgreement = appearance; agreementSentAt = now
                // The 25 s readiness limit restarts at a send: a still-shown dialog right after an answer is not stuck.
                round.agreementBegan = now
                evaluate("JSON.stringify(window.__univerAutomationV1.submitAgreement(${quote(document)},${quote(appearance)}))")
            }
        }
        if (now - (round.agreementBegan ?: now) > 25_000) {
            report(AutomationStatus.AGREEMENT_FAILED)
            return recoverLater(round, now, Step.NEXT, failed = true)
        }
        return Step.NEXT
    }

    /** The string a library call returned, or null for anything else or for no answer within 3 s. */
    private suspend fun evaluate(script: String): String? = withTimeoutOrNull(3_000) { web.evaluate(script) }
        ?.let { result -> runCatching { JSONTokener(result).nextValue() as? String }.getOrNull() }

    private fun quote(value: String) = jsString(value)

    /**
     * [call] carries the password, so the script itself checks where it runs: the page may have changed between the
     * address check in Kotlin and the evaluation.
     */
    private fun onSite(call: String) = "(function(){return location.origin===${quote(SitePolicy.ORIGIN)}?$call:null})()"

    private companion object {
        /** A login form older than this is reloaded before the saved account is submitted to it. */
        const val STALE_FORM = 600_000L
    }
}
