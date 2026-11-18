package com.univerflow.kaznu.ui

import android.animation.ValueAnimator
import android.os.Build
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.webkit.WebView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.BaseProgressIndicator
import com.univerflow.kaznu.R

/**
 * Native counterpart of the web layer's motion: the same ease-out curve and short durations. Every change has a cause
 * the student can see: surfaces arrive and leave, labels rise into place, neighbours make room instead of jumping.
 */
object Motion {
    /** The app's "Interface animations" setting; the system animator scale is honoured as well. */
    @Volatile var enabled = true
    private val easeOut = PathInterpolator(.23f, 1f, .32f, 1f)
    private const val ENTER_MS = 200L
    private const val EXIT_MS = 140L

    val active get() = enabled && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled())

    private fun dp(view: View, value: Int) = value * view.resources.displayMetrics.density

    /** The surface is on its way out: still drawn for its fade, but no longer shown as far as the screen is concerned. */
    fun leaving(view: View) = view.getTag(R.id.motion_leaving) == true

    private fun settle(view: View) {
        view.setTag(R.id.motion_leaving, null)
        view.alpha = 1f
        view.translationY = 0f
    }

    /** [rise] slides a banner from the app bar it belongs to; full-screen surfaces only fade. */
    fun show(view: View, visible: Boolean, rise: Boolean = false) {
        val lift = if (rise) -dp(view, 8) else 0f
        if (!visible) {
            if (leaving(view)) return
            view.animate().cancel()
            if (!view.isVisible || !active || !view.isAttachedToWindow) { settle(view); view.isVisible = false; return }
            view.setTag(R.id.motion_leaving, true)
            view.animate().alpha(0f).translationY(lift).setDuration(EXIT_MS).setInterpolator(easeOut)
                .withEndAction { settle(view); view.isVisible = false }.start()
            return
        }
        if (view.isVisible && !leaving(view)) return // Already shown or fading in: repeated renders must not restart it.
        val turningBack = leaving(view)
        view.animate().cancel()
        view.setTag(R.id.motion_leaving, null)
        view.isVisible = true
        if (!active) { settle(view); return }
        if (!turningBack) { view.alpha = 0f; view.translationY = lift }
        view.animate().alpha(1f).translationY(0f).setDuration(ENTER_MS).setInterpolator(easeOut).start()
    }

    /** On screen at once, even mid-fade: a cover has to be there before what it hides appears. */
    fun cover(view: View) {
        view.animate().cancel()
        settle(view)
        view.isVisible = true
    }

    /** Gone at once, even mid-fade: a placeholder fading over the page it stood for would mix the two. */
    fun drop(view: View) {
        view.animate().cancel()
        settle(view)
        view.isVisible = false
    }

    /** Changed words rise into place, so a new status is seen to change; the same words stay still. */
    fun text(view: TextView, value: CharSequence?) {
        if (view.text.toString() == value?.toString().orEmpty()) return
        view.text = value
        arrive(view)
    }

    /** A label or row that was just updated in place. */
    fun arrive(view: View) {
        if (!active || !view.isLaidOut) return
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = dp(view, 6)
        view.animate().alpha(1f).translationY(0f).setDuration(ENTER_MS).setInterpolator(easeOut).start()
    }

    /** A small mark taking its state, such as a PIN dot filling. */
    fun pop(view: View) {
        if (!active) return
        view.animate().cancel()
        view.scaleX = .6f
        view.scaleY = .6f
        view.animate().scaleX(1f).scaleY(1f).setDuration(EXIT_MS).setInterpolator(easeOut).start()
    }

    /** A control turning inactive or active again. */
    fun dim(view: View, alpha: Float) {
        view.animate().cancel()
        if (active && view.isLaidOut) view.animate().alpha(alpha).setDuration(EXIT_MS).setInterpolator(easeOut).start()
        else view.alpha = alpha
    }

    /** A layer opening over the page: it rises while it fades in. */
    fun enter(view: View) {
        if (!active) return
        view.alpha = 0f
        view.translationY = dp(view, 24)
        view.animate().alpha(1f).translationY(0f).setDuration(ENTER_MS).setInterpolator(easeOut).start()
    }

    /** A layer closing: [done] removes it once it has faded, or at once without animations. */
    fun leave(view: View, done: () -> Unit) {
        if (!active || !view.isAttachedToWindow) { done(); return }
        view.animate().cancel()
        view.animate().alpha(0f).setDuration(EXIT_MS).setInterpolator(easeOut).withEndAction(done).start()
    }

    /** The next layout change under [root] moves its neighbours instead of jumping. Pages and lists animate themselves. */
    fun layout(root: ViewGroup) {
        if (!active) return
        TransitionManager.beginDelayedTransition(root, AutoTransition().apply {
            ordering = TransitionSet.ORDERING_TOGETHER
            duration = ENTER_MS
            interpolator = easeOut
            excludeChildren(RecyclerView::class.java, true)
            excludeChildren(WebView::class.java, true)
        })
    }

    /** A loading bar eases in and out through its own behaviours; without animations it just appears and goes. */
    fun progress(indicator: BaseProgressIndicator<*>, visible: Boolean) {
        if (!active) indicator.isVisible = visible
        else if (visible) indicator.show()
        else indicator.hide()
    }
}
