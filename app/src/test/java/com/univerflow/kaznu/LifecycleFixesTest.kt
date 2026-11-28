package com.univerflow.kaznu

import com.univerflow.kaznu.domain.Credentials
import com.univerflow.kaznu.domain.UnlockResult
import com.univerflow.kaznu.main.LinkReturnAction
import com.univerflow.kaznu.main.MainNavigationGate
import com.univerflow.kaznu.main.RendererRecovery
import com.univerflow.kaznu.main.changeWithVerifiedPin
import com.univerflow.kaznu.ui.PinFlowController
import com.univerflow.kaznu.ui.PinPad
import com.univerflow.kaznu.web.RecoveryBackoff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Behavioral regression tests for MainActivity renderer recovery, late callback guards, and LockUi/PinFlowController lifecycle. */
@OptIn(ExperimentalCoroutinesApi::class)
class LifecycleFixesTest {

    // --- Fix (1): RendererRecovery ---

    private class RecoveryHarness(
        var canRecover: Boolean = true,
        var now: Long = 1_000L,
    ) {
        val backoff = RecoveryBackoff(longArrayOf(1_000, 5_000, 30_000, 120_000, 300_000))
        var scheduled: Runnable? = null
        var scheduledDelay: Long? = null
        val cancelled = mutableListOf<Runnable>()
        var recreates = 0

        val recovery = RendererRecovery(
            backoff = backoff,
            clock = { now },
            schedule = { task, delay -> scheduled = task; scheduledDelay = delay },
            cancelCallback = { task ->
                cancelled += task
                if (scheduled === task) scheduled = null
            },
            canRecover = { canRecover },
            recreate = { recreates++ },
        )

        fun fire() {
            val task = scheduled
            assertNotNull("Expected a pending renderer recovery task", task)
            scheduled = null
            task!!.run()
        }
    }

    @Test fun rendererRecoveryRemovesSameRunnableOnDestroyOrLockAndNeverRecreates() {
        val h = RecoveryHarness()
        h.recovery.scheduleIfNeeded()
        val task = h.scheduled
        assertNotNull(task)
        assertEquals(1_000L, h.scheduledDelay)
        assertTrue(h.recovery.pending)

        // Second call while pending does not double-schedule or double-spend backoff.
        h.recovery.scheduleIfNeeded()
        assertSame(task, h.scheduled)
        assertEquals(1, h.backoff.attempts)

        // Cancel on onDestroy() or lock removes the exact scheduled Runnable reference.
        h.recovery.cancel()
        assertNull(h.scheduled)
        assertFalse(h.recovery.pending)
        assertEquals(listOf(task), h.cancelled)
        assertEquals(0, h.recreates)

        // Even if the platform still ran a racing callback after state became unavailable, it must not recreate.
        h.canRecover = false
        task!!.run()
        assertEquals(0, h.recreates)
    }

    @Test fun rendererRecoveryRechecksStateWhenTaskRunsAndAllowsLaterReschedule() {
        val h = RecoveryHarness()
        h.recovery.scheduleIfNeeded()
        assertTrue(h.recovery.pending)

        // Before the delayed runnable fires, the app locks, opens settings, or leaves STARTED.
        h.canRecover = false
        h.fire()
        assertEquals("Must not recreate when locked/settings/backgrounded", 0, h.recreates)
        assertFalse("Pending flag clears so recovery can reschedule on return", h.recovery.pending)

        // When conditions become valid again, recovery schedules with the next backoff step and recreates.
        h.canRecover = true
        h.now += 2_000L
        h.recovery.scheduleIfNeeded()
        assertEquals(5_000L, h.scheduledDelay)
        h.fire()
        assertEquals(1, h.recreates)
    }

    // --- Fix (2): MainNavigationGate (captureNewWindow, linkReturned, print/viewer routing) ---

