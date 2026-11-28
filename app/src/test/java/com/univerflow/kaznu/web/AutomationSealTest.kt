package com.univerflow.kaznu.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** The saved account goes only to the automation registered at document start with this WebView's seal. */
class AutomationSealTest {
    private val seal = "0123456789abcdef0123456789abcdef"

    @Test
    fun theSealReplacesThePlaceholderOnly() {
        assertEquals("var SEAL = '$seal';", AutomationController.sealed("var SEAL = '__UNIVER_AUTOMATION_SEAL__';", seal))
    }

    @Test
    fun theCredentialCallChecksTheSealBeforeItCallsAnything() {
        val script = AutomationController.onSite(seal, "login(\"t\",\"student\",\"password\")")
        val check = script.indexOf("a.seal!=='$seal'")
        assertTrue(check > 0)
        assertTrue("the origin is checked first", script.indexOf("location.origin!==\"https://univer.kaznu.kz\"") in 0 until check)
        assertTrue("the call goes to the compared object, after the check", script.indexOf("return a.login(") > check)
        assertEquals("the automation is read once", 1, script.split("window.__univerAutomationV1").size - 1)
        assertTrue(script.contains("return '${AutomationController.UNPROTECTED}'"))
    }

    @Test
    fun theLibraryKeepsItsPlaceholderForTheLateCopy() {
        // The copy evaluated late (no document-start scripts) must stay unsealed, so it refuses credentials.
        val asset = java.io.File("src/main/assets/univer-automation.js").readText()
        assertTrue(asset.contains("'__UNIVER_AUTOMATION_SEAL__'"))
        assertFalse(Regex("[0-9a-f]{32}").containsMatchIn(asset))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aSealThatCouldBreakOutOfItsQuotesIsRefused() {
        AutomationController.onSite("x');alert(1);('", "probe()")
    }

    @Test(expected = IllegalArgumentException::class)
    fun aLibraryWithoutThePlaceholderIsRefused() {
        AutomationController.sealed("window.__univerAutomationV1 = {};", seal)
    }

    @Test
    fun automationLibraryIsReadOnceAndSharedAcrossInstances() {
        val source = AutomationController.SharedSource()
        val reads = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = pool.invokeAll((1..8).map {
                Callable {
                    source.get {
                        reads.incrementAndGet()
                        "var SEAL = '__UNIVER_AUTOMATION_SEAL__';" + "x".repeat(1024)
                    }
                }
            }).map { it.get() }
            assertEquals("the asset is read once even under concurrent controllers", 1, reads.get())
            results.forEach { assertSame("every controller shares the same String copy", results.first(), it) }
            val otherSeal = "fedcba9876543210fedcba9876543210"
            assertTrue(AutomationController.sealed(results.first(), seal).contains(seal))
            assertTrue(AutomationController.sealed(results.first(), otherSeal).contains(otherSeal))
            assertTrue("sealing does not alter the shared template", results.first().contains("'__UNIVER_AUTOMATION_SEAL__'"))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun theCookieMarkIsTheScriptsFnv1a() {
        // tests/dom/automation-reauth-handoff.test.cjs pins the same vector for the script's own mark.
        assertEquals("f9776d75", AutomationController.cookieMark(seal, "PHPSESSID", "abc"))
    }

    @Test
    fun theHandoffCarriesTheDeadlineAndSaltedMarksButNoCookieValue() {
        val salt = "fedcba9876543210fedcba9876543210"
        val handoff = AutomationController.handoff("PHPSESSID=s3cret-session; lang=ru; flag", salt, 1_234_567L)
        assertTrue(handoff.startsWith("{deadline:1234567,salt:\"$salt\",session:["))
        assertTrue(handoff.contains("[\"PHPSESSID\",\"${AutomationController.cookieMark(salt, "PHPSESSID", "s3cret-session")}\"]"))
        assertTrue(handoff.contains("[\"flag\",\"${AutomationController.cookieMark(salt, "flag", "")}\"]"))
        assertFalse(handoff.contains("s3cret-session"))
        assertEquals("{deadline:5,salt:\"$salt\",session:[]}", AutomationController.handoff(null, salt, 5))
    }

    @Test
    fun theSubmitWindowEndsBeforeTheNativeWaitForTheAnswer() {
        assertTrue(AutomationController.SUBMIT_WINDOW in 1 until 3_000)
    }
}
