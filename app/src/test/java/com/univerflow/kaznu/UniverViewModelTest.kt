package com.univerflow.kaznu

import androidx.lifecycle.ViewModelStore
import com.univerflow.kaznu.domain.*
import com.univerflow.kaznu.main.TextSizeDialog
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class UniverViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    private fun model(repository: FakeRepository): UniverViewModel =
        UniverViewModel(repository).also { store.put("session", it) }

    @Test fun startupFailureStopsAutomationAndStillShowsAReadyErrorState() = runTest(dispatcher) {
        val repository = FakeRepository().apply { onLoad = { throw IOException("Fixture storage failure") } }
        val model = model(repository)
        advanceUntilIdle()
        assertTrue(model.state.value.ready)
        assertTrue(model.state.value.storageError)
        assertTrue(model.state.value.paused)
        assertTrue(model.guard.blocked)
        assertNull(model.credentials)
    }

    @Test fun retryAndRefreshKeepASignOutUntilTheStudentSignsIn() = runTest(dispatcher) {
        val repository = FakeRepository().apply { saved = saved.copy(credentials = Credentials("fixture-user", "fixture-password")) }
        val model = model(repository)
        advanceUntilIdle()
        model.pauseAutoLogin()
        assertTrue(model.state.value.paused)

        model.retrySignIn(signIn = false)
        assertTrue("Retry on the error screen and Refresh do not sign in again", model.state.value.paused)
        assertTrue("The sign-out is still the saved choice", repository.saved.paused)
        assertTrue(model.guard.blocked)

        model.retrySignIn(signIn = true)
        assertFalse("The student's own Sign in ends the pause", model.state.value.paused)
        assertFalse(repository.saved.paused)
        assertFalse(model.guard.blocked)
    }

    @Test fun retryAfterAFailureLetsSignInTryAgain() = runTest(dispatcher) {
        val repository = FakeRepository().apply { saved = saved.copy(credentials = Credentials("fixture-user", "fixture-password")) }
        val model = model(repository)
        advanceUntilIdle()
        model.guard.stop() // What repeated refusals leave: sign-in stopped, with no pause chosen by the student.
        assertFalse(model.state.value.paused)
        assertTrue(model.guard.blocked)

        model.retrySignIn(signIn = false)
        assertFalse("Retry starts the attempt limits afresh", model.guard.blocked)
        assertFalse(model.state.value.paused)
    }

    @Test fun commandWaitsForStartupInsteadOfLosingItsStateToTheInitialSnapshot() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeRepository().apply { onLoad = { gate.await(); saved } }
        val model = model(repository)
        val command = launch { model.setMobileLayout(false) }
        runCurrent()
        assertFalse(model.state.value.ready)
        assertTrue(repository.saved.mobileLayout)
        gate.complete(Unit)
        advanceUntilIdle()
        command.join()
        assertFalse(repository.saved.mobileLayout)
        assertFalse(model.state.value.mobileLayout)
        assertTrue(model.state.value.ready)
    }

    @Test fun cancellingActivityWaiterDoesNotLoseAnAcceptedLanguageWrite() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeRepository().apply { onLanguage = { gate.await() } }
        val model = model(repository)
        advanceUntilIdle()
        val activityWaiter = launch { model.setLanguage(AppLanguage.KAZAKH) }
        runCurrent()
        assertEquals(AppLanguage.KAZAKH, model.state.value.language)
        activityWaiter.cancelAndJoin()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(AppLanguage.KAZAKH, repository.saved.language)
        assertEquals(AppLanguage.KAZAKH, model.state.value.language)
    }

    @Test fun clearingViewModelCancelsSettingsWorkAndDoesNotPersistAfterDestruction() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeRepository().apply { onLanguage = { gate.await() } }
        val model = model(repository)
        advanceUntilIdle()
        val command = launch { model.setLanguage(AppLanguage.ENGLISH) }
        runCurrent()
        store.clear()
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(command.isCancelled)
        assertEquals(AppLanguage.RUSSIAN, repository.saved.language)
    }

    @Test fun twoFailedRapidSelectionsRollBackToTheLastSavedLanguage() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeRepository().apply {
            onLanguage = { gate.await(); throw IOException("Fixture write failure") }
        }
        val model = model(repository)
        advanceUntilIdle()
        val first = async { try { model.setLanguage(AppLanguage.ENGLISH); false } catch (_: IOException) { true } }
        runCurrent()
        val second = async { try { model.setLanguage(AppLanguage.KAZAKH); false } catch (_: IOException) { true } }
        runCurrent()
        assertEquals(AppLanguage.KAZAKH, model.state.value.language)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(first.await()); assertTrue(second.await())
        assertEquals(AppLanguage.RUSSIAN, model.state.value.language)
        assertEquals(repository.saved.language, model.state.value.language)
    }

    @Test fun earlierFailureCannotRevertANewerSelectionOfTheSameLanguage() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeRepository().apply {
            onLanguage = { if (languageWrites.size == 1) { gate.await(); throw IOException("First write fails") } }
        }
        val model = model(repository)
        advanceUntilIdle()
        val first = async { try { model.setLanguage(AppLanguage.ENGLISH) } catch (_: IOException) { } }
        runCurrent()
        val second = async { model.setLanguage(AppLanguage.KAZAKH) }
        val third = async { model.setLanguage(AppLanguage.ENGLISH) }
        runCurrent()
        val observed = mutableListOf<AppLanguage>()
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            model.state.collect { observed.add(it.language) }
        }
        gate.complete(Unit)
        advanceUntilIdle()
        first.await(); second.await(); third.await()
        assertEquals(listOf(AppLanguage.ENGLISH), observed)
        assertEquals(AppLanguage.ENGLISH, repository.saved.language)
        observer.cancel()
    }

    /** Removing the account goes through the same accepted transaction as a logout, so the site session is wiped too. */
    @Test fun credentialRemovalSerializesAfterASaveAndKeepsOtherSettings() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeRepository().apply { onSave = { gate.await() } }
        val model = model(repository)
        advanceUntilIdle()
        model.setLanguage(AppLanguage.KAZAKH)
        model.setMobileLayout(false)
        val save = launch { model.save(" fixture-user ", "fixture-password") }
        runCurrent()
        val delete = launch { model.endSession(delete = true) }
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        save.join(); delete.join()
        assertNull(model.credentials)
        assertNull(repository.saved.credentials)
        assertTrue(model.state.value.paused)
        assertTrue(model.guard.blocked)
        assertFalse(model.state.value.hasCredentials)
        assertTrue("The Activity must still erase the site session", model.sessionClearPending)
        assertEquals(AppLanguage.KAZAKH, model.state.value.language)
        assertFalse(model.state.value.mobileLayout)
    }

    @Test fun shortcutsPersistOnlyAcceptedChangesAndAnimationsAreStored() = runTest(dispatcher) {
        val repository = FakeRepository()
        val model = model(repository)
        advanceUntilIdle()
        val link = "https://univer.kaznu.kz/student/messages/"
        assertTrue(model.updateShortcuts(ShortcutRequest.Pin(true, "Сообщения", link)))
        assertEquals(listOf(PinnedLink("Сообщения", link)), model.state.value.shortcuts.pins)
        assertEquals(1, repository.shortcutWrites.size)
        // A duplicate is accepted without a second write; an unsafe address is rejected.
        assertTrue(model.updateShortcuts(ShortcutRequest.Pin(true, "Сообщения", link)))
        assertFalse(model.updateShortcuts(ShortcutRequest.Pin(true, "x", "javascript:alert(1)")))
        assertEquals(1, repository.shortcutWrites.size)
        model.setAnimations(false)
        assertFalse(model.state.value.animations)
        assertFalse(repository.saved.animations)
    }

    @Test fun themeIsLoadedAndStoredBeforeTheStateChanges() = runTest(dispatcher) {
        val repository = FakeRepository().apply { saved = saved.copy(theme = AppTheme.DARK) }
        val model = model(repository)
        advanceUntilIdle()
        assertEquals(AppTheme.DARK, model.state.value.theme)
        model.setTheme(AppTheme.LIGHT)
        assertEquals(AppTheme.LIGHT, model.state.value.theme)
        assertEquals(AppTheme.LIGHT, repository.saved.theme)
        assertEquals(AppTheme.SYSTEM, AppTheme.fromCode("unknown"))
    }

    @Test fun acceptedSaveCompletesAndRequestsASessionWipeEvenIfTheActivityWaiterIsCancelled() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeRepository().apply { onSave = { gate.await() } }
        val model = model(repository)
        advanceUntilIdle()
        val waiter = launch { model.save("fixture-user", "fixture-password") }
        runCurrent()
        waiter.cancelAndJoin() // Rotation destroys the Activity while the write is in flight.
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("fixture-user", model.credentials?.login)
        assertTrue(model.state.value.hasCredentials)
        assertTrue("The previous site session must still be wiped", model.sessionClearPending)
        model.sessionCleared()
        assertFalse(model.sessionClearPending)
    }

    @Test fun aPinChangeOutlivingItsScreenReadsTheDigitsThatWereEntered() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeRepository().apply { onPin = { gate.await() } }
        val model = model(repository)
        advanceUntilIdle()
        val old = "123456".toCharArray()
        val new = "708194".toCharArray()
        val screen = launch { model.changePin(old, new) }
        runCurrent()
        // Rotation while the pad says "Checking…": the screen's work is cancelled and it erases its own arrays.
        screen.cancelAndJoin()
        old.fill(Char(0)); new.fill(Char(0))
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("123456", "708194"), repository.pinReads)
    }

    @Test fun logoutIsOneAcceptedTransactionThatSurvivesRecreation() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeRepository().apply { saved = saved.copy(credentials = Credentials("fixture-user", "fixture-password")) }
        val model = model(repository)
        advanceUntilIdle()
        repository.onPause = { gate.await() }
        val waiter = launch { model.endSession(delete = true) }
        runCurrent()
        waiter.cancelAndJoin()
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(repository.saved.paused)
        assertNull(repository.saved.credentials)
        assertFalse(model.state.value.hasCredentials)
        assertTrue(model.singleSignOnGuard.blocked)
        assertTrue(model.sessionClearPending)
    }

    @Test fun requestedSectionSurvivesRecreationButNotLogout() = runTest(dispatcher) {
        val model = model(FakeRepository())
        advanceUntilIdle()
        model.resumeTarget.url = "https://univer.kaznu.kz/student/umkd/"
        model.resumeTarget.pending = true // The same ViewModel serves the recreated Activity.
        assertEquals("https://univer.kaznu.kz/student/umkd/", model.resumeTarget.url)
        model.endSession(delete = false)
        assertNull(model.resumeTarget.url)
        assertFalse(model.resumeTarget.pending)
    }

    @Test fun savingTheSameAccountAgainDoesNotWipeTheSession() = runTest(dispatcher) {
        val model = model(FakeRepository())
        advanceUntilIdle()
        model.save("fixture-user", "fixture-password")
        model.sessionCleared()
        model.save("fixture-user", "fixture-password")
        assertFalse(model.sessionClearPending)
        model.save("fixture-user", "other-password")
        assertTrue(model.sessionClearPending)
    }

    @Test fun siteNotificationsBelongToTheSignedInAccountAndAreSavedWithIt() = runTest(dispatcher) {
        val repository = FakeRepository()
        val model = model(repository)
        advanceUntilIdle()
        val scan = NoticeScan("/student/bachelor/", listOf(NoticeLink("https://univer.kaznu.kz/student/rating/", "Анкетирование студентов", 2)), emptyList())
        assertTrue("no account: a late scan is ignored", model.applyNoticeScan(scan).isEmpty())
        assertTrue(model.state.value.notices.isEmpty())
        model.save("fixture-user", "fixture-password")
        assertEquals(1, model.applyNoticeScan(scan).size)
        assertEquals(1, repository.saved.notices.size)
        assertTrue("the same marks again are not new", model.applyNoticeScan(scan).isEmpty())
        model.markAllNoticesRead()
        assertTrue(repository.saved.notices.single().read)
        model.endSession(delete = false)
        assertTrue(model.state.value.notices.isEmpty())
        assertTrue(repository.saved.notices.isEmpty())
        assertTrue("paused after logout: ignored", model.applyNoticeScan(scan).isEmpty())
    }

    @Test fun aScanStartedForThePreviousAccountCannotRefillTheList() = runTest(dispatcher) {
        val repository = FakeRepository()
        val model = model(repository)
        advanceUntilIdle()
        model.save("fixture-user", "fixture-password")
        val started = model.noticeSession
        model.save("other-user", "other-password")
        assertNotEquals(started, model.noticeSession)
        val late = NoticeScan("/student/bachelor/", listOf(NoticeLink("https://univer.kaznu.kz/student/rating/", "Анкетирование студентов", 2)), emptyList())
        assertTrue("a read from the previous account is dropped", model.applyNoticeScan(late, started).isEmpty())
        assertTrue(model.state.value.notices.isEmpty())
        assertTrue(repository.saved.notices.isEmpty())
        assertEquals(1, model.applyNoticeScan(late, model.noticeSession).size)
    }

    @Test fun aFailedClearDoesNotFailTheLogoutAndIsRetriedOnTheNextSignIn() = runTest(dispatcher) {
        val repository = FakeRepository()
        val model = model(repository)
        advanceUntilIdle()
        model.save("fixture-user", "fixture-password")
        val scan = NoticeScan("/student/bachelor/", listOf(NoticeLink("https://univer.kaznu.kz/student/rating/", "Анкетирование студентов", 2)), emptyList())
        assertEquals(1, model.applyNoticeScan(scan).size)
        repository.onNotices = { throw IOException("disk full") }
        model.endSession(delete = false)
        assertTrue("the logout still happened", model.state.value.paused)
        assertTrue(model.state.value.notices.isEmpty())
        assertEquals("the stored list could not be emptied yet", 1, repository.saved.notices.size)
        repository.onNotices = { }
        model.save("fixture-user", "fixture-password")
        assertTrue("the pending clear is retried", repository.saved.notices.isEmpty())
    }

    @Test fun unfinishedSessionClearSurvivesAProcessRestartAndIsAcknowledgedOnlyAfterErasure() = runTest(dispatcher) {
        val repository = FakeRepository()
        val first = model(repository)
        advanceUntilIdle()
        first.save("first-user", "fixture-password")
        first.sessionCleared()
        first.endSession(delete = false)
        assertTrue(repository.saved.sessionClearPending)
        val restarted = model(repository)
        advanceUntilIdle()
        assertTrue(restarted.sessionClearPending)
        assertTrue(restarted.state.value.paused)
        restarted.sessionCleared()
        assertFalse(repository.saved.sessionClearPending)
        assertTrue("Acknowledging the wipe never resumes explicit logout", repository.saved.paused)
    }

    @Test fun accountReplacementPersistsTheWipeBeforeWritingTheNewCredentialRecord() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            saved = saved.copy(credentials = Credentials("old-user", "old-password"))
            onSave = {
                assertTrue("The process can die during this write", saved.sessionClearPending)
                assertTrue("The old record cannot resume automatically", saved.paused)
            }
        }
        val first = model(repository)
        advanceUntilIdle()
        first.save("new-user", "new-password")
        val restarted = model(repository)
        advanceUntilIdle()
        assertEquals("new-user", restarted.credentials?.login)
        assertTrue("The WebView must be erased before opening that account", restarted.sessionClearPending)
    }

    @Test fun failedWipeAcknowledgementKeepsTheDurableMarker() = runTest(dispatcher) {
        val repository = FakeRepository().apply { saved = saved.copy(sessionClearPending = true) }
        val model = model(repository)
        advanceUntilIdle()
        repository.onFinishClear = { throw IOException("Fixture write failure") }
        assertTrue(runCatching { model.sessionCleared() }.isFailure)
        assertTrue(model.sessionClearPending)
        assertTrue(repository.saved.sessionClearPending)
    }

    @Test fun manualWebsiteLoginSurvivesRestartWithoutCreatingCredentialsAndEndsOnLogout() = runTest(dispatcher) {
        val repository = FakeRepository()
        val first = model(repository)
        advanceUntilIdle()
        first.startManualLogin()
        assertTrue(first.state.value.canBrowse)
        assertFalse(first.state.value.hasCredentials)
        assertNull(first.credentials)
        assertNull(repository.saved.credentials)
        val restarted = model(repository)
        advanceUntilIdle()
        assertTrue(restarted.state.value.manualLogin)
        assertTrue(restarted.state.value.canBrowse)
        assertFalse(restarted.state.value.hasCredentials)
        restarted.endSession(delete = false)
        assertFalse(restarted.state.value.canBrowse)
        assertFalse(repository.saved.manualLogin)
        assertTrue(repository.saved.paused)
        assertTrue(repository.saved.sessionClearPending)
    }

    @Test fun savingCredentialsReplacesManualModeOnlyAfterDurableSessionSeparation() = runTest(dispatcher) {
        val repository = FakeRepository()
        val model = model(repository)
        advanceUntilIdle()
        model.startManualLogin()
        model.save("fixture-user", "fixture-password")
        assertTrue(repository.saved.sessionClearPending)
        assertFalse(repository.saved.manualLogin)
        assertFalse(model.state.value.manualLogin)
        assertTrue(model.state.value.hasCredentials)
    }

    @Test fun rapidTextZoomBackToOriginalIsNotLostWhileFirstWriteIsInFlight() = runTest(dispatcher) {
        val firstWriteGate = CompletableDeferred<Unit>()
        val repository = FakeRepository().apply {
            onTextZoom = { percent -> if (percent == 110) firstWriteGate.await() }
        }
        val model = model(repository)
        advanceUntilIdle()
        assertEquals(100, model.state.value.textZoom)

        val failures = mutableListOf<Exception>()
        val onSliderChange = TextSizeDialog.onZoomChange(this, model) { failures += it }

        onSliderChange(110)
        runCurrent()
        // First write is blocked in flight; the published state is still 100%.
        assertEquals(listOf(110), repository.textZoomWrites)
        assertEquals(100, model.state.value.textZoom)

        // Driving 100% through the production slider callback while 110% is blocked must not drop the revert.
        onSliderChange(100)
        runCurrent()

        firstWriteGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(failures.isEmpty())
        assertEquals(listOf(110, 100), repository.textZoomWrites)
        assertEquals(100, repository.saved.textZoom)
        assertEquals(100, model.state.value.textZoom)
    }

    @Test fun unchangedOrDuplicateTextZoomSkipsRedundantWrites() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeRepository().apply {
            onTextZoom = { if (textZoomWrites.size == 1) gate.await() }
        }
        val model = model(repository)
        advanceUntilIdle()

        val failures = mutableListOf<Exception>()
        val onSliderChange = TextSizeDialog.onZoomChange(this, model) { failures += it }

        // Same value (including clamped equivalent) performs no repository write.
        onSliderChange(100)
        onSliderChange(104)
        advanceUntilIdle()
        assertTrue(repository.textZoomWrites.isEmpty())

        // Duplicate queued write while first 110% write is in flight writes only once.
        onSliderChange(110)
        runCurrent()
        onSliderChange(110)
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(110, model.state.value.textZoom)
        assertEquals(listOf(110), repository.textZoomWrites)
        assertTrue(failures.isEmpty())

        // If a write fails before updating state, the callback reports the failure and a queued return to the previous value skips a redundant write.
        val failGate = CompletableDeferred<Unit>()
        repository.onTextZoom = { percent ->
            if (percent == 120) { failGate.await(); throw IOException("Fixture write failure") }
        }
        onSliderChange(120)
        runCurrent()
        onSliderChange(110)
        runCurrent()
        failGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, failures.size)
        assertTrue(failures.single() is IOException)
        assertEquals(110, model.state.value.textZoom)
        assertEquals(listOf(110, 120), repository.textZoomWrites)
    }

    @Test fun cancellingSliderScopeDoesNotReportFailureOrDropInFlightTextZoom() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeRepository().apply {
            onTextZoom = { gate.await() }
        }
        val model = model(repository)
        advanceUntilIdle()

        val activityScope = CoroutineScope(SupervisorJob() + dispatcher)
        val failures = mutableListOf<Exception>()
        val onSliderChange = TextSizeDialog.onZoomChange(activityScope, model) { failures += it }

        onSliderChange(120)
        runCurrent()
        activityScope.cancel()
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue("Cancelling the Activity scope must not trigger storage_error", failures.isEmpty())
        assertEquals(120, repository.saved.textZoom)
        assertEquals(120, model.state.value.textZoom)
    }

    private class FakeRepository : SessionRepository {
        var saved = StoredSession()
        var onLoad: suspend () -> StoredSession = { saved }
        var onLanguage: suspend (AppLanguage) -> Unit = { }
        var onSave: suspend () -> Unit = { }
        val languageWrites = mutableListOf<AppLanguage>()

        override suspend fun load() = onLoad()
        override suspend fun saveCredentials(credentials: Credentials) {
            onSave()
            saved = saved.copy(credentials = credentials, paused = false, manualLogin = false)
        }
        override suspend fun deleteCredentials() { saved = saved.copy(credentials = null, paused = true) }
        override suspend fun beginSessionClear() { onPause(); saved = saved.copy(paused = true, sessionClearPending = true, manualLogin = false) }
        override suspend fun setManualLogin(enabled: Boolean) { saved = saved.copy(manualLogin = enabled, paused = if (enabled) false else saved.paused) }
        var onFinishClear: suspend () -> Unit = { }
        override suspend fun finishSessionClear() { onFinishClear(); saved = saved.copy(sessionClearPending = false) }
        var onPause: suspend () -> Unit = { }
        override suspend fun setAutoLoginPaused(paused: Boolean) { onPause(); saved = saved.copy(paused = paused) }
        override suspend fun setLanguage(language: AppLanguage) {
            languageWrites.add(language)
            onLanguage(language)
            saved = saved.copy(language = language)
        }
        override suspend fun setMobileLayout(enabled: Boolean) { saved = saved.copy(mobileLayout = enabled) }
        var onTextZoom: suspend (Int) -> Unit = { }
        val textZoomWrites = mutableListOf<Int>()
        override suspend fun setTextZoom(percent: Int) {
            textZoomWrites.add(percent)
            onTextZoom(percent)
            saved = saved.copy(textZoom = percent)
        }
        override suspend fun setAnimations(enabled: Boolean) { saved = saved.copy(animations = enabled) }
        override suspend fun setExternalLinks(enabled: Boolean) { saved = saved.copy(externalLinks = enabled) }
        override suspend fun setTheme(theme: AppTheme) { saved = saved.copy(theme = theme) }
        val shortcutWrites = mutableListOf<HomeShortcuts>()
        override suspend fun setShortcuts(shortcuts: HomeShortcuts) { shortcutWrites.add(shortcuts); saved = saved.copy(shortcuts = shortcuts) }
        var onNotices: suspend () -> Unit = { }
        override suspend fun setNotices(notices: List<SiteNotice>) { onNotices(); saved = saved.copy(notices = notices) }
        var onPin: suspend () -> Unit = { }
        val pinReads = mutableListOf<String>()
        override suspend fun changePin(oldPin: CharArray, newPin: CharArray): UnlockResult {
            onPin()
            pinReads += String(oldPin); pinReads += String(newPin)
            return UnlockResult.Unavailable
        }
    }
}
