package com.univerflow.kaznu.ui

import android.view.View
import android.webkit.WebView
import androidx.core.view.isVisible
import com.univerflow.kaznu.web.AutomationStatus

/** Covers the first navigation/known read notice without hiding or changing the website DOM. */
class NoticeLoadingOverlay(private val web: WebView, private val overlay: View,
    private val timeoutMillis: Long = 30_000) {
    private var expired = false
    private var previousAccessibility = web.importantForAccessibility
    private val timeout = Runnable { expired = true; hide() }

    /** Whether the page is covered; the overlay may still be fading out when it no longer is. */
    val covering get() = overlay.isVisible && !Motion.leaving(overlay)

    fun beginStartup() { expired = false; show() }

    fun status(status: AutomationStatus) {
        when (status) {
            AutomationStatus.NOTICE_CONFIRMING -> if (!expired) show()
            AutomationStatus.SIGNING_IN -> Unit
            else -> dismiss()
        }
    }

    private fun show() {
        if (covering) return
        previousAccessibility = web.importantForAccessibility
        web.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        Motion.cover(overlay)
        overlay.postDelayed(timeout, timeoutMillis)
    }

    private fun hide() {
        overlay.removeCallbacks(timeout)
        if (covering) web.importantForAccessibility = previousAccessibility
        Motion.show(overlay, false)
    }

    fun dismiss() { hide(); expired = false }
    fun close() = dismiss()
}
