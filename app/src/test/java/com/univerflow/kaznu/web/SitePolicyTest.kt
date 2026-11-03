package com.univerflow.kaznu.web

import org.junit.Assert.*
import org.junit.Test
import com.univerflow.kaznu.domain.AppLanguage

class SitePolicyTest {
    @Test fun onlyExactFlagRoutesSelectAppLanguage() {
        assertEquals(AppLanguage.KAZAKH, SitePolicy.languageChange(SitePolicy.ORIGIN + "/lang/change/kz/"))
        assertEquals(AppLanguage.RUSSIAN, SitePolicy.languageChange(SitePolicy.ORIGIN + "/lang/change/ru"))
        assertEquals(AppLanguage.ENGLISH, SitePolicy.languageChange(SitePolicy.ORIGIN + "/lang/change/en/"))
        listOf("https://evil.test/lang/change/en/", "http://univer.kaznu.kz/lang/change/en/",
            "${SitePolicy.ORIGIN}/student/transcript/6/", "${SitePolicy.ORIGIN}/lang/change/fr/",
            "${SitePolicy.ORIGIN}/lang/change/en/extra", "${SitePolicy.ORIGIN}/lang/change/%65n/").forEach {
            assertNull(it, SitePolicy.languageChange(it))
        }
    }
    @Test fun printRoutesIncludeLanguagesAndSemestersButExcludeActionsAndDownloads() {
        listOf("/student/iup/0", "/student/iup/1/2/", "/student/transcript/", "/student/transcript/2/",
            "/student/recbook/6/", "/student/interactive/syllabus/print/8843/2").forEach {
            assertTrue(it, SitePolicy.printDocument(SitePolicy.ORIGIN + it))
        }
        listOf("/student/umkd/get/42/99/2026/1", "/student/profile/", "/student/contract/list/",
            "/student/transcript/delete", "/student/interactive/syllabus/list/").forEach {
            assertFalse(it, SitePolicy.printDocument(SitePolicy.ORIGIN + it))
        }
    }
    @Test fun drawerSectionsFollowTheCurrentStudentRoute() {
        assertEquals(AppSection.SCHEDULE, SitePolicy.section(SitePolicy.ORIGIN + "/student/myschedule/2026/1"))
        assertEquals(AppSection.JOURNAL, SitePolicy.section(SitePolicy.ORIGIN + "/student/attendance/full/2026/1"))
        assertEquals(AppSection.MATERIALS, SitePolicy.section(SitePolicy.ORIGIN + "/student/umkd/42/2026/1"))
        assertEquals(AppSection.ATTESTATION, SitePolicy.section(SitePolicy.ORIGIN + "/student/attestation/2025/2"))
        assertEquals(AppSection.DISTANCE, SitePolicy.section(SitePolicy.ORIGIN + "/student/discussion/task/list/536582/101966/2026/1/9/"))
        assertEquals(AppSection.HOME, SitePolicy.section(SitePolicy.ORIGIN + "/student/attestations/"))
        assertEquals("https://univer.kaznu.kz/student/discussion/index/", AppSection.DISTANCE.url)
        assertEquals(AppSection.HOME, SitePolicy.section(SitePolicy.ORIGIN + "/news/student/2"))
        assertEquals(AppSection.HOME, SitePolicy.section(SitePolicy.ORIGIN + "/student/myschedulex/"))
        assertNull(SitePolicy.section("https://univer.kaznu.kz.evil.test/student/myschedule/"))
        assertEquals("https://univer.kaznu.kz/student/attendance/full/", AppSection.JOURNAL.url)
    }
    @Test fun documentRoutingRejectsOffOriginAndUnsafeProtocols() {
        listOf("http://univer.kaznu.kz/student/transcript/", "https://univer.kaznu.kz.evil.test/student/transcript/",
            "https://user@univer.kaznu.kz/student/transcript/", "https://univer.kaznu.kz:8443/student/transcript/",
            "javascript:alert(1)").forEach { assertFalse(it, SitePolicy.printDocument(it)) }
    }
    @Test fun windowsRoutesForNewsSchedulePrintAndGuides() {
        assertEquals(SitePolicy.ORIGIN + "/news/student", SitePolicy.newsShortcut(SitePolicy.ORIGIN + "/"))
        assertEquals(SitePolicy.ORIGIN + "/news/student/3", SitePolicy.newsShortcut(SitePolicy.ORIGIN + "/my/news/3"))
        assertEquals(SitePolicy.ORIGIN + "/news/student", SitePolicy.newsShortcut(SitePolicy.ORIGIN + "/my/news/1"))
        assertNull(SitePolicy.newsShortcut(SitePolicy.ORIGIN + "/?lang=kz"))
        assertNull(SitePolicy.newsShortcut(SitePolicy.ORIGIN + "/student/bachelor/"))
        assertNull(SitePolicy.newsShortcut("https://univer.kaznu.kz.evil.test/"))
        assertTrue(SitePolicy.printDocument(SitePolicy.ORIGIN + "/student/myschedule/print/2026/1"))
        assertTrue(SitePolicy.documentDownload(SitePolicy.ORIGIN + "/files/guide/Student_Guide.PDF"))
        assertTrue(SitePolicy.documentDownload(SitePolicy.ORIGIN + "/student/umkd/get/1/2/2026/1"))
        assertFalse(SitePolicy.documentDownload(SitePolicy.ORIGIN + "/student/umkd/"))
        assertFalse(SitePolicy.documentDownload("https://example.org/guide.pdf"))
    }
    @Test fun onlySectionPagesAreReopenedAfterAnAutomaticLogin() {
        listOf("/student/umkd/", "/student/myschedule/2026/1", "/student/attendance/full/", "/ssc/UserServiceList", "/news/student/3").forEach {
            assertTrue(it, SitePolicy.resumable(SitePolicy.ORIGIN + it))
        }
        listOf("/", "/my/news/2", "/user/login", "/user/logout", "/user/loginMoodle", "/lang/change/kz/",
            "/student/attentionStudents/12", "/student/attention", "/student/umkd/get/1/2/2026/1", "/student/transcript/",
            "/files/guide.pdf").forEach { assertFalse(it, SitePolicy.resumable(SitePolicy.ORIGIN + it)) }
        assertFalse(SitePolicy.resumable("https://evil.test/student/umkd/"))
        assertTrue(SitePolicy.loginPage(SitePolicy.ORIGIN + "/user/login?ReturnUrl=%2F"))
        assertFalse("Moodle's entry is not the session-ended form", SitePolicy.loginPage(SitePolicy.ORIGIN + "/user/loginMoodle"))
    }
}
