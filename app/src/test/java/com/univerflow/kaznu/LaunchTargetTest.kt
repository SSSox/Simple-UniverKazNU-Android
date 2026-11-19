package com.univerflow.kaznu

import com.univerflow.kaznu.launch.LaunchTarget
import com.univerflow.kaznu.web.AppSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LaunchTargetTest {
    @Test fun onlyTheAppsOwnRequestWithAKnownNameOpensASection() {
        assertEquals(LaunchTarget.SCHEDULE, LaunchTarget.parse(LaunchTarget.ACTION, "SCHEDULE"))
        assertEquals(AppSection.JOURNAL, LaunchTarget.parse(LaunchTarget.ACTION, "JOURNAL")!!.section)
        assertNull("Downloads is a screen of the app, not a page", LaunchTarget.parse(LaunchTarget.ACTION, "DOWNLOADS")!!.section)
        // The launcher's own start, another action, a missing or unknown name: the usual start page.
        assertNull(LaunchTarget.parse("android.intent.action.MAIN", "SCHEDULE"))
        assertNull(LaunchTarget.parse(LaunchTarget.ACTION, null))
        assertNull(LaunchTarget.parse(LaunchTarget.ACTION, "schedule"))
        assertNull(LaunchTarget.parse(LaunchTarget.ACTION, "HOME"))
        // An address never stands in for a name: another app cannot send the main screen somewhere.
        assertNull(LaunchTarget.parse(LaunchTarget.ACTION, "https://univer.kaznu.kz/student/myschedule/"))
        assertNull(LaunchTarget.parse(LaunchTarget.ACTION, "https://example.com/"))
    }
}
