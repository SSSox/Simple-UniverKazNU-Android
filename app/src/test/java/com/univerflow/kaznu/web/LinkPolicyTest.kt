package com.univerflow.kaznu.web

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkPolicyTest {
    private val drive = "https://drive.google.com/file/d/1/view"

    @Test fun aTeachersHttpsPageOpensInTheAppUnlessTheUserChoseTheBrowser() {
        assertEquals(LinkOpening.APP, LinkPolicy.open(drive, preferBrowser = false, windowAvailable = true))
        assertEquals(LinkOpening.BROWSER, LinkPolicy.open(drive, preferBrowser = true, windowAvailable = true))
        // An old WebView has no separate profile: the browser, never the profile Univer signs in with.
        assertEquals(LinkOpening.BROWSER, LinkPolicy.open(drive, preferBrowser = false, windowAvailable = false))
    }

    @Test fun mailPhonePlainHttpAndBareAddressesGoToTheSystemAndOtherSchemesNowhere() {
        listOf("http://lib.kaznu.kz/book", "mailto:dean@kaznu.kz", "tel:+77272211000", "https://192.168.1.10/", "https://localhost/")
            .forEach { assertEquals(it, LinkOpening.BROWSER, LinkPolicy.open(it, false, true)) }
        listOf("intent://scan/#Intent;scheme=zxing;end", "file:///sdcard/a.pdf", "javascript:alert(1)", "https://user@example.org/",
            "https://univer.kaznu.kz/student/umkd/", null)
            .forEach { assertEquals(it.toString(), LinkOpening.BLOCKED, LinkPolicy.open(it, false, true)) }
    }

    @Test fun pagesInTheWindowStayAndOnlyATapReachesTheSystem() {
        assertEquals(LinkStep.STAY, LinkPolicy.step("https://docs.google.com/document/d/1", gesture = false, redirect = true))
        assertEquals(LinkStep.STAY, LinkPolicy.step("https://192.168.1.10/intranet", gesture = true, redirect = false))
        assertEquals(LinkStep.SYSTEM, LinkPolicy.step("mailto:dean@kaznu.kz", gesture = true, redirect = false))
        assertEquals(LinkStep.SYSTEM, LinkPolicy.step("http://lib.kaznu.kz/book", gesture = true, redirect = false))
        assertEquals(LinkStep.BLOCK, LinkPolicy.step("http://lib.kaznu.kz/book", gesture = false, redirect = true))
        assertEquals(LinkStep.BLOCK, LinkPolicy.step("tel:+7700", gesture = false, redirect = false))
        listOf("intent://x#Intent;end", "market://details?id=x", "file:///sdcard/a", "data:text/html,x", "about:blank", "javascript:x", null)
            .forEach { assertEquals(it.toString(), LinkStep.BLOCK, LinkPolicy.step(it, gesture = true, redirect = false)) }
    }

    @Test fun anotherSiteCannotDriveTheSignedInSession() {
        val section = "https://univer.kaznu.kz/student/myschedule/"
        assertEquals(LinkStep.MAIN, LinkPolicy.step(section, gesture = true, redirect = false))
        assertEquals(LinkStep.MAIN, LinkPolicy.step("https://univer.kaznu.kz/student/umkd/get/3321866/99281/2026/1", true, false))
        assertEquals(LinkStep.MAIN, LinkPolicy.step("https://dl.kaznu.kz/course/view.php?id=7", true, false))
        // A redirect or a script never hands an address over, whatever it is.
        assertEquals(LinkStep.BLOCK, LinkPolicy.step(section, gesture = false, redirect = false))
        assertEquals(LinkStep.BLOCK, LinkPolicy.step(section, gesture = true, redirect = true))
        // Endpoints that change the account are refused even on a tap.
        listOf("https://univer.kaznu.kz/user/logout", "https://univer.kaznu.kz/lang/change/en/", "https://univer.kaznu.kz/user/login",
            "https://univer.kaznu.kz/student/attentionStudents/12", "https://univer.kaznu.kz/", "https://dl.kaznu.kz/login/logout.php?sesskey=x",
            "https://dl.kaznu.kz/course/view.php?id=7&sesskey=abc")
            .forEach { assertEquals(it, LinkStep.BLOCK, LinkPolicy.step(it, gesture = true, redirect = false)) }
    }
}
