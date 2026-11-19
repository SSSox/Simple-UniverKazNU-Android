package com.univerflow.kaznu.web

import com.univerflow.kaznu.domain.Timetable

/** The schedule page's week (and the one Бакалавр reads): JSON after [Timetable.MESSAGE_PREFIX], checked by [Timetable.of]. */
object TimetableMessage {
    fun parse(message: String?, now: Long = System.currentTimeMillis()): Timetable? =
        PageJson.map(message, Timetable.MESSAGE_PREFIX, Timetable.MAX_LENGTH, maxDepth = 3)?.let { Timetable.of(it, now) }
}
