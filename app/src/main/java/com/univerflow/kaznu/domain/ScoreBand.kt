package com.univerflow.kaznu.domain

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToLong

/** The four bands a score falls in by its share of the maximum. */
enum class Band { LOW, PASS, GOOD, TOP }

/**
 * Where one score stands: its [band], its share of the maximum in [percent], the marks 50, 70 and 90 in this score's own
 * points ([marks]), the [next] mark still ahead (null past 90) and the points [left] to it (never 0 while short of it).
 * With a goal, [next] is the goal's mark and [reached] says the score is at or above it.
 */
data class ScoreStanding(val band: Band, val percent: Double, val marks: List<Double>, val next: Double?, val left: Double, val reached: Boolean = false)

/**
 * The marks students go by, as a share of a score's maximum: below 50 % is short, 50–69 % admits but may cost the
 * scholarship, 70–89 % is good, 90 % and up excellent. The same rule as `scoreBand` in assets/univer-dom.js, so the
 * report and the page never disagree.
 */
object ScoreBand {
    val MARKS = listOf(50, 70, 90)
    private val NUMBER = Regex("^-?\\d+(?:\\.\\d+)?$")

    /** "63", "12,5" or "12.5" as a number; null for anything else, a dash or an empty grade included. */
    fun number(raw: String?): Double? {
        val text = raw?.trim()?.replace(',', '.') ?: return null
        return if (NUMBER.matches(text)) text.toDouble() else null
    }

    /** The mark a goal stands for: «Удовлетворительно» 50, «Хорошо» 70, «Отлично» 90 (a share of the maximum). */
    fun goalMark(goal: Band?): Int? = when (goal) { Band.PASS -> 50; Band.GOOD -> 70; Band.TOP -> 90; else -> null }

    /** Where [value] of [max] stands; with a [goal] (`PASS`, `GOOD`, `TOP`) [ScoreStanding.next] is the goal's mark. */
    fun of(value: String?, max: String?, goal: Band? = null): ScoreStanding? {
        val points = number(value) ?: return null
        val top = number(max)?.takeIf { it > 0 } ?: return null
        val percent = (points / top * 100).coerceIn(0.0, 100.0)
        val aimed = goalMark(goal)
        val mark = if (aimed != null) aimed.takeIf { percent < it } else MARKS.firstOrNull { percent < it }
        val band = when {
            percent >= 90 -> Band.TOP
            percent >= 70 -> Band.GOOD
            percent >= 50 -> Band.PASS
            else -> Band.LOW
        }
        return ScoreStanding(
            band = band,
            percent = (percent * 10).roundToLong() / 10.0,
            marks = MARKS.map { (it * top).roundToLong() / 100.0 },
            next = mark?.let { (it * top).roundToLong() / 100.0 },
            left = if (mark == null) 0.0 else max(0.1, ceil((mark * top / 100 - points) * 10 - 1e-9) / 10),
            reached = aimed != null && mark == null,
        )
    }

    /** A grade as the site wrote it ("76,667" stays three decimals, like the page), in the language's decimal mark. */
    fun display(raw: String, language: AppLanguage): String {
        val text = raw.trim()
        if (number(text) == null) return raw
        return if (language == AppLanguage.ENGLISH) text.replace(',', '.') else text.replace('.', ',')
    }

    /** "7", "0,5" or "17.5": whole numbers without a fraction, the decimal mark of the language. */
    fun format(value: Double, language: AppLanguage): String {
        val rounded = (value * 100).roundToLong() / 100.0
        val text = if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
        return if (language == AppLanguage.ENGLISH) text else text.replace('.', ',')
    }
}
