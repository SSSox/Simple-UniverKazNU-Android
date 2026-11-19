package com.univerflow.kaznu.launch

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.CalendarContract
import com.univerflow.kaznu.domain.CalendarEvent

/**
 * Hands an event to the phone's calendar app, which shows it for the student to save: the app needs no calendar
 * permission and writes nothing itself.
 */
object CalendarHandoff {
    fun intent(event: CalendarEvent): Intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.start)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end)
        .putExtra(CalendarContract.Events.TITLE, event.title)
        .apply {
            if (event.place.isNotEmpty()) putExtra(CalendarContract.Events.EVENT_LOCATION, event.place)
            if (event.notes.isNotEmpty()) putExtra(CalendarContract.Events.DESCRIPTION, event.notes)
            event.rule?.let { putExtra(CalendarContract.Events.RRULE, it) }
        }

    /** False when the phone has no calendar app. */
    fun open(activity: Activity, event: CalendarEvent): Boolean =
        try { activity.startActivity(intent(event)); true } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }
}
