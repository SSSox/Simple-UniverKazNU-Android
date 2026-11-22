package com.univerflow.kaznu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.domain.AppTheme
import com.univerflow.kaznu.domain.StartPage
import com.univerflow.kaznu.domain.Credentials
import com.univerflow.kaznu.domain.HomeShortcuts
import com.univerflow.kaznu.domain.LockSettings
import com.univerflow.kaznu.domain.NoticeScan
import com.univerflow.kaznu.domain.ReminderSettings
import com.univerflow.kaznu.domain.Notices
import com.univerflow.kaznu.domain.SiteNotice
import com.univerflow.kaznu.domain.ShortcutRequest
import com.univerflow.kaznu.domain.Shortcuts
import com.univerflow.kaznu.domain.SessionRepository
import com.univerflow.kaznu.domain.TextZoom
import com.univerflow.kaznu.domain.UnlockResult
import com.univerflow.kaznu.web.RecoveryBackoff
import com.univerflow.kaznu.web.SessionGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AppState(val ready: Boolean = false, val language: AppLanguage = AppLanguage.RUSSIAN,
    val hasCredentials: Boolean = false, val paused: Boolean = false, val storageError: Boolean = false,
    val mobileLayout: Boolean = true, val textZoom: Int = TextZoom.DEFAULT, val animations: Boolean = true,
    val shortcuts: HomeShortcuts = HomeShortcuts(), val lock: LockSettings = LockSettings(),
    /** The protected account exists but the PIN (or biometrics) has not opened it in this run: nothing is loaded or shown. */
    val locked: Boolean = false,
    val theme: AppTheme = AppTheme.SYSTEM,
    val startPage: StartPage = StartPage.NEWS,
    val notices: List<SiteNotice> = emptyList(),
    val manualLogin: Boolean = false,
    /** Links to other sites open in the system browser, not in the app's link window. */
    val externalLinks: Boolean = false,
    /** «Напоминать о парах» and how long before a class. */
    val reminders: ReminderSettings = ReminderSettings(),
    /** «Следить за оценками в фоне». */
    val gradeAlerts: Boolean = false) {
    val canBrowse: Boolean get() = hasCredentials || manualLogin
}

