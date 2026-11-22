package com.univerflow.kaznu.today

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.univerflow.kaznu.data.ExamStore
import com.univerflow.kaznu.data.TimetableStore

/** Keeps the widget and the class reminder in step with the saved week, the settings and the phone's clock. */
object TodaySync {
    /** A new week, a reminder setting or the language changed. Reads a file: off the main thread. */
    fun changed(context: Context) {
        TodayWidget.refresh(context)
        ClassReminders.schedule(context)
        ExamReminders.schedule(context)
    }

    /** Sign-out: the week, its alarm and its reminder go, and the widget shows that nothing is saved. */
    fun erase(context: Context) {
        TimetableStore.erase(context)
        ClassReminders.cancel(context)
        ExamStore.erase(context)
        ExamReminders.cancel(context)
        TodayWidget.refresh(context)
    }

    /**
     * A restart drops alarms; a new clock, zone or language changes what the widget says. Manifest-registered for
     * broadcasts Android still delivers that way, and not exported: only the system sends them.
     */
    class TimeEvents : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action in ACTIONS) Background.run(this) { changed(context) }
        }

        private companion object {
            val ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_LOCALE_CHANGED)
        }
    }
}
