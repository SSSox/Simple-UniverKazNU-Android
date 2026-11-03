package com.univerflow.kaznu.web

/**
 * When the next automatic recovery (a fresh GET or a recreated screen, never a replayed POST) may run: delays grow to
 * the last one and never give up; [quietReset] of quiet or a [reset] starts again from the first.
 */
class RecoveryBackoff(
    private val delays: LongArray = longArrayOf(3_000, 10_000, 30_000, 60_000, 120_000, 300_000),
    private val quietReset: Long = 600_000,
) {
    private var used = 0
    private var last: Long? = null

    /** The wait before the next recovery; reading it does not spend one. */
    fun delay(now: Long): Long {
        if (last?.let { now - it >= quietReset } == true) used = 0
        return delays[minOf(used, delays.lastIndex)]
    }

    /** A recovery is being sent now. */
    fun spend(now: Long) {
        delay(now)
        used++
        last = now
    }

    /** How many recoveries in a row have been sent since the last reset. */
    val attempts get() = used

    fun reset() { used = 0; last = null }
}
