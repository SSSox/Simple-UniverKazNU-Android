package com.univerflow.kaznu.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.withScale
import com.univerflow.kaznu.R
import com.univerflow.kaznu.web.AppSection
import com.univerflow.kaznu.web.SitePolicy

/**
 * The first screen of the adapted page while it loads: the site's tabs and section menu stand exactly where they will
 * be, with quiet placeholders in the shape of the content coming. Nothing here loops; the bar under the app bar shows
 * the wait. All measures are the page's own CSS pixels, which are dp at the normal text size.
 */
class PageSkeleton @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private enum class Shape { NEWS, SCHEDULE, GRADES, ATTESTATION, LIST, DOCUMENT }

    private var shape = Shape.NEWS
    private var activeTab = 0
    private val density = resources.displayMetrics.density
    private val rect = RectF()
    private val ground = context.getColor(R.color.md_background)
    private val surface = paint(R.color.skeleton_surface)
    private val line = paint(R.color.skeleton_line).apply { style = Paint.Style.STROKE; strokeWidth = 1f }
    private val bar = paint(R.color.skeleton_bar)
    private val tint = paint(R.color.skeleton_tint)
    private val accent = paint(R.color.skeleton_accent)
    private val onAccent = paint(R.color.skeleton_on_accent)

    private fun paint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = context.getColor(color) }

    /** Takes the shape of the page at [url]. */
    fun show(url: String?) {
        val path = runCatching { Uri.parse(url).path }.getOrNull().orEmpty()
        val news = path == "/" || path.startsWith("/news")
        val next = when (SitePolicy.section(url)) {
            AppSection.SCHEDULE -> Shape.SCHEDULE
            AppSection.JOURNAL -> Shape.GRADES
            AppSection.ATTESTATION -> Shape.ATTESTATION
            else -> if (news) Shape.NEWS else Shape.LIST
        }
        val tab = if (news) 0 else if (path.startsWith("/ssc")) 2 else 1
        if (next == shape && tab == activeTab) return
        shape = next
        activeTab = tab
        invalidate()
    }

    /** Takes the shape of a printed document: one sheet, without the site's navigation. */
    fun showDocument() {
        shape = Shape.DOCUMENT
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(ground)
        // In dp, read before the block: inside it Canvas's own width and density (an Int) would shadow the view's.
        val width = this.width / density
        // The scale is undone afterwards: the view's foreground and focus highlight are drawn on the same canvas.
        canvas.withScale(density, density) {
            if (shape != Shape.DOCUMENT) chrome(width)
            when (shape) {
                Shape.NEWS -> news(width)
                Shape.SCHEDULE -> schedule(width)
                Shape.GRADES -> grades(width)
                Shape.ATTESTATION -> attestation(width)
                Shape.LIST -> list(width)
                Shape.DOCUMENT -> document(width)
            }
        }
    }

    private val bottom get() = height / density

    /** The site's three tabs and the "All sections" bar, on every page. */
    private fun Canvas.chrome(width: Float) {
        val tab = (width - 2 * EDGE - 12f) / 3
        repeat(3) { index ->
            val x = EDGE + index * (tab + 6f)
            val active = index == activeTab
            if (active) block(x, 8f, tab, 44f, 10f, accent) else card(x, 8f, tab, 44f, 10f)
            block(x + tab / 2 - 38f, 22f, 16f, 16f, 4f, if (active) onAccent else bar)
            block(x + tab / 2 - 14f, 25f, 52f, 10f, 5f, if (active) onAccent else bar)
        }
        card(EDGE, 60f, width - 2 * EDGE, 50f)
        block(EDGE + 14f, 76f, 18f, 18f, 4f, bar)
        block(EDGE + 44f, 79f, 96f, 12f, 6f, bar)
    }

    private fun Canvas.news(width: Float) {
        val half = (width - 2 * EDGE - 8f) / 2
        repeat(6) { index ->
            val x = EDGE + (index % 2) * (half + 8f)
            val y = 130f + (index / 2) * 60f
            card(x, y, half, 52f)
            block(x + 10f, y + 10f, 32f, 32f, 8f, tint)
            block(x + 52f, y + 20f, half * .42f, 12f, 6f, bar)
        }
        card(EDGE, 330f, width - 2 * EDGE - 116f, 44f, 10f)
        card(width - EDGE - 108f, 330f, 108f, 44f, 10f)
        card(EDGE, 386f, width - 2 * EDGE, 44f, 10f)
        var y = 438f
        while (y < bottom) {
            val left = EDGE + 15f
            card(EDGE, y, width - 2 * EDGE, 213f)
            block(left, y + 13f, 83f, 24f, 8f, tint)
            block(left + 89f, y + 13f, 64f, 24f, 8f, bar)
            text(left, y + 52f, width - 2 * left, .62f, .9f, .78f, .84f)
            y += 229f
        }
    }

    private fun Canvas.schedule(width: Float) {
        val full = width - 2 * EDGE
        text(EDGE, 134f, full - 56f, .94f, .86f, .5f, height = 16f, pitch = 27f)
        card(width - EDGE - 44f, 130f, 44f, 44f, 10f)
        terms(width, 221f)
        card(EDGE, 285f, full, 44f, 10f)
        card(EDGE, 341f, full, 207f)
        text(EDGE + 14f, 359f, full - 28f, .5f, height = 12f)
        block(EDGE + 14f, 387f, full * .46f, 20f, 10f, bar)
        text(EDGE + 14f, 425f, full - 28f, .36f, .8f)
        card(EDGE + 14f, 490f, 128f, 44f, 10f)
        block(EDGE, 560f, 231f, 48f, 10f, bar)
        val cell = (full - 24f) / 7.4f
        var x = EDGE
        repeat(7) { index ->
            val cellWidth = if (index == 0) cell * 1.4f else cell
            card(x, 620f, cellWidth, 56f, 8f)
            block(x + cellWidth / 2 - 11f, 634f, 22f, 12f, 6f, bar)
            x += cellWidth + 4f
        }
        cards(width, 728f, 395f)
    }

    private fun Canvas.grades(width: Float) {
        val full = width - 2 * EDGE
        text(EDGE, 134f, full, .74f, height = 18f)
        terms(width, 167f)
        card(EDGE, 231f, full, 44f, 10f)
        card(EDGE, 283f, full, 44f, 10f)
        card(EDGE, 339f, full, bottom)
        block(EDGE + 13f, 356f, 160f, 12f, 6f, bar)
        var y = 386f
        while (y < bottom) {
            block(EDGE + 13f, y + 18f, full * .34f, 12f, 6f, bar)
            block(width - EDGE - 90f - 83f, y + 10f, 77f, 30f, 8f, tint)
            block(width - EDGE - 90f, y + 10f, 77f, 30f, 8f, tint)
            y += 49f
        }
    }

    private fun Canvas.attestation(width: Float) {
        val full = width - 2 * EDGE
        card(EDGE, 130f, full, 174f)
        text(EDGE + 17f, 146f, full - 34f, .7f, .32f, height = 14f, pitch = 24f)
        val stat = (full - 34f - 16f) / 3
        repeat(3) { block(EDGE + 17f + it * (stat + 8f), 203f, stat, 87f, 10f, tint) }
        terms(width, 316f)
        var y = 384f
        while (y < bottom) {
            card(EDGE, y, full, 167f)
            block(EDGE + 14f, y + 16f, full * .46f, 14f, 7f, bar)
            block(width - EDGE - 50f, y + 12f, 36f, 32f, 8f, tint)
            val column = (full - 28f - 12f) / 4
            repeat(4) { index ->
                val x = EDGE + 14f + index * (column + 4f)
                if (index == 3) block(x, y + 56f, column, 56f, 8f, tint)
                block(x + 8f, y + 64f, 24f, 10f, 5f, bar)
                block(x + 8f, y + 82f, 32f, 16f, 8f, bar)
            }
            y += 175f
        }
    }

    private fun Canvas.list(width: Float) {
        val full = width - 2 * EDGE
        text(EDGE, 134f, full, .48f, height = 18f)
        terms(width, 167f)
        var y = 231f
        while (y < bottom) {
            card(EDGE, y, full, 72f)
            block(EDGE + 14f, y + 18f, 36f, 36f, 8f, tint)
            text(EDGE + 64f, y + 20f, full - 110f, .68f, .36f, height = 12f, pitch = 22f)
            y += 80f
        }
    }

    private fun Canvas.document(width: Float) {
        val full = width - 2 * EDGE
        card(EDGE, EDGE, full, bottom)
        block(width / 2 - full * .3f, 40f, full * .6f, 16f, 8f, bar)
        block(width / 2 - full * .2f, 68f, full * .4f, 12f, 6f, bar)
        var y = 116f
        var line = 0
        while (y < bottom) {
            text(EDGE + 16f, y, full - 32f, if (line % 5 == 4) .56f else .92f)
            y += if (line % 5 == 4) 44f else 24f
            line++
        }
    }

    /** The previous and next semester. */
    private fun Canvas.terms(width: Float, y: Float) {
        val half = (width - 2 * EDGE - 8f) / 2
        repeat(2) { index ->
            val x = EDGE + index * (half + 8f)
            card(x, y, half, 52f)
            val start = if (index == 0) x + 40f else x + half - 40f - half * .5f
            text(start, y + 12f, half * .5f, .6f, 1f, height = 11f, pitch = 18f)
        }
    }

    private fun Canvas.cards(width: Float, top: Float, height: Float) {
        var y = top
        while (y < bottom) { card(EDGE, y, width - 2 * EDGE, height); y += height + 12f }
    }

    /** Lines of text, as fractions of [width]. */
    private fun Canvas.text(x: Float, top: Float, width: Float, vararg lines: Float, height: Float = 12f, pitch: Float = 24f) {
        lines.forEachIndexed { index, part -> block(x, top + index * pitch, width * part, height, height / 2, bar) }
    }

    private fun Canvas.block(x: Float, y: Float, width: Float, height: Float, radius: Float, paint: Paint) {
        rect.set(x, y, x + width, y + height)
        drawRoundRect(rect, radius, radius, paint)
    }

    private fun Canvas.card(x: Float, y: Float, width: Float, height: Float, radius: Float = 12f) {
        block(x, y, width, height, radius, surface)
        rect.inset(.5f, .5f)
        drawRoundRect(rect, radius, radius, line)
    }

    private companion object {
        /** The page's side margin. */
        const val EDGE = 12f
    }
}
