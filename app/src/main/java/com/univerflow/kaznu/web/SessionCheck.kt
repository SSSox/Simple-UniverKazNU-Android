package com.univerflow.kaznu.web

import android.webkit.WebView
import com.univerflow.kaznu.grades.JournalFetch
import com.univerflow.kaznu.grades.JournalPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Whether the account's session is over, asked by the app itself before the saved password goes into the site's
 * reauthentication dialog. The site draws that dialog only for a session that is over, but any script on any of its
 * pages could draw a lookalike to catch what the app types into it; the app's own request (the background check's
 * journal GET with the WebView's cookies, no redirect followed) cannot be answered by such a script.
 */
object SessionCheck {
    /** A "still signed in" answer holds this long for any dialog: a page drawing one after another gets no new request. */
    const val LIVE_FOR = 60_000L
    /** A "signed out" answer holds this long, and only for the very [Key] it was asked for; an older one is asked again. */
    const val OVER_FOR = LIVE_FOR
    /** No answer (no network) is asked again after this; each further miss in a row doubles it, up to [UNKNOWN_MAX]. */
    const val UNKNOWN_FOR = 15_000L
    const val UNKNOWN_MAX = 120_000L

    /** How long the [misses]-th missing answer in a row stands: 15 s, 30 s, 1 min, then 2 min while the network stays away. */
    fun unknownFor(misses: Int): Long = (UNKNOWN_FOR shl (misses - 1).coerceIn(0, 3)).coerceAtMost(UNKNOWN_MAX)

    /** true: over (signed out); false: still signed in; null: no answer. */
    fun of(page: JournalPage): Boolean? = when (page) {
        JournalPage.SignedOut -> true
        is JournalPage.Found -> false
        JournalPage.Failed -> null
    }

    /**
     * Whether the last answer ([over], given at [at] for the dialog now shown when [sameDialog]) still stands at [now].
     * "Over" stands only for the dialog it was asked for, and for [OVER_FOR] at most: once the student is signed in
     * again, or the answer is old, the dialog is asked about afresh. The other answers stand for a while for any dialog.
     */
    fun reuse(over: Boolean?, sameDialog: Boolean, at: Long?, now: Long, misses: Int = 1): Boolean = when {
        at == null || now < at -> false
        over == true -> sameDialog && now - at < OVER_FOR
        over == false -> now - at < LIVE_FOR
        else -> now - at < unknownFor(misses)
    }

    /**
     * The last answer, the dialog it was asked about and when it came, for one WebView's automation. Stamped when the
     * answer arrives, not when the request went out: a slow request does not shorten the time the answer stands.
     */
    class Answers {
        /** The last answer: true over, false still signed in, null none (or never asked). */
        var over: Boolean? = null
            private set
        /** What the last answer was asked about: the whole [Key] (account, session, document and dialog). */
        private var challenge: Any? = null
        private var at: Long? = null
        /** Requests in a row that got no answer. */
        var misses = 0
            private set

        /** Whether the last answer still stands at [now] for [challenge], the [Key] asked about now (see [reuse]). */
        fun stands(challenge: Any, now: Long) = reuse(over, this.challenge == challenge, at, now, misses)

        /** [over] arrived at [arrivedAt] for the dialog [challenge]. */
        fun record(challenge: Any, over: Boolean?, arrivedAt: Long) {
            this.challenge = challenge; this.over = over; at = arrivedAt
            misses = if (over == null) misses + 1 else 0
        }
    }

    /**
     * Whether a cookie the check's answer renews may be kept: never. The check only asks; it never signs anyone in. A
     * response that arrives after a sign-out cleared the cookies, after another account signed in, or after the check
     * was dropped must not bring the old session back, and the WebView keeps a live session going on its own.
     */
    val KEEP_NO_COOKIE: () -> Boolean = { false }

    /**
     * The journal GET with [web]'s user agent, off the main thread, sending exactly the Cookie header it is given (the
     * [Key.session] the answer is for), never one read again from the cookie jar; it writes no cookie (see [KEEP_NO_COOKIE]).
     */
    fun journal(web: WebView): suspend (cookie: String?) -> Boolean? = journal({ web.settings.userAgentString }) { agent, cookie, current ->
        JournalFetch.get(agent, header = cookie, current = current)
    }

    /** [fetch] as [journal] calls it: on the IO dispatcher, with the [cookie] snapshot it was asked for and [KEEP_NO_COOKIE]. */
    fun journal(agent: () -> String, fetch: (agent: String, cookie: String?, current: () -> Boolean) -> JournalPage): suspend (cookie: String?) -> Boolean? = { cookie ->
        val userAgent = agent()
        of(withContext(Dispatchers.IO) { fetch(userAgent, cookie, KEEP_NO_COOKIE) })
    }

    /**
     * The session a check is asked for: exactly the Cookie header JournalFetch sends for [JournalFetch.ADDRESS]
     * ([cookies] as CookieManager gives it for that address), null when it sends none. Any change to what the request
     * would carry is another session.
     */
    fun session(cookies: String?): String? = cookies?.takeIf { it.isNotBlank() }

    /**
     * What one request is asked about: the account ([account], see GradeStore.generation), the WebView's [session], the
     * [document] and the reauthentication [challenge] that drew the dialog. An answer is given only for the same key.
     */
    data class Key(val account: Int, val session: String?, val document: String, val challenge: String)

    /** One answer of [Shared.poll]: [over] as [of] gives it, arrived at [at]. */
    class Reply(val over: Boolean?, val at: Long)

    /**
     * At most one journal request at a time for one WebView's automation, never waited for. The request runs in
     * [scope], not in the observation loop that asked: the loop goes on probing the page while it is out, a loop ended by
     * minimising the app leaves it running, and the loop started on reopening gets its answer when the [Key] is still
     * the same instead of sending a second request. A request for another key (a sign-out, another account, a renewed
     * session, another document or dialog since) is cancelled and its answer is never given; no new request starts
     * until its blocking read has actually ended, so two never overlap.
     */
    class Shared(private val scope: CoroutineScope, private val clock: () -> Long, private val request: suspend (cookie: String?) -> Boolean?) {
        private var running: Job? = null
        private var runningFor: Key? = null
        private var answer: Reply? = null

        /** Whether a request (current or stale, still reading) is out. */
        val busy get() = running?.isCompleted == false

        /**
         * The answer for [key] once it has come, else null: a request for [key] is then out (sent now if none was), or a
         * stale one is still ending. Each answer is given once.
         */
        fun poll(key: Key): Reply? {
            running?.let { job ->
                if (!job.isCompleted) {
                    if (runningFor != key) job.cancel() // Stale: dropped, and waited out before a replacement.
                    return null
                }
                running = null
                val came = answer.also { answer = null }
                if (came != null && runningFor == key) return came
            }
            answer = null; runningFor = key
            // The request sends the key's own Cookie header: its answer is about exactly that session.
            val session = key.session
            running = scope.launch { val over = request(session); answer = Reply(over, clock()) }
            return null
        }

        /** Drops the request in flight, if any (the automation closes). */
        fun cancel() { running?.cancel(); answer = null }
    }
}
