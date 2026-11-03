package com.univerflow.kaznu

import com.univerflow.kaznu.data.LockCrypto
import com.univerflow.kaznu.data.LockRecord
import com.univerflow.kaznu.data.LockedVaultCore
import com.univerflow.kaznu.data.OuterWrapper
import com.univerflow.kaznu.data.Sealed
import com.univerflow.kaznu.data.WrongPinException
import com.univerflow.kaznu.data.AttemptLedger
import com.univerflow.kaznu.data.guessPin
import com.univerflow.kaznu.domain.AppLockGate
import com.univerflow.kaznu.domain.AutoLock
import com.univerflow.kaznu.domain.Credentials
import com.univerflow.kaznu.domain.PinLockout
import com.univerflow.kaznu.domain.UnlockResult
import com.univerflow.kaznu.domain.PinPolicy
import com.univerflow.kaznu.domain.PinWeakness
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException

/** The record rules on the JVM; the outer Keystore key is replaced by a software key. */
class LockCoreTest {
    private class SoftwareOuter(val key: ByteArray = LockCrypto.randomBytes(32)) : OuterWrapper {
        override fun wrap(plaintext: ByteArray, aad: ByteArray) = LockCrypto.seal(key, plaintext, aad)
        override fun unwrap(sealed: Sealed, aad: ByteArray) = LockCrypto.open(key, sealed, aad)
    }

    // Few iterations: the tests check the rules, not the cost of guessing.
    private val iterations = 1_000
    private val account = Credentials("student.login", "p@ss w0rd — ёқ")
    private fun pin(text: String) = text.toCharArray()

    @Test fun pbkdf2MatchesThePublishedVectorAndThePlatformProvider() {
        // RFC 7914 section 11: PBKDF2-HMAC-SHA-256, P="passwd", S="salt", c=1, dkLen=64 starts with this value.
        val vector = LockCrypto.pbkdf2Sha256("passwd".toByteArray(), "salt".toByteArray(), 1, 64)
        assertEquals("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc", vector.copyOf(32).joinToString("") { "%02x".format(it) })
        val salt = LockCrypto.randomBytes(16)
        assertArrayEquals(LockCrypto.pbkdf2Sha256("123456".toByteArray(), salt, 777), LockCrypto.deriveKey(pin("123456"), salt, 777))
    }

    @Test fun rightPinOpensTheAccountAndAWrongOneIsOnlyWrong() {
        val core = LockedVaultCore(SoftwareOuter())
        val (record, _) = core.create(account, pin("493827"), iterations)
        val opened = core.unlockWithPin(record, pin("493827"))
        assertEquals(account.login, opened.credentials.login)
        assertEquals(account.password, opened.credentials.password)
        assertThrows(WrongPinException::class.java) { core.unlockWithPin(record, pin("493828")) }
    }

    @Test fun withoutTheOuterKeyEvenTheRightPinOpensNothing() {
        val (record, _) = LockedVaultCore(SoftwareOuter()).create(account, pin("493827"), iterations)
        // The record copied to another phone (or the Keystore key lost) is unusable, and that is not "wrong PIN".
        val error = assertThrows(GeneralSecurityException::class.java) { LockedVaultCore(SoftwareOuter()).unlockWithPin(record, pin("493827")) }
        assertFalse(error is WrongPinException)
    }

    @Test fun storedBytesHoldNoPlainLoginOrPassword() {
        val (record, _) = LockedVaultCore(SoftwareOuter()).create(account, pin("493827"), iterations)
        val bytes = record.encode()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertFalse(text.contains("student.login")); assertFalse(text.contains("w0rd"))
        assertFalse(String(bytes, Charsets.UTF_8).contains("493827"))
    }

