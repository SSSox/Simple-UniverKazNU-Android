package com.univerflow.kaznu.ui

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.webkit.WebView
import com.univerflow.kaznu.preview.PreviewHtml
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln

/**
 * Two fingers on the file viewer's page. Above 100% it is WebView's own zoom; at or below 100% the viewer previews the
 * pinch under the fingers ([preview] gets the step it would land on) and lays the page out at the nearest zoom step on
 * release ([settle]). A pinch keeps the mode it started in. Taps and scrolling reach WebView unchanged.
 */
class LayoutPinch(
    private val web: WebView,
    private val layoutZoom: () -> Int,
    private val preview: (step: Int) -> Unit,
    private val settle: (step: Int) -> Unit,
) {
    /** Who handles the current two-finger gesture: nobody yet, undecided at 100%, the viewer, after it, or WebView. */
    private enum class Mode { NONE, DECIDING, OURS, DONE, WEBVIEW }

    private var mode = Mode.NONE
    private var start = 1f
    private var factor = 1f
    /** Events of a pinch whose direction is not known yet; WebView gets them if it is its own. */
    private val held = ArrayList<MotionEvent>()

    @SuppressLint("ClickableViewAccessibility") // Only two-finger gestures are taken; taps and scrolling reach WebView unchanged.
    fun attach() { web.setOnTouchListener { _, event -> touch(event) } }

    /** The screen is closing: copies of a gesture still held go back to the system. */
    fun release() { forget() }

    // Measured on screen: during the preview the view is scaled, and its own coordinates with it.
    private fun span(event: MotionEvent) = if (event.pointerCount < 2) 0f
        else hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1)) * web.scaleX

    /** WebView has seen the first finger: it is told the gesture ended, so it neither scrolls nor zooms. */
    private fun cancel(event: MotionEvent) =
        MotionEvent.obtain(event).also { it.action = MotionEvent.ACTION_CANCEL; web.onTouchEvent(it); it.recycle() }

    private fun forget() { held.forEach(MotionEvent::recycle); held.clear() }

    private fun showPreview(event: MotionEvent) {
        factor = span(event) / start
        val zoom = layoutZoom()
        val target = (zoom * factor).coerceIn(PreviewHtml.MIN_ZOOM.toFloat(), 100f)
        web.scaleX = target / zoom
        web.scaleY = target / zoom
        preview(nearestStep(target))
    }

    private fun finish() {
        mode = Mode.DONE
        web.scaleX = 1f
        web.scaleY = 1f
        settle(nearestStep(layoutZoom() * factor))
    }

    private fun replay() {
        held.forEach { web.onTouchEvent(it); it.recycle() }
        held.clear()
        mode = Mode.WEBVIEW
    }

    private fun touch(event: MotionEvent): Boolean = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> { mode = Mode.NONE; forget(); false }
        MotionEvent.ACTION_POINTER_DOWN -> when {
            mode != Mode.NONE || event.pointerCount != 2 -> mode == Mode.OURS || mode == Mode.DONE
            // Magnified: WebView zooms in either direction, down to 100%.
            @Suppress("DEPRECATION") web.canZoomOut() -> { mode = Mode.WEBVIEW; false }
            else -> {
                start = span(event).coerceAtLeast(1f)
                factor = 1f
                web.pivotX = (event.getX(0) + event.getX(1)) / 2
                web.pivotY = (event.getY(0) + event.getY(1)) / 2
                if (layoutZoom() < 100) { mode = Mode.OURS; cancel(event) }
                // At 100%: outwards is WebView's magnification, inwards ours; held until the direction shows.
                else { mode = Mode.DECIDING; held += MotionEvent.obtain(event) }
                true
            }
        }
        MotionEvent.ACTION_MOVE -> when (mode) {
            Mode.DECIDING -> {
                held += MotionEvent.obtain(event)
                val spread = span(event) / start
                if (spread > 1.04f) replay()
                else if (spread < 0.96f) { mode = Mode.OURS; forget(); cancel(event); showPreview(event) }
                true
            }
            Mode.OURS -> { showPreview(event); true }
            Mode.DONE -> true
            else -> false
        }
        MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> when (mode) {
            // Fingers lifted before the direction showed: WebView gets the whole gesture.
            Mode.DECIDING -> { held += MotionEvent.obtain(event); replay(); true }
            Mode.OURS -> { finish(); true }
            Mode.DONE -> true
            else -> false
        }
        else -> mode == Mode.OURS || mode == Mode.DONE
    }

    companion object {
        /** The zoom step nearest to [percent] (on a ratio scale, as zoom is felt). */
        fun nearestStep(percent: Float) = PreviewHtml.ZOOM_STEPS.minBy { abs(ln(it / percent)) }
    }
}