class UniverViewModel(private val repository: SessionRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(AppState())
    val state = mutableState.asStateFlow()
    var credentials: Credentials? = null
        private set
    // Univer answers the login POST and the AJAX re-authentication after 20-30 s; waiting 60 s keeps a
    // normal slow answer from being reported as a failure.
    val guard = SessionGuard(loginTimeout = 60_000, reauthenticationTimeout = 60_000)
    // Observed live: Univer answers the Moodle entry's login POST after 20–30 s. The service window
    // itself gives up after 90 s, so the submit is not called stalled before 80 s.
    val singleSignOnGuard = SessionGuard(loginTimeout = 80_000, reauthenticationTimeout = 80_000)
    /**
     * The site session must still be wiped after an accepted logout or credential change; the Activity clears it once
     * done. The storage marker also survives process death, before the next screen opens the site.
     */
    var sessionClearPending = false
        private set
    /** Ends with every logout, wipe and account change: a notification read started before it is discarded. */
    var noticeSession = 0
        private set
    /** The stored list still holds a previous account's notifications because clearing it failed. */
    private var noticesClearPending = false
    /** In memory only: the section to reopen after the next automatic sign-in of the main window. */
    val resumeTarget = com.univerflow.kaznu.web.ResumeTarget()
    /** Automatic reloads of a page that failed to load, and recreations of a screen whose renderer was lost. */
    val pageRecovery = RecoveryBackoff()
    val rendererRecovery = RecoveryBackoff(longArrayOf(1_000, 5_000, 30_000, 120_000, 300_000))
    private val changes = Mutex()
    private var languageVersion = 0L
    private var persistedLanguage = AppLanguage.RUSSIAN

    private val initialization = viewModelScope.async {
        try {
            val loaded = repository.load()
            credentials = loaded.credentials
            sessionClearPending = loaded.sessionClearPending
            persistedLanguage = loaded.language
            mutableState.value = AppState(true, loaded.language, loaded.credentials != null || loaded.lock.enabled, loaded.paused,
                loaded.storageError, loaded.mobileLayout, loaded.textZoom, loaded.animations, loaded.shortcuts,
                loaded.lock, locked = loaded.lock.enabled, theme = loaded.theme, notices = loaded.notices, manualLogin = loaded.manualLogin,
                externalLinks = loaded.externalLinks, reminders = loaded.reminders, gradeAlerts = loaded.gradeAlerts, startPage = loaded.startPage)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            guard.stop()
            mutableState.update { it.copy(ready = true, paused = true, storageError = true) }
        }
    }

    // Activity recreation cancels the waiter, not an accepted settings operation. All work still
    // belongs to the ViewModel and is cancelled when the ViewModel itself is cleared.
    private suspend fun <T> retained(action: suspend () -> T): T =
        viewModelScope.async(start = CoroutineStart.UNDISPATCHED) {
            withContext(Dispatchers.Main.immediate) {
                ensureActive()
                initialization.await()
                action()
            }
        }.await()

    // Like [retained]: an accepted save/logout finishes even when the Activity that asked is recreated.
    private suspend fun <T> sessionChange(action: suspend () -> T): T =
        viewModelScope.async(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
            initialization.await()
            changes.withLock { action() }
        }.await()

    suspend fun save(login: String, password: String) = sessionChange {
        val next = Credentials(login.trim(), password)
        require(next.login.isNotEmpty() && next.password.isNotEmpty())
        val previous = credentials
        val changed = previous == null || previous.login != next.login || previous.password != next.password
        if (changed) {
            repository.beginSessionClear()
            sessionClearPending = true
        }
        repository.saveCredentials(next)
        credentials = next
        if (changed) { guard.reset(); singleSignOnGuard.reset() } else { guard.retry(); singleSignOnGuard.retry() }
        mutableState.update { it.copy(hasCredentials = true, paused = false, storageError = false, manualLogin = false) }
        // Another account's notifications are not this one's.
        if (previous?.login != next.login) {
            noticeSession++
            if (mutableState.value.notices.isNotEmpty() || noticesClearPending) clearNotices()
        } else if (noticesClearPending) clearNotices() // a logout whose clear failed: retry it now
    }

    suspend fun setLanguage(language: AppLanguage) = retained {
        val version = ++languageVersion
        mutableState.update { it.copy(language = language) }
        try {
            changes.withLock {
                repository.setLanguage(language)
                persistedLanguage = language
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            // Only the latest request can roll back, and only to a successfully saved value.
            if (version == languageVersion) mutableState.update { it.copy(language = persistedLanguage) }
            throw failure
        }
    }

    suspend fun pauseAutoLogin() = sessionChange {
        guard.stop()
        singleSignOnGuard.stop()
        repository.setAutoLoginPaused(true)
        mutableState.update { it.copy(paused = true) }
    }

    /** Opens the real site's login without asking the vault for a password. The browser keeps its normal session. */
    suspend fun startManualLogin() = sessionChange {
        check(!state.value.hasCredentials && !state.value.lock.enabled && !sessionClearPending)
        repository.setManualLogin(true)
        guard.stop(); singleSignOnGuard.stop()
        mutableState.update { it.copy(manualLogin = true, paused = false, storageError = false) }
    }

    /** Logout (or removal of the saved account) as one accepted transaction; the caller then wipes the session. */
    suspend fun endSession(delete: Boolean) = sessionChange {
        guard.stop()
        singleSignOnGuard.stop()
        repository.beginSessionClear()
        sessionClearPending = true
        resumeTarget.url = null; resumeTarget.pending = false
        mutableState.update { it.copy(paused = true, manualLogin = false) }
        clearNotices()
        if (delete) {
            repository.deleteCredentials()
            credentials = null
            // The PIN protected only this account: it goes with it.
            mutableState.update { it.copy(hasCredentials = false, paused = true, storageError = false, locked = false,
                lock = LockSettings(offerHandled = it.lock.offerHandled, autoLockMs = it.lock.autoLockMs)) }
        }
    }

    suspend fun sessionCleared() = sessionChange {
        repository.finishSessionClear()
        sessionClearPending = false
    }

    suspend fun setMobileLayout(enabled: Boolean) = retained {
        changes.withLock {
            repository.setMobileLayout(enabled)
            mutableState.update { it.copy(mobileLayout = enabled) }
        }
    }

    suspend fun setTextZoom(percent: Int) = retained {
        val value = TextZoom.clamp(percent)
        changes.withLock {
            repository.setTextZoom(value)
            mutableState.update { it.copy(textZoom = value) }
        }
    }

    suspend fun setAnimations(enabled: Boolean) = retained {
        changes.withLock {
            repository.setAnimations(enabled)
            mutableState.update { it.copy(animations = enabled) }
        }
    }

    suspend fun setExternalLinks(enabled: Boolean) = retained {
        changes.withLock {
            repository.setExternalLinks(enabled)
            mutableState.update { it.copy(externalLinks = enabled) }
        }
    }

    suspend fun setStartPage(page: StartPage) = retained {
        changes.withLock {
            repository.setStartPage(page)
            mutableState.update { it.copy(startPage = page) }
        }
    }

    suspend fun setReminders(settings: ReminderSettings) = retained {
        changes.withLock {
            repository.setReminders(settings)
            mutableState.update { it.copy(reminders = settings) }
        }
    }

    suspend fun setGradeAlerts(enabled: Boolean) = retained {
        changes.withLock {
            repository.setGradeAlerts(enabled)
            mutableState.update { it.copy(gradeAlerts = enabled) }
        }
    }

    /** Stores the theme; the screen applies it (recreating itself) only after it is saved. */
    suspend fun setTheme(theme: AppTheme) = retained {
        changes.withLock {
            repository.setTheme(theme)
            mutableState.update { it.copy(theme = theme) }
        }
    }

    /** Applies a validated pin or tile-order request; false when it is rejected (malformed or over the limit). */
    suspend fun updateShortcuts(request: ShortcutRequest): Boolean = retained {
        changes.withLock {
            val next = Shortcuts.apply(mutableState.value.shortcuts, request) ?: return@withLock false
            if (next != mutableState.value.shortcuts) {
                repository.setShortcuts(next)
                mutableState.update { it.copy(shortcuts = next) }
            }
            true
        }
    }

    /**
     * Applies a page read and returns the notifications that became unread. Ignored while signed out, paused or locked,
     * and when [session] has ended: a read from a previous session or account must not refill the list.
     */
    suspend fun applyNoticeScan(scan: NoticeScan, session: Int = noticeSession): List<SiteNotice> = retained {
        changes.withLock {
            val state = mutableState.value
            if (session != noticeSession || !state.canBrowse || state.paused || state.locked) return@withLock emptyList()
            val next = Notices.merge(state.notices, scan, System.currentTimeMillis())
            if (next == state.notices && !noticesClearPending) return@withLock emptyList()
            repository.setNotices(next)
            noticesClearPending = false
            mutableState.update { it.copy(notices = next) }
            Notices.newlyUnread(state.notices, next)
        }
    }

    suspend fun markNoticeRead(key: String) = updateNotices { Notices.markRead(it, key) }

    suspend fun markAllNoticesRead() = updateNotices(Notices::markAllRead)

    private suspend fun updateNotices(change: (List<SiteNotice>) -> List<SiteNotice>) = retained {
        changes.withLock {
            val next = change(mutableState.value.notices)
            if (next != mutableState.value.notices || noticesClearPending) {
                repository.setNotices(next)
                noticesClearPending = false
                mutableState.update { it.copy(notices = next) }
            }
        }
    }

    /**
     * Inside a session change (the lock is held): ends the [noticeSession] and empties the list. A failed write must
     * not fail the logout or account change, so it leaves an empty list on screen and is retried on the next write.
     */
    private suspend fun clearNotices() {
        noticeSession++
        mutableState.update { it.copy(notices = emptyList()) }
        noticesClearPending = try { repository.setNotices(emptyList()); false }
        catch (cancelled: CancellationException) { noticesClearPending = true; throw cancelled }
        catch (_: Exception) { true }
    }

    /**
     * Loading the site again. A student who signed out stays signed out through Retry, Refresh and a restored page:
     * only their own "Sign in" ([signIn]) ends that pause. A stop is lifted so sign-in can try again after a failure,
     * but the attempt limits keep counting (SessionGuard.retry): a wrong saved password is not resent on every press.
     */
    suspend fun retrySignIn(signIn: Boolean) { if (signIn || !state.value.paused) resumeAutoLogin() }

    suspend fun resumeAutoLogin() = sessionChange {
        repository.setAutoLoginPaused(false)
        guard.retry()
        singleSignOnGuard.retry()
        pageRecovery.reset(); rendererRecovery.reset()
        mutableState.update { it.copy(paused = false) }
    }

    // App lock: with it enabled, [credentials] stays null until a PIN or biometrics opens it, and again after each lock.

    private suspend fun wiped() {
        clearNotices()
        guard.stop()
        singleSignOnGuard.stop()
        sessionClearPending = true
        resumeTarget.url = null; resumeTarget.pending = false
        credentials = null
        mutableState.update { it.copy(hasCredentials = false, paused = true, storageError = false, locked = false,
            lock = LockSettings(offerHandled = it.lock.offerHandled, autoLockMs = it.lock.autoLockMs)) }
    }

    private suspend fun handle(result: UnlockResult): UnlockResult {
        when (result) {
            is UnlockResult.Success -> {
                credentials = result.credentials
                mutableState.update { it.copy(locked = false) }
            }
            UnlockResult.Wiped -> wiped()
            UnlockResult.BiometricInvalid -> mutableState.update { it.copy(lock = it.lock.copy(biometric = false)) }
            else -> Unit
        }
        return result
    }

    /**
     * The vault reads private copies of the entered digits, taken before anything suspends and erased once the change
     * is over. The change runs in viewModelScope and outlives a recreated screen (rotation, theme or language change),
     * which erases its own arrays when its work is cancelled: reading those, a check would guess, or store, blank digits.
     */
    private suspend fun <T> withDigits(first: CharArray, second: CharArray?, action: suspend (CharArray, CharArray?) -> T): T {
        val one = first.copyOf()
        val two = second?.copyOf()
        return sessionChange { try { action(one, two) } finally { one.fill('\u0000'); two?.fill('\u0000') } }
    }

    suspend fun unlockWithPin(pin: CharArray): UnlockResult = withDigits(pin, null) { digits, _ -> handle(repository.unlockWithPin(digits)) }

    suspend fun unlockWithBiometric(cipher: javax.crypto.Cipher): UnlockResult = sessionChange { handle(repository.unlockWithBiometric(cipher)) }

    /** The cipher for the biometric prompt, or null when biometrics cannot unlock (not enabled, or the phone's biometrics changed). */
    suspend fun biometricUnlockCipher(): javax.crypto.Cipher? = sessionChange {
        val cipher = repository.biometricUnlockCipher()
        if (cipher == null && mutableState.value.lock.biometric) mutableState.update { it.copy(lock = it.lock.copy(biometric = false)) }
        cipher
    }

    /**
     * Locks the app: the password leaves memory. The locked state is published before the vault write, so an "open"
     * snapshot cannot paint the signed-in site while the key is erased.
     */
    suspend fun lockNow() = sessionChange {
        if (!mutableState.value.lock.enabled || mutableState.value.locked) return@sessionChange
        credentials = null
        mutableState.update { it.copy(locked = true) }
        repository.lockNow()
    }

    /** Protects the saved account with [pin]; the account must be unlocked (entered or opened) at this moment. */
    suspend fun enableLock(pin: CharArray) = withDigits(pin, null) { digits, _ ->
        val current = credentials ?: error("No account to protect")
        repository.enableLock(current, digits)
        mutableState.update { it.copy(lock = it.lock.copy(enabled = true, offerHandled = true), locked = false) }
    }

    suspend fun changePin(oldPin: CharArray, newPin: CharArray): UnlockResult =
        withDigits(oldPin, newPin) { old, new -> handle(repository.changePin(old, new!!)) }

    suspend fun disableLock(pin: CharArray): UnlockResult = withDigits(pin, null) { digits, _ ->
        val result = handle(repository.disableLock(digits))
        if (result is UnlockResult.Success) mutableState.update { it.copy(lock = it.lock.copy(enabled = false, biometric = false)) }
        result
    }

    suspend fun biometricEnrollCipher() = sessionChange { repository.biometricEnrollCipher() }
    suspend fun finishBiometric(cipher: javax.crypto.Cipher) = sessionChange {
        repository.finishBiometric(cipher)
        mutableState.update { it.copy(lock = it.lock.copy(biometric = true)) }
    }
    suspend fun removeBiometric() = sessionChange {
        repository.removeBiometric()
        mutableState.update { it.copy(lock = it.lock.copy(biometric = false)) }
    }

    suspend fun setAutoLockMs(milliseconds: Long) = retained {
        changes.withLock {
            repository.setAutoLockMs(milliseconds)
            mutableState.update { it.copy(lock = it.lock.copy(autoLockMs = com.univerflow.kaznu.domain.AutoLock.normalize(milliseconds))) }
        }
    }

    /** The "protect sign-in with a PIN" offer was answered; it is not made again. */
    suspend fun markLockOfferHandled() = retained {
        changes.withLock {
            repository.markLockOfferHandled()
            mutableState.update { it.copy(lock = it.lock.copy(offerHandled = true)) }
        }
    }
}