    @Test fun recordSurvivesEncodingAndRejectsDamage() {
        val core = LockedVaultCore(SoftwareOuter())
        val (record, _) = core.create(account, pin("493827"), iterations)
        val again = LockRecord.decode(record.encode())
        assertEquals(account.login, core.unlockWithPin(again, pin("493827")).credentials.login)
        assertThrows(IllegalArgumentException::class.java) { LockRecord.decode(byteArrayOf(1, 2, 3)) }
        val flipped = record.encode().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }
        assertThrows(GeneralSecurityException::class.java) { core.unlockWithPin(LockRecord.decode(flipped), pin("493827")) }
    }

    @Test fun newPinKeepsTheAccountAndRetiresTheOldPin() {
        val core = LockedVaultCore(SoftwareOuter())
        val (record, key) = core.create(account, pin("493827"), iterations)
        val changed = core.withNewPin(record, key, pin("708194"), iterations)
        assertEquals(account.login, core.unlockWithPin(changed, pin("708194")).credentials.login)
        assertThrows(WrongPinException::class.java) { core.unlockWithPin(changed, pin("493827")) }
        assertNotEquals("A fresh salt for every PIN", record.salt.toList(), changed.salt.toList())
    }

    @Test fun changedCredentialsReplaceTheSealedAccountOnly() {
        val core = LockedVaultCore(SoftwareOuter())
        val (record, key) = core.create(account, pin("493827"), iterations)
        val next = core.withCredentials(record, key, Credentials("other.login", "new-pass"))
        assertEquals("other.login", core.unlockWithPin(next, pin("493827")).credentials.login)
    }

    @Test fun biometricWrapOpensTheSameDataKeyAndStaysThroughAPinChange() {
        val core = LockedVaultCore(SoftwareOuter())
        val (record, key) = core.create(account, pin("493827"), iterations)
        val biometricKey = LockCrypto.randomBytes(32)
        val withBiometric = record.withBiometric(LockCrypto.seal(biometricKey, key, core.aadBiometric))
        val recovered = LockCrypto.open(biometricKey, withBiometric.biometric!!, core.aadBiometric)
        assertEquals(account.login, core.credentialsWith(withBiometric, recovered).login)
        val afterPinChange = core.withNewPin(withBiometric, key, pin("708194"), iterations)
        assertTrue("A new PIN does not drop the biometric wrap", afterPinChange.biometric != null)
        assertNull(afterPinChange.withBiometric(null).biometric)
    }

    @Test fun easyPinsAreRefusedAndOrdinaryOnesAreNot() {
        assertEquals(PinWeakness.REPEATED, PinPolicy.weakness(pin("000000")))
        assertEquals(PinWeakness.SEQUENCE, PinPolicy.weakness(pin("123456")))
        assertEquals(PinWeakness.SEQUENCE, PinPolicy.weakness(pin("876543")))
        assertEquals(PinWeakness.COMMON, PinPolicy.weakness(pin("121212")))
        assertEquals(PinWeakness.COMMON, PinPolicy.weakness(pin("123123")))
        assertEquals(PinWeakness.MALFORMED, PinPolicy.weakness(pin("12345")))
        assertEquals(PinWeakness.MALFORMED, PinPolicy.weakness(pin("12345a")))
        assertNull(PinPolicy.weakness(pin("493827")))
        assertNull(PinPolicy.weakness(pin("708194")))
    }

    @Test fun waitsGrowAndTheTenthWrongPinErases() {
        assertEquals(0L, PinLockout.delayAfter(1)); assertEquals(0L, PinLockout.delayAfter(4))
        assertEquals(30_000L, PinLockout.delayAfter(5))
        assertTrue(PinLockout.delayAfter(9) > PinLockout.delayAfter(8) && PinLockout.delayAfter(8) > PinLockout.delayAfter(7))
        assertFalse(PinLockout.shouldWipe(9)); assertTrue(PinLockout.shouldWipe(10))
        assertEquals(0, PinLockout.attemptsLeft(10)); assertEquals(6, PinLockout.attemptsLeft(4))
    }

    @Test fun aWaitCountsDownAndARestartGivesItBackWhole() {
        assertEquals(20_000L, PinLockout.remaining(30_000, 1_000, 11_000, sameBoot = true))
        assertEquals(0L, PinLockout.remaining(30_000, 1_000, 99_000, sameBoot = true))
        assertEquals("A reboot must not shorten the wait", 30_000L, PinLockout.remaining(30_000, 1_000, 11_000, sameBoot = false))
        assertEquals("The clock starting again from zero is a reboot too", 30_000L, PinLockout.remaining(30_000, 500_000, 3_000, sameBoot = true))
        assertEquals(0L, PinLockout.remaining(0, 0, 10, sameBoot = true))
    }

    @Test fun aStorageFailureReturnsTheAttemptAndAWrongPinKeepsIt() {
        val broken = FakeAttempts(4)
        val unread = guessPin(broken, { throw GeneralSecurityException("key") }, {}, {})
        assertTrue(unread is UnlockResult.Unavailable)
        assertEquals("A failed read must not move the student toward the wipe", 4, broken.failures)
        val wrong = FakeAttempts(1)
        val refused = guessPin(wrong, { throw WrongPinException() }, {}, {})
        assertTrue(refused is UnlockResult.WrongPin)
        assertEquals(2, wrong.failures)
        assertEquals(0L, wrong.waitMs)
        val ninth = FakeAttempts(8)
        assertTrue(guessPin(ninth, { throw WrongPinException() }, {}, { ninth.clearAttempts() }) is UnlockResult.WrongPin)
        assertEquals(30 * 60_000L, ninth.waitMs)
        var wiped = false
        val tenth = FakeAttempts(9)
        assertEquals(UnlockResult.Wiped, guessPin(tenth, { throw WrongPinException() }, {}, { wiped = true; tenth.clearAttempts() }))
        assertTrue(wiped)
        val opened = FakeAttempts(3)
        val cancelled = kotlin.runCatching {
            guessPin(opened, { account }, { throw kotlinx.coroutines.CancellationException("write") }, {})
        }
        assertTrue(cancelled.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals("A right PIN whose follow-up write was cancelled spends nothing", 0, opened.failures)
    }

    @Test fun aFailedReadGivesTheAttemptBackAndAWrongPinKeepsIt() {
        assertEquals(5, PinLockout.retained(5, wrongPin = true))
        assertEquals(4, PinLockout.retained(5, wrongPin = false))
        assertEquals(0, PinLockout.retained(0, wrongPin = false))
    }

    @Test fun anOpenSnapshotDoesNotClearALockTheGateAlreadyLatched() {
        assertTrue(AppLockGate.reconcile(enabled = true, viewModelLocked = false, latched = true, justUnlocked = false))
        assertFalse("The student just unlocked", AppLockGate.reconcile(enabled = true, viewModelLocked = false, latched = true, justUnlocked = true))
        assertTrue(AppLockGate.reconcile(enabled = true, viewModelLocked = true, latched = false, justUnlocked = false))
        assertFalse(AppLockGate.reconcile(enabled = false, viewModelLocked = true, latched = true, justUnlocked = false))
        assertFalse(AppLockGate.reconcile(enabled = true, viewModelLocked = false, latched = false, justUnlocked = false))
    }

    @Test fun aScreenOpenedOverThePinStaysLockedAndAShortBackgroundTripDoesNot() {
        assertTrue(AppLockGate.locks(armed = true, locked = true, alreadyVisible = 1, stoppedAtElapsed = 0, nowElapsed = 10_000, autoLockMs = 60_000))
        assertFalse(AppLockGate.locks(armed = false, locked = true, alreadyVisible = 0, stoppedAtElapsed = 0, nowElapsed = 10_000, autoLockMs = 60_000))
        assertFalse("Another visible screen is not a return from the background",
            AppLockGate.locks(armed = true, locked = false, alreadyVisible = 1, stoppedAtElapsed = 1_000, nowElapsed = 90_000, autoLockMs = 60_000))
        assertFalse(AppLockGate.locks(armed = true, locked = false, alreadyVisible = 0, stoppedAtElapsed = 0, nowElapsed = 10_000, autoLockMs = 60_000))
        assertFalse(AppLockGate.locks(armed = true, locked = false, alreadyVisible = 0, stoppedAtElapsed = 1_000, nowElapsed = 60_999, autoLockMs = 60_000))
        assertTrue(AppLockGate.locks(armed = true, locked = false, alreadyVisible = 0, stoppedAtElapsed = 1_000, nowElapsed = 61_000, autoLockMs = 60_000))
    }

    private class FakeAttempts(override var failures: Int) : AttemptLedger {
        var waitMs = 0L
        override fun remainingWait() = 0L
        override fun startWait(durationMs: Long) { waitMs = durationMs }
        override fun clearAttempts() { failures = 0; waitMs = 0 }
    }

    @Test fun onlyKnownAutoLockTimesAreKept() {
        assertEquals(15_000L, AutoLock.normalize(15_000))
        assertEquals(AutoLock.DEFAULT, AutoLock.normalize(123))
    }
}
