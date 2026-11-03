package com.univerflow.kaznu

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.univerflow.kaznu.data.AppPreferences
import com.univerflow.kaznu.data.CredentialVault
import com.univerflow.kaznu.data.Credentials
import com.univerflow.kaznu.data.KeystoreWrapper
import com.univerflow.kaznu.data.LocalSessionRepository
import com.univerflow.kaznu.data.LockPreferences
import com.univerflow.kaznu.data.LockVault
import com.univerflow.kaznu.data.WrongPinException
import com.univerflow.kaznu.domain.UnlockResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real Android Keystore, isolated key aliases and files: the installed account is never touched. */
@RunWith(AndroidJUnit4::class)
class LockVaultTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val account = Credentials("fixture-user", "fixture-password-Ж-'\"\\")
    private val outerAlias = "univer.test.lock.outer"
    private val legacyAlias = "univer.test.lock.legacy"
    private val lockFile = "univer.test.locked.enc"
    private val prefsName = "univer.test.lock.prefs"
    private val lockPrefsName = "univer.test.lock.attempts"
    private var now = 1_000_000L

    private fun lockVault() = LockVault(context, KeystoreWrapper(outerAlias), "univer.test.lock.bio", lockFile)
    private fun legacy() = CredentialVault(context, legacyAlias)
    private fun repository(lock: LockVault = lockVault()) = LocalSessionRepository(legacy(), AppPreferences(context, prefsName), lock,
        LockPreferences(context, lockPrefsName), Dispatchers.Unconfined, elapsed = { now })

    private fun clean() {
        lockVault().delete(); legacy().delete()
        context.deleteSharedPreferences(prefsName); context.deleteSharedPreferences(lockPrefsName)
    }
    @Before fun before() = clean()
    @After fun after() = clean()

    @Test fun protectedRecordOpensOnlyWithThePinAndHoldsNoPlainText() {
        val vault = lockVault()
        vault.create(account, "493827".toCharArray())
        val bytes = File(context.noBackupFilesDir, lockFile).readBytes()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertFalse(text.contains(account.login)); assertFalse(text.contains("fixture-password")); assertFalse(text.contains("493827"))
        vault.lock()
        assertFalse("Locked: the data key is gone from memory", vault.isUnlocked)
        assertThrows(WrongPinException::class.java) { vault.unlockWithPin("493828".toCharArray()) }
        assertFalse(vault.isUnlocked)
        val opened = vault.unlockWithPin("493827".toCharArray())
        assertEquals(account.login, opened.login); assertEquals(account.password, opened.password)
        assertTrue(vault.isUnlocked)
    }

    @Test fun deletingTheOuterKeyMakesTheRecordUnreadableEvenWithTheRightPin() {
        val vault = lockVault()
        vault.create(account, "493827".toCharArray())
        vault.lock()
        KeystoreWrapper(outerAlias).delete() // what a restore on another phone looks like
        val failure = runCatching { vault.unlockWithPin("493827".toCharArray()) }.exceptionOrNull()
        assertTrue("An unusable record is not a wrong PIN", failure != null && failure !is WrongPinException)
    }

    @Test fun newPinKeepsTheAccountAndChangedCredentialsAreStored() {
        val vault = lockVault()
        vault.create(account, "493827".toCharArray())
        vault.changePin("708194".toCharArray())
        vault.saveCredentials(Credentials("other-user", "other-pass"))
        vault.lock()
        assertThrows(WrongPinException::class.java) { vault.unlockWithPin("493827".toCharArray()) }
        assertEquals("other-user", vault.unlockWithPin("708194".toCharArray()).login)
    }

    @Test fun repositoryMigratesCountsAttemptsWaitsAndErasesAfterTenWrongPins() = runBlocking {
        val repository = repository()
        legacy().save(account)
        repository.enableLock(account, "493827".toCharArray())
        assertTrue(lockVault().exists())
        assertNull("The unprotected copy is gone once the protected one opens", legacy().read())
        val loaded = repository.load()
        assertNull("Locked: nothing is read without the PIN", loaded.credentials)
        assertTrue(loaded.lock.enabled)

        repeat(4) { assertTrue(repository.unlockWithPin("111222".toCharArray()) is UnlockResult.WrongPin) }
        val fifth = repository.unlockWithPin("111222".toCharArray()) as UnlockResult.WrongPin
        assertEquals(30_000L, fifth.waitMs)
        assertTrue("Even the right PIN waits", repository.unlockWithPin("493827".toCharArray()) is UnlockResult.WaitRequired)
        now += 31_000
        val right = repository.unlockWithPin("493827".toCharArray())
        assertTrue(right is UnlockResult.Success)
        assertEquals(account.login, (right as UnlockResult.Success).credentials.login)

        // A right PIN cleared the counter; ten wrong ones in a row (each after its wait) erase the account.
        var last: UnlockResult = UnlockResult.Unavailable
        repeat(10) { last = repository.unlockWithPin("111222".toCharArray()); now += 40 * 60_000L }
        assertEquals(UnlockResult.Wiped, last)
        assertFalse(lockVault().exists())
        assertNull(legacy().read())
        assertFalse(repository.load().lock.enabled)
    }

    @Test fun turningThePinOffReturnsTheAccountToPlainKeystoreStorage() = runBlocking {
        val repository = repository()
        legacy().save(account)
        repository.enableLock(account, "493827".toCharArray())
        assertTrue(repository.disableLock("000000".toCharArray()) is UnlockResult.WrongPin)
        assertTrue(lockVault().exists())
        assertTrue(repository.disableLock("493827".toCharArray()) is UnlockResult.Success)
        assertFalse(lockVault().exists())
        assertEquals(account.login, legacy().read()!!.login)
        assertEquals(account.password, legacy().read()!!.password)
        assertEquals(account.login, repository.load().credentials!!.login)
    }

    @Test fun savingNewCredentialsWhileProtectedKeepsThemInsideTheLockedRecord() = runBlocking {
        val repository = repository()
        legacy().save(account)
        repository.enableLock(account, "493827".toCharArray())
        repository.unlockWithPin("493827".toCharArray())
        repository.saveCredentials(Credentials("changed", "changed-pass"))
        assertNull("No unprotected copy appears", legacy().read())
        repository.lockNow()
        val again = repository.unlockWithPin("493827".toCharArray()) as UnlockResult.Success
        assertEquals("changed", again.credentials.login)
    }
}
