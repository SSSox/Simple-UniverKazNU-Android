package com.univerflow.kaznu.data

import android.os.SystemClock
import android.security.keystore.KeyPermanentlyInvalidatedException
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.domain.AppTheme
import com.univerflow.kaznu.domain.StartPage
import com.univerflow.kaznu.domain.Credentials
import com.univerflow.kaznu.domain.HomeShortcuts
import com.univerflow.kaznu.domain.SiteNotice
import com.univerflow.kaznu.domain.LockSettings
import com.univerflow.kaznu.domain.PinLockout
import com.univerflow.kaznu.domain.PinPolicy
import com.univerflow.kaznu.domain.ReminderSettings
import com.univerflow.kaznu.domain.SessionRepository
import com.univerflow.kaznu.domain.StoredSession
import com.univerflow.kaznu.domain.UnlockResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.crypto.Cipher

/**
 * The one storage boundary for the encrypted records and preferences. With a PIN the account lives only in
 * [lockVault], so nothing in this process holds the password until the student unlocks it.
 */
class LocalSessionRepository(
    private val vault: CredentialVault,
    private val preferences: AppPreferences,
    private val lockVault: LockVault? = null,
    private val lockPreferences: LockPreferences? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
) : SessionRepository {
    private val storage = Mutex()

    private suspend fun <T> access(action: () -> T): T = storage.withLock { committedOn(ioDispatcher, action) }

    override suspend fun load(): StoredSession = access {
        var storageError = false
        val locked = lockVault?.exists() == true
        val credentials = if (locked || preferences.manualLogin) null else try { vault.read() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { storageError = true; null }
        val lock = LockSettings(locked, locked && lockVault?.biometricEnrolled() == true,
            lockPreferences?.autoLockMs ?: LockSettings().autoLockMs, lockPreferences?.offerHandled ?: false)
        StoredSession(credentials, preferences.language, preferences.autoLoginPaused,
            storageError, preferences.mobileLayout, preferences.textZoom, preferences.animations, preferences.shortcuts, lock, preferences.theme, preferences.notices,
            preferences.sessionClearPending, preferences.manualLogin, externalLinks = preferences.externalLinks,
            reminders = preferences.reminders, gradeAlerts = preferences.gradeAlerts, startPage = preferences.startPage)
    }

    override suspend fun saveCredentials(credentials: Credentials) = access {
        if (lockVault?.exists() == true) lockVault.saveCredentials(credentials) else vault.save(credentials)
        preferences.accountSaved()
    }

    override suspend fun deleteCredentials() = access { eraseAccount() }
    override suspend fun beginSessionClear() = access { preferences.beginSessionClear() }
    override suspend fun finishSessionClear() = access { preferences.finishSessionClear() }
    override suspend fun setManualLogin(enabled: Boolean) = access { preferences.manualLogin = enabled }

    /** Removes the account everywhere, the lock with it: a PIN without an account protects nothing. */
    private fun eraseAccount() {
        preferences.beginSessionClear()
        vault.delete()
        lockVault?.delete()
        lockPreferences?.resetKeepingOffer()
        preferences.autoLoginPaused = true
    }

    // App lock.

    override suspend fun enableLock(credentials: Credentials, pin: CharArray) = access {
        val lock = lockVault ?: throw UnsupportedOperationException()
        require(PinPolicy.weakness(pin) == null)
        lock.create(credentials, pin)
        // Only now that the protected copy opens, the unprotected one and its key are removed.
        vault.delete()
        lockPreferences?.clearAttempts()
        Unit
    }

    /** One guess. The counter rules live in [guessPin], which the unit test drives without Keystore. */
    private fun guess(pin: CharArray, onRight: (Credentials) -> Unit): UnlockResult {
        val lock = lockVault ?: return UnlockResult.Unavailable
        val prefs = lockPreferences ?: return UnlockResult.Unavailable
        return guessPin(PreferenceAttempts(prefs, elapsed), { lock.unlockWithPin(pin) }, { credentials ->
            // An unprotected copy left by an interrupted migration goes first: [onRight] may write a fresh one.
            try { vault.delete() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
            onRight(credentials)
        }, ::eraseAccount)
    }

    override suspend fun unlockWithPin(pin: CharArray): UnlockResult = access { guess(pin) { } }

    override suspend fun changePin(oldPin: CharArray, newPin: CharArray): UnlockResult = access {
        require(PinPolicy.weakness(newPin) == null)
        guess(oldPin) { lockVault?.changePin(newPin) }
    }

    override suspend fun disableLock(pin: CharArray): UnlockResult = access {
        guess(pin) { credentials ->
            // The plain record is written before the protected one goes, so an interruption never loses the account.
            vault.save(credentials)
            lockVault?.delete()
            lockPreferences?.resetKeepingOffer()
        }
    }

    override suspend fun lockNow() = access { lockVault?.lock() ?: Unit }

    override suspend fun setAutoLockMs(milliseconds: Long) = access { lockPreferences?.autoLockMs = milliseconds }
    override suspend fun markLockOfferHandled() = access { lockPreferences?.offerHandled = true }

    override suspend fun biometricUnlockCipher(): Cipher? = access {
        try { lockVault?.takeIf { it.biometricEnrolled() }?.biometricUnlockCipher() }
        catch (_: KeyPermanentlyInvalidatedException) { lockVault?.removeBiometric(); null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    override suspend fun unlockWithBiometric(cipher: Cipher): UnlockResult = access {
        val lock = lockVault ?: return@access UnlockResult.Unavailable
        try {
            val credentials = lock.unlockWithBiometric(cipher)
            lockPreferences?.clearAttempts()
            UnlockResult.Success(credentials)
        } catch (_: KeyPermanentlyInvalidatedException) { lock.removeBiometric(); UnlockResult.BiometricInvalid }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { UnlockResult.Unavailable }
    }

    override suspend fun biometricEnrollCipher(): Cipher = access { (lockVault ?: throw UnsupportedOperationException()).biometricEnrollCipher() }
    override suspend fun finishBiometric(cipher: Cipher) = access { (lockVault ?: throw UnsupportedOperationException()).finishBiometric(cipher) }
    override suspend fun removeBiometric() = access { lockVault?.removeBiometric() ?: Unit }

    override suspend fun setAutoLoginPaused(paused: Boolean) = access { preferences.autoLoginPaused = paused }
    override suspend fun setLanguage(language: AppLanguage) = access { preferences.language = language }
    override suspend fun setMobileLayout(enabled: Boolean) = access { preferences.mobileLayout = enabled }
    override suspend fun setTextZoom(percent: Int) = access { preferences.textZoom = percent }
    override suspend fun setAnimations(enabled: Boolean) = access { preferences.animations = enabled }
    override suspend fun setExternalLinks(enabled: Boolean) = access { preferences.externalLinks = enabled }
    override suspend fun setReminders(settings: ReminderSettings) = access { preferences.reminders = settings }
    override suspend fun setGradeAlerts(enabled: Boolean) = access { preferences.gradeAlerts = enabled }
    override suspend fun setTheme(theme: AppTheme) = access { preferences.theme = theme }
    override suspend fun setStartPage(page: StartPage) = access { preferences.startPage = page }
    override suspend fun setShortcuts(shortcuts: HomeShortcuts) = access { preferences.shortcuts = shortcuts }
    override suspend fun setNotices(notices: List<SiteNotice>) = access { preferences.notices = notices }
}

/** The wrong-PIN counter. No Android types, so a unit test can spend and return an attempt. */
internal interface AttemptLedger {
    var failures: Int
    fun remainingWait(): Long
    fun startWait(durationMs: Long)
    fun clearAttempts()
}

private class PreferenceAttempts(private val preferences: LockPreferences, private val elapsed: () -> Long) : AttemptLedger {
    override var failures: Int
        get() = preferences.failures
        set(value) { preferences.failures = value }
    override fun remainingWait() = preferences.remainingWait(elapsed())
    override fun startWait(durationMs: Long) = preferences.startWait(durationMs, elapsed())
    override fun clearAttempts() = preferences.clearAttempts()
}

/**
 * One guess. The counter is stored first, so killing the app cannot take a guess back. A right PIN clears it.
 * A wrong PIN keeps it; the tenth erases the account. A record that cannot be read is not a wrong PIN.
 */
internal fun guessPin(
    ledger: AttemptLedger,
    unlock: () -> Credentials,
    afterOpen: (Credentials) -> Unit,
    onWipe: () -> Unit,
): UnlockResult {
    val wait = ledger.remainingWait()
    if (wait > 0) return UnlockResult.WaitRequired(wait)
    val failures = ledger.failures + 1
    ledger.failures = failures
    var opened = false
    return try {
        val credentials = unlock()
        opened = true
        afterOpen(credentials)
        ledger.clearAttempts()
        UnlockResult.Success(credentials)
    } catch (_: WrongPinException) {
        if (PinLockout.shouldWipe(failures)) {
            onWipe()
            UnlockResult.Wiped
        } else {
            val delay = PinLockout.delayAfter(failures)
            if (delay > 0) ledger.startWait(delay)
            UnlockResult.WrongPin(PinLockout.attemptsLeft(failures), delay)
        }
    } catch (cancelled: CancellationException) {
        // The PIN was right. A cancelled follow-up write must not spend one of the ten attempts.
        if (opened) ledger.clearAttempts() else ledger.failures = PinLockout.retained(failures, wrongPin = false)
        throw cancelled
    } catch (_: Exception) {
        // The PIN was not checked (the record or its key failed). Give the attempt back.
        if (opened) ledger.clearAttempts() else ledger.failures = PinLockout.retained(failures, wrongPin = false)
        UnlockResult.Unavailable
    }
}

/**
 * Runs [action] on [dispatcher] and hands its result back even if the caller is cancelled meanwhile. A cancelled
 * caller still never starts the action (no PIN is guessed, no PBKDF2 runs); but once the action has run (a guess
 * counted, the account wiped, the PIN changed), plain withContext would discard that answer on the way back to the
 * caller's dispatcher, and the caller could never bring memory and state in line with what was written.
 */
internal suspend fun <T> committedOn(dispatcher: CoroutineDispatcher, action: () -> T): T {
    val caller = currentCoroutineContext().job
    // The job that asked for an unlock check: once it is cancelling, no guess starts, even before its cancellation
    // has reached [caller].
    val asker = currentCoroutineContext()[VerificationAsker]?.job
    return withContext(NonCancellable) {
        withContext(dispatcher) {
            asker?.ensureActive()
            caller.ensureActive()
            action()
        }
    }
}

/** Marks a vault call made for [job]'s unlock check; [committedOn] checks [job] right before the action starts. */
internal class VerificationAsker(val job: kotlinx.coroutines.Job) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<VerificationAsker>
}
