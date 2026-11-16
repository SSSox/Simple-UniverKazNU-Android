package com.univerflow.kaznu

import com.univerflow.kaznu.data.AttemptLedger
import com.univerflow.kaznu.data.WrongPinException
import com.univerflow.kaznu.data.guessPin
import com.univerflow.kaznu.domain.Credentials
import com.univerflow.kaznu.domain.PinLockout
import com.univerflow.kaznu.domain.UnlockResult
import com.univerflow.kaznu.main.changeWithVerifiedPin
import com.univerflow.kaznu.ui.WrongPinTexts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the PIN screens do between the checks: a PIN change that is retried or closed, and the text after wrong PINs. */
class LockScreenRulesTest {
    private class Ledger(override var failures: Int = 0) : AttemptLedger {
        override fun remainingWait() = 0L
        override fun startWait(durationMs: Long) {}
        override fun clearAttempts() { failures = 0 }
    }

    /** A PIN change with the repository's own counting: a wrong current PIN is a guess, a failed write is not. */
    private class Vault(var pin: String) {
        val ledger = Ledger()
        var storeFails = false
        fun change(current: CharArray, next: CharArray): UnlockResult = guessPin(ledger, {
            if (!current.contentEquals(pin.toCharArray())) throw WrongPinException()
            Credentials("fixture-user", "fixture-password")
        }, {
            if (storeFails) throw java.io.IOException("synthetic full disk")
            pin = String(next)
        }, {})
    }

    private fun erased(digits: CharArray) = digits.all { it == '\u0000' }

    @Test fun aFailedStoreLeavesTheCurrentPinForTheNextTryInsteadOfSpendingAttempts() = runTest {
        val vault = Vault("493827").apply { storeFails = true }
        val current = "493827".toCharArray()
        assertEquals(UnlockResult.Unavailable, changeWithVerifiedPin(current, "708194".toCharArray()) { a, b -> vault.change(a, b) })
        assertEquals("The checked PIN survives a failed store", "493827", String(current))
        vault.storeFails = false
        assertTrue(changeWithVerifiedPin(current, "708194".toCharArray()) { a, b -> vault.change(a, b) } is UnlockResult.Success)
        assertEquals("The retry was not counted as a wrong PIN", 0, vault.ledger.failures)
        assertEquals("708194", vault.pin)
        assertTrue("The change is over: the old digits are gone", erased(current))
    }

    @Test fun closingTheScreenDuringTheCheckDoesNotBlankTheDigitsBeingChecked() = runTest {
        val vault = Vault("493827")
        val current = "493827".toCharArray()
        val reading = CompletableDeferred<Unit>()
        val change = async { changeWithVerifiedPin(current, "708194".toCharArray()) { a, b -> reading.await(); vault.change(a, b) } }
        runCurrent()
        // The close button erases the current PIN while the storage thread has not read it yet.
        current.fill('\u0000')
        reading.complete(Unit)
        assertTrue(change.await() is UnlockResult.Success)
        assertEquals("No attempt was spent behind the student's back", 0, vault.ledger.failures)
        assertEquals("708194", vault.pin)
    }

    @Test fun aCancelledChangeErasesBothCopies() = runTest {
        var handed: CharArray? = null
        val current = "493827".toCharArray()
        val job = launch { changeWithVerifiedPin(current, "708194".toCharArray()) { a, _ -> handed = a; CompletableDeferred<UnlockResult>().await() } }
        runCurrent()
        assertFalse("The try reads its own copy", erased(handed!!))
        job.cancel()
        runCurrent()
        assertTrue(erased(current))
        assertTrue(erased(handed!!))
    }

    private val texts = WrongPinTexts(
        wrongPin = "Wrong PIN. Attempts left: %1\$d",
        lastAttempt = "One attempt left. The next wrong PIN deletes the saved account.",
        wait = "Too many attempts. Try again in %1\$s",
    )

    @Test fun everyWrongPinThatStartsAWaitStillTellsTheAttemptsLeftAndTheLastOneWarns() {
        for (failure in 5 until PinLockout.WIPE_AT) {
            val result = guessPin(Ledger(failure - 1), { throw WrongPinException() }, {}, {}) as UnlockResult.WrongPin
            assertTrue("A wait follows wrong PIN $failure", result.waitMs > 0)
            val counting = texts.duringWait("0:30", result.attemptsLeft)
            val after = texts.afterWrongPin(result.attemptsLeft)
            assertTrue("The countdown stays", counting.startsWith("Too many attempts. Try again in 0:30"))
            if (failure == PinLockout.WIPE_AT - 1) {
                assertTrue("Wrong PIN $failure: the countdown warns about the wipe", counting.endsWith(texts.lastAttempt))
                assertEquals(texts.lastAttempt, after)
            } else {
                val left = "Attempts left: ${PinLockout.WIPE_AT - failure}"
                assertTrue("Wrong PIN $failure: $counting", counting.endsWith(left))
                assertTrue("Wrong PIN $failure after the wait: $after", after.endsWith(left))
            }
        }
    }

    @Test fun earlyWrongPinsShowTheCountAndAWaitWithoutOneShowsOnlyTheCountdown() {
        assertEquals("Wrong PIN. Attempts left: 6", texts.afterWrongPin(6))
        assertEquals("Too many attempts. Try again in 1:00", texts.duringWait("1:00", null))
    }
}
