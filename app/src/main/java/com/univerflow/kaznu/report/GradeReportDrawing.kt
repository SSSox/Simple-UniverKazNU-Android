package com.univerflow.kaznu.report

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import com.univerflow.kaznu.R
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.domain.Band
import com.univerflow.kaznu.domain.GradeReport
import com.univerflow.kaznu.domain.ScoreBand
import java.io.File
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The grade report as a parent or a curator reads it on paper or in a messenger: the term, then each course's control
 * periods with their band in words, a bar with the marks 50, 70 and 90 and how far the next mark is, after a short guide
 * to the colours. One drawing in PDF points serves both outputs: the same A4 pages as a PDF ([writePdf]) and as images
 * ([writePng]).
 * [words] is a context in the report's language; [date] is the day it was made, already formatted.
 */
class GradeReportDrawing(private val words: Context, private val language: AppLanguage, private val report: GradeReport, private val date: String) {
    /**
     * A piece that is never split across pages: its height, the space above it (dropped at the top of a page) and how to
     * draw it from its own top-left corner. A course's name travels with its first control period; a later period knows
     * its [course], to name it again at the top of a new page.
     */
    class Block(val height: Float, val gap: Float, val course: String? = null, val draw: (Canvas) -> Unit)

    private val width = PAGE_WIDTH - 2 * MARGIN
    val blocks: List<Block> = buildList {
        add(header())
        add(guide())
        report.goal?.let { add(goal(it)) }
        report.courses.forEach { addAll(course(it)) }
    }
    /** Courses that went on over a page break in the last [writePdf] or [writePng]. */
    var carriedOver = 0
        private set

    /** One A4 page: its blocks with the top of each, and where the last one ends. */
    private class Page(val placed: List<Pair<Block, Float>>, val bottom: Float)

    /** The blocks on A4 pages, turning only between control periods. */
    private fun pages(): List<Page> {
        val pages = mutableListOf<Page>()
        var placed = mutableListOf<Pair<Block, Float>>()
        var top = MARGIN
        var bottom = MARGIN
        carriedOver = 0
        blocks.forEach { block ->
            if (top > MARGIN) top += block.gap
            if (top > MARGIN && top + block.height > PAGE_HEIGHT - MARGIN) {
                pages += Page(placed, bottom)
                placed = mutableListOf()
                top = MARGIN
                // A page never starts with a bare «РК2»: the course carried over is named again.
                block.course?.let { name ->
                    val head = carried(name)
                    placed += head to top
                    top += head.height + ROW_GAP
                    carriedOver++
                }
            }
            placed += block to top
            top += block.height
            bottom = top
        }
        pages += Page(placed, bottom)
        return pages
    }

