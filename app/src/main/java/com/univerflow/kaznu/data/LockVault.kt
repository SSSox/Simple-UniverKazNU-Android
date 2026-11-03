package com.univerflow.kaznu.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.univerflow.kaznu.domain.Credentials
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The outer key of the PIN layer: AES-256-GCM in Android Keystore, in the secure hardware where the phone has it. */
class KeystoreWrapper(private val alias: String = "univer.lock.outer.v1") : OuterWrapper {
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun generate(strongBox: Boolean): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).setRandomizedEncryptionRequired(true)
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) builder.setIsStrongBoxBacked(true)
        init(builder.build())
        generateKey()
    }

    private fun key(): SecretKey {
        (store().getKey(alias, null) as? SecretKey)?.let { return it }
        // A dedicated security chip is used when present; any failure falls back to the ordinary Keystore.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) try { generate(true) } catch (_: Exception) { generate(false) } else generate(false)
    }

    override fun wrap(plaintext: ByteArray, aad: ByteArray): Sealed {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(aad) }
        return Sealed(cipher.iv, cipher.doFinal(plaintext))
    }

    override fun unwrap(sealed: Sealed, aad: ByteArray): ByteArray {
        val key = store().getKey(alias, null) as? SecretKey ?: throw java.security.GeneralSecurityException("Lock key unavailable")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed.iv)); updateAAD(aad) }
        return cipher.doFinal(sealed.data)
    }

    fun delete() { store().apply { if (containsAlias(alias)) deleteEntry(alias) } }
}

/**
 * The account protected by the app PIN (and, if chosen, biometrics). Call on Dispatchers.IO. While unlocked the data
 * key stays in memory; [lock] erases it. The record is in no-backup storage and never leaves the phone.
 */
class LockVault(
    context: Context,
    private val outer: KeystoreWrapper = KeystoreWrapper(),
    private val biometricAlias: String = "univer.lock.bio.v1",
    fileName: String = "univer.credentials.locked.v1.enc",
) {
    private val file = AtomicFile(File(context.noBackupFilesDir, fileName))
    private val core = LockedVaultCore(outer)
    private var dataKey: ByteArray? = null

    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun exists() = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()
    private fun read() = LockRecord.decode(file.openRead().use { it.readBytes() })
    private fun write(record: LockRecord) {
        val output = file.startWrite()
        try { output.write(record.encode()); file.finishWrite(output) }
        catch (failure: Exception) { file.failWrite(output); throw failure }
    }

    fun biometricEnrolled() = exists() && runCatching { read().biometric != null }.getOrDefault(false)
    val isUnlocked get() = dataKey != null

    /** Protects [credentials] with [pin]; the vault stays unlocked. The caller removes the old unprotected copy. */
    fun create(credentials: Credentials, pin: CharArray) {
        val (record, key) = core.create(credentials, pin)
        write(record)
        replaceDataKey(key)
        // Read it back once: a record that cannot be opened must not replace the working one.
        check(core.credentialsWith(read(), key).login == credentials.login)
    }

    fun unlockWithPin(pin: CharArray): Credentials {
        val unlocked = core.unlockWithPin(read(), pin)
        replaceDataKey(unlocked.dataKey)
        return unlocked.credentials
    }

    fun saveCredentials(credentials: Credentials) {
        val key = dataKey ?: error("Locked")
        write(core.withCredentials(read(), key, credentials))
    }

    /** A new PIN after the old one was proven right (the caller checked it with [unlockWithPin]). */
    fun changePin(pin: CharArray) {
        val key = dataKey ?: error("Locked")
        write(core.withNewPin(read(), key, pin))
    }

    // Biometrics: a Keystore key that needs a fingerprint or face for every use wraps the data key a second time.

    private fun biometricKey(): SecretKey {
        store().apply { if (containsAlias(biometricAlias)) deleteEntry(biometricAlias) }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            val builder = KeyGenParameterSpec.Builder(biometricAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            else @Suppress("DEPRECATION") builder.setUserAuthenticationValidityDurationSeconds(-1)
            init(builder.build())
            generateKey()
        }
    }

    /** The cipher the biometric prompt unlocks to wrap the data key; [finishBiometric] completes the enrolment. */
    fun biometricEnrollCipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, biometricKey()) }

    fun finishBiometric(cipher: Cipher) {
        val key = dataKey ?: error("Locked")
        cipher.updateAAD(core.aadBiometric)
        val sealed = Sealed(cipher.iv, cipher.doFinal(key))
        write(read().withBiometric(sealed))
    }

    /** Throws [KeyPermanentlyInvalidatedException] once the phone's biometrics changed. */
    fun biometricUnlockCipher(): Cipher {
        val sealed = read().biometric ?: error("Biometrics not enabled")
        val key = store().getKey(biometricAlias, null) as? SecretKey ?: throw KeyPermanentlyInvalidatedException()
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed.iv)) }
    }

    fun unlockWithBiometric(cipher: Cipher): Credentials {
        val record = read()
        val sealed = record.biometric ?: error("Biometrics not enabled")
        cipher.updateAAD(core.aadBiometric)
        val key = cipher.doFinal(sealed.data)
        val credentials = core.credentialsWith(record, key)
        replaceDataKey(key)
        return credentials
    }

    fun removeBiometric() {
        if (exists()) write(read().withBiometric(null))
        store().apply { if (containsAlias(biometricAlias)) deleteEntry(biometricAlias) }
    }

    private fun replaceDataKey(key: ByteArray) { dataKey?.fill(0); dataKey = key }

    /** Erases the data key from memory: nothing can be read until the PIN or the biometrics unlock it again. */
    fun lock() { dataKey?.fill(0); dataKey = null }

    fun delete() {
        lock()
        file.delete()
        outer.delete()
        store().apply { if (containsAlias(biometricAlias)) deleteEntry(biometricAlias) }
    }
}
