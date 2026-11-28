package com.univerflow.kaznu.main

import com.univerflow.kaznu.web.LinkPolicy
import com.univerflow.kaznu.web.RecoveryBackoff
import com.univerflow.kaznu.web.SingleSignOnPolicy
import com.univerflow.kaznu.web.SitePolicy

/**
 * Schedules a single cancellable renderer-recovery task and re-checks screen availability right before recreating.
 * Pure logic so lifecycle, lock and settings guards can be verified in JVM unit tests.
 */
internal class RendererRecovery(
    private val backoff: RecoveryBackoff,
    private val clock: () -> Long,
    private val schedule: (Runnable, Long) -> Unit,
    private val cancelCallback: (Runnable) -> Unit,
    private val canRecover: () -> Boolean,
    private val recreate: () -> Unit,
) {
    var pending = false
        private set

    private val task = Runnable {
        if (canRecover()) recreate() else pending = false
    }

    fun scheduleIfNeeded() {
        if (pending || !canRecover()) return
        pending = true
        val now = clock()
        val delay = backoff.delay(now)
        backoff.spend(now)
        schedule(task, delay)
    }

    fun cancel() {
        cancelCallback(task)
        pending = false
    }
}

/**
 * A page handed back from the link window while the renderer was lost ([LinkReturnAction.PEND]); the page that comes
 * up after recovery takes it once. It belongs to the site session it was tapped in: ending that session drops it, so
 * a course or Moodle page of one account never opens in the next.
 */
internal class PendingLinkReturn {
    var url: String? = null
        private set
    /** The screen's session generation the link was kept in; another generation never takes it. */
    var generation = 0
        private set

    fun keep(url: String, generation: Int = 0) { this.url = url; this.generation = generation }

    /**
     * The kept page once it can be shown: still kept while the app is [locked] (handed back after unlock), dropped
     * when the session [generation] is no longer the one it was kept in.
     */
    fun take(generation: Int = 0, locked: Boolean = false): String? {
        if (this.generation != generation) url = null
        if (locked) return null
        return url.also { url = null }
    }

    /** What a recreated screen keeps: the link of the current [generation] only. */
    fun saved(generation: Int): String? = url?.takeIf { this.generation == generation }

    /** Restores a saved link only when it was saved in the same session [generation] the screen restores. */
    fun restore(url: String?, savedGeneration: Int, generation: Int) {
        if (url != null && savedGeneration == generation) keep(url, generation)
    }

    fun sessionEnded() { url = null }
}

/** [PEND]: the renderer is being recovered; the link is kept and handed back again once the page is up. */
internal enum class LinkReturnAction { IGNORE, LOAD_MAIN, ROUTE, PEND }

/**
 * Availability and routing guards for asynchronous main-screen callbacks ([captureNewWindow], [linkReturned],
 * document viewer navigation) so late callbacks never touch a destroyed, locked or unavailable Activity/WebView
 * and print/service/download URLs never bypass [MainActivity.route].
 */
internal object MainNavigationGate {
    fun canRoute(
        isDestroyed: Boolean,
        isFinishing: Boolean,
        rendererUnavailable: Boolean,
        sessionChanging: Boolean,
        sessionClearPending: Boolean,
    ): Boolean = !isDestroyed && !isFinishing && !rendererUnavailable && !sessionChanging && !sessionClearPending

    fun canCaptureWindow(
        isUserGesture: Boolean,
        isDestroyed: Boolean,
        isFinishing: Boolean,
        rendererUnavailable: Boolean,
        sessionChanging: Boolean,
        sessionClearPending: Boolean,
        locked: Boolean,
        canBrowse: Boolean,
    ): Boolean = isUserGesture && !locked && canBrowse &&
        canRoute(isDestroyed, isFinishing, rendererUnavailable, sessionChanging, sessionClearPending)

    fun canHandleCapturedWindow(
        isDestroyed: Boolean,
        isFinishing: Boolean,
        rendererUnavailable: Boolean,
        sessionChanging: Boolean,
        sessionClearPending: Boolean,
        locked: Boolean,
        canBrowse: Boolean,
        shouldRoute: Boolean,
    ): Boolean = shouldRoute && !locked && canBrowse &&
        canRoute(isDestroyed, isFinishing, rendererUnavailable, sessionChanging, sessionClearPending)

    /** Trusted Univer pages that load directly in the main WebView rather than a download or document/service viewer. */
    fun loadsInMainWebView(url: String?): Boolean =
        SitePolicy.trusted(url) && !SitePolicy.documentDownload(url) &&
            !SitePolicy.printDocument(url) && !SingleSignOnPolicy.opensViewer(url)

    fun linkReturnAction(
        url: String?,
        isDestroyed: Boolean,
        isFinishing: Boolean,
        rendererUnavailable: Boolean,
        sessionChanging: Boolean,
        sessionClearPending: Boolean,
        locked: Boolean,
        canBrowse: Boolean,
    ): LinkReturnAction {
        if (locked || !canBrowse || !canRoute(isDestroyed, isFinishing, false, sessionChanging, sessionClearPending) ||
            !LinkPolicy.handBack(url)) return LinkReturnAction.IGNORE
        if (rendererUnavailable) return LinkReturnAction.PEND
        return if (loadsInMainWebView(url)) LinkReturnAction.LOAD_MAIN else LinkReturnAction.ROUTE
    }
}
