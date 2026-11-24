package com.univerflow.kaznu.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
