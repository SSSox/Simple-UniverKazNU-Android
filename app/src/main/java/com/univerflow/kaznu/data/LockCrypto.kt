package com.univerflow.kaznu.data

import com.univerflow.kaznu.domain.Credentials
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.GeneralSecurityException
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** AES-GCM output: the random IV and the ciphertext with its authentication tag. */
class Sealed(val iv: ByteArray, val data: ByteArray)

/** The PIN was wrong: the data key could not be opened with the key derived from it. */
class WrongPinException : GeneralSecurityException("Wrong PIN")

/**
 * The outer layer around the PIN-protected data key. On the phone it is an Android Keystore key that never leaves
 * the secure hardware, so the stored record cannot be guessed offline even though a PIN has few combinations.
 */
interface OuterWrapper {
    fun wrap(plaintext: ByteArray, aad: ByteArray): Sealed
    fun unwrap(sealed: Sealed, aad: ByteArray): ByteArray
}

/** Primitives with no Android types, so every rule is tested on the JVM. */
object LockCrypto {
    private val random = SecureRandom()
    const val IV_SIZE = 12
    const val KEY_SIZE = 32

    /** Iterations for the platform's PBKDF2-HMAC-SHA256 (Android 8+ and the JVM). */
    const val ITERATIONS = 210_000
    /** Android 7 has no SHA-256 PBKDF2 provider; the portable version runs fewer rounds to stay usable. */
    const val FALLBACK_ITERATIONS = 60_000

    fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)

    fun platformKdfAvailable() = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256"); true } catch (_: NoSuchAlgorithmException) { false }
    fun defaultIterations() = if (platformKdfAvailable()) ITERATIONS else FALLBACK_ITERATIONS

    /** PBKDF2-HMAC-SHA256, 256 bits. The PIN is read as ASCII digits. */
    fun deriveKey(pin: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        require(iterations > 0 && pin.isNotEmpty())
        val spec = PBEKeySpec(pin, salt, iterations, KEY_SIZE * 8)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        catch (_: NoSuchAlgorithmException) {
            val bytes = ByteArray(pin.size) { pin[it].code.toByte() }
            try { pbkdf2Sha256(bytes, salt, iterations) } finally { bytes.fill(0) }
        } finally { spec.clearPassword() }
    }

    /** RFC 8018 PBKDF2 with HMAC-SHA256 on the plain Mac API, for providers without the SecretKeyFactory. */
    internal fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, length: Int = KEY_SIZE): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password, "HmacSHA256")) }
        val output = ByteArray(length)
        var offset = 0
        var block = 1
        while (offset < length) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (round in 1 until iterations) {
                u = mac.doFinal(u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            val take = minOf(t.size, length - offset)
            System.arraycopy(t, 0, output, offset, take)
            offset += take
            block++
        }
        return output
    }

    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray): Sealed {
        val iv = randomBytes(IV_SIZE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return Sealed(iv, cipher.doFinal(plaintext))
    }

    /** Throws AEADBadTagException (a GeneralSecurityException) when the key or the data is not the one sealed. */
    fun open(key: ByteArray, sealed: Sealed, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed.iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(sealed.data)
    }
}

/**
 * The protected account on disk: the credentials sealed with a random data key, and that data key wrapped twice for
 * the PIN (a key derived from the PIN, then the outer hardware key) and, optionally, once for the fingerprint/face key.
 */
class LockRecord(
    val iterations: Int,
    val salt: ByteArray,
    val pinOuter: Sealed,
    val biometric: Sealed?,
    val credentials: Sealed,
) {
    fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC); out.writeByte(VERSION)
            out.writeInt(iterations)
            out.writeBlob(salt)
            out.writeSealed(pinOuter)
            out.writeBoolean(biometric != null)
            biometric?.let { out.writeSealed(it) }
            out.writeSealed(credentials)
        }
        return bytes.toByteArray()
    }

    fun withBiometric(value: Sealed?) = LockRecord(iterations, salt, pinOuter, value, credentials)

    companion object {
        private const val MAGIC = 0x55564C32 // "UVL2"
        private const val VERSION = 1
        private const val LIMIT = 8192

        fun decode(bytes: ByteArray): LockRecord = try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == MAGIC && input.readUnsignedByte() == VERSION) { "Unknown record" }
                val iterations = input.readInt()
                require(iterations in 1_000..2_000_000)
                val salt = input.readBlob()
                val pin = input.readSealed()
                val biometric = if (input.readBoolean()) input.readSealed() else null
                val credentials = input.readSealed()
                LockRecord(iterations, salt, pin, biometric, credentials)
            }
        } catch (failure: java.io.IOException) { throw IllegalArgumentException("Unreadable record", failure) }

        private fun DataOutputStream.writeBlob(value: ByteArray) { writeInt(value.size); write(value) }
        private fun DataOutputStream.writeSealed(value: Sealed) { writeBlob(value.iv); writeBlob(value.data) }
        private fun DataInputStream.readBlob(): ByteArray {
            val size = readInt()
            require(size in 0..LIMIT)
            return ByteArray(size).also { readFully(it) }
        }
        private fun DataInputStream.readSealed() = Sealed(readBlob(), readBlob())

        /** A [Sealed] as one byte string, so the outer key can wrap it. */
        fun pack(value: Sealed): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { it.writeSealed(value) }
            return bytes.toByteArray()
        }
        fun unpack(bytes: ByteArray): Sealed = try {
            DataInputStream(ByteArrayInputStream(bytes)).use { it.readSealed() }
        } catch (failure: java.io.IOException) { throw IllegalArgumentException("Unreadable wrapped key", failure) }
    }
}

