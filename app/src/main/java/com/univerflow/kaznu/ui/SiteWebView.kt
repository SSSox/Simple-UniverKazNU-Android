package com.univerflow.kaznu.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.webkit.WebView

/**
 * The main screen's WebView, which also tells the pull-to-refresh whether the page itself was pushed past its top.
 * Chromium reports that edge through onOverScrolled only for a scroll nothing inside the page took: a table, a
 * dialog or a menu scrolling back up keeps it false, so pulling them down never reloads the page under them.
 */
class SiteWebView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : WebView(context, attrs) {
    /** This touch pulled the page past its top edge; a new touch starts again from false. */
    var pulledPastTop = false
        private set

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) pulledPastTop = false
        return super.dispatchTouchEvent(event)
    }

    override fun onOverScrolled(scrollX: Int, scrollY: Int, clampedX: Boolean, clampedY: Boolean) {
        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY)
        if (clampedY && scrollY <= 0 && !canScrollVertically(-1)) pulledPastTop = true
    }
}
