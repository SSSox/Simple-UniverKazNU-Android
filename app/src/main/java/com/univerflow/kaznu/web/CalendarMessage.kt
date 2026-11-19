package com.univerflow.kaznu.web

import com.univerflow.kaznu.domain.CalendarEvent

/** «В календарь» on an exam or a class: JSON after [CalendarEvent.MESSAGE_PREFIX], checked by [CalendarEvent.of]. */
object CalendarMessage {
    fun parse(message: String?, now: Long = System.currentTimeMillis()): CalendarEvent? =
        PageJson.map(message, CalendarEvent.MESSAGE_PREFIX, CalendarEvent.MAX_LENGTH, maxDepth = 1)?.let { CalendarEvent.of(it, now) }
}
