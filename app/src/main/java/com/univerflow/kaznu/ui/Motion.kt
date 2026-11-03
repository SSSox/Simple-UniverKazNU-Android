package com.univerflow.kaznu.ui

import android.animation.ValueAnimator
import android.os.Build
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.view.isVisible

/**
 * Native counterpart of the web layer's motion: the same ease-out curve and short durations.
 * Surfaces fade in so they do not teleport; they leave at once so nothing stale blocks input.
 */
object Motion {
    /** The app's "Interface animations" setting; the system animator scale is honoured as well. */
    @Volatile var enabled = true
    private val easeOut = PathInterpolator(.23f, 1f, .32f, 1f)

    private fun allowed() = enabled && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled())

    /** [rise] slides a banner down from the app bar it belongs to; full-screen surfaces only fade. */
    fun show(view: View, visible: Boolean, rise: Boolean = false) {
        if (!visible) {
            view.animate().cancel()
            view.alpha = 1f
            view.translationY = 0f
            view.isVisible = false
            return
        }
        if (view.isVisible) return // Already shown or fading in: repeated renders must not restart it.
        view.animate().cancel()
        view.isVisible = true
        if (!allowed()) { view.alpha = 1f; view.translationY = 0f; return }
        view.alpha = 0f
        view.translationY = if (rise) -8 * view.resources.displayMetrics.density else 0f
        view.animate().alpha(1f).translationY(0f).setDuration(160).setInterpolator(easeOut).start()
    }
}
