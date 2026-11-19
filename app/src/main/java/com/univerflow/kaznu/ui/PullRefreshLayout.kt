package com.univerflow.kaznu.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * Pull to refresh for the site's page. SwipeRefreshLayout drops a whole gesture whose first touch it refused, and
 * the page only learns it was pulled past its top once the finger has moved: so the first touch is taken whenever
 * the page sits at its top, and the pull itself starts only after [SiteWebView] saw the page's own edge.
 */
class PullRefreshLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : SwipeRefreshLayout(context, attrs) {
    /** The screen belongs to the student's page: no login, PIN, document window or session change over it. */
    var allowed: () -> Boolean = { false }
    private var touchStarting = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        touchStarting = event.actionMasked == MotionEvent.ACTION_DOWN
        return super.onInterceptTouchEvent(event)
    }

    override fun canChildScrollUp(): Boolean {
        // The layout's own circle is a child too: the page is the one SiteWebView.
        val page = (0 until childCount).firstNotNullOfOrNull { getChildAt(it) as? SiteWebView } ?: return true
        if (!allowed()) return true
        return if (touchStarting) page.canScrollVertically(-1) else !page.pulledPastTop
    }
}
