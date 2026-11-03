package com.univerflow.kaznu.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

typealias Credentials = com.univerflow.kaznu.domain.Credentials

/** Call on Dispatchers.IO. The key never leaves Android Keystore. */
class CredentialVault(context: Context, private val alias: String = "univer.credentials.v1") {
    private val record = AtomicFile(File(context.noBackupFilesDir, "$alias.enc"))
    private val aad = "univer.credentials.v1".toByteArray(Charsets.UTF_8)
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun encryptionKey(): SecretKey {
        (store().getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }

    fun read(): Credentials? {
        // AtomicFile.openRead() can recover the backup after an interrupted replace.
        if (!record.baseFile.exists() && !File(record.baseFile.path + ".bak").exists()) return null
        val envelope = JSONObject(record.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
        require(envelope.getInt("version") == 1)
        val key = store().getKey(alias, null) as? SecretKey ?: error("Credential key unavailable")
        val iv = Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)
        require(iv.size == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        val plaintext = cipher.doFinal(Base64.decode(envelope.getString("data"), Base64.NO_WRAP))
        try {
            val data = JSONObject(plaintext.toString(Charsets.UTF_8))
            return Credentials(data.getString("login"), data.getString("password"))
        } finally { plaintext.fill(0) }
    }

    private fun encryptingCipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, encryptionKey())
    }

    fun save(credentials: Credentials) {
        // A key made unusable by the system (lock screen removed, restore on another device) can never
        // decrypt the old record either: replace it so the user can enter the account again.
        val cipher = try { encryptingCipher() }
        catch (_: GeneralSecurityException) { store().apply { if (containsAlias(alias)) deleteEntry(alias) }; encryptingCipher() }
        cipher.updateAAD(aad)
        val plaintext = JSONObject().put("login", credentials.login).put("password", credentials.password)
            .toString().toByteArray(Charsets.UTF_8)
        val encrypted = try { cipher.doFinal(plaintext) } finally { plaintext.fill(0) }
        val envelope = JSONObject().put("version", 1)
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .toString().toByteArray(Charsets.UTF_8)
        val output = record.startWrite()
        try { output.write(envelope); record.finishWrite(output) }
        catch (failure: Exception) { record.failWrite(output); throw failure }
    }

    fun delete() {
        record.delete()
        store().apply { if (containsAlias(alias)) deleteEntry(alias) }
    }
}