    @Test fun captureNewWindowGuardsRejectDestroyedFinishingUnavailableLockedOrChangingStates() {
        assertTrue(MainNavigationGate.canCaptureWindow(
            isUserGesture = true, isDestroyed = false, isFinishing = false, rendererUnavailable = false,
            sessionChanging = false, sessionClearPending = false, locked = false, canBrowse = true,
        ))
        assertTrue(MainNavigationGate.canHandleCapturedWindow(
            isDestroyed = false, isFinishing = false, rendererUnavailable = false,
            sessionChanging = false, sessionClearPending = false, locked = false, canBrowse = true, shouldRoute = true,
        ))

        val blockedCases = listOf(
            MainNavigationGate.canHandleCapturedWindow(true, false, false, false, false, false, true, true),
            MainNavigationGate.canHandleCapturedWindow(false, true, false, false, false, false, true, true),
            MainNavigationGate.canHandleCapturedWindow(false, false, true, false, false, false, true, true),
            MainNavigationGate.canHandleCapturedWindow(false, false, false, true, false, false, true, true),
            MainNavigationGate.canHandleCapturedWindow(false, false, false, false, true, false, true, true),
            MainNavigationGate.canHandleCapturedWindow(false, false, false, false, false, true, true, true),
            MainNavigationGate.canHandleCapturedWindow(false, false, false, false, false, false, false, true),
            MainNavigationGate.canHandleCapturedWindow(false, false, false, false, false, false, true, false),
        )
        blockedCases.forEachIndexed { index, allowed ->
            assertFalse("Blocked case $index must not handle late captureNewWindow callback", allowed)
        }
    }

    @Test fun linkReturnedIgnoresDestroyedFinishingUnavailableLockedOrChangingStates() {
        val schedule = "https://univer.kaznu.kz/student/myschedule/"
        assertEquals(LinkReturnAction.LOAD_MAIN, MainNavigationGate.linkReturnAction(
            schedule, isDestroyed = false, isFinishing = false, rendererUnavailable = false,
            sessionChanging = false, sessionClearPending = false, locked = false, canBrowse = true,
        ))

        val ignored = listOf(
            MainNavigationGate.linkReturnAction(schedule, true, false, false, false, false, false, true),
            MainNavigationGate.linkReturnAction(schedule, false, true, false, false, false, false, true),
            MainNavigationGate.linkReturnAction(schedule, false, false, false, true, false, false, true),
            MainNavigationGate.linkReturnAction(schedule, false, false, false, false, true, false, true),
            MainNavigationGate.linkReturnAction(schedule, false, false, false, false, false, true, true),
            MainNavigationGate.linkReturnAction(schedule, false, false, false, false, false, false, false),
            MainNavigationGate.linkReturnAction("https://univer.kaznu.kz/user/logout", false, false, false, false, false, false, true),
        )
        ignored.forEachIndexed { index, action ->
            assertEquals("Blocked linkReturned case $index must be ignored", LinkReturnAction.IGNORE, action)
        }
    }

    @Test fun linkReturnedDuringRendererRecoveryIsKeptNotDropped() {
        val schedule = "https://univer.kaznu.kz/student/myschedule/"
        assertEquals(LinkReturnAction.PEND, MainNavigationGate.linkReturnAction(
            schedule, isDestroyed = false, isFinishing = false, rendererUnavailable = true,
            sessionChanging = false, sessionClearPending = false, locked = false, canBrowse = true,
        ))
        // Nothing else that blocks a hand-back is relaxed while the renderer is down.
        listOf(
            MainNavigationGate.linkReturnAction(schedule, true, false, true, false, false, false, true),
            MainNavigationGate.linkReturnAction(schedule, false, true, true, false, false, false, true),
            MainNavigationGate.linkReturnAction(schedule, false, false, true, true, false, false, true),
            MainNavigationGate.linkReturnAction(schedule, false, false, true, false, true, false, true),
            MainNavigationGate.linkReturnAction(schedule, false, false, true, false, false, true, true),
            MainNavigationGate.linkReturnAction(schedule, false, false, true, false, false, false, false),
            MainNavigationGate.linkReturnAction("https://univer.kaznu.kz/user/logout", false, false, true, false, false, false, true),
        ).forEachIndexed { index, action -> assertEquals("Case $index must still be ignored", LinkReturnAction.IGNORE, action) }
    }