    /** Writes the A4 pages and returns how many there are. */
    fun writePdf(file: File): Int {
        val laid = pages()
        val document = PdfDocument()
        try {
            laid.forEachIndexed { index, page ->
                val sheet = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH.toInt(), PAGE_HEIGHT.toInt(), index + 1).create())
                sheet.canvas.drawColor(Color.WHITE)
                page.placed.forEach { (block, top) -> place(sheet.canvas, block, top) }
                document.finishPage(sheet)
            }
            file.outputStream().use(document::writeTo)
            return laid.size
        } finally {
            document.close()
        }
    }

    /**
     * Writes the PDF's pages as images twice its size (sharp on a phone), each cut below its last block: a short report
     * stays one short picture, a long one turns pages where the PDF does and its text keeps its size. One page goes to
     * [file]; several go beside it as "name-1.png", "name-2.png"…, in order. Returns the files written. [beforePage] runs
     * before each page and may throw to stop (a cancelled report draws no further pages).
     */
    fun writePng(file: File, beforePage: () -> Unit = {}): List<File> {
        val laid = pages()
        return laid.mapIndexed { index, page ->
            beforePage()
            val target = if (laid.size == 1) file else File(file.parentFile, "${file.nameWithoutExtension}-${index + 1}.${file.extension}")
            val bitmap = createBitmap((PAGE_WIDTH * SCALE).roundToInt(), ((page.bottom + MARGIN) * SCALE).roundToInt())
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                canvas.scale(SCALE, SCALE)
                page.placed.forEach { (block, top) -> place(canvas, block, top) }
                target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                bitmap.recycle()
            }
            target
        }
    }

    private fun place(canvas: Canvas, block: Block, top: Float) {
        canvas.withTranslation(MARGIN, top) { block.draw(this) }
    }

    private fun header(): Block {
        val title = layout(words.getString(R.string.report_title), paint(22f, INK, bold = true), width)
        val lines = buildList {
            if (report.student.isNotEmpty()) add(layout(report.student, paint(13f, INK, bold = true), width))
            report.term?.let { term -> add(layout(termText(term), paint(11f, TEXT), width)) }
            add(layout(words.getString(R.string.report_made, date), paint(9f, MUTED), width))
        }
        val height = title.height + 6f + lines.sumOf { it.height + 3.0 }.toFloat()
        return Block(height, 0f) { canvas ->
            var y = 0f
            canvas.text(title, 0f, y); y += title.height + 6f
            lines.forEach { canvas.text(it, 0f, y); y += it.height + 3f }
        }
    }

    private fun termText(term: GradeReport.Term) = words.getString(
        when (term.term) { 1 -> R.string.report_term_autumn; 2 -> R.string.report_term_spring; else -> R.string.report_term_summer },
        term.year, term.year + 1)

    /** What РК means and what each colour says, in a quiet box: the report is read without the app. */
    private fun guide(): Block {
        val pad = 12f
        val inner = width - 2 * pad
        val heading = layout(words.getString(R.string.report_guide_title), paint(11f, INK, bold = true), inner)
        val body = layout(words.getString(R.string.report_guide), paint(10f, TEXT), inner)
        val column = (inner - 12f) / 2
        val items = listOf(Band.LOW to R.string.report_legend_low, Band.PASS to R.string.report_legend_pass,
            Band.GOOD to R.string.report_legend_good, Band.TOP to R.string.report_legend_top)
            .map { (band, id) -> band to layout(words.getString(id), paint(10f, TEXT), column - 14f) }
        val rows = items.chunked(2).map { row -> row.maxOf { it.second.height }.toFloat() }
        val height = pad + heading.height + 4f + body.height + 8f + rows.sum() + 4f * (rows.size - 1) + pad
        return Block(height, GAP) { canvas ->
            val box = RectF(0f, 0f, width, height)
            canvas.drawRoundRect(box, 8f, 8f, fill(SURFACE))
            canvas.drawRoundRect(box.apply { inset(0.5f, 0.5f) }, 8f, 8f, stroke(LINE))
            var y = pad
            canvas.text(heading, pad, y); y += heading.height + 4f
            canvas.text(body, pad, y); y += body.height + 8f
            items.chunked(2).forEachIndexed { index, row ->
                row.forEachIndexed { at, (band, text) ->
                    val x = pad + at * (column + 12f)
                    canvas.drawRoundRect(RectF(x, y + 2f, x + 9f, y + 11f), 2f, 2f, fill(colour(band)))
                    canvas.text(text, x + 14f, y)
                }
                y += rows[index] + 4f
            }
        }
    }

    /** The goal chosen on the page, in words: the reader knows what the «left» numbers are counted to. */
    private fun goal(goal: Band): Block {
        val pad = 10f
        val name = words.getString(bandName(goal))
        val text = SpannableStringBuilder(words.getString(R.string.report_goal, name, ScoreBand.goalMark(goal)))
        text.indexOf(name).takeIf { it >= 0 }?.let { text.setSpan(StyleSpan(Typeface.BOLD), it, it + name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        val body = layout(text, paint(10.5f, INK), width - 2 * pad - 4f)
        val height = body.height + 2 * pad
        return Block(height, GAP) { canvas ->
            canvas.drawRoundRect(RectF(0f, 0f, width, height), 8f, 8f, fill(soft(goal)))
            canvas.drawRoundRect(RectF(0f, 0f, 4f, height), 2f, 2f, fill(colour(goal)))
            canvas.text(body, pad + 4f, pad)
        }
    }

    /** The course's name with its first period, then each further period on its own: a page turns only between them. */
    private fun course(course: GradeReport.Course): List<Block> {
        val name = layout(course.name, paint(13f, INK, bold = true), width)
        val rows = (if (course.scores.isEmpty()) listOf(emptyRow(words.getString(R.string.report_no_grades))) else course.scores.map(::scoreRow)) +
            listOfNotNull(absences(course.absences))
        val first = rows.first()
        val top = name.height + 6f
        return listOf(Block(top + first.height, GAP) { canvas ->
            canvas.text(name, 0f, 0f)
            canvas.withTranslation(0f, top) { first.draw(this) }
        }) + rows.drop(1).map { Block(it.height, it.gap, course.name, it.draw) }
    }

    /** "Пропуски: 4": the journal's «н» marks, after the course's periods; nothing when there are none. */
    private fun absences(count: Int): Block? {
        if (count <= 0) return null
        val text = SpannableStringBuilder(words.getString(R.string.report_absences, count))
        val digits = count.toString()
        text.lastIndexOf(digits).takeIf { it >= 0 }?.let { text.setSpan(StyleSpan(Typeface.BOLD), it, it + digits.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        val line = layout(text, paint(10f, TEXT), width - 20f)
        return Block(line.height + 4f, ROW_GAP) { canvas -> canvas.text(line, 10f, 2f) }
    }

    private fun carried(name: String): Block {
        val text = layout(words.getString(R.string.report_continued, name), paint(13f, INK, bold = true), width)
        return Block(text.height.toFloat(), 0f) { canvas -> canvas.text(text, 0f, 0f) }
    }

    private fun number(raw: String) = ScoreBand.display(raw, language)

    /** One control period: "РК1 Хорошо … 87 из 100", the bar with its marks, then how far the next mark is. */
    private fun scoreRow(score: GradeReport.Score): Block {
        val standing = ScoreBand.of(score.value, score.max, report.goal) ?: return emptyRow(
            if (score.value.isEmpty()) words.getString(R.string.report_no_grade) else amount(score), label(score))
        val pad = 10f
        val band = colour(standing.band)
        val ink = ink(standing.band)
        val left = SpannableStringBuilder(label(score)).append("   ")
        val start = left.length
        left.append(words.getString(bandName(standing.band)))
        left.setSpan(ForegroundColorSpan(ink), start, left.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val amount = amount(score)
        val bold = paint(11f, INK, bold = true)
        val head = layout(left, bold, width - 2 * pad - bold.measureText(amount) - 8f)
        val nextText = standing.next?.let { words.getString(R.string.report_short, ScoreBand.format(it, language), ScoreBand.format(standing.left, language)) }
            ?: words.getString(if (standing.reached) R.string.report_goal_reached else R.string.report_reached)
        // What is left is the line a parent reads first: heavier, larger and in the band's ink; a reached mark stays plain.
        val nextPaint = if (standing.next != null) paint(10.5f, ink, bold = true) else paint(9.5f, TEXT)
        val small = paint(8f, MUTED)
        val labels = standing.marks.map { ScoreBand.format(it, language) }
        // How far the next mark is sits under the bar's start, left of the 50 mark, when it fits there; else on its own line.
        val inline = nextPaint.measureText(nextText) <= (width - 2 * pad) * ScoreBand.MARKS[0] / 100f - small.measureText(labels[0]) / 2 - 8f
        val barTop = pad + head.height + 6f
        val baseline = barTop + BAR + 3f + maxOf(-small.ascent(), if (inline) -nextPaint.ascent() else 0f)
        val next = if (inline) null else layout(nextText, nextPaint, width - 2 * pad)
        val nextTop = baseline + small.descent() + 3f
        val height = if (next == null) baseline + nextPaint.descent() + pad else nextTop + next.height + pad
        return Block(height, ROW_GAP) { canvas ->
            canvas.drawRoundRect(RectF(0f, 0f, width, height), 8f, 8f, fill(soft(standing.band)))
            canvas.text(head, pad, pad)
            canvas.drawText(amount, width - pad - bold.measureText(amount), pad - bold.ascent(), bold)
            val track = RectF(pad, barTop, width - pad, barTop + BAR)
            canvas.drawRoundRect(track, BAR / 2, BAR / 2, fill(Color.WHITE))
            canvas.drawRoundRect(RectF(track).apply { inset(0.4f, 0.4f) }, BAR / 2, BAR / 2, stroke(LINE))
            val filled = (standing.percent / 100).toFloat() * track.width()
            if (filled > 0f) canvas.drawRoundRect(RectF(track.left, track.top, track.left + filled, track.bottom), BAR / 2, BAR / 2, fill(band))
            ScoreBand.MARKS.forEachIndexed { index, mark ->
                val x = track.left + track.width() * mark / 100f
                canvas.drawRect(x - 0.6f, track.top, x + 0.6f, track.bottom, fill(if (standing.percent >= mark) Color.WHITE else LINE_STRONG))
                canvas.drawText(labels[index], x - small.measureText(labels[index]) / 2, baseline, small)
            }
            if (next == null) canvas.drawText(nextText, pad, baseline, nextPaint) else canvas.text(next, pad, nextTop)
        }
    }

    /** "РК1" reads as "PK1" to an English reader: the English report spells it in Latin letters. */
    private fun label(score: GradeReport.Score) = if (language == AppLanguage.ENGLISH) score.label.replace("РК", "RK") else score.label

    private fun amount(score: GradeReport.Score): String {
        val value = if (score.value.isEmpty()) "–" else number(score.value)
        return if (score.max.isEmpty()) value else words.getString(R.string.report_out_of, value, number(score.max))
    }

    /** A period without a grade (or without a maximum to measure it by) is one quiet line. */
    private fun emptyRow(text: String, label: String? = null): Block {
        val pad = 10f
        val bold = paint(11f, INK, bold = true)
        val quiet = layout(text, paint(10f, MUTED), width - 2 * pad - (label?.let { bold.measureText(it) + 12f } ?: 0f))
        val height = pad * 2 + maxOf(quiet.height.toFloat(), -bold.ascent() + bold.descent())
        return Block(height, ROW_GAP) { canvas ->
            canvas.drawRoundRect(RectF(0f, 0f, width, height), 8f, 8f, fill(SURFACE))
            var x = pad
            if (label != null) { canvas.drawText(label, x, pad - bold.ascent(), bold); x += bold.measureText(label) + 12f }
            canvas.text(quiet, x, pad)
        }
    }

    private fun bandName(band: Band) = when (band) {
        Band.LOW -> R.string.report_band_low; Band.PASS -> R.string.report_band_pass
        Band.GOOD -> R.string.report_band_good; Band.TOP -> R.string.report_band_top
    }

    private fun paint(size: Float, color: Int, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color; typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }
    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
    private fun stroke(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = 0.8f }
    private fun layout(text: CharSequence, paint: TextPaint, width: Float): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, ceil(width.coerceAtLeast(1f)).toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
    private fun Canvas.text(layout: StaticLayout, x: Float, y: Float) = withTranslation(x, y) { layout.draw(this) }

    companion object {
        const val PAGE_WIDTH = 595f
        const val PAGE_HEIGHT = 842f
        private const val MARGIN = 40f
        private const val GAP = 14f
        private const val ROW_GAP = 6f
        private const val BAR = 7f
        /** Image pixels per PDF point: a page is 1190 px wide, at most about 8 MB of bitmap at a time. */
        const val SCALE = 2f
        // The page's light palette (univer-style.js --u-* and --u-band-* tokens): paper is always light.
        private const val INK = 0xFF16283B.toInt()
        private const val TEXT = 0xFF2A3D51.toInt()
        private const val MUTED = 0xFF5B6B7C.toInt()
        private const val LINE = 0xFFDFE5EC.toInt()
        private const val LINE_STRONG = 0xFFC8D3DF.toInt()
        private const val SURFACE = 0xFFF7F9FB.toInt()

        /** The band's muted bar and legend colour. */
        fun colour(band: Band) = when (band) {
            Band.LOW -> 0xFFD77A70.toInt(); Band.PASS -> 0xFFCC9A4C.toInt()
            Band.GOOD -> 0xFF5FA67F.toInt(); Band.TOP -> 0xFF6B97CC.toInt()
        }
        /** The band's name: 5.4:1 and up on its [soft] background. */
        fun ink(band: Band) = when (band) {
            Band.LOW -> 0xFFA2453D.toInt(); Band.PASS -> 0xFF87591A.toInt()
            Band.GOOD -> 0xFF2E6F4C.toInt(); Band.TOP -> 0xFF30629C.toInt()
        }
        fun soft(band: Band) = when (band) {
            Band.LOW -> 0xFFFBF3F2.toInt(); Band.PASS -> 0xFFFAF5EC.toInt()
            Band.GOOD -> 0xFFEEF6F1.toInt(); Band.TOP -> 0xFFEEF3F9.toInt()
        }
    }
}
