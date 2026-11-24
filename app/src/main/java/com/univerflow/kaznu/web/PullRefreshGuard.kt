package com.univerflow.kaznu.web

/**
 * Decides whether a pull-to-refresh gesture may reload the current document after [PageScripts.REFRESH_GUARD] runs.
 *
 * Reloads only after an explicit `"ok"` answer from the same document while refresh is still allowed. A timeout, an
 * unexpected answer, a navigation, or a disallowed state stops the indicator without reloading so typed drafts and
 * open dialogs are never lost to a slow or failing page.
 */
class PullRefreshGuard(
    private val scheduleTimeout: (Runnable) -> Unit,
    private val cancelTimeout: (Runnable) -> Unit,
    private val evaluate: (script: String, callback: (String?) -> Unit) -> Unit,
    private val allowed: () -> Boolean,
    private val epoch: () -> Long,
    private val currentUrl: () -> String?,
    private val onReload: () -> Unit,
    private val onStop: () -> Unit,
    private val onKept: (Kept) -> Unit,
) {
    enum class Kept { INPUT, DIALOG }

    private var attemptId = 0L
    private var activeAttempt = 0L
    private var pendingTimeout: Runnable? = null

    fun start() {
        clear()
        val url = currentUrl()
        if (!allowed() || !SitePolicy.trusted(url) || SitePolicy.loginPage(url)) {
            onStop()
            return
        }
        val id = ++attemptId
        activeAttempt = id
        val startedEpoch = epoch()
        val timeout = Runnable {
            if (activeAttempt != id) return@Runnable
            clear()
            onStop()
        }
        pendingTimeout = timeout
        scheduleTimeout(timeout)
        evaluate(PageScripts.REFRESH_GUARD) { answer ->
            if (activeAttempt != id) return@evaluate
            clear()
            if (startedEpoch != epoch() || url != currentUrl() || !allowed()) {
                onStop()
                return@evaluate
            }
            when (answer) {
                "\"ok\"" -> onReload()
                "\"edited\"" -> { onStop(); onKept(Kept.INPUT) }
                "\"dialog\"" -> { onStop(); onKept(Kept.DIALOG) }
                else -> onStop()
            }
        }
    }

    /** Cancels any pending guard check so its timer or a late WebView answer has no effect. */
    fun cancel() {
        clear()
    }

    private fun clear() {
        activeAttempt = 0L
        pendingTimeout?.let(cancelTimeout)
        pendingTimeout = null
    }
}
