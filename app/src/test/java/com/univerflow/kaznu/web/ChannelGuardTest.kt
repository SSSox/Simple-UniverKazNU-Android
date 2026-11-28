package com.univerflow.kaznu.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelGuardTest {
    private val secret = "0123456789abcdef0123456789abcdef"

    @Test
    fun keyedChannelDropsWhatTheSiteSendsItself() {
        assertEquals("univer-pin:{}", ChannelGuard.unwrap(secret + "univer-pin:{}", secret, active = true))
        assertNull(ChannelGuard.unwrap("univer-pin:{\"pin\":true,\"url\":\"https://example.com/\"}", secret, active = true))
        assertNull(ChannelGuard.unwrap("fedcba9876543210fedcba9876543210univer-pin:{}", secret, active = true))
        assertNull(ChannelGuard.unwrap(null, secret, active = true))
    }

    @Test
    fun withoutDocumentStartScriptsTheOldUnkeyedChannelStays() {
        assertEquals("univer-pin:{}", ChannelGuard.unwrap("univer-pin:{}", secret, active = false))
        assertFalse(ChannelGuard.senders(secret, active = false).contains(secret))
        // The modules' sender lets no tap command through an unkeyed channel (MobilePageAdapter's NATIVE).
        assertTrue(ChannelGuard.senders(secret, active = false).contains("keyed:false"))
    }

    @Test
    fun anUnkeyedChannelRefusesEveryTapAndStillTakesReports() {
        listOf("univer-pin:{}", "univer-download-all:{}", "univer-report:{}", "univer-calendar:{}", "univer-export:{}").forEach {
            assertFalse(it, ChannelGuard.accepts(it, actions = false))
        }
        listOf("univer-schedule:{}", "univer-grades:{}", "univer-exams:{}").forEach {
            assertTrue(it, ChannelGuard.accepts(it, actions = false))
        }
        assertTrue(ChannelGuard.accepts("univer-calendar:{}", actions = true))
        assertFalse(ChannelGuard.accepts("univer-reports:{}", actions = true))
        assertEquals(ChannelGuard.Kind.CALENDAR, ChannelGuard.kind("univer-calendar:{\"pin\":true,\"url\":\"https://evil.example/\"}"))
        assertNull(ShortcutBridge.parse("univer-calendar:{\"pin\":true,\"url\":\"https://evil.example/\"}"))
        assertNull(CalendarMessage.parse("univer-calendar:{\"pin\":true,\"url\":\"https://evil.example/\"}", now = 0L))
    }

    @Test
    fun secretsAreFreshHexAndGoOnlyIntoTheClaim() {
        val a = ChannelGuard.newSecret()
        assertTrue(Regex("[0-9a-f]{32}").matches(a))
        assertFalse(a == ChannelGuard.newSecret())
        assertEquals("(location.origin==='https://univer.kaznu.kz'&&window.__univerClaim?window.__univerClaim('$secret'):null)", ChannelGuard.senders(secret, active = true))
        assertEquals("var s='$secret';", ChannelGuard.source("var s='__UNIVER_CHANNEL_SECRET__';", secret))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aSecretThatCouldBreakOutOfItsQuotesIsRefused() {
        ChannelGuard.senders("x');alert(1);('", active = true)
    }
}
