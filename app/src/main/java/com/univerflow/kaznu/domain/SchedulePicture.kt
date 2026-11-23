package com.univerflow.kaznu.domain

/** A picture's scale and pixel height; [cut]: the bottom did not fit and is left out. */
data class PictureCanvas(val scale: Float, val pixels: Int, val cut: Boolean)

/** One day of a picture's week: [date] (epoch day) and its classes by start time. */
data class PictureDay(val date: Long, val lessons: List<TimetableLesson>)

/**
 * One column of the picture. [missing]: the week could not be read, so the column says so and shows no classes ([first] and
 * [last] are then 0). [labelled]: the site numbered the week, so the picture may call it odd or even.
 */
data class PictureWeek(val number: Int, val labelled: Boolean, val first: Long, val last: Long, val days: List<PictureDay>, val missing: Boolean) {
    val odd: Boolean get() = number % 2 == 1
}

/**
 * What the picture of a [SchedulePair] shows: the weeks side by side in the order of their numbers, each with the days
 * that have classes. A class on a weekday the site's week does not reach (a week that starts midweek) is left out, as it
 * is on the site; an unread week is a column of its own with nothing in it, never another week's classes.
 */
object SchedulePicture {
    /**
     * The scale and pixel height of a picture [height] layout units tall: twice the units, smaller for a long week but never
     * under half, and never taller than [maxPixels] (what is below that is left out rather than running out of memory;
     * [PictureCanvas.cut] then says so, and the picture shows it).
     */
    fun canvas(height: Float, maxPixels: Float, scale: Float): PictureCanvas {
        val fitted = (maxPixels / height).coerceIn(MIN_SCALE, scale)
        // Cut only when even half scale does not fit: height x fitted can round a hair past the limit.
        return PictureCanvas(fitted, minOf(height * fitted, maxPixels).toInt().coerceAtLeast(1), maxPixels / height < MIN_SCALE)
    }

    private const val MIN_SCALE = 0.5f

    fun weeks(pair: SchedulePair): List<PictureWeek> {
        val read = pair.weeks.map { week ->
            val days = week.lessons.filter { week.monday + it.day in week.first..week.last }.groupBy { it.day }.toSortedMap().map { (day, lessons) ->
                PictureDay(week.monday + day, lessons.sortedWith(compareBy({ it.start }, { it.end }, { it.subject })))
            }
            PictureWeek(week.number, week.labelled, week.first, week.last, days, missing = false)
        }
        val labelled = pair.weeks.all { it.labelled }
        return (read + pair.missing.map { PictureWeek(it, labelled, 0, 0, emptyList(), missing = true) }).sortedBy { it.number }
    }
}
