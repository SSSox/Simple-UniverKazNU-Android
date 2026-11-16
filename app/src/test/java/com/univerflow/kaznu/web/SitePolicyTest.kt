package com.univerflow.kaznu.web

import org.junit.Assert.*
import org.junit.Test
import com.univerflow.kaznu.domain.AppLanguage

class SitePolicyTest {
    @Test fun onlyExplicitUserNavigationCanLaunchAnExternalApplication() {
        val target = "https://example.org/lecture"
        assertTrue(SitePolicy.externalNavigation(target, true, false, "GET"))
        assertFalse(SitePolicy.externalNavigation(target, false, true, "GET"))
        assertFalse(SitePolicy.externalNavigation(target, true, true, "GET"))
        assertFalse(SitePolicy.externalNavigation(target, true, false, "POST"))
        listOf("javascript:alert(1)", "intent://example.org/", "file:///sdcard/a", "https://user@example.org/",
            "http:relative", "https:///missing-host", "https://example.org/\n").forEach {
            assertFalse(it, SitePolicy.externalNavigation(it, true, false, "GET"))
        }
        assertTrue(SitePolicy.external("mailto:help@example.org"))
        assertTrue(SitePolicy.external("tel:+77001234567"))
    }
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
    @Test fun onlyAPlainPageOnANamedHostIsATeachersLink() {
        listOf("https://drive.google.com/file/d/1/view", "http://lib.kaznu.kz/book?id=3", "https://moodle.kaznu.kz/course/view.php?id=7",
            "https://xn--80ak6aa92e.xn--p1ai/").forEach { assertTrue(it, SitePolicy.studyLink(it)) }
        listOf("https://univer.kaznu.kz/student/umkd/", "https://UNIVER.kaznu.kz/x", "mailto:a@example.org", "tel:+7700",
            "https://user@example.org/", "https://192.168.1.10/", "https://0x7f.1/", "https://[::1]/", "https://localhost/",
            "https://example.org./", "ftp://example.org/", "javascript:alert(1)", "https://example.org/\n", null)
            .forEach { assertFalse(it.toString(), SitePolicy.studyLink(it)) }
        val material = "https://univer.kaznu.kz/student/umkd/get/3321866/99281/2026/1"
        assertTrue(SitePolicy.materialLink(material, "https://drive.google.com/file/d/1/view"))
        assertFalse(SitePolicy.materialLink(material, "https://univer.kaznu.kz/student/umkd/"))
        assertFalse(SitePolicy.materialLink("https://univer.kaznu.kz/files/guide.pdf", "https://drive.google.com/x"))
        assertFalse(SitePolicy.materialLink("https://univer.kaznu.kz.evil.test/student/umkd/get/1/2/2026/1", "https://drive.google.com/x"))
    }
    @Test fun onlyTheSuppliedNoticePageOnTheTrustedOriginIsTheReadNotice() {
        listOf("/student/attentionStudents/12", "/student/attentionStudents/12/", "/student/attentionStudents/7?from=menu").forEach {
            assertTrue(it, SitePolicy.readNotice(SitePolicy.ORIGIN + it))
        }
        listOf("/student/attention", "/student/attentionStudents/", "/student/attentionStudents/12/edit", "/student/attentionStudents/x",
            "/student/umkd/").forEach { assertFalse(it, SitePolicy.readNotice(SitePolicy.ORIGIN + it)) }
        assertFalse(SitePolicy.readNotice("https://univer.kaznu.kz.evil.test/student/attentionStudents/12"))
        assertFalse(SitePolicy.readNotice("http://univer.kaznu.kz/student/attentionStudents/12"))
        assertFalse(SitePolicy.readNotice(null))
    }
    @Test fun onlyTheSitesOwnRouteIsTheOnlineTest() {
        assertTrue(SitePolicy.onlineTest("https://univer.kaznu.kz/student/AuthorizationOnOnlineTestForm"))
        assertTrue(SitePolicy.onlineTest("https://univer.kaznu.kz/student/authorizationononlinetestform/"))
        listOf("https://univer.kaznu.kz/student/AuthorizationOnOnlineTestForm/1", "https://univer.kaznu.kz/student/TestList",
            "http://univer.kaznu.kz/student/AuthorizationOnOnlineTestForm", "https://univer.kaznu.kz.evil.test/student/AuthorizationOnOnlineTestForm", null)
            .forEach { assertFalse(it.toString(), SitePolicy.onlineTest(it)) }
    }
    @Test fun onlyTheExactPortalOverHttpsIsTheOnlineTestsExit() {
        listOf("https://online-test.kaznu.kz/Account/LoginFromUniver?token=abc", "https://ONLINE-TEST.kaznu.kz/", "https://online-test.kaznu.kz:443/x")
            .forEach { assertTrue(it, SitePolicy.onlineTestPortal(it)) }
        listOf("http://online-test.kaznu.kz/x", "https://online-test.kaznu.kz:8443/x", "https://online-test.kaznu.kz.evil.test/x",
            "https://evil-online-test.kaznu.kz/x", "https://user@online-test.kaznu.kz/x", "https://online-test.kaznu.kz@evil.test/x",
            "https://online-test.kaznu.kz/x\nSet-Cookie:a", "https://univer.kaznu.kz/student/AuthorizationOnOnlineTestForm", "javascript:alert(1)", null)
            .forEach { assertFalse(it.toString(), SitePolicy.onlineTestPortal(it)) }
    }
    @Test fun onlyTheArmedServerRedirectAfterTheStudentsTapOpensThePortal() {
        val portal = "https://online-test.kaznu.kz/Account/LoginFromUniver?token=abc"
        assertTrue(SitePolicy.onlineTestExit(portal, "GET", redirect = true, armed = true))
        assertFalse(SitePolicy.onlineTestExit(portal, "GET", redirect = false, armed = true))
        assertFalse(SitePolicy.onlineTestExit(portal, "GET", redirect = true, armed = false))
        assertFalse(SitePolicy.onlineTestExit(portal, "POST", redirect = true, armed = true))
        assertFalse(SitePolicy.onlineTestExit("https://moodle.kaznu.kz/login", "GET", redirect = true, armed = true))
        val route = "https://univer.kaznu.kz/student/AuthorizationOnOnlineTestForm"
        assertTrue(SitePolicy.onlineTestTap(route, "GET", userGesture = true, redirect = false))
        assertFalse(SitePolicy.onlineTestTap(route, "GET", userGesture = false, redirect = false))
        assertFalse(SitePolicy.onlineTestTap(route, "GET", userGesture = true, redirect = true))
        assertFalse(SitePolicy.onlineTestTap("https://univer.kaznu.kz/student/TestList", "GET", userGesture = true, redirect = false))
    }
    @Test fun thePortalOpensInTheLinkWindowUnlessLinksGoToTheBrowser() {
        val portal = "https://online-test.kaznu.kz/Account/LoginFromUniver?token=abc"
        assertEquals(LinkOpening.APP, LinkPolicy.open(portal, preferBrowser = false, windowAvailable = true))
        assertEquals(LinkOpening.BROWSER, LinkPolicy.open(portal, preferBrowser = true, windowAvailable = true))
    }
}