    @Test fun aSessionResetDropsTheLinkQueuedDuringRendererRecovery() {
        val pending = com.univerflow.kaznu.main.PendingLinkReturn()
        pending.keep("https://univer.kaznu.kz/student/myschedule/")
        pending.sessionEnded()
        assertNull("The old account's link must not open in the next session", pending.take())

        pending.keep("https://univer.kaznu.kz/student/myschedule/")
        assertEquals("https://univer.kaznu.kz/student/myschedule/", pending.take())
        assertNull("A queued link opens once", pending.take())
    }

    @Test fun aQueuedLinkSurvivesRecreationsWhileLockedAndOpensAfterUnlockInItsSessionOnly() {
        val schedule = "https://univer.kaznu.kz/student/myschedule/"
        // Kept during recovery in session 3; the screen is recreated (rotation) while the app is locked.
        val first = com.univerflow.kaznu.main.PendingLinkReturn().apply { keep(schedule, 3) }
        val bundle = first.saved(3)
        assertEquals(schedule, bundle)
        val second = com.univerflow.kaznu.main.PendingLinkReturn().apply { restore(bundle, 3, 3) }
        assertNull("Locked: not handed back yet", second.take(3, locked = true))
        // Rotated again before unlocking: still saved.
        val third = com.univerflow.kaznu.main.PendingLinkReturn().apply { restore(second.saved(3), 3, 3) }
        assertEquals("Unlocked: the page opens", schedule, third.take(3, locked = false))
        assertNull("Once", third.take(3))

        // Another session generation never takes, saves or restores it.
        val other = com.univerflow.kaznu.main.PendingLinkReturn().apply { keep(schedule, 3) }
        assertNull(other.saved(4))
        assertNull(other.take(4))
        assertNull("Dropped, not only hidden", other.take(3))
        val restored = com.univerflow.kaznu.main.PendingLinkReturn().apply { restore(schedule, 3, 4) }
        assertNull(restored.take(4))
        assertNull(com.univerflow.kaznu.main.PendingLinkReturn().apply { restore(schedule, -1, 0) }.take(0))
    }

    @Test fun printViewerAndDownloadUrlsNeverBypassMainWebViewRoutingGuards() {
        // Normal trusted page loads in main WebView.
        assertTrue(MainNavigationGate.loadsInMainWebView("https://univer.kaznu.kz/student/myschedule/"))

        // Print documents, Moodle viewer entry, Moodle pages, and course downloads must never bypass route().
        listOf(
            "https://univer.kaznu.kz/student/transcript/",
            "https://univer.kaznu.kz/student/iup/1",
            "https://univer.kaznu.kz/student/recbook/",
            "https://univer.kaznu.kz/user/loginMoodle",
            "https://dl.kaznu.kz/course/view.php?id=7",
            "https://univer.kaznu.kz/student/umkd/get/3321866/99281/2026/1",
        ).forEach { url ->
            assertFalse("Must route via viewer/download, never main WebView: $url", MainNavigationGate.loadsInMainWebView(url))
        }

        // Material files and Moodle pages handed back from LinkViewerActivity go to ROUTE, not LOAD_MAIN.
        assertEquals(LinkReturnAction.ROUTE, MainNavigationGate.linkReturnAction(
            "https://univer.kaznu.kz/student/umkd/get/3321866/99281/2026/1",
            isDestroyed = false, isFinishing = false, rendererUnavailable = false,
            sessionChanging = false, sessionClearPending = false, locked = false, canBrowse = true,
        ))
        assertEquals(LinkReturnAction.ROUTE, MainNavigationGate.linkReturnAction(
            "https://dl.kaznu.kz/course/view.php?id=7",
            isDestroyed = false, isFinishing = false, rendererUnavailable = false,
            sessionChanging = false, sessionClearPending = false, locked = false, canBrowse = true,
        ))
    }

