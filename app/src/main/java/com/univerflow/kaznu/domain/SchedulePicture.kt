package com.univerflow.kaznu.domain

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
    fun weeks(pair: SchedulePair): List<PictureWeek> {
        val read = pair.weeks.map { week ->
            val days = week.lessons.filter { week.monday + it.day >= week.first }.groupBy { it.day }.toSortedMap().map { (day, lessons) ->
                PictureDay(week.monday + day, lessons.sortedWith(compareBy({ it.start }, { it.end }, { it.subject })))
            }
            PictureWeek(week.number, week.labelled, week.first, week.last, days, missing = false)
        }
        val labelled = pair.weeks.all { it.labelled }
        return (read + pair.missing.map { PictureWeek(it, labelled, 0, 0, emptyList(), missing = true) }).sortedBy { it.number }
    }
}
