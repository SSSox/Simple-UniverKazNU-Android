package com.univerflow.kaznu.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadOfferTest {
    private val file = "https://files.example.org/lecture.pdf"
    private val started = mutableListOf<String>()
    private val offer = DownloadOffer { started += it }

    @Test fun aDownloadStartsNothingUntilTheUserTapsTheOffer() {
        // The page answered an automatic navigation as an attachment: only the bar is shown.
        val token = offer.offer(file)
        assertNotNull(token)
        assertTrue(started.isEmpty())
        assertEquals(file, offer.pending)
        offer.accept(token!!)
        assertEquals(listOf(file), started)
        // One tap opens it once.
        offer.accept(token)
        assertEquals(listOf(file), started)
        assertNull(offer.pending)
    }

    @Test fun aDismissedOrReplacedOfferNeverOpens() {
        val first = offer.offer(file)!!
        offer.dismiss(first)
        offer.accept(first)
        assertTrue(started.isEmpty())
        // A newer file replaces the bar; the old bar going away leaves the new offer, even for the same address.
        val again = offer.offer(file)!!
        val newer = offer.offer(file)!!
        offer.dismiss(again)
        offer.accept(again)
        assertTrue(started.isEmpty())
        offer.accept(newer)
        assertEquals(listOf(file), started)
    }

    @Test fun onlyHttpsFilesOfOtherSitesMayBeOffered() {
        listOf("http://files.example.org/a.pdf", "mailto:dean@kaznu.kz", "tel:+7700", "intent://x#Intent;end", "file:///sdcard/a.pdf",
            "data:application/pdf;base64,AA", "blob:https://example.org/1", "https://user@example.org/a.pdf",
            "https://univer.kaznu.kz/user/logout", "https://univer.kaznu.kz/student/umkd/get/1/2/3/4",
            "https://dl.kaznu.kz/login/logout.php?sesskey=x", null)
            .forEach {
                assertNull(it.toString(), offer.offer(it))
                assertFalse(it.toString(), LinkPolicy.download(it))
            }
        assertNull(offer.pending)
        assertTrue(started.isEmpty())
        assertTrue(LinkPolicy.download("https://drive.google.com/uc?id=1&export=download"))
    }

    @Test fun percentEncodedOrUnicodeDotSpellingsOfUniverAndMoodleAreNeverOffered() {
        listOf("univer.kaznu.kz", "dl.kaznu.kz", "dl.kaznu.edu.kz").forEach { host ->
            val spellings = listOf(
                host.replace(".", "%2E"), host.replace(".", "%2e"), host.replaceFirst(".", "%2E"),
                host.replaceFirst("u", "%75"), host.replaceFirst("k", "%6B"),
                host.replace(".", "。"), host.replace(".", "．"), host.replace(".", "｡"),
                host.replaceFirst(".", "。"), "www." + host.replace(".", "。"),
                host.replace(".", "。") + "。", host.replace(".", "%2E") + "%2E",
            )
            spellings.forEach { spelled ->
                listOf("https://$spelled/a.pdf", "https://$spelled:443/a.pdf", "https://user@$spelled/a.pdf").forEach {
                    assertFalse(it, LinkPolicy.download(it))
                }
            }
        }
    }

    @Test fun univerAndMoodleOnAnotherPortOrWithATrailingDotAreNeverOffered() {
        listOf("https://univer.kaznu.kz:8443/user/logout", "https://univer.kaznu.kz./user/logout",
            "https://UNIVER.KAZNU.KZ.:444/user/logout", "https://univer.kaznu.kz:443./student/umkd/get/1/2/3/4",
            "https://dl.kaznu.kz:8080/login/logout.php?sesskey=x", "https://dl.kaznu.kz./login/logout.php?sesskey=x",
            "https://dl.kaznu.edu.kz:9443/login/logout.php", "https://Dl.Kaznu.Edu.Kz./login/logout.php")
            .forEach {
                assertNull(it, offer.offer(it))
                assertFalse(it, LinkPolicy.download(it))
            }
        assertNull(offer.pending)
        assertTrue(started.isEmpty())
        // Trusted navigation keeps its exact-origin rule: these variants are not the signed-in site.
        assertFalse(SitePolicy.trusted("https://univer.kaznu.kz:8443/student/myschedule/"))
        assertFalse(SitePolicy.trusted("https://univer.kaznu.kz./student/myschedule/"))
        assertFalse(SingleSignOnPolicy.moodle("https://dl.kaznu.kz:8080/course/view.php?id=7"))
        // Another site whose name only ends like Univer's is still offered.
        assertTrue(LinkPolicy.download("https://notuniver.kaznu.kz.example.org/a.pdf"))
        assertTrue(LinkPolicy.download("https://files.example.org:8443/a.pdf"))
    }

    @Test fun accountRoutesStayRefusedUnderRedirectSpellings() {
        // The same endpoints LinkPolicy refuses to hand back, written the way a redirect or another spelling arrives.
        listOf(
            "https://univer.kaznu.kz/user/logout", "https://univer.kaznu.kz/user/logout/", "https://UNIVER.KAZNU.KZ/User/Logout",
            "https://univer.kaznu.kz/user/./logout", "https://univer.kaznu.kz/%75ser/logout", "https://univer.kaznu.kz:8443/user/logout",
            "https://univer.kaznu.kz./user/logout", "https://univer.kaznu.kz.:444/user/logout", "https://univer.kaznu.kz:443./user/logout",
            "https://www.univer.kaznu.kz/user/logout", "https://www.univer.kaznu.kz.:8443/user/logout",
            "https://www.univer.kaznu.kz/user/login?ReturnUrl=%2F", "https://univer.kaznu.kz/user/loginMoodle",
            "https://univer.kaznu.kz/lang/change/en/", "https://univer.kaznu.kz:8080/LANG/CHANGE/KZ/",
            "https://www.univer.kaznu.kz./lang/change/en", "https://univer.kaznu.kz/student/attentionStudents/12",
            "https://univer.kaznu.kz/student/attentionStudents/12/", "https://WWW.Univer.Kaznu.Kz:9443/student/attention",
            "https://univer.kaznu.kz/", "https://univer.kaznu.kz:8443/", "https://files.univer.kaznu.kz/user/logout",
            "https://dl.kaznu.kz/login/logout.php?sesskey=x", "https://dl.kaznu.edu.kz/login/logout.php?sesskey=x",
            "https://DL.KAZNU.EDU.KZ:8080/LOGIN/LOGOUT.PHP", "https://dl.kaznu.kz./login/index.php",
            "https://www.dl.kaznu.kz/login/logout.php?sesskey=x", "https://www.dl.kaznu.edu.kz./auth/univer/login.php?token=abc",
            "https://dl.kaznu.kz/course/view.php?id=7&sesskey=abc", "https://dl.kaznu.edu.kz:9443/mod/assign/view.php?id=1&sesskey=x",
            "https://a.b.dl.kaznu.edu.kz/admin/", "https://dl.kaznu.edu.kz/login/logout.php/",
        ).forEach {
            assertNull(it, offer.offer(it))
            assertFalse(it, LinkPolicy.download(it))
        }
        assertNull(offer.pending)
        assertTrue(started.isEmpty())
        // A different site that only looks similar, including one label too many, is still a file of another site.
        listOf("https://notuniver.kaznu.kz.example.org/user/logout", "https://univer.kaznu.kz.evil.test/user/logout",
            "https://www.univer.kaznu.kz.evil.test/a.pdf", "https://notdl.kaznu.edu.kz/login/logout.php",
            "https://www.example.org/user/logout", "https://moodle.kaznu.kz/login/logout.php",
            "https://files.example.org:8443/a.pdf").forEach { assertTrue(it, LinkPolicy.download(it)) }
    }
}
