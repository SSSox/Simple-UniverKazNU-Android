package com.univerflow.kaznu.data

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.univerflow.kaznu.domain.AutoLock
import com.univerflow.kaznu.domain.PinLockout

/**
 * Wrong-PIN counter, the current wait and the lock options. No secrets. The counter is written BEFORE a PIN is
 * checked, so killing the app in the middle of a guess cannot save an attempt.
 */
@SuppressLint("UseKtx")
class LockPreferences(private val context: Context, name: String = "univer.lock") {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    var failures: Int
        get() = preferences.getInt("failures", 0)
        set(value) { check(preferences.edit().putInt("failures", value).commit()) }
    var autoLockMs: Long
        get() = AutoLock.normalize(preferences.getLong("auto_lock_ms", AutoLock.DEFAULT))
        set(value) { check(preferences.edit().putLong("auto_lock_ms", AutoLock.normalize(value)).commit()) }
    /** The "protect sign-in with a PIN" offer was answered; it is never shown again, but the PIN stays available in settings. */
    var offerHandled: Boolean
        get() = preferences.getBoolean("offer_handled", false)
        set(value) { check(preferences.edit().putBoolean("offer_handled", value).commit()) }

    private fun bootCount() = try { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) } catch (_: Exception) { -1 }

    /** Starts a wait after a wrong PIN. */
    fun startWait(durationMs: Long, nowElapsed: Long) {
        check(preferences.edit().putLong("wait_ms", durationMs).putLong("wait_start", nowElapsed).putInt("wait_boot", bootCount()).commit())
    }

    /** Milliseconds still to wait; a restart of the phone gives back the whole wait. */
    fun remainingWait(nowElapsed: Long): Long {
        val duration = preferences.getLong("wait_ms", 0L)
        if (duration <= 0L) return 0L
        // Without a boot counter the monotonic clock alone tells a restart (it starts again from zero).
        val boot = bootCount(); val stored = preferences.getInt("wait_boot", -1)
        val sameBoot = boot == -1 || stored == -1 || boot == stored
        val left = PinLockout.remaining(duration, preferences.getLong("wait_start", 0L), nowElapsed, sameBoot)
        // After a restart the wait starts again from now, and stays measured from there.
        if (!sameBoot && left > 0) startWait(duration, nowElapsed)
        return left
    }

    fun clearAttempts() {
        check(preferences.edit().putInt("failures", 0).remove("wait_ms").remove("wait_start").remove("wait_boot").commit())
    }

    /** Everything about the lock except that the offer was answered. */
    fun resetKeepingOffer() {
        check(preferences.edit().putInt("failures", 0).remove("wait_ms").remove("wait_start").remove("wait_boot").remove("auto_lock_ms").commit())
    }
}
