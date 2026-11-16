package com.univerflow.kaznu.ui

import android.content.Context
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.univerflow.kaznu.data.LockPreferences
import com.univerflow.kaznu.data.LockVault
import com.univerflow.kaznu.domain.AppLockGate
import com.univerflow.kaznu.domain.AutoLock

/**
 * Process-wide lock state: the app locks when every activity was out of sight longer than the chosen time (rotation
 * and our own screens do not count). A new process starts locked until the main screen clears [locked].
 */
object AppLockSession {
    @Volatile var armed = false
        private set
    @Volatile var locked = true
        private set
    @Volatile private var autoLockMs = AutoLock.DEFAULT
    private var started = 0
    private var stoppedAt = 0L
    private val listeners = LinkedHashSet<() -> Unit>()
    internal var clock: () -> Long = SystemClock::elapsedRealtime

    private var initialized = false

    /**
     * A screen that opens before the main one, or on top of the PIN, must not bypass the lock. The first call in a
     * process reads from storage whether a PIN is set: the record's existence, not any state in memory.
     */
    @Synchronized fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        armed = LockVault(app).exists()
        locked = armed
        autoLockMs = LockPreferences(app).autoLockMs
    }

    /**
     * Called by the main screen on every state change; [justUnlocked]: the PIN or biometrics just succeeded.
     * An older "open" snapshot must not clear a lock [onStarted] just applied.
     */
    fun update(enabled: Boolean, viewModelLocked: Boolean, autoLock: Long, justUnlocked: Boolean) {
        armed = enabled
        autoLockMs = autoLock
        locked = AppLockGate.reconcile(enabled, viewModelLocked, locked, justUnlocked)
        // Forget the background mark only while a screen is actually visible and the app is open.
        if (!locked && started > 0) stoppedAt = 0L
    }

    /** Locks now and tells every screen that is waiting for it. */
    fun lock() {
        if (!armed) return
        locked = true
        listeners.toList().forEach { it() }
    }

    internal fun onStarted() {
        val alreadyVisible = started
        val mustLock = AppLockGate.locks(armed, locked, alreadyVisible, stoppedAt, clock(), autoLockMs)
        started++
        if (!armed) return
        if (mustLock) lock()
        // Only the return to the foreground consumes the background timestamp.
        if (alreadyVisible == 0) stoppedAt = 0L
    }

    internal fun onStopped(configurationChange: Boolean) {
        started = (started - 1).coerceAtLeast(0)
        // A rotation recreates the screen: it is not the app being left.
        if (started == 0 && !configurationChange) stoppedAt = clock()
    }

    /**
     * A screen opened from the main one: with a PIN set it closes when the app locks and stays out of screenshots and
     * recents; [root] keeps clear of the system bars (and the keyboard with [ime]).
     */
    fun secondaryScreen(activity: ComponentActivity, root: android.view.View, ime: Boolean = false) {
        initialize(activity)
        if (armed) activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        track(activity) { activity.finish() }
        val light = !ThemeMode.isDark(activity)
        androidx.core.view.WindowCompat.getInsetsController(activity.window, root).apply {
            isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light
        }
        val types = androidx.core.view.WindowInsetsCompat.Type.systemBars() or if (ime) androidx.core.view.WindowInsetsCompat.Type.ime() else 0
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(types)
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            view.paintSystemBands(bars.top)
            insets
        }
    }

    /** Registers [activity]; [whenLocked] runs on every lock (the main screen covers itself, others close). */
    fun track(activity: ComponentActivity, whenLocked: () -> Unit) {
        listeners += whenLocked
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { onStarted() }
            override fun onStop(owner: LifecycleOwner) { onStopped(activity.isChangingConfigurations) }
            override fun onDestroy(owner: LifecycleOwner) { listeners -= whenLocked }
        })
    }
}
