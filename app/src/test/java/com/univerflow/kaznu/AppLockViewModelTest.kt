package com.univerflow.kaznu

import androidx.lifecycle.ViewModelStore
import com.univerflow.kaznu.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** What the ViewModel keeps in memory around the PIN: nothing of the account while the app is locked. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val account = Credentials("fixture-user", "fixture-password")

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    /** A repository where the account is readable only through the right PIN, like the real protected record. */
    private class ProtectedRepository(val account: Credentials, val pin: String, var enabled: Boolean) : SessionRepository {
        var failures = 0
        var locked = 0
        var erased = false
        override suspend fun load() = StoredSession(if (enabled) null else account, lock = LockSettings(enabled = enabled))
        override suspend fun saveCredentials(credentials: Credentials) {}
        override suspend fun deleteCredentials() { enabled = false; erased = true }
        override suspend fun setAutoLoginPaused(paused: Boolean) {}
        override suspend fun setLanguage(language: AppLanguage) {}
        override suspend fun setMobileLayout(enabled: Boolean) {}
        override suspend fun setTextZoom(percent: Int) {}
        override suspend fun setAnimations(enabled: Boolean) {}
        override suspend fun setTheme(theme: AppTheme) {}
        override suspend fun setShortcuts(shortcuts: HomeShortcuts) {}
        override suspend fun enableLock(credentials: Credentials, pin: CharArray) { enabled = true }
        override suspend fun unlockWithPin(pin: CharArray): UnlockResult =
            if (String(pin) == this.pin) { failures = 0; UnlockResult.Success(account) }
            else if (++failures >= PinLockout.WIPE_AT) { enabled = false; erased = true; UnlockResult.Wiped }
            else UnlockResult.WrongPin(PinLockout.attemptsLeft(failures), 0)
        override suspend fun disableLock(pin: CharArray): UnlockResult {
            val result = unlockWithPin(pin)
            if (result is UnlockResult.Success) enabled = false
            return result
        }
        var onLock: suspend () -> Unit = {}
        override suspend fun lockNow() { locked++; onLock() }
    }

    private fun model(repository: SessionRepository) = UniverViewModel(repository).also { store.put("session", it) }

    @Test fun aProtectedAccountStartsLockedWithNothingInMemory() = runTest(dispatcher) {
        val model = model(ProtectedRepository(account, "493827", enabled = true))
        advanceUntilIdle()
        assertTrue(model.state.value.locked)
        assertTrue("The account exists, so the app shows its lock, not the sign-in form", model.state.value.hasCredentials)
        assertTrue(model.state.value.lock.enabled)
        assertNull("No password in memory before the PIN", model.credentials)
    }

    @Test fun aWrongPinOpensNothingAndTheRightOneOpensTheAccount() = runTest(dispatcher) {
        val model = model(ProtectedRepository(account, "493827", enabled = true))
        advanceUntilIdle()
        assertTrue(model.unlockWithPin("111222".toCharArray()) is UnlockResult.WrongPin)
        assertTrue(model.state.value.locked); assertNull(model.credentials)
        assertTrue(model.unlockWithPin("493827".toCharArray()) is UnlockResult.Success)
        assertFalse(model.state.value.locked)
        assertEquals("fixture-user", model.credentials!!.login)
    }

    @Test fun lockingDropsTheAccountFromMemoryAndAskedForThePinAgain() = runTest(dispatcher) {
        val repository = ProtectedRepository(account, "493827", enabled = true)
        val model = model(repository)
        advanceUntilIdle()
        model.unlockWithPin("493827".toCharArray())
        model.lockNow()
        assertNull(model.credentials)
        assertTrue(model.state.value.locked)
        assertEquals("The repository erased its key too", 1, repository.locked)
        model.lockNow()
        assertEquals("Locking twice does nothing more", 1, repository.locked)
    }

    @Test fun lockingHidesTheAccountBeforeTheVaultFinishes() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repository = ProtectedRepository(account, "493827", enabled = true)
        repository.onLock = { gate.await() }
        val model = model(repository)
        advanceUntilIdle()
        assertTrue(model.unlockWithPin("493827".toCharArray()) is UnlockResult.Success)
        val locking = launch { model.lockNow() }
        advanceUntilIdle()
        assertNull("The password is already gone while the vault write is still running", model.credentials)
        assertTrue(model.state.value.locked)
        assertEquals(1, repository.locked)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(locking.isCompleted)
    }

    @Test fun withoutAPinTheAppNeverLocksAndKeepsItsOldBehaviour() = runTest(dispatcher) {
        val repository = ProtectedRepository(account, "493827", enabled = false)
        val model = model(repository)
        advanceUntilIdle()
        assertFalse(model.state.value.locked)
        assertEquals("fixture-user", model.credentials!!.login)
        model.lockNow()
        assertFalse(model.state.value.locked)
        assertNotNull(model.credentials)
        assertEquals(0, repository.locked)
    }

    @Test fun tenWrongPinsEraseTheAccountAndSendTheStudentToSignIn() = runTest(dispatcher) {
        val repository = ProtectedRepository(account, "493827", enabled = true)
        val model = model(repository)
        advanceUntilIdle()
        repeat(9) { assertTrue(model.unlockWithPin("111222".toCharArray()) is UnlockResult.WrongPin) }
        assertEquals(UnlockResult.Wiped, model.unlockWithPin("111222".toCharArray()))
        assertTrue(repository.erased)
        assertFalse(model.state.value.hasCredentials)
        assertFalse(model.state.value.locked)
        assertFalse(model.state.value.lock.enabled)
        assertNull(model.credentials)
        assertTrue("The site session is wiped too", model.sessionClearPending)
        assertTrue(model.guard.blocked)
    }

    @Test fun aPinCanBeSetOnlyForAnAccountThatIsOpenAndTurnedOffOnlyWithThePin() = runTest(dispatcher) {
        val repository = ProtectedRepository(account, "493827", enabled = false)
        val model = model(repository)
        advanceUntilIdle()
        model.enableLock("493827".toCharArray())
        assertTrue(model.state.value.lock.enabled)
        assertTrue("Setting the PIN answers the offer", model.state.value.lock.offerHandled)
        assertFalse(model.state.value.locked)
        assertTrue(model.disableLock("000000".toCharArray()) is UnlockResult.WrongPin)
        assertTrue(model.state.value.lock.enabled)
        assertTrue(model.disableLock("493827".toCharArray()) is UnlockResult.Success)
        assertFalse(model.state.value.lock.enabled)
    }

    @Test fun enteringAPinForAnAccountThatIsNotOpenIsRefused() = runTest(dispatcher) {
        val model = model(ProtectedRepository(account, "493827", enabled = true))
        advanceUntilIdle()
        val failure = runCatching { model.enableLock("708194".toCharArray()) }.exceptionOrNull()
        assertNotNull("No account in memory: there is nothing to protect", failure)
    }

    @Test fun removingTheSavedAccountRemovesTheLockWithIt() = runTest(dispatcher) {
        val model = model(ProtectedRepository(account, "493827", enabled = true))
        advanceUntilIdle()
        model.unlockWithPin("493827".toCharArray())
        model.endSession(delete = true)
        assertFalse(model.state.value.hasCredentials)
        assertFalse(model.state.value.lock.enabled)
        assertFalse(model.state.value.locked)
    }

    @Test fun theOfferIsRemembered() = runTest(dispatcher) {
        val model = model(ProtectedRepository(account, "493827", enabled = false))
        advanceUntilIdle()
        assertFalse(model.state.value.lock.offerHandled)
        model.markLockOfferHandled()
        assertTrue(model.state.value.lock.offerHandled)
    }
}
