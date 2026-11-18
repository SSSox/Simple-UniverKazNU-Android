package com.univerflow.kaznu.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.withScale
import com.univerflow.kaznu.R
import com.univerflow.kaznu.web.PageShape
import com.univerflow.kaznu.web.PageShapes

/**
 * The first screen of the adapted page while it loads: the site's tabs and section menu stand exactly where they will
 * be, with quiet placeholders where that page's blocks will appear. Every page has its own drawing, measured on the
 * phone layout (see [PageShapes]), so the placeholder and the page it leaves for line up. Nothing here loops; the bar
 * under the app bar shows the wait. All measures are the page's own CSS pixels, which are dp at the normal text size.
 */
class PageSkeleton @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var shape = PageShape.NEWS
    private var document = false
    private var activeTab = 0
    private var w = 0f
    private val full get() = w - 2 * EDGE
    private val bottom get() = height / density
    private val density = resources.displayMetrics.density
    private val rect = RectF()
    private val ground = context.getColor(R.color.md_background)
    private val surface = paint(R.color.skeleton_surface)
    private val line = paint(R.color.skeleton_line).apply { style = Paint.Style.STROKE; strokeWidth = 1f }
    private val dashed = paint(R.color.skeleton_line).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f; pathEffect = DashPathEffect(floatArrayOf(4f, 3f), 0f)
    }
    private val bar = paint(R.color.skeleton_bar)
    private val tint = paint(R.color.skeleton_tint)
    private val accent = paint(R.color.skeleton_accent)
    private val onAccent = paint(R.color.skeleton_on_accent)

    /** Home tiles the student has (the built-in ones left and the pins), so the home placeholder ends where its tiles do. */
    var tiles = 6
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private fun paint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = context.getColor(color) }

    /** Takes the shape of the page at [url]. */
    fun show(url: String?) {
        val next = PageShapes.of(url)
        val tab = PageShapes.tab(url)
        if (!document && next == shape && tab == activeTab) return
        document = false
        shape = next
        activeTab = tab
        invalidate()
    }

    /** Takes the shape of a printed document: one sheet, without the site's navigation. */
    fun showDocument() {
        document = true
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(ground)
        // In dp, read before the block: inside it Canvas's own width and density (an Int) would shadow the view's.
        w = this.width / density
        // The drawings are measured on phones 360 dp and wider: a narrower screen draws them at 360 and squeezes them to
        // fit, so fixed-width chips and buttons neither overlap nor run off the edge.
        val squeeze = if (w in 1f..NARROWEST) w / NARROWEST else 1f
        if (squeeze < 1f) w = NARROWEST
        // The scale is undone afterwards: the view's foreground and focus highlight are drawn on the same canvas.
        canvas.withScale(density * squeeze, density) {
            if (document) { document(); return@withScale }
            chrome()
            when (shape) {
                PageShape.NEWS -> news()
                PageShape.BACHELOR -> bachelor()
                PageShape.SERVICES -> services()
                PageShape.NEWS_LIST -> newsList()
                PageShape.FAQ -> faq()
                PageShape.CATALOG -> catalog()
                PageShape.MESSAGES -> messages()
                PageShape.CALCULATOR -> calculator()
                PageShape.APPEALS -> { primary(EDGE, 138f, 204f, 48f, 12f); empty(198f, 100f); footer(376f) }
                PageShape.SURVEYS -> surveys()
                PageShape.SURVEY -> survey()
                PageShape.QUESTIONS -> questions()
                PageShape.FORM -> form()
                PageShape.PRACTICE -> practice()
                PageShape.ADVISOR -> advisor()
                PageShape.CONTRACTS -> contracts()
                PageShape.CALENDAR -> calendar()
                PageShape.EXAMS -> exams()
                PageShape.MIDTERM -> { title(.45f); empty(187f, 122f); footer(387f) }
                PageShape.PLAN -> plan()
                PageShape.ATTESTATION -> attestation()
                PageShape.PROFILE -> { title(.7f); heading(175f, .25f); facts(EDGE, 221f, full, 1, 1, 1, 1, 1, 2, 1, 1, 1, 1, 1, 1) }
                PageShape.IGPA -> igpa()
                PageShape.TESTS -> tests()
                PageShape.DEBTS -> debts()
                PageShape.RETAKES -> { title(.75f); empty(199f, 100f); empty(335f, 80f); footer(493f) }
                PageShape.RAISE -> raise()
                PageShape.SCHEDULE -> schedule()
                PageShape.JOURNAL -> journal()
                PageShape.MATERIALS -> materials()
                PageShape.DISTANCE -> distance()
                PageShape.LIST -> list()
            }
        }
    }

    /** The site's three tabs in one group and the "All sections" bar, on every page. */
    private fun Canvas.chrome() {
        card(EDGE, 8f, full, 48f, 14f)
        val tab = (full - 16f) / 3
        repeat(3) { index ->
            val x = EDGE + 4f + index * (tab + 4f)
            val active = index == activeTab
            if (active) block(x, 12f, tab, 40f, 10f, accent)
            block(x + tab / 2 - 36f, 24f, 16f, 16f, 4f, if (active) onAccent else bar)
            block(x + tab / 2 - 12f, 27f, 50f, 10f, 5f, if (active) onAccent else bar)
        }
        card(EDGE, 64f, full, 50f)
        block(EDGE + 14f, 80f, 18f, 18f, 4f, bar)
        block(EDGE + 44f, 83f, 96f, 12f, 6f, bar)
        chevron(w - EDGE - 16f, 89f)
    }

    /** The home tiles in two columns and the dashed «Новый раздел» after them; returns where they end. */
    private fun Canvas.tiles(): Float {
        val half = (full - 10f) / 2
        var index = 0
        while (true) {
            val x = EDGE + (index % 2) * (half + 10f)
            val y = 138f + (index / 2) * 70f
            if (index >= tiles) {
                val tall = if (index % 2 == 0) 54f else 60f
                rect.set(x + .5f, y + .5f, x + half - .5f, y + tall - .5f)
                drawRoundRect(rect, 10f, 10f, dashed)
                block(x + 12f, y + tall / 2 - 9f, 18f, 18f, 4f, bar)
                block(x + 40f, y + tall / 2 - 6f, half * .42f, 12f, 6f, bar)
                return y + tall
            }
            card(x, y, half, 60f)
            block(x + 12f, y + 14f, 32f, 32f, 8f, tint)
            block(x + 52f, y + 24f, half * .42f, 12f, 6f, bar)
            index++
        }
    }

    private fun Canvas.news() {
        val search = tiles() + 16f
        card(EDGE, search, full, 44f, 10f)
        block(EDGE + 14f, search + 15f, 14f, 14f, 7f, bar)
        val period = (full - 8f) * .47f
        button(EDGE, search + 52f, period)
        button(EDGE + period + 8f, search + 52f, full - period - 8f)
        var y = search + 106f
        while (y < bottom) {
            card(EDGE, y, full, 232f)
            block(EDGE + 15f, y + 13f, 83f, 24f, 8f, tint)
            block(EDGE + 104f, y + 13f, 64f, 24f, 8f, bar)
            text(EDGE + 15f, y + 52f, full - 30f, .62f, .9f, .78f, .84f)
            y += 248f
        }
    }

    /** Бакалавр: the tiles, the two GPA figures and the day's overview. */
    private fun Canvas.bachelor() {
        val top = tiles() + 12f
        val half = (full - 10f) / 2
        repeat(2) { index ->
            val x = EDGE + index * (half + 10f)
            card(x, top, half, 78f)
            block(x + 15f, top + 15f, 40f, 26f, 8f, bar)
            block(x + 15f, top + 52f, half * .5f, 11f, 5.5f, bar)
        }
        overview(top + 90f, 455f)
        overview(top + 557f, 382f)
    }

    /** Е-услуги: its two desks, the requests and the services list. */
    private fun Canvas.services() {
        val half = (full - 8f) / 2
        repeat(2) { card(EDGE + it * (half + 8f), 126f, half, 48f, 14f); block(EDGE + it * (half + 8f) + 16f, 144f, half * .5f, 12f, 6f, bar) }
        overview(186f, 394f)
        card(EDGE, 604f, full, 301f)
        text(EDGE + 15f, 620f, full - 30f, .3f, height = 11f)
        text(EDGE + 15f, 675f, full - 30f, .86f, .5f, height = 14f, pitch = 24f)
    }

    private fun Canvas.overview(y: Float, tall: Float) {
        card(EDGE, y, full, tall, 14f)
        block(EDGE + 15f, y + 20f, full * .34f, 14f, 7f, bar)
        block(w - EDGE - 15f - 96f, y + 21f, 96f, 11f, 5.5f, bar)
        var row = y + 62f
        while (row + 48f < y + tall && row < bottom) {
            block(EDGE + 15f, row, 44f, 14f, 7f, bar)
            text(EDGE + 71f, row, full - 86f, .7f, .42f, height = 12f, pitch = 22f)
            row += 72f
        }
    }

    /** Новости МОН РК: a date tile beside each article. */
    private fun Canvas.newsList() {
        var y = 146f
        while (y < bottom) {
            card(EDGE, y, full, 306f)
            block(EDGE + 17f, y + 15f, 72f, 72f, 12f, tint)
            block(EDGE + 17f, y + 99f, 56f, 10f, 5f, bar)
            text(117f, y + 17f, full - 122f, .96f, .9f, .94f, .6f, height = 14f, pitch = 24f)
            text(117f, y + 130f, full - 122f, .9f, .94f, .86f, .7f, height = 11f, pitch = 22f)
            button(117f, y + 247f, 189f)
            y += 322f
        }
    }

    private fun Canvas.faq() {
        var y = 138f
        var index = 0
        while (y < bottom) {
            val tall = if (index == 0) 55f else 80f
            fold(y, tall, if (index == 0) 1 else 2)
            y += tall + 22f
            index++
        }
    }

    /** Документы: «Назад / Документы», the search and the folded catalogue. */
    private fun Canvas.catalog() {
        block(EDGE, 140f, 185f, 16f, 8f, bar)
        card(EDGE, 165f, full, 44f, 10f)
        block(EDGE + 14f, 180f, 14f, 14f, 7f, bar)
        var y = 221f
        while (y < bottom) {
            fold(y, 55f)
            block(EDGE + 14f + full * .56f + 10f, y + 18f, 28f, 18f, 9f, tint)
            y += 77f
        }
    }

    private fun Canvas.messages() {
        primary(EDGE, 144f, 102f, 44f, 22f)
        card(EDGE + 110f, 144f, 110f, 44f, 22f)
        block(EDGE + 130f, 160f, 70f, 12f, 6f, bar)
        formCard(210f, 135f)
        card(EDGE, 363f, full, 102f)
        block(EDGE + 13f, 408f, 130f, 12f, 6f, bar)
        heading(489f, .75f)
        formCard(535f, 135f)
        heading(678f, .45f)
        formCard(724f, 328f)
    }

    /** A form card: a label over its field. */
    private fun Canvas.formCard(y: Float, tall: Float) {
        card(EDGE, y, full, tall)
        block(EDGE + 23f, y + 28f, 160f, 12f, 6f, bar)
        card(EDGE + 23f, y + 60f, full * .58f, 44f, 10f)
    }

    /** Калькулятор GPA: one card per subject and the running total over the page. */
    private fun Canvas.calculator() {
        val width = full - 16f
        var y = 150f
        while (y < bottom) {
            card(EDGE + 8f, y, width, 171f)
            block(33f, y + 17f, 80f, 10f, 5f, bar)
            card(33f, y + 39f, width - 26f, 44f, 10f)
            block(33f, y + 96f, 110f, 10f, 5f, bar)
            block(197f, y + 96f, 50f, 10f, 5f, bar)
            card(33f, y + 114f, 152f, 48f, 8f)
            card(197f, y + 118f, width - 190f, 44f, 10f)
            y += 181f
        }
        if (bottom > 400f) {
            card(EDGE + 8f, bottom - 72f, full * .68f, 52f)
            block(EDGE + 24f, bottom - 52f, 64f, 12f, 6f, bar)
            block(EDGE + 96f, bottom - 58f, 52f, 22f, 8f, tint)
        }
    }

    /** Анкетирование: the answer scale and notice, then the surveys. */
    private fun Canvas.surveys() {
        text(EDGE, 148f, full, .9f, .45f, height = 16f, pitch = 28f)
        val inner = w - 60f - 28f
        note(202f, 379f)
        block(EDGE + 15f, 218f, 14f, 14f, 7f, bar)
        card(60f, 219f, inner, 121f)
        block(74f, 233f, 90f, 11f, 5.5f, bar)
        repeat(5) { block(74f + it * 44f, 262f, 36f, 36f, 8f, tint) }
        block(74f, 316f, 90f, 9f, 4.5f, bar)
        block(60f + inner - 14f - 50f, 316f, 50f, 9f, 4.5f, bar)
        block(60f, 365f, inner * .45f, 14f, 7f, bar)
        text(60f, 395f, inner, .92f, .88f, .94f, .9f, .6f, height = 12f, pitch = 22f)
        button(60f, 524f, 189f)
        var y = 597f
        while (y < bottom) {
            card(EDGE, y, full, 290f)
            block(EDGE + 13f, y + 16f, 60f, 10f, 5f, bar)
            text(EDGE + 13f, y + 36f, full - 26f, .8f, .5f, height = 14f, pitch = 22f)
            block(EDGE + 13f, y + 90f, 40f, 10f, 5f, bar)
            block(EDGE + full / 2, y + 90f, 110f, 10f, 5f, bar)
            text(EDGE + 13f, y + 110f, full / 2 - 26f, .9f, .7f, height = 12f, pitch = 22f)
            block(EDGE + full / 2, y + 110f, 80f, 12f, 6f, bar)
            block(EDGE + 13f, y + 176f, 96f, 23f, 11.5f, tint)
            button(112f, y + 227f, 88f)
            y += 306f
        }
    }

    /** A survey: its progress and one card for each subject to rate. */
    private fun Canvas.survey() {
        text(EDGE, 148f, full, .9f, .6f, height = 16f, pitch = 26f)
        card(EDGE, 206f, full, 115f, 14f)
        block(29f, 234f, 50f, 12f, 6f, bar)
        block(w - EDGE - 17f - 40f, 224f, 40f, 30f, 8f, bar)
        block(29f, 268f, full - 34f, 8f, 4f, tint)
        block(29f, 286f, full * .6f, 11f, 5.5f, bar)
        var y = 346f
        while (y < bottom) {
            card(EDGE, y, full, 195f)
            block(25f, y + 28f, full * .6f, 14f, 7f, bar)
            text(25f, y + 76f, 182f, .9f, .6f, height = 12f, pitch = 22f)
            block(w - EDGE - 143f, y + 90f, 77f, 24f, 12f, tint)
            block(25f, y + 158f, 80f, 12f, 6f, bar)
            y += 203f
        }
    }

    /** A survey's questions, each with the answer scale. */
    private fun Canvas.questions() {
        text(EDGE, 148f, full, .9f, .6f, height = 16f, pitch = 26f)
        val box = minOf((full - 34f - 32f) / 5, 56f)
        var y = 206f
        while (y < bottom) {
            card(EDGE, y, full, 168f)
            text(EDGE + 17f, y + 18f, full - 34f, .9f, .6f, height = 14f, pitch = 24f)
            repeat(5) { block(EDGE + 17f + it * (box + 8f), y + 96f, box, 40f, 8f, tint) }
            y += 180f
        }
    }

    /** Редактирование личных данных: the help note, the e-mail, the photos and the upload. */
    private fun Canvas.form() {
        title(.55f)
        note(187f, 54f)
        block(29f, 206f, 16f, 16f, 8f, bar)
        block(52f, 208f, 60f, 12f, 6f, bar)
        card(EDGE + 8f, 265f, full - 16f, 677f)
        val field = full * .78f
        block(41f, 289f, 50f, 11f, 5.5f, bar)
        card(41f, 307f, field, 44f, 10f)
        card(41f, 404f, field, 256f)
        block(60f, 425f, 80f, 12f, 6f, bar)
        block(161f, 425f, 70f, 12f, 6f, bar)
        block(60f, 452f, 84f, 112f, 4f, bar)
        rect.set(161.5f, 452.5f, 244.5f, 563.5f)
        drawRoundRect(rect, 8f, 8f, dashed)
        block(41f, 683f, 150f, 12f, 6f, bar)
        card(41f, 703f, field, 44f, 10f)
        card(49f, 709f, 72f, 32f, 8f)
        var y = 780f
        while (y < minOf(bottom, 900f)) {
            block(41f, y, 70f, 11f, 5.5f, bar)
            card(41f, y + 18f, field, 44f, 10f)
            y += 80f
        }
    }

    /** Практика: the instruction link, then the practice card with its semesters. */
    private fun Canvas.practice() {
        title(.22f)
        card(EDGE + 8f, 195f, full - 16f, 66f)
        block(34f, 212f, 32f, 32f, 8f, tint)
        block(78f, 222f, full * .38f, 13f, 6.5f, bar)
        button(w - EDGE - 22f - 81f, 207f, 81f, 42f)
        card(EDGE, 293f, full, 363f)
        block(w / 2 - full * .15f, 324f, full * .3f, 16f, 8f, bar)
        val half = (full - 34f) / 2
        repeat(2) { card(EDGE + 13f + it * (half + 8f), 361f, half, 52f, 10f); block(EDGE + 30f + it * (half + 8f), 375f, half * .5f, 11f, 5.5f, bar) }
        block(25f, 468f, full * .55f, 16f, 8f, bar)
        block(25f, 514f, full - 26f, 100f, 6f, bar)
        footer(734f)
    }

    /** Мой эдвайзер: the long notice and the advisor's card. */
    private fun Canvas.advisor() {
        title(.6f)
        note(175f, 198f)
        block(29f, 191f, 14f, 14f, 7f, bar)
        text(52f, 191f, full - 56f, .92f, .9f, .88f, .92f, .9f, .84f, .4f, height = 12f, pitch = 24f)
        heading(389f, .3f)
        card(EDGE, 435f, full, 127f, 16f)
        block(29f, 452f, 56f, 56f, 28f, tint)
        block(99f, 462f, 90f, 10f, 5f, bar)
        block(99f, 481f, full * .55f, 14f, 7f, bar)
        drawLine(EDGE + 17f, 522f, w - EDGE - 17f, 522f, line)
        block(29f, 534f, 14f, 14f, 4f, bar)
        block(52f, 535f, 120f, 11f, 5.5f, bar)
        footer(640f)
    }

    private fun Canvas.contracts() {
        title(.25f)
        button(EDGE, 179f, 154f, 48f)
        button(w - EDGE - 154f, 179f, 154f, 48f)
        card(EDGE, 249f, full, 102f)
        block(EDGE + 13f, 294f, 120f, 12f, 6f, bar)
        footer(429f)
    }

    /** Академический календарь: the semester chips and the study facts. */
    private fun Canvas.calendar() {
        title(.6f)
        repeat(8) { index ->
            val x = EDGE + 8f + (index % 3) * 111.5f
            val y = 207f + (index / 3) * 52f
            if (index == 0) card(x, y, 103f, 44f, 10f, tint) else card(x, y, 103f, 44f, 10f)
            block(x + 18f, y + 16f, 67f, 12f, 6f, bar)
        }
        facts(EDGE + 8f, 367f, full - 16f, 1, 1, 2, 1, 1)
        heading(816f, .7f)
    }

    /** Расписание экзаменов: the instruction files, the semesters, the next exam, then the days. */
    private fun Canvas.exams() {
        block(EDGE, 150f, full * .3f, 16f, 8f, bar)
        card(w - EDGE - 182f, 142f, 182f, 38f, 10f)
        block(w - EDGE - 166f, 155f, 120f, 12f, 6f, bar)
        block(EDGE, 212f, full * .55f, 16f, 8f, bar)
        terms(253f)
        card(EDGE, 325f, full, 110f, 16f, tint)
        block(29f, 339f, 120f, 11f, 5.5f, bar)
        block(29f, 366f, full * .45f, 16f, 8f, bar)
        block(29f, 397f, full * .45f, 11f, 5.5f, bar)
        card(w - EDGE - 14f - 66f, 339f, 66f, 82f)
        var y = 455f
        while (y < bottom) {
            card(EDGE, y, 48f, 48f)
            block(72f, y + 17f, 80f, 14f, 7f, bar)
            block(w - EDGE - 90f, y + 19f, 90f, 11f, 5.5f, bar)
            slot(y + 56f)
            slot(y + 167f)
            y += 290f
        }
    }

    private fun Canvas.slot(y: Float) {
        card(EDGE, y, full, 103f, 14f)
        block(EDGE + 17f, y + 18f, 44f, 13f, 6.5f, bar)
        block(85f, y + 16f, 84f, 20f, 10f, tint)
        block(85f, y + 46f, full * .5f, 15f, 7.5f, bar)
        block(85f, y + 76f, full * .62f, 11f, 5.5f, bar)
    }

    /** Учебный план: the facts, the semester count, the toggle and the first semester. */
    private fun Canvas.plan() {
        title(.3f)
        facts(EDGE + 8f, 183f, full - 16f, 1, 1, 1, 2, 1, 1)
        card(EDGE + 8f, 647f, full - 16f, 45f)
        block(34f, 663f, 160f, 12f, 6f, bar)
        block(w - EDGE - 36f, 663f, 14f, 12f, 6f, bar)
        button(EDGE + 8f, 708f, 284f)
        block(EDGE, 800f, full * .25f, 16f, 8f, bar)
        var y = 849f
        while (y < bottom) { card(EDGE, y, full, 120f); y += 132f }
    }

    private fun Canvas.attestation() {
        card(EDGE, 142f, full, 174f)
        text(EDGE + 17f, 159f, full - 34f, .7f, .32f, height = 14f, pitch = 24f)
        val stat = (full - 34f - 16f) / 3
        repeat(3) { block(EDGE + 17f + it * (stat + 8f), 213f, stat, 87f, 10f, tint) }
        terms(328f)
        var y = 396f
        while (y < bottom) {
            card(EDGE, y, full, 167f)
            block(EDGE + 14f, y + 16f, full * .46f, 14f, 7f, bar)
            block(w - EDGE - 50f, y + 12f, 36f, 32f, 8f, tint)
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

    /** Мой IGPA: the year switch, its sections, the period and the figures. */
    private fun Canvas.igpa() {
        block(EDGE, 166f, 107f, 22f, 8f, bar)
        card(w - EDGE - 178f, 162f, 178f, 46f, 10f)
        block(w - EDGE - 168f, 177f, 16f, 16f, 4f, bar)
        block(w - EDGE - 122f, 179f, 66f, 12f, 6f, bar)
        block(w - EDGE - 26f, 177f, 16f, 16f, 4f, bar)
        block(EDGE, 250f, 150f, 12f, 6f, bar)
        block(w - EDGE - 207f, 241f, 207f, 30f, 8f, tint)
        card(EDGE, 289f, full, 52f)
        primary(EDGE + 4f, 293f, 86f, 44f, 10f)
        block(EDGE + 110f, 309f, 104f, 12f, 6f, bar)
        block(EDGE + 236f, 309f, 64f, 12f, 6f, bar)
        block(EDGE, 369f, 84f, 16f, 8f, bar)
        card(EDGE, 396f, full, 48f)
        block(EDGE + full / 2, 400f, full / 2 - 4f, 40f, 10f, tint)
        block(EDGE + full / 4 - 50f, 414f, 100f, 12f, 6f, bar)
        block(EDGE + full * .75f - 50f, 414f, 100f, 12f, 6f, bar)
        val half = (full - 14f) / 2
        repeat(4) { index ->
            val x = EDGE + (index % 2) * (half + 14f)
            val y = 462f + (index / 2) * 123f
            if (index == 0) card(x, y, half, 111f, 8f, tint) else card(x, y, half, 111f, 8f)
            block(x + 13f, y + 16f, half * .55f, 11f, 5.5f, bar)
            block(x + 13f, y + 67f, half * .6f, 18f, 9f, bar)
        }
    }

    /** Тестирование: instruction files, the video, the help tip and the semesters. */
    private fun Canvas.tests() {
        title(.3f)
        repeat(3) {
            val y = 195f + it * 71f
            card(EDGE + 8f, y, full - 16f, 63f)
            block(34f, y + 15f, 32f, 32f, 8f, tint)
            text(78f, y + 14f, full - 90f, .8f, .5f, height = 12f, pitch = 20f)
        }
        block(EDGE + 8f, 407f, full - 16f, 150f, 4f, bar)
        block(w / 2 - 24f, 458f, 48f, 48f, 24f, surface)
        button(EDGE + 8f, 577f, 301f, 46f)
        card(EDGE + 8f, 642f, full - 16f, 94f, 12f, tint)
        text(52f, 658f, full - 64f, .9f, .86f, .4f, height = 12f, pitch = 22f)
        block(EDGE, 754f, full * .7f, 16f, 8f, bar)
        button(EDGE, 785f, 288f, 48f)
        button(w - EDGE - 288f, 841f, 288f, 48f)
    }

    /** Задолженности: the memo, then the two lists. */
    private fun Canvas.debts() {
        title(.3f)
        block(EDGE + 8f, 187f, 100f, 16f, 8f, bar)
        text(EDGE + 8f, 213f, full - 16f, .9f, .92f, .85f, .9f, .6f, height = 16f, pitch = 26f)
        button(EDGE + 8f, 380f, 191f)
        block(EDGE, 456f, full * .75f, 16f, 8f, bar)
        empty(505f, 100f)
        empty(641f, 80f)
        footer(799f)
    }

    /** Повышение GPA: the semester switch, then the two lists. */
    private fun Canvas.raise() {
        title(.4f)
        card(EDGE + 8f, 201f, full - 16f, 70f)
        button(w / 2 - 50f, 214f, 101f)
        empty(307f, 100f)
        empty(443f, 80f)
        footer(601f)
    }

    private fun Canvas.schedule() {
        text(EDGE, 150f, full - 56f, .94f, .86f, .5f, height = 16f, pitch = 27f)
        card(w - EDGE - 44f, 142f, 44f, 44f, 10f)
        terms(233f)
        card(EDGE, 297f, 48f, 48f, 10f)
        card(EDGE + 56f, 299f, full - 112f, 44f, 10f)
        card(w - EDGE - 48f, 297f, 48f, 48f, 10f)
        card(EDGE, 357f, full, 209f)
        block(EDGE + 15f, 375f, full * .3f, 12f, 6f, bar)
        block(EDGE + 15f, 410f, 173f, 26f, 10f, bar)
        block(EDGE + 15f, 449f, full * .8f, 16f, 8f, bar)
        block(EDGE + 15f, 478f, full * .6f, 11f, 5.5f, bar)
        card(EDGE + 15f, 508f, 128f, 44f, 10f)
        card(EDGE, 578f, 191f, 44f, 10f)
        primary(EDGE + 4f, 582f, 90f, 36f, 8f)
        card(EDGE, 634f, full, 181f)
        block(EDGE + 13f, 651f, 178f, 14f, 7f, bar)
        text(EDGE + 13f, 683f, full - 26f, .9f, .7f, .8f, height = 12f, pitch = 24f)
        val cell = (full - 24f) / 7.4f
        var x = EDGE
        repeat(7) { index ->
            val cellWidth = if (index == 0) cell * 1.4f else cell
            card(x, 826f, cellWidth, 56f, 8f)
            block(x + cellWidth / 2 - 11f, 840f, 22f, 12f, 6f, bar)
            x += cellWidth + 4f
        }
        var y = 894f
        while (y < bottom) { card(EDGE, y, full, 120f); y += 132f }
    }

    /** Журнал: the semesters, the course chips, the lesson filter and the summary by subject. */
    private fun Canvas.journal() {
        title(.74f)
        terms(179f)
        var x = EDGE
        listOf(54f, 109f, 107f, 128f, 116f, 164f).forEachIndexed { index, chip ->
            if (x >= w) return@forEachIndexed
            if (index == 0) primary(x, 271f, chip, 44f, 10f) else button(x, 271f, chip)
            x += chip + 6f
        }
        card(EDGE, 356f, full, 44f, 10f)
        block(EDGE + 4f, 360f, full / 4, 36f, 8f, tint)
        card(EDGE, 412f, full, 529f)
        block(EDGE + 15f, 430f, full * .45f, 14f, 7f, bar)
        var y = 467f
        while (y + 51f < 941f && y < bottom) {
            block(EDGE + 15f, y + 19f, 150f, 12f, 6f, bar)
            block(w - EDGE - 157f, y + 8f, 68f, 34f, 8f, tint)
            block(w - EDGE - 83f, y + 8f, 68f, 34f, 8f, tint)
            y += 51f
        }
    }

    /** УМКД: the semesters and one row for each course. */
    private fun Canvas.materials() {
        block(EDGE, 148f, 58f, 16f, 8f, bar)
        terms(179f)
        heading(239f, .4f)
        var y = 292f
        var first = true
        while (y < bottom) {
            val tall = if (first) 80f else 60f
            card(EDGE, y, full, tall)
            block(EDGE + 13f, y + tall / 2 - 18f, 36f, 36f, 8f, tint)
            block(EDGE + 61f, y + 13f, full * .5f, 14f, 7f, bar)
            if (first) block(EDGE + 61f, y + 33f, full * .25f, 14f, 7f, bar)
            block(EDGE + 61f, y + tall - 24f, 8f, 8f, 4f, tint)
            block(EDGE + 75f, y + tall - 26f, 100f, 11f, 5.5f, bar)
            chevron(w - EDGE - 18f, y + tall / 2)
            y += tall + 10f
            first = false
        }
    }

    /** Дистанционное обучение: the instructions, the open tasks and the courses. */
    private fun Canvas.distance() {
        fold(134f, 50f)
        block(EDGE, 222f, full * .7f, 14f, 7f, bar)
        val half = (full - 8f) / 2
        card(EDGE, 262f, half, 52f)
        text(EDGE + 40f, 274f, half * .5f, .6f, 1f, height = 11f, pitch = 18f)
        card(EDGE, 330f, full, 70f, 14f)
        block(EDGE + 15f, 348f, 130f, 14f, 7f, bar)
        block(EDGE + 15f, 372f, 180f, 11f, 5.5f, bar)
        var y = 413f
        while (y < bottom) {
            card(EDGE, y, full, 66f, 14f)
            block(EDGE + 15f, y + 16f, full * .45f, 14f, 7f, bar)
            block(EDGE + 15f, y + 40f, 110f, 11f, 5.5f, bar)
            chevron(w - EDGE - 18f, y + 33f)
            y += 74f
        }
    }

    /** Any other page: a heading and cards. */
    private fun Canvas.list() {
        title(.48f)
        var y = 191f
        while (y < bottom) {
            card(EDGE, y, full, 72f)
            block(EDGE + 14f, y + 18f, 36f, 36f, 8f, tint)
            text(EDGE + 64f, y + 20f, full - 110f, .68f, .36f, height = 12f, pitch = 22f)
            y += 80f
        }
    }

    private fun Canvas.document() {
        card(EDGE, EDGE, full, bottom)
        block(w / 2 - full * .3f, 40f, full * .6f, 16f, 8f, bar)
        block(w / 2 - full * .2f, 68f, full * .4f, 12f, 6f, bar)
        var y = 116f
        var row = 0
        while (y < bottom) {
            text(EDGE + 16f, y, full - 32f, if (row % 5 == 4) .56f else .92f)
            y += if (row % 5 == 4) 44f else 24f
            row++
        }
    }

    /** The page title, a 20px line under the menu. */
    private fun Canvas.title(part: Float) = block(EDGE, 150f, full * part, 16f, 8f, bar)

    /** A 17px heading in its 42px row. */
    private fun Canvas.heading(y: Float, part: Float) = block(EDGE, y + 14f, full * part, 14f, 7f, bar)

    private fun Canvas.chevron(right: Float, centerY: Float) = block(right - 12f, centerY - 4f, 12f, 8f, 4f, bar)

    /** A folded section: one or two lines of its name and the arrow. */
    private fun Canvas.fold(y: Float, tall: Float = 55f, lines: Int = 1) {
        card(EDGE, y, full, tall)
        if (lines == 1) block(EDGE + 14f, y + tall / 2 - 6f, full * .56f, 12f, 6f, bar)
        else text(EDGE + 14f, y + tall / 2 - 17f, full - 70f, .82f, .5f, height = 12f, pitch = 22f)
        chevron(w - EDGE - 16f, y + tall / 2)
    }

    /** The site's empty state: an icon, what is missing and a hint. */
    private fun Canvas.empty(y: Float, tall: Float) {
        card(EDGE, y, full, tall, 14f)
        block(EDGE + 17f, y + 17f, 40f, 40f, 12f, tint)
        block(EDGE + 71f, y + 20f, full * .55f, 14f, 7f, bar)
        if (tall >= 100f) text(EDGE + 71f, y + 46f, full - 88f, .9f, .6f, height = 10f, pitch = 18f)
        else block(EDGE + 71f, y + 46f, full * .3f, 10f, 5f, bar)
    }

    /** A tinted notice card. */
    private fun Canvas.note(y: Float, tall: Float) = card(EDGE, y, full, tall, 12f, tint)

    /** Label and value rows; a row with 2 has a value on two lines. */
    private fun Canvas.facts(x: Float, y: Float, width: Float, vararg rows: Int) {
        card(x, y, width, rows.sumOf { 68 + 22 * (it - 1) }.toFloat())
        var top = y
        rows.forEachIndexed { index, lines ->
            if (top > bottom) return
            block(x + 14f, top + 16f, width * .3f, 10f, 5f, bar)
            repeat(lines) { block(x + 14f, top + 36f + it * 22f, width * (if (it == 0) .5f else .3f), 12f, 6f, bar) }
            top += 68f + 22f * (lines - 1)
            if (index < rows.lastIndex) drawLine(x, top, x + width, top, line)
        }
    }

    /** The «Контакты и информация КазНУ» and «Полезные ссылки» folds and the copyright under short pages. */
    private fun Canvas.footer(y: Float) {
        fold(y)
        fold(y + 64f)
        block(w / 2 - full * .32f, y + 150f, full * .64f, 10f, 5f, bar)
    }

    /** The previous and next semester. */
    private fun Canvas.terms(y: Float) {
        val half = (full - 8f) / 2
        repeat(2) { index ->
            val x = EDGE + index * (half + 8f)
            card(x, y, half, 52f)
            block(if (index == 0) x + 14f else x + half - 28f, y + 20f, 14f, 12f, 6f, bar)
            val start = if (index == 0) x + 40f else x + half - 40f - half * .5f
            text(start, y + 12f, half * .5f, .6f, 1f, height = 11f, pitch = 18f)
        }
    }

    private fun Canvas.button(x: Float, y: Float, width: Float, tall: Float = 44f) {
        card(x, y, width, tall, 10f)
        block(x + 14f, y + tall / 2 - 5f, (width - 28f) * .7f, 10f, 5f, bar)
    }

    private fun Canvas.primary(x: Float, y: Float, width: Float, tall: Float, radius: Float) {
        block(x, y, width, tall, radius, accent)
        block(x + 16f, y + tall / 2 - 5f, (width - 32f) * .7f, 10f, 5f, onAccent)
    }

    /** Lines of text, as fractions of [width]. */
    private fun Canvas.text(x: Float, top: Float, width: Float, vararg lines: Float, height: Float = 12f, pitch: Float = 24f) {
        lines.forEachIndexed { index, part -> block(x, top + index * pitch, width * part, height, height / 2, bar) }
    }

    private fun Canvas.block(x: Float, y: Float, width: Float, height: Float, radius: Float, paint: Paint) {
        rect.set(x, y, x + width, y + height)
        drawRoundRect(rect, radius, radius, paint)
    }

    private fun Canvas.card(x: Float, y: Float, width: Float, height: Float, radius: Float = 12f, fill: Paint = surface) {
        block(x, y, width, height, radius, fill)
        rect.inset(.5f, .5f)
        drawRoundRect(rect, radius, radius, line)
    }

    private companion object {
        /** The page's side margin. */
        const val EDGE = 12f
        /** The narrowest width the drawings are laid out for, in dp. */
        const val NARROWEST = 360f
    }
}
