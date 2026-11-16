package com.univerflow.kaznu.web

import org.junit.Assert.*
import org.junit.Test

class SingleSignOnPolicyTest {
    @Test fun onlyObservedEntriesOpenServiceViewer() {
        assertTrue(SingleSignOnPolicy.entry(SingleSignOnPolicy.ENTRY))
        assertTrue(SingleSignOnPolicy.entry("https://univer.kaznu.kz:443/user/loginmoodle/?ReturnUrl=%2F"))
        assertTrue(SingleSignOnPolicy.opensViewer("https://dl.kaznu.kz/my/"))
        assertTrue(SingleSignOnPolicy.opensViewer("https://dl.kaznu.edu.kz/course/view.php?id=42"))
        listOf("https://univer.kaznu.kz/user/login", "https://univer.kaznu.kz/user/loginTeams",
            "https://univer.kaznu.kz/user/loginMoodle/extra", "https://other.kaznu.kz/").forEach {
            assertFalse(it, SingleSignOnPolicy.opensViewer(it))
        }
    }

    @Test fun handOffCannotExpandTrustToOtherHostsOrUnsafeUrls() {
        listOf(null, "http://dl.kaznu.kz/", "https://dl.kaznu.kz.evil.test/", "https://evil.test/user/loginMoodle",
            "https://dl.kaznu.kz:8443/", "https://user@dl.kaznu.edu.kz/", "https://dl.kaznu.kz@evil.test/",
            "javascript:alert(1)", "https://dl.kaznu.kz/%zz", "https://dl.kaznu.edu.kz\n.evil.test/").forEach {
            assertFalse(it, SingleSignOnPolicy.allowed(it))
        }
        assertTrue(SingleSignOnPolicy.allowed("https://univer.kaznu.kz/user/login?ReturnUrl=/"))
        assertTrue(SingleSignOnPolicy.allowed("https://DL.KAZNU.EDU.KZ:443/my/"))
        assertFalse(SitePolicy.trusted("https://dl.kaznu.edu.kz/"))
    }
    @Test fun onlyMoodleOwnSignInPageMeansItsSessionEnded() {
        assertTrue(SingleSignOnPolicy.moodleSignIn("https://dl.kaznu.edu.kz/login/index.php"))
        assertTrue(SingleSignOnPolicy.moodleSignIn("https://dl.kaznu.kz/login/?errorcode=4"))
        listOf("https://dl.kaznu.edu.kz/my/", "https://dl.kaznu.edu.kz/login/logout.php?sesskey=x",
            "https://univer.kaznu.kz/login/index.php", "https://evil.test/login/index.php",
            "https://dl.kaznu.edu.kz/course/login/index.php").forEach { assertFalse(it, SingleSignOnPolicy.moodleSignIn(it)) }
    }
    @Test fun moodlePagesToReturnToExcludeSessionStepsAndKeys() {
        listOf("https://dl.kaznu.edu.kz/mod/assign/view.php?id=42&action=editsubmission",
            "https://dl.kaznu.edu.kz/course/view.php?id=7#section-2", "https://dl.kaznu.kz/my/").forEach {
            assertTrue(it, SingleSignOnPolicy.moodleResumable(it))
        }
        listOf("https://dl.kaznu.edu.kz/login/index.php", "https://dl.kaznu.edu.kz/login/logout.php?sesskey=abc",
            "https://dl.kaznu.edu.kz/auth/univer/login.php?token=abc", "https://dl.kaznu.edu.kz/mod/assign/view.php?id=1&sesskey=x",
            "https://dl.kaznu.edu.kz/admin/", "https://univer.kaznu.kz/student/umkd/", "https://evil.test/my/").forEach {
            assertFalse(it, SingleSignOnPolicy.moodleResumable(it))
        }
        assertTrue(SingleSignOnPolicy.moodleLogout("https://dl.kaznu.edu.kz/login/logout.php?sesskey=abc"))
        assertFalse(SingleSignOnPolicy.moodleLogout("https://univer.kaznu.kz/login/logout.php"))
    }
    @Test fun moodleFilesStayWithinMoodleAndUniverFilesWithinUniver() {
        val file = "https://dl.kaznu.edu.kz/pluginfile.php/12/mod_assign/intro/task.pdf?forcedownload=1"
        assertTrue(SingleSignOnPolicy.downloadAllowed(file))
        assertTrue(SingleSignOnPolicy.sameService(file, "https://dl.kaznu.kz/pluginfile.php/12/x.pdf"))
        assertFalse(SingleSignOnPolicy.sameService(file, "https://univer.kaznu.kz/student/umkd/"))
        assertFalse(SingleSignOnPolicy.downloadAllowed("https://evil.test/pluginfile.php/1/a.pdf"))
    }
}
