package com.univerflow.kaznu.web

import android.webkit.WebView
import com.univerflow.kaznu.grades.JournalFetch
import com.univerflow.kaznu.grades.JournalPage
import kotlinx.coroutines.Dispatchers
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
     * "Over" stands only for the dialog it was asked for: once the student is signed in again, a new dialog is asked
     * about afresh. The other answers stand for a while for any dialog.
     */
    fun reuse(over: Boolean?, sameDialog: Boolean, at: Long?, now: Long, misses: Int = 1): Boolean = when {
        at == null || now < at -> false
        over == true -> sameDialog
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
        private var challenge: String? = null
        private var at: Long? = null
        /** Requests in a row that got no answer. */
        var misses = 0
            private set

        /** Whether the last answer still stands at [now] for the dialog [challenge] (see [reuse]). */
        fun stands(challenge: String, now: Long) = reuse(over, this.challenge == challenge, at, now, misses)

        /** [over] arrived at [arrivedAt] for the dialog [challenge]. */
        fun record(challenge: String, over: Boolean?, arrivedAt: Long) {
            this.challenge = challenge; this.over = over; at = arrivedAt
            misses = if (over == null) misses + 1 else 0
        }
    }

    /** The journal GET with [web]'s user agent, off the main thread. */
    fun journal(web: WebView): suspend () -> Boolean? = {
        val agent = web.settings.userAgentString
        of(withContext(Dispatchers.IO) { JournalFetch.get(agent) })
    }
}
