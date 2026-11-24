package com.univerflow.kaznu.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowCaptureTest {
    @Test fun onlyDirectMainFrameGetNavigationsAreRoutable() {
        assertTrue(WindowCapturePolicy.shouldRoute(isForMainFrame = true, method = "GET", isRedirect = false))
        assertTrue(WindowCapturePolicy.shouldRoute(isForMainFrame = true, method = "get", isRedirect = false))
        assertFalse(WindowCapturePolicy.shouldRoute(isForMainFrame = true, method = "POST", isRedirect = false))
        assertFalse(WindowCapturePolicy.shouldRoute(isForMainFrame = true, method = "PUT", isRedirect = false))
        assertFalse(WindowCapturePolicy.shouldRoute(isForMainFrame = true, method = "HEAD", isRedirect = false))
        assertFalse(WindowCapturePolicy.shouldRoute(isForMainFrame = true, method = null, isRedirect = false))
        assertFalse(WindowCapturePolicy.shouldRoute(isForMainFrame = true, method = "GET", isRedirect = true))
        assertFalse(WindowCapturePolicy.shouldRoute(isForMainFrame = false, method = "GET", isRedirect = false))
    }

    @Test fun formPostInNewWindowIsBlockedInInterceptAndNeverRouted() {
        val routed = mutableListOf<String>()
        var scheduledRetires = 0
        var immediateRetires = 0
        val gate = WindowCaptureGate<String>(
            scheduleRetire = { scheduledRetires++ },
            retireNow = { immediateRetires++ },
            onNavigation = routed::add,
        )

        // Form POST skips shouldOverrideUrlLoading and hits shouldInterceptRequest on WebView's IO thread.
        assertTrue(gate.onInterceptRequest())
        assertEquals(emptyList<String>(), routed)
        assertEquals(1, scheduledRetires)
        assertEquals(0, immediateRetires)

        // Any late navigation after interception must still be ignored.
        assertTrue(gate.onOverrideUrlLoading("https://external.example/follow-up", isForMainFrame = true, method = "GET", isRedirect = false))
        assertEquals(emptyList<String>(), routed)
    }

    @Test fun directMainFrameGetIsRoutedOnceAndRetiresPopup() {
        val routed = mutableListOf<String>()
        var retires = 0
        val gate = WindowCaptureGate<String>(
            scheduleRetire = { retires++ },
            retireNow = { retires++ },
            onNavigation = routed::add,
        )

        assertTrue(gate.onOverrideUrlLoading("https://univer.kaznu.kz/student/transcript/", isForMainFrame = true, method = "GET", isRedirect = false))
        assertEquals(listOf("https://univer.kaznu.kz/student/transcript/"), routed)
        assertEquals(1, retires)

        // A second callback before destroy finishes cannot route a second URL.
        assertTrue(gate.onOverrideUrlLoading("https://external.example/second", isForMainFrame = true, method = "GET", isRedirect = false))
        assertEquals(listOf("https://univer.kaznu.kz/student/transcript/"), routed)
    }

    @Test fun nonGetRedirectOrSubframeInOverrideIsDroppedAndRetiresPopup() {
        listOf(
            Triple(true, "POST", false),
            Triple(true, "GET", true),
            Triple(false, "GET", false),
        ).forEach { (mainFrame, method, redirect) ->
            val routed = mutableListOf<String>()
            var retires = 0
            val gate = WindowCaptureGate<String>(
                scheduleRetire = { retires++ },
                retireNow = { retires++ },
                onNavigation = routed::add,
            )
            assertTrue(gate.onOverrideUrlLoading("https://external.example/target", mainFrame, method, redirect))
            assertEquals("must not route ($mainFrame, $method, $redirect)", emptyList<String>(), routed)
            assertEquals(1, retires)
        }
    }

    @Test fun timeoutClosesGateBeforeLateNavigationCanRoute() {
        val routed = mutableListOf<String>()
        var retires = 0
        val gate = WindowCaptureGate<String>(
            scheduleRetire = { retires++ },
            retireNow = { retires++ },
            onNavigation = routed::add,
        )

        gate.onTimeoutOrCrash()
        assertEquals(1, retires)
        assertTrue(gate.onOverrideUrlLoading("https://external.example/late", isForMainFrame = true, method = "GET", isRedirect = false))
        assertEquals(emptyList<String>(), routed)
    }
}