    // --- Fix (3): PinFlowController (LockUi / LockFlow lifecycle, cancellation, Back/close, buffer clearing) ---

    internal class FakePinPad : PinPad {
        override var isCloseable: Boolean = false
        override var onComplete: ((CharArray) -> Unit)? = null
        override var onBiometric: (() -> Unit)? = null
        override var onForgot: (() -> Unit)? = null
        override var onClose: (() -> Unit)? = null

        var inputAllowed = true
        var closeAllowed = true
        var lastMessage: String? = null
        val messages = mutableListOf<String?>()
        var shakes = 0
        var resets = 0
        var succeeds = 0

        override fun configure(titleText: String, hint: String?, closeable: Boolean, biometric: Boolean, forgotText: String?) {
            isCloseable = closeable
            lastMessage = null
        }

        override fun showMessage(text: String?, isError: Boolean) {
            lastMessage = text
            messages += text
        }

        override fun setInputEnabled(enabled: Boolean) { inputAllowed = enabled }
        override fun setCloseEnabled(enabled: Boolean) { closeAllowed = enabled }
        override fun reset() { resets++ }
        override fun succeed() { inputAllowed = false; closeAllowed = false; succeeds++ }
        override fun shake() { shakes++ }

        fun tapClose() { if (closeAllowed) onClose?.invoke() }
        fun enter(digits: CharArray) { onComplete?.invoke(digits) }
    }

    private fun testTexts(id: Int): String = when (id) {
        R.string.lock_wrong_pin -> "Wrong PIN: %1\$d"
        R.string.lock_last_attempt -> "Last attempt"
        R.string.lock_wait -> "Wait %1\$s"
        R.string.lock_checking -> "Checking"
        R.string.lock_unavailable -> "Unavailable"
        R.string.storage_error -> "Storage error"
        R.string.lock_mismatch -> "Mismatch"
        else -> "text:$id"
    }

    private fun erased(digits: CharArray) = digits.all { it == '\u0000' }

    @Test fun hideDuringVerifyCancelsJobPropagatesCancellationClearsPinAndStartsNoDetachedCountdown() = runTest {
        val pad = FakePinPad()
        var attached = true
        var releases = 0
        var verifiedCalls = 0
        var cancelledCaught = false
        val gate = CompletableDeferred<UnlockResult>()
        val controller = PinFlowController(
            scope = this,
            text = ::testTexts,
            clock = { testScheduler.currentTime },
            acquirePad = { attached = true; pad },
            padActive = { attached },
            releasePad = { attached = false; releases++ },
            moveTaskToBack = {},
        )

        controller.enter(
            title = R.string.lock_unlock_title, hint = null, closeable = true, biometric = false, forgot = false,
            verify = {
                try { gate.await() } catch (c: CancellationException) { cancelledCaught = true; throw c }
            },
            onVerified = { verifiedCalls++ },
        )

        val pin = "493827".toCharArray()
        pad.enter(pin)
        runCurrent()

        // While PBKDF2 is running, input, close, and Back are disabled.
        assertFalse(pad.inputAllowed)
        assertFalse(pad.closeAllowed)
        assertTrue(controller.handleBack())
        assertEquals("Back must not close while verifying", 0, releases)
        pad.tapClose()
        assertEquals("Close button must not close while verifying", 0, releases)

        // Duplicate entry while verifying is ignored and immediately wiped.
        val dup = "708194".toCharArray()
        pad.enter(dup)
        assertTrue("Duplicate PIN buffer must be cleared immediately", erased(dup))

        // hide() cancels the in-flight verify job, propagates CancellationException, and wipes the PIN buffer.
        controller.hide()
        runCurrent()

        assertTrue("CancellationException must propagate to verify()", cancelledCaught)
        assertTrue("In-flight PIN buffer must be cleared on hide/cancel", erased(pin))
        assertEquals(0, verifiedCalls)
        assertEquals(1, releases)

        // No detached countdown runs after hide.
        val countBefore = pad.messages.size
        advanceTimeBy(10_000)
        assertEquals(countBefore, pad.messages.size)
    }

