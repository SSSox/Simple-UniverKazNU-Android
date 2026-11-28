package com.univerflow.kaznu

import androidx.lifecycle.ViewModelStore
import com.univerflow.kaznu.domain.*
import com.univerflow.kaznu.ui.PinFlowController
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
        override suspend fun beginSessionClear() { setAutoLoginPaused(true) }
        override suspend fun finishSessionClear() { }
        override suspend fun setManualLogin(enabled: Boolean) { }
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
        override suspend fun setExternalLinks(enabled: Boolean) {}
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

    @Test fun aWipeAnsweredJustBeforeCancellationStillClearsTheAccountFromMemoryAndState() = runTest(dispatcher) {
        // The vault answers Wiped, then the work is cancelled (the pad hidden, the screen re-entered) before the
        // follow-up has run; the follow-up suspends on the notice store, where a cancelled coroutine would stop.
        val repository = object : SessionRepository by ProtectedRepository(account, "493827", enabled = true) {
            override suspend fun unlockWithPin(pin: CharArray): UnlockResult {
                currentCoroutineContext().job.cancel()
                return UnlockResult.Wiped
            }
            override suspend fun setNotices(notices: List<SiteNotice>) { yield() }
        }
        val model = model(repository)
        advanceUntilIdle()
        try { model.unlockWithPin("111222".toCharArray()) } catch (_: CancellationException) { }
        advanceUntilIdle()
        assertFalse("The wiped account must not stay in state", model.state.value.hasCredentials)
        assertTrue(model.state.value.paused)
        assertNull(model.credentials)
    }

    /** The repository's own dispatcher, run by hand so a test can act while the vault call waits for it. */
    private class VaultDispatcher : CoroutineDispatcher() {
        val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { tasks += block }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    private val io = VaultDispatcher()

    /** The pad driven by the real controller; the vault call crosses to [io] through [committedOn], as in production. */
    private fun TestScope.padAndVault(asked: suspend () -> Unit = {}, guessed: () -> Unit): Triple<PinFlowController, LifecycleFixesTest.FakePinPad, ProtectedRepository> {
        val base = ProtectedRepository(account, "493827", enabled = true)
        val repository = object : SessionRepository by base {
            override suspend fun unlockWithPin(pin: CharArray): UnlockResult = com.univerflow.kaznu.data.committedOn(io) {
                guessed()
                // The last wrong guess: the vault wipes the account on the IO side.
                base.enabled = false; base.erased = true
                UnlockResult.Wiped
            }
        }
        val model = model(repository)
        val pad = LifecycleFixesTest.FakePinPad()
        var attached = true
        val controller = PinFlowController(
            scope = this, text = { "" }, clock = { testScheduler.currentTime },
            acquirePad = { attached = true; pad }, padActive = { attached }, releasePad = { attached = false },
            moveTaskToBack = { },
        )
        controller.enter(title = R.string.lock_unlock_title, hint = null, closeable = false, biometric = false, forgot = false,
            verify = { asked(); model.unlockWithPin(it) }, onVerified = { it.fill('\u0000') })
        modelUnderTest = model
        return Triple(controller, pad, base)
    }
    private lateinit var modelUnderTest: UniverViewModel

    @Test fun hidingThePadBeforeTheVaultStartsCancelsTheGuess() = runTest(dispatcher) {
        var guesses = 0
        val (controller, pad, base) = padAndVault { guesses++ }
        advanceUntilIdle()
        pad.enter("111222".toCharArray())
        advanceUntilIdle()
        assertTrue("The check has reached the repository and waits for its dispatcher", io.tasks.isNotEmpty())
        controller.hide()
        advanceUntilIdle(); io.runAll(); advanceUntilIdle()
        assertEquals("A dismissed pad must not leave its guess (PBKDF2) to run", 0, guesses)
        assertFalse(base.erased)
        assertTrue(modelUnderTest.state.value.hasCredentials)
        assertTrue(modelUnderTest.state.value.locked)
    }

    @Test fun theVaultDispatcherRunningRightAfterHideStartsNoGuess() = runTest(dispatcher) {
        // hide() cancels the pad's verify job; the vault's dispatcher runs before that job has resumed on main.
        var guesses = 0
        val (controller, pad, base) = padAndVault { guesses++ }
        advanceUntilIdle()
        pad.enter("111222".toCharArray())
        advanceUntilIdle()
        assertTrue(io.tasks.isNotEmpty())
        controller.hide()
        io.runAll()
        advanceUntilIdle(); io.runAll(); advanceUntilIdle()
        assertEquals("No guess may start in the gap before the cancelled waiter resumes", 0, guesses)
        assertFalse(base.erased)
        assertTrue(modelUnderTest.state.value.locked)
    }

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    @Test fun theVaultDispatcherRunningWhileHideIsStillCancellingStartsNoGuess() = runTest(dispatcher) {
        // The vault's dispatcher runs while hide() is cancelling the pad's verify job: after that job is cancelling,
        // before its cancellation has reached the ViewModel's worker.
        var guesses = 0
        var ranMidCancel = false
        val (controller, pad, base) = padAndVault(asked = {
            kotlinx.coroutines.currentCoroutineContext().job.invokeOnCompletion(onCancelling = true) {
                ranMidCancel = io.tasks.isNotEmpty(); io.runAll()
            }
        }) { guesses++ }
        advanceUntilIdle()
        pad.enter("111222".toCharArray())
        advanceUntilIdle()
        assertTrue(io.tasks.isNotEmpty())
        controller.hide()
        assertTrue("The vault's dispatcher ran during the cancellation", ranMidCancel)
        advanceUntilIdle(); io.runAll(); advanceUntilIdle()
        assertEquals("No guess may start once the pad's job is cancelling", 0, guesses)
        assertFalse(base.erased)
        assertTrue(modelUnderTest.state.value.locked)
    }

    @Test fun pinCopiesAreErasedWhenTheCheckIsCancelledBeforeStartUpEnds() = runTest(dispatcher) {
        val loaded = CompletableDeferred<Unit>()
        val base = ProtectedRepository(account, "493827", enabled = true)
        val repository = object : SessionRepository by base {
            override suspend fun load(): StoredSession { loaded.await(); return base.load() }
        }
        val model = model(repository)
        val copies = mutableListOf<CharArray>()
        model.copyDigits = { it.copyOf().also(copies::add) }
        val check = launch { model.unlockWithPin("493827".toCharArray()) }
        advanceUntilIdle()
        assertEquals("The check waits for start-up with its copy", 1, copies.size)
        check.cancel()
        advanceUntilIdle()
        assertTrue("A check cancelled before the vault leaves no digits", copies.all { copy -> copy.all { it == '\u0000' } })
        loaded.complete(Unit)
        advanceUntilIdle()
    }

    @Test fun aGuessTheVaultRanIsFollowedUpEvenWhenThePadIsHiddenMeanwhile() = runTest(dispatcher) {
        lateinit var hide: () -> Unit
        val (controller, pad, base) = padAndVault { hide() }
        hide = { controller.hide() }
        advanceUntilIdle()
        pad.enter("111222".toCharArray())
        advanceUntilIdle()
        io.runAll() // The guess runs, and the pad is hidden while it does.
        advanceUntilIdle()
        assertTrue(base.erased)
        assertFalse("The wiped account must leave state although the pad was hidden mid-guess",
            modelUnderTest.state.value.hasCredentials)
        assertTrue(modelUnderTest.state.value.paused)
        assertNull(modelUnderTest.credentials)
    }

    @Test fun theOfferIsRemembered() = runTest(dispatcher) {
        val model = model(ProtectedRepository(account, "493827", enabled = false))
        advanceUntilIdle()
        assertFalse(model.state.value.lock.offerHandled)
        model.markLockOfferHandled()
        assertTrue(model.state.value.lock.offerHandled)
    }
}
