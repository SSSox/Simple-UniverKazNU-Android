package com.univerflow.kaznu.today

import android.content.BroadcastReceiver

/** A receiver's short file and alarm work, off the main thread; the system keeps the process until it finishes. */
internal object Background {
    fun run(receiver: BroadcastReceiver, work: () -> Unit) {
        val pending = receiver.goAsync()
        Thread {
            try { work() } catch (_: Exception) { } finally { pending.finish() }
        }.start()
    }
}