    @Test fun reentryCancelsPreviousVerifyJobAndStaleResultNeverAffectsNewFlow() = runTest {
        val pad = FakePinPad()
        var attached = true
        var firstCancelled = false
        var firstVerified = 0
        var secondVerified = 0
        val firstGate = CompletableDeferred<UnlockResult>()
        val controller = PinFlowController(
            scope = this,
            text = ::testTexts,
            clock = { testScheduler.currentTime },
            acquirePad = { attached = true; pad },
            padActive = { attached },
            releasePad = { attached = false },
            moveTaskToBack = {},
        )

        controller.enter(
            title = R.string.lock_verify_title, hint = null, closeable = true, biometric = false, forgot = false,
            verify = {
                try { firstGate.await() } catch (c: CancellationException) { firstCancelled = true; throw c }
            },
            onVerified = { firstVerified++ },
        )
        val firstPin = "493827".toCharArray()
        pad.enter(firstPin)
        runCurrent()

        // Re-enter with a new unlock flow (e.g. auto-lock replacing changePin).
        controller.enter(
            title = R.string.lock_unlock_title, hint = null, closeable = false, biometric = false, forgot = true,
            verify = { UnlockResult.Success(Credentials("u", "p")) },
            onVerified = { it.fill('\u0000'); secondVerified++ },
        )
        runCurrent()

        assertTrue("Re-entry must cancel previous in-flight verify", firstCancelled)
        assertTrue("Previous PIN buffer must be wiped on re-entry", erased(firstPin))
        assertTrue("New flow re-enables input and controls", pad.inputAllowed && pad.closeAllowed)

        val secondPin = "708194".toCharArray()
        pad.enter(secondPin)
        runCurrent()
        assertEquals(0, firstVerified)
        assertEquals(1, secondVerified)
        assertTrue(erased(secondPin))
    }

    @Test fun backOnTheUnlockScreenWhileVerifyingStillSendsTheAppToTheBackground() = runTest {
        val pad = FakePinPad()
        var attached = true
        var backgrounded = 0
        var verified = 0
        val gate = CompletableDeferred<UnlockResult>()
        val controller = PinFlowController(
            scope = this,
            text = ::testTexts,
            clock = { testScheduler.currentTime },
            acquirePad = { attached = true; pad },
            padActive = { attached },
            releasePad = { attached = false },
            moveTaskToBack = { backgrounded++ },
        )
        controller.enter(
            title = R.string.lock_unlock_title, hint = null, closeable = false, biometric = false, forgot = false,
            verify = { gate.await() }, onVerified = { verified++ },
        )
        pad.enter("493827".toCharArray())
        runCurrent()
        assertFalse(pad.inputAllowed)

        assertTrue(controller.handleBack())
        assertEquals("Back on the unlock screen must leave the app even mid-verification", 1, backgrounded)
        assertTrue("The pad stays in place", attached)

        // The verification it left running still finishes.
        gate.complete(UnlockResult.Success(com.univerflow.kaznu.domain.Credentials("fixture-user", "fixture-password")))
        runCurrent()
        assertEquals(1, verified)
    }