/** What an unlock hands back: the account and the data key that is needed to change what is stored. */
class Unlocked(val dataKey: ByteArray, val credentials: Credentials)

/** The record rules with the outer key injected: the phone uses the Keystore, tests use a software key. */
class LockedVaultCore(private val outer: OuterWrapper) {
    private val aadCredentials = "univer.credentials.locked.v1".toByteArray(Charsets.UTF_8)
    private val aadPinInner = "univer.lock.pin.inner.v1".toByteArray(Charsets.UTF_8)
    private val aadPinOuter = "univer.lock.pin.outer.v1".toByteArray(Charsets.UTF_8)
    val aadBiometric = "univer.lock.bio.v1".toByteArray(Charsets.UTF_8)

    fun create(credentials: Credentials, pin: CharArray, iterations: Int = LockCrypto.defaultIterations()): Pair<LockRecord, ByteArray> {
        val dataKey = LockCrypto.randomBytes(LockCrypto.KEY_SIZE)
        return wrapPin(dataKey, pin, iterations, null, sealCredentials(dataKey, credentials)) to dataKey
    }

    private fun wrapPin(dataKey: ByteArray, pin: CharArray, iterations: Int, biometric: Sealed?, credentials: Sealed): LockRecord {
        val salt = LockCrypto.randomBytes(16)
        val kek = LockCrypto.deriveKey(pin, salt, iterations)
        try {
            val inner = LockCrypto.seal(kek, dataKey, aadPinInner)
            return LockRecord(iterations, salt, outer.wrap(LockRecord.pack(inner), aadPinOuter), biometric, credentials)
        } finally { kek.fill(0) }
    }

    fun unlockWithPin(record: LockRecord, pin: CharArray): Unlocked {
        // A failure of the outer key is not a wrong PIN: it propagates as an unusable record.
        val inner = LockRecord.unpack(outer.unwrap(record.pinOuter, aadPinOuter))
        val kek = LockCrypto.deriveKey(pin, record.salt, record.iterations)
        val dataKey = try { LockCrypto.open(kek, inner, aadPinInner) }
        catch (_: javax.crypto.BadPaddingException) { throw WrongPinException() }
        finally { kek.fill(0) }
        return Unlocked(dataKey, openCredentials(record, dataKey))
    }

    fun credentialsWith(record: LockRecord, dataKey: ByteArray) = openCredentials(record, dataKey)

    fun withCredentials(record: LockRecord, dataKey: ByteArray, credentials: Credentials) =
        LockRecord(record.iterations, record.salt, record.pinOuter, record.biometric, sealCredentials(dataKey, credentials))

    /** A new PIN keeps the data key, so the credentials and the biometric wrap stay valid. */
    fun withNewPin(record: LockRecord, dataKey: ByteArray, pin: CharArray, iterations: Int = LockCrypto.defaultIterations()) =
        wrapPin(dataKey, pin, iterations, record.biometric, record.credentials)

    private fun sealCredentials(dataKey: ByteArray, credentials: Credentials): Sealed {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use {
            val login = credentials.login.toByteArray(Charsets.UTF_8); val password = credentials.password.toByteArray(Charsets.UTF_8)
            it.writeInt(login.size); it.write(login); it.writeInt(password.size); it.write(password)
            login.fill(0); password.fill(0)
        }
        val plaintext = bytes.toByteArray()
        return try { LockCrypto.seal(dataKey, plaintext, aadCredentials) } finally { plaintext.fill(0) }
    }

    private fun openCredentials(record: LockRecord, dataKey: ByteArray): Credentials {
        val plaintext = LockCrypto.open(dataKey, record.credentials, aadCredentials)
        try {
            DataInputStream(ByteArrayInputStream(plaintext)).use { input ->
                fun read(): String { val size = input.readInt(); require(size in 0..4096); return String(ByteArray(size).also { input.readFully(it) }, Charsets.UTF_8) }
                return Credentials(read(), read())
            }
        } finally { plaintext.fill(0) }
    }
}
