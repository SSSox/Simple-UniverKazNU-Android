package com.univerflow.kaznu.web

/**
 * Decides when the page's placeholder stands over the web view; [show] only draws that decision.
 *
 * A tap covers the page at once, because the site can take seconds to answer and only then would the page start to
 * change. From there the cover stays until the next document is ready. The old document finishing its adaptation must not
 * lift it, and a failed load must never be uncovered: the web view then holds the browser's own error page.
 */
class PageCover(private val show: (visible: Boolean, atOnce: Boolean) -> Unit) {
    /** A tapped link or a retry is on its way and no page has started yet. */
    var leaving = false
        private set
    private var failedDocument = false

    /**
     * A navigation was requested; [covered] is false where the page stays as the site draws it. Over a failed load the
     * cover comes at once: while it faded in, the browser's error page would show through.
     */
    fun leave(covered: Boolean) {
        leaving = true
        if (covered) show(true, failedDocument)
    }

    /** A document committed: from here on the adapter says when it is ready. */
    fun started() { leaving = false }

    fun concealed(hidden: Boolean) {
        if (leaving && !hidden) return
        show(hidden, false)
    }

    /** The load failed; the error screen takes over from the placeholder. */
    fun failed() {
        leaving = false
        failedDocument = true
        show(false, false)
    }

    fun loaded() { failedDocument = false }

    /** No page ever came (a file, a 204). Returns true when what is underneath is a failed load, which needs its error screen. */
    fun abandoned(): Boolean {
        leaving = false
        if (failedDocument) return true
        show(false, false)
        return false
    }
}