    @Test fun createCancelsAcceptOnHideClearsBothPinBuffersAndOldPinWithoutRunningOnAccepted() = runTest {
        val pad = FakePinPad()
        var attached = true
        var acceptCancelled = false
        var acceptedCallbacks = 0
        val oldPin = "493827".toCharArray()
        val acceptGate = CompletableDeferred<Boolean>()
        val controller = PinFlowController(
            scope = this,
            text = ::testTexts,
            clock = { testScheduler.currentTime },
            acquirePad = { attached = true; pad },
            padActive = { attached },
            releasePad = { attached = false },
            moveTaskToBack = {},
        )

        controller.create(
            closeable = true,
            accept = {
                try { acceptGate.await() } catch (c: CancellationException) { acceptCancelled = true; throw c }
            },
            onClose = {},
            onAccepted = { acceptedCallbacks++ },
            onCleared = { oldPin.fill('\u0000') },
        )

        val first = "708194".toCharArray()
        val confirm = "708194".toCharArray()
        pad.enter(first)
        pad.enter(confirm)
        runCurrent()

        // Close/Back disabled during PBKDF2 accept.
        assertFalse(pad.inputAllowed)
        assertFalse(pad.closeAllowed)
        assertTrue(controller.handleBack())
        assertTrue(attached)

        // Hide cancels accept and clears first, confirm, and oldPin.
        controller.hide()
        runCurrent()

        assertTrue(acceptCancelled)
        assertTrue("First entry buffer must be cleared on cancel", erased(first))
        assertTrue("Confirmation buffer must be cleared on cancel", erased(confirm))
        assertTrue("Caller's verified old PIN must be cleared on hide", erased(oldPin))
        assertEquals("onAccepted must not run after hide/cancel", 0, acceptedCallbacks)
    }

    @Test fun hideWhileIdleOnConfirmStepClearsFirstAndCallerHeldPinBuffer() = runTest {
        val pad = FakePinPad()
        var attached = true
        val oldPin = "493827".toCharArray()
        val controller = PinFlowController(
            scope = this,
            text = ::testTexts,
            clock = { testScheduler.currentTime },
            acquirePad = { attached = true; pad },
            padActive = { attached },
            releasePad = { attached = false },
            moveTaskToBack = {},
        )

        controller.create(
            closeable = true,
            accept = { true },
            onClose = {},
            onCleared = { oldPin.fill('\u0000') },
        )
        val first = "708194".toCharArray()
        pad.enter(first)
        assertFalse(erased(first))
        assertFalse(erased(oldPin))

        controller.hide()
        assertTrue("First PIN buffer must be cleared when hidden before confirmation", erased(first))
        assertTrue("Old PIN buffer must be cleared when hidden before confirmation", erased(oldPin))
    }

    @Test fun wrongPinWaitKeepsCloseEnabledAndStopsCountdownIfPadDetaches() = runTest {
        val pad = FakePinPad()
        var attached = true
        var closedCount = 0
        val controller = PinFlowController(
            scope = this,
            text = ::testTexts,
            clock = { testScheduler.currentTime },
            acquirePad = { attached = true; pad },
            padActive = { attached },
            releasePad = { attached = false },
            moveTaskToBack = {},
        )

        controller.enter(
            title = R.string.lock_verify_title, hint = null, closeable = true, biometric = false, forgot = false,
            verify = { UnlockResult.WrongPin(attemptsLeft = 4, waitMs = 2_000L) },
            onVerified = {},
            onClose = { closedCount++ },
        )

        val wrong = "493827".toCharArray()
        pad.enter(wrong)
        runCurrent()

        assertTrue(erased(wrong))
        assertFalse("Digit keys stay disabled during wait countdown", pad.inputAllowed)
        assertTrue("Close button re-enables once PBKDF2 finishes even during wait", pad.closeAllowed)
        assertTrue(pad.lastMessage.orEmpty().contains("Wait 0:02"))

        // Back closes the closeable screen during wait and cancels the countdown cleanly.
        assertTrue(controller.handleBack())
        assertEquals(1, closedCount)
        assertFalse(attached)

        val messagesAtClose = pad.messages.size
        advanceTimeBy(5_000)
        assertEquals("Cancelled countdown must not keep posting messages", messagesAtClose, pad.messages.size)
        assertFalse("Cancelled countdown must not re-enable input", pad.inputAllowed)
    }

