package com.univerflow.kaznu.domain

/** Why a PIN was refused as a new code. */
enum class PinWeakness { MALFORMED, REPEATED, SEQUENCE, COMMON }

/** The shape of the app PIN and the choices refused as too easy to guess. Plain Kotlin, no Android types. */
object PinPolicy {
    const val LENGTH = 6
    private val common = setOf("112233", "112211", "123321", "159357", "147258", "258369", "100000", "101010", "202020", "696969", "121212", "123123")

    fun isWellFormed(pin: CharArray) = pin.size == LENGTH && pin.all { it in '0'..'9' }

    /** null when the PIN may be used. */
    fun weakness(pin: CharArray): PinWeakness? {
        if (!isWellFormed(pin)) return PinWeakness.MALFORMED
        val digits = pin.map { it - '0' }
        if (digits.all { it == digits[0] }) return PinWeakness.REPEATED
        val steps = digits.zipWithNext { a, b -> b - a }
        if (steps.all { it == 1 } || steps.all { it == -1 }) return PinWeakness.SEQUENCE
        val text = String(pin)
        if (text in common || text.substring(0, 2).repeat(3) == text || text.substring(0, 3).repeat(2) == text) return PinWeakness.COMMON
        return null
    }
}

/**
 * Delays after wrong PINs. The first four wrong codes cost nothing, then each one waits longer, and the tenth
 * erases the saved account: a stolen phone cannot be brute-forced, the student only signs in again.
 */
object PinLockout {
    const val WIPE_AT = 10

    /** How long to wait after the [failures]-th wrong PIN in a row. */
    fun delayAfter(failures: Int): Long = when (failures) {
        5 -> 30_000L
        6 -> 60_000L
        7 -> 5 * 60_000L
        8 -> 15 * 60_000L
        9 -> 30 * 60_000L
        else -> 0L
    }

    fun attemptsLeft(failures: Int) = (WIPE_AT - failures).coerceAtLeast(0)
    fun shouldWipe(failures: Int) = failures >= WIPE_AT

    /**
     * Failures to keep after a guess counted before the record was checked: a wrong PIN keeps [counted], an
     * unreadable record gives the attempt back, so a Keystore or I/O failure cannot walk the student to the wipe.
     */
    fun retained(counted: Int, wrongPin: Boolean) = if (wrongPin) counted else (counted - 1).coerceAtLeast(0)

    /**
     * What is left of a wait that started at [startElapsed] (elapsed realtime). After a reboot the clock restarted,
     * so a restart of the phone gives back the whole wait instead of shortening it.
     */
    fun remaining(durationMs: Long, startElapsed: Long, nowElapsed: Long, sameBoot: Boolean): Long {
        if (durationMs <= 0L) return 0L
        if (!sameBoot || nowElapsed < startElapsed) return durationMs
        return (durationMs - (nowElapsed - startElapsed)).coerceIn(0L, durationMs)
    }
}

/** How long the app may stay in the background before the PIN is asked again. */
object AutoLock {
    val options = listOf(15_000L, 60_000L, 5 * 60_000L)
    const val DEFAULT = 60_000L
    fun normalize(value: Long) = if (value in options) value else DEFAULT
}

/**
 * Whether the activity about to appear must be locked; [alreadyVisible] counts other started activities. A file or
 * Downloads opened over the locked main screen is not a return from the background and must close too.
 */
object AppLockGate {
    fun locks(armed: Boolean, locked: Boolean, alreadyVisible: Int, stoppedAtElapsed: Long, nowElapsed: Long, autoLockMs: Long): Boolean {
        if (!armed) return false
        if (locked) return true
        return alreadyVisible == 0 && stoppedAtElapsed != 0L && nowElapsed - stoppedAtElapsed >= autoLockMs
    }

    /**
     * The process lock after a ViewModel snapshot. An "open" snapshot from before the gate locked must not clear
     * [latched] (painting it shows the signed-in site); only [justUnlocked] does.
     */
    fun reconcile(enabled: Boolean, viewModelLocked: Boolean, latched: Boolean, justUnlocked: Boolean): Boolean {
        if (!enabled) return false
        if (viewModelLocked) return true
        if (justUnlocked) return false
        return latched
    }
}

data class LockSettings(
    val enabled: Boolean = false,
    val biometric: Boolean = false,
    val autoLockMs: Long = AutoLock.DEFAULT,
    /** The student already answered the "protect sign-in with a PIN" offer (either way). */
    val offerHandled: Boolean = false,
)

sealed interface UnlockResult {
    class Success(val credentials: Credentials) : UnlockResult
    class WrongPin(val attemptsLeft: Int, val waitMs: Long) : UnlockResult
    class WaitRequired(val remainingMs: Long) : UnlockResult
    /** Too many wrong PINs: the saved account was erased. */
    data object Wiped : UnlockResult
    /** The biometric key no longer exists (a new fingerprint was enrolled): the PIN still works. */
    data object BiometricInvalid : UnlockResult
    /** The protected record cannot be read any more (its key is gone): the account has to be entered again. */
    data object Unavailable : UnlockResult
}
