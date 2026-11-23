package com.univerflow.kaznu.report

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import com.univerflow.kaznu.R
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.domain.PictureWeek
import com.univerflow.kaznu.domain.SchedulePair
import com.univerflow.kaznu.domain.SchedulePicture
import com.univerflow.kaznu.domain.Timetable
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The schedule's two weeks side by side as one picture for a messenger: a column per week (its number and whether it is odd
 * or even, then its days with their classes), in the order of the week numbers. A week that could not be read is a column
 * that says so. Drawn in light colours at twice the size of the layout's units; a very long week is drawn smaller, never
 * under half, and what would pass the pixel limit is left out (SchedulePicture.canvas). [words] is a context in the picture's language.
 */
class ScheduleDrawing(private val words: Context, private val language: AppLanguage, private val pair: SchedulePair) {
    private val locale = if (language == AppLanguage.ENGLISH) Locale.US else Locale.forLanguageTag(language.localeCode)
    private val utc = TimeZone.getTimeZone("UTC")
    private val weeks = SchedulePicture.weeks(pair)

    /** One column: its height in layout units and how to draw it from its top-left corner. */
    private class Column(val height: Float, val draw: (Canvas) -> Unit)

    /** Writes the PNG to [file]; [beforeDraw] runs first and may throw to stop (a cancelled picture draws nothing). */
    fun writePng(file: File, beforeDraw: () -> Unit = {}): File {
        beforeDraw()
        val columns = weeks.map(::column)
        val height = (columns.maxOf { it.height } + 2 * MARGIN)
        val width = columns.size * COLUMN + (columns.size + 1) * MARGIN
        val (scale, pixels, cut) = SchedulePicture.canvas(height, MAX_PIXELS, SCALE)
        val bitmap = createBitmap((width * scale).roundToInt(), pixels)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            canvas.withScale(scale, scale) {
                val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LINE; strokeWidth = 1f }
                columns.forEachIndexed { index, column ->
                    val x = MARGIN + index * (COLUMN + MARGIN)
                    if (index > 0) drawLine(x - MARGIN / 2, MARGIN, x - MARGIN / 2, height - MARGIN, rule)
                    withTranslation(x, MARGIN) { column.draw(this) }
                }
            }
            if (cut) drawCut(canvas, bitmap.width.toFloat(), pixels.toFloat())
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    /** The bottom that did not fit fades out under a «⋯»: no words, so the picture needs no translation for it. */
    private fun drawCut(canvas: Canvas, width: Float, bottom: Float) {
        val band = CUT_BAND
        canvas.drawRect(0f, bottom - band, width, bottom, Paint().apply {
            shader = LinearGradient(0f, bottom - band, 0f, bottom - band / 3, Color.TRANSPARENT, Color.WHITE, Shader.TileMode.CLAMP)
        })
        canvas.drawText("⋯", width / 2, bottom - band / 6, paint(28f, MUTED, bold = true).apply { textAlign = Paint.Align.CENTER })
    }

    private fun column(week: PictureWeek): Column {
        val items = mutableListOf<Pair<Float, (Canvas) -> Unit>>()
        var top = 0f
        fun add(height: Float, gap: Float, draw: (Canvas) -> Unit) { top += gap; val at = top; items += at to draw; top += height }
        fun text(layout: StaticLayout, gap: Float, x: Float = 0f) = add(layout.height.toFloat(), gap) { it.withTranslation(x, 0f) { layout.draw(this) } }

        val heading = layout(headingText(week), paint(20f, INK, bold = true), COLUMN)
        text(heading, 0f)
        range(week)?.let { text(layout(it, paint(12f, MUTED), COLUMN), 2f) }
        when {
            week.missing -> text(layout(words.getString(R.string.schedule_unread), paint(14f, MUTED), COLUMN), 16f)
            week.days.isEmpty() -> text(layout(words.getString(R.string.schedule_no_classes), paint(14f, MUTED), COLUMN), 16f)
            else -> week.days.forEach { day ->
                text(layout(dayTitle(day.date), paint(13f, BRAND, bold = true), COLUMN), 18f)
                add(1f, 4f) { it.drawLine(0f, 0f, COLUMN, 0f, Paint().apply { color = LINE; strokeWidth = 1f }) }
                day.lessons.forEach { lesson ->
                    val time = layout(Timetable.clock(lesson.start) + "–" + Timetable.clock(lesson.end), paint(13f, INK, bold = true), TIME)
                    val title = layout(if (lesson.type.isEmpty()) lesson.subject else "${lesson.subject} (${lesson.type})", paint(14f, INK), COLUMN - TIME - 8f)
                    val details = listOfNotNull(lesson.room.takeIf { it.isNotEmpty() }?.let { words.getString(R.string.schedule_room, it) },
                        lesson.teacher.takeIf { it.isNotEmpty() }).joinToString(" · ")
                    val more = details.takeIf { it.isNotEmpty() }?.let { layout(it, paint(12f, MUTED), COLUMN - TIME - 8f) }
                    val height = title.height + (more?.let { 2f + it.height } ?: 0f)
                    add(maxOf(height, time.height.toFloat()), 8f) { canvas ->
                        canvas.withTranslation(0f, 0f) { time.draw(this) }
                        canvas.withTranslation(TIME + 8f, 0f) { title.draw(this) }
                        more?.let { canvas.withTranslation(TIME + 8f, title.height + 2f) { it.draw(this) } }
                    }
                }
            }
        }
        return Column(top) { canvas -> items.forEach { (at, draw) -> canvas.withTranslation(0f, at) { draw(this) } } }
    }

    private fun headingText(week: PictureWeek): String = when {
        week.labelled -> words.getString(R.string.schedule_week_heading, week.number, words.getString(if (week.odd) R.string.schedule_odd else R.string.schedule_even))
        week.missing -> words.getString(R.string.schedule_other_week)
        else -> range(week).orEmpty()
    }

    /** "16 нояб. 2026 – 22 нояб. 2026", under a heading that is not the dates themselves. */
    private fun range(week: PictureWeek): String? {
        if (week.missing || !week.labelled) return null
        val format = DateFormat.getDateInstance(DateFormat.MEDIUM, locale).apply { timeZone = utc }
        return format.format(Date(week.first * DAY)) + " – " + format.format(Date(week.last * DAY))
    }

    private fun dayTitle(date: Long): String =
        SimpleDateFormat("EEEE, d MMMM", locale).apply { timeZone = utc }.format(Date(date * DAY)).replaceFirstChar { it.titlecase(locale) }

    private fun paint(size: Float, color: Int, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color; typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun layout(text: CharSequence, paint: TextPaint, width: Float): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, ceil(width.coerceAtLeast(1f)).toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()

    companion object {
        /** Layout units of a column's width and of the margins; pixels = units x [SCALE]. */
        const val COLUMN = 360f
        private const val MARGIN = 20f
        private const val TIME = 76f
        const val SCALE = 2f
        /** The most pixels of height: a longer week is drawn smaller (down to one pixel a unit). */
        private const val MAX_PIXELS = 12000f
        /** Pixels of the fade over a cut picture's bottom. */
        private const val CUT_BAND = 72f
        private const val DAY = 86_400_000L
        private const val INK = 0xFF16283B.toInt()
        private const val MUTED = 0xFF5B6B7C.toInt()
        private const val BRAND = 0xFF1B5FA6.toInt()
        private const val LINE = 0xFFDFE5EC.toInt()
    }
}
