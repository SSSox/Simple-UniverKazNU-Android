package com.univerflow.kaznu

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.univerflow.kaznu.ui.context
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.data.CredentialVault
import com.univerflow.kaznu.data.Credentials
import com.univerflow.kaznu.data.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CredentialVaultTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "univer.test.credentials" // Isolated from the real credential record/key.
    @Test fun encryptedRoundTripRandomIvAndDeletion() {
        val vault = CredentialVault(context, alias)
        try {
            vault.delete()
            assertNull(vault.read())
            val sample = Credentials("fixture-user", "fixture-password-Ж-'\"\\")
            vault.save(sample)
            val first = File(context.noBackupFilesDir, "$alias.enc").readText()
            assertFalse(first.contains(sample.login)); assertFalse(first.contains(sample.password))
            assertEquals(sample.login, vault.read()!!.login)
            assertEquals(sample.password, vault.read()!!.password)
            assertFalse(sample.toString().contains(sample.password))
            vault.save(sample)
            assertNotEquals(first, File(context.noBackupFilesDir, "$alias.enc").readText())
            vault.delete()
            assertNull(vault.read())
        } finally { vault.delete() }
    }
    @Test fun corruptCiphertextFailsClosed() {
        val vault = CredentialVault(context, alias)
        try {
            vault.save(Credentials("fixture-user", "fixture-password"))
            val file = File(context.noBackupFilesDir, "$alias.enc")
            val envelope = org.json.JSONObject(file.readText())
            val bytes = android.util.Base64.decode(envelope.getString("data"), android.util.Base64.NO_WRAP)
            bytes[0] = (bytes[0].toInt() xor 1).toByte()
            envelope.put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
            file.writeText(envelope.toString())
            assertTrue(runCatching { vault.read() }.isFailure)
        } finally { vault.delete() }
    }
    @Test fun interruptedAtomicReplacementRestoresBackupWithoutLosingAccount() {
        val vault = CredentialVault(context, alias)
        try {
            val sample = Credentials("fixture-user", "fixture-password")
            vault.save(sample)
            val file = File(context.noBackupFilesDir, "$alias.enc")
            assertTrue(file.renameTo(File(file.path + ".bak")))
            assertEquals(sample.login, vault.read()!!.login)
            assertEquals(sample.password, vault.read()!!.password)
        } finally { vault.delete() }
    }
    @Test fun allThreeNativeLocalesResolveAndRussianIsDefault() {
        assertEquals(AppLanguage.RUSSIAN, AppLanguage.fromCode(null))
        assertEquals("Настройки", AppLanguage.RUSSIAN.context(context).getString(R.string.settings))
        assertEquals("Баптаулар", AppLanguage.KAZAKH.context(context).getString(R.string.settings))
        assertEquals("Settings", AppLanguage.ENGLISH.context(context).getString(R.string.settings))
    }
    @Test fun rapidLanguageSelectionsPersistTheLastChoice() {
        val prefsName = "univer.test.language.preferences"
        context.deleteSharedPreferences(prefsName)
        val prefs = AppPreferences(context, prefsName)
        val vault = CredentialVault(context, "univer.test.language")
        val store = androidx.lifecycle.ViewModelStore()
        val model = UniverViewModel(com.univerflow.kaznu.data.LocalSessionRepository(vault, prefs))
        store.put("languages", model)
        try {
            runBlocking {
                model.state.first { it.ready }
                withContext(Dispatchers.Main) {
                    listOf(AppLanguage.ENGLISH, AppLanguage.RUSSIAN, AppLanguage.KAZAKH)
                        .map { language -> launch { model.setLanguage(language) } }.joinAll()
                }
            }
            assertEquals(AppLanguage.KAZAKH, model.state.value.language)
            assertEquals(AppLanguage.KAZAKH, prefs.language)
        } finally {
            vault.delete(); context.deleteSharedPreferences(prefsName)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
        }
    }
}
