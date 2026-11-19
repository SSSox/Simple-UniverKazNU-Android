package com.univerflow.kaznu.domain

/** «Напоминать о парах»: off until the student turns it on; [lead] minutes before a class, one of [LEADS]. */
data class ReminderSettings(val enabled: Boolean = false, val lead: Int = DEFAULT_LEAD) {
    companion object {
        const val DEFAULT_LEAD = 10
        val LEADS = listOf(5, 10, 15, 30)

        fun lead(value: Int) = value.takeIf { it in LEADS } ?: DEFAULT_LEAD
    }
}
