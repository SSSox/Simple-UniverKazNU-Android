package com.univerflow.kaznu.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.content.withStyledAttributes
import androidx.core.graphics.withClip
import com.univerflow.kaznu.R

/**
 * A rounded plate the way the site layer draws its cards and score chips: a [fill], a hairline [stroke], an optional
 * [edge] stripe along the start (the site's exam slot) and an optional [foot] bar along the bottom that is [footShare]
 * of the width (a score chip). The corners also clip the children, such as a card's tinted header or a row's highlight.
 * Everything is drawn by hand, not with an outline, so a software canvas (QA pictures) shows what the screen shows.
 */
class PlateLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private val density = resources.displayMetrics.density
    var fill: Int = ContextCompat.getColor(context, R.color.card_surface)
        set(value) { field = value; invalidate() }
    /** The hairline; null draws none. */
    var stroke: Int? = ContextCompat.getColor(context, R.color.md_outline_variant)
        set(value) { field = value; invalidate() }
    var radius: Float = 12 * density
        set(value) { field = value; invalidate() }
    /** A 3dp stripe inside the start edge; null draws none. */
    var edge: Int? = null
        set(value) { field = value; invalidate() }
    /** A 3dp bar along the bottom, [footShare] (0..1) of the width from the start; null draws none. */
    var foot: Int? = null
        set(value) { field = value; invalidate() }
    var footShare: Float = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val shape = Path()

    init {
        setWillNotDraw(false)
        context.withStyledAttributes(attrs, R.styleable.PlateLayout) { radius = getDimension(R.styleable.PlateLayout_plateRadius, radius) }
    }

    private fun outline(): Path {
        box.set(0f, 0f, width.toFloat(), height.toFloat())
        shape.reset()
        shape.addRoundRect(box, radius, radius, Path.Direction.CW)
        return shape
    }

    override fun onDraw(canvas: Canvas) {
        val path = outline()
        paint.style = Paint.Style.FILL
        paint.color = fill
        canvas.drawPath(path, paint)
        val band = 3 * density
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        // The view's own size: inside withClip `width` would be the canvas's.
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.withClip(path) {
            edge?.let {
                paint.color = it
                if (rtl) drawRect(w - band, 0f, w, h, paint) else drawRect(0f, 0f, band, h, paint)
            }
            foot?.takeIf { footShare > 0f }?.let {
                paint.color = it
                val length = w * footShare
                if (rtl) drawRect(w - length, h - band, w, h, paint) else drawRect(0f, h - band, length, h, paint)
            }
        }
        super.onDraw(canvas)
    }

    override fun dispatchDraw(canvas: Canvas) {
        canvas.withClip(outline()) { super.dispatchDraw(this) }
        // The hairline goes over the children, so a clipped header's edge always sits under it.
        stroke?.let {
            val half = density / 2
            box.set(half, half, width - half, height - half)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density
            paint.color = it
            canvas.drawRoundRect(box, radius - half, radius - half, paint)
        }
    }
}
