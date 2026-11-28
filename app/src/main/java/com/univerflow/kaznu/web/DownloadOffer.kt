package com.univerflow.kaznu.web

/**
 * A file the link window received: WebView treats an attachment as a download even when no one tapped (an automatic
 * redirect, a script), so the address is only held here and [launch] runs from the user's tap on the offer, never
 * from the download itself. A newer file replaces the one on offer; an address [LinkPolicy.download] refuses is never held.
 */
class DownloadOffer(private val launch: (String) -> Unit) {
    private var serial = 0L
    private var token = 0L
    /** The address waiting for a tap, or null. */
    var pending: String? = null
        private set

    /** Holds [url] for a tap and returns the offer's token; null when it may not be offered at all. */
    fun offer(url: String?): Long? {
        if (!LinkPolicy.download(url)) return null
        pending = url
        token = ++serial
        return token
    }

    /** The user's tap on offer [offer]: opens its file once, and only while that offer is still the current one. */
    fun accept(offer: Long) {
        val url = pending?.takeIf { offer == token } ?: return
        pending = null
        launch(url)
    }

    /** Offer [offer] went away unanswered (dismissed or timed out); a newer offer stays. */
    fun dismiss(offer: Long) { if (offer == token) pending = null }
}