    @Test fun detachedPadWhenVerifyReturnsWrongPinStartsNoCountdownAndClearsPin() = runTest {
        val pad = FakePinPad()
        var attached = true
        val gate = CompletableDeferred<UnlockResult>()
        val controller = PinFlowController(
            scope = this,
            text = ::testTexts,
            clock = { testScheduler.currentTime },
            acquirePad = { attached = true; pad },
            padActive = { attached },
            releasePad = { attached = false },
            moveTaskToBack = {},
        )

        controller.enter(
            title = R.string.lock_unlock_title, hint = null, closeable = false, biometric = false, forgot = true,
            verify = { gate.await() },
            onVerified = {},
        )

        val pin = "493827".toCharArray()
        pad.enter(pin)
        runCurrent()
        val messagesBeforeDetach = pad.messages.size

        // Pad becomes detached (e.g. Activity finishing) right as verify completes with a lockout wait.
        attached = false
        gate.complete(UnlockResult.WrongPin(attemptsLeft = 4, waitMs = 30_000L))
        runCurrent()
        advanceTimeBy(5_000)

        assertTrue("PIN buffer must still be cleared on detached completion", erased(pin))
        assertEquals("Detached pad must not start a countdown", messagesBeforeDetach, pad.messages.size)
    }

    @Test fun changePinKeepOpenTransitionCompletesWithoutSelfCancellingAndClearsAllBuffers() = runTest {
        val pad = FakePinPad()
        var attached = true
        var accepted = 0
        var storeFails = true
        var capturedOld: CharArray? = null
        val controller = PinFlowController(
            scope = this,
            text = ::testTexts,
            clock = { testScheduler.currentTime },
            acquirePad = { attached = true; pad },
            padActive = { attached },
            releasePad = { attached = false },
            moveTaskToBack = {},
        )

        controller.enter(
            title = R.string.lock_verify_title, hint = null, closeable = true, biometric = false, forgot = false,
            verify = { UnlockResult.Success(Credentials("u", "p")) },
            keepOpen = true,
            onVerified = { old ->
                capturedOld = old
                controller.create(
                    title = R.string.lock_new_title,
                    closeable = true,
                    accept = { next ->
                        val result = changeWithVerifiedPin(old, next) { _, _ ->
                            if (storeFails) UnlockResult.Unavailable else UnlockResult.Success(Credentials("u", "p"))
                        }
                        result is UnlockResult.Success
                    },
                    onClose = {},
                    onAccepted = { accepted++ },
                    onCleared = { old.fill('\u0000') },
                )
            },
        )

        val step1 = "493827".toCharArray()
        pad.enter(step1)
        runCurrent()
        assertTrue("Step 1 PIN buffer wiped", erased(step1))
        assertNotNull(capturedOld)
        assertFalse("Step 2 keeps old PIN until stored or exited", erased(capturedOld!!))
        assertTrue(pad.inputAllowed && pad.closeAllowed)

        // First store attempt fails: old PIN survives for retry, new buffers wiped.
        val firstTry1 = "708194".toCharArray()
        val firstTry2 = "708194".toCharArray()
        pad.enter(firstTry1)
        pad.enter(firstTry2)
        runCurrent()
        assertTrue(erased(firstTry1) && erased(firstTry2))
        assertFalse(erased(capturedOld!!))
        assertEquals(0, accepted)
        assertTrue(pad.inputAllowed && pad.closeAllowed)

        // Second store attempt succeeds: screen hides, onAccepted runs after hide, all buffers cleared.
        storeFails = false
        val secondTry1 = "708194".toCharArray()
        val secondTry2 = "708194".toCharArray()
        pad.enter(secondTry1)
        pad.enter(secondTry2)
        runCurrent()
        assertTrue(erased(secondTry1) && erased(secondTry2))
        assertTrue("Old PIN wiped on completion", erased(capturedOld!!))
        assertFalse("Screen hidden before onAccepted", attached)
        assertEquals(1, accepted)
    }

    @Test fun cancelledScopeStillClearsEnteredPinBuffer() {
        val pad = FakePinPad()
        val deadScope = CoroutineScope(SupervisorJob())
        deadScope.cancel()
        val controller = PinFlowController(
            scope = deadScope,
            text = ::testTexts,
            clock = { 0L },
            acquirePad = { pad },
            padActive = { true },
            releasePad = {},
            moveTaskToBack = {},
        )

        controller.enter(
            title = R.string.lock_unlock_title, hint = null, closeable = false, biometric = false, forgot = false,
            verify = { UnlockResult.Unavailable },
            onVerified = {},
        )
        val pin = "493827".toCharArray()
        pad.enter(pin)
        assertTrue("PIN buffer must be wiped even when coroutine scope is already cancelled", erased(pin))

        controller.create(closeable = true, accept = { true }, onClose = {})
        val first = "708194".toCharArray()
        val confirm = "708194".toCharArray()
        pad.enter(first)
        pad.enter(confirm)
        assertTrue("First PIN buffer must be wiped on cancelled scope", erased(first))
        assertTrue("Confirm PIN buffer must be wiped on cancelled scope", erased(confirm))
    }

    @Test fun immediateDispatcherSyncCompletionAndCancellationNeverStickBusyOrClobberNextSession() = runTest(UnconfinedTestDispatcher()) {
        val pad = FakePinPad()
        var attached = true
        var accepted = 0
        val acceptGate = CompletableDeferred<Boolean>()
        var acceptCancelled = false
        val controller = PinFlowController(
            scope = this,
            text = ::testTexts,
            clock = { testScheduler.currentTime },
            acquirePad = { attached = true; pad },
            padActive = { attached },
            releasePad = { attached = false },
            moveTaskToBack = {},
        )

        // 1. Synchronous CancellationException on an immediate dispatcher must not leave busy stuck true.
        controller.enter(
            title = R.string.lock_verify_title, hint = null, closeable = true, biometric = false, forgot = false,
            verify = { throw CancellationException("sync cancel") },
            onVerified = {},
        )
        val cancelledPin = "493827".toCharArray()
        pad.enter(cancelledPin)
        assertTrue("PIN must be wiped on synchronous cancellation", erased(cancelledPin))
        assertTrue("handleBack must still work (not stuck busy)", controller.handleBack())

        // 2. Synchronous Success(keepOpen = true) chaining directly into create() must keep the new session's work cancellable.
        controller.enter(
            title = R.string.lock_verify_title, hint = null, closeable = true, biometric = false, forgot = false,
            verify = { UnlockResult.Success(Credentials("u", "p")) },
            keepOpen = true,
            onVerified = { old ->
                controller.create(
                    title = R.string.lock_new_title,
                    closeable = true,
                    accept = {
                        try { acceptGate.await() } catch (c: CancellationException) { acceptCancelled = true; throw c }
                    },
                    onClose = {},
                    onAccepted = { accepted++ },
                    onCleared = { old.fill('\u0000') },
                )
            },
        )
        pad.enter("493827".toCharArray())
        val next1 = "708194".toCharArray()
        val next2 = "708194".toCharArray()
        pad.enter(next1)
        pad.enter(next2)
        assertFalse(pad.inputAllowed)
        assertFalse(pad.closeAllowed)

        // hide() must cancel the chained create()'s accept job (not an overwritten enter() job).
        controller.hide()
        assertTrue("Chained create() accept job must be cancelled by hide()", acceptCancelled)
        assertTrue(erased(next1) && erased(next2))
        assertEquals(0, accepted)
    }
}
