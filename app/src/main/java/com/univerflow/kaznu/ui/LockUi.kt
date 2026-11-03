package com.univerflow.kaznu.ui

import android.os.SystemClock
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.univerflow.kaznu.R
import com.univerflow.kaznu.domain.PinPolicy
import com.univerflow.kaznu.domain.PinWeakness
import com.univerflow.kaznu.domain.UnlockResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shows the PIN pad over the whole activity (above the drawer, the web page and every dialog's parent) and runs the
 * three PIN conversations: enter the PIN, and choose a new PIN twice. Text comes from [text] in the app's language.
 */
class LockUi(private val activity: AppCompatActivity, private val text: (Int) -> String) {
    private var screen: PinScreen? = null
    private var countdown: Job? = null

    val isShowing get() = screen?.isAttachedToWindow == true

    private fun screen(): PinScreen = screen?.takeIf { it.parent != null } ?: PinScreen(activity, text).also {
        screen = it
        activity.addContentView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        it.elevation = 100f
    }

    /** The Back button: a closeable screen closes, the unlock screen sends the app to the background. */
    fun handleBack(): Boolean {
        val view = screen ?: return false
        if (view.isCloseable) view.onClose?.invoke() else activity.moveTaskToBack(true)
        return true
    }

    fun hide() {
        countdown?.cancel(); countdown = null
        screen?.let { (it.parent as? ViewGroup)?.removeView(it) }
        screen = null
    }

    private fun clock(milliseconds: Long): String {
        val seconds = ((milliseconds + 999) / 1000).coerceAtLeast(1)
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    /** Disables the keys and counts the wait down; the keys return when it is over. */
    private fun wait(view: PinScreen, milliseconds: Long) {
        countdown?.cancel()
        val deadline = SystemClock.elapsedRealtime() + milliseconds
        view.setInputEnabled(false)
        countdown = activity.lifecycleScope.launch {
            while (true) {
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) break
                view.showMessage(text(R.string.lock_wait).format(clock(left)))
                delay(500)
            }
            view.showMessage(null)
            view.setInputEnabled(true)
        }
    }

    /**
     * Asks for the current PIN. [verify] decides; a right PIN reaches [onVerified] with its own copy of the digits (the
     * caller erases it) and the screen closes unless [keepOpen] is set for a follow-up step.
     */
    fun enter(
        title: Int, hint: Int?, closeable: Boolean, biometric: Boolean, forgot: Boolean,
        verify: suspend (CharArray) -> UnlockResult,
        onVerified: (CharArray) -> Unit,
        onBiometric: () -> Unit = {}, onForgot: () -> Unit = {}, onClose: () -> Unit = {}, onWiped: () -> Unit = {},
        keepOpen: Boolean = false,
    ) {
        val view = screen()
        view.configure(text(title), hint?.let(text), closeable, biometric, if (forgot) text(R.string.lock_forgot) else null)
        view.reset(); view.setInputEnabled(true)
        view.onBiometric = onBiometric
        view.onForgot = onForgot
        view.onClose = { hide(); onClose() }
        view.onComplete = { pin ->
            activity.lifecycleScope.launch {
                view.setInputEnabled(false)
                view.showMessage(text(R.string.lock_checking), isError = false)
                val result = try { verify(pin) } catch (cancelled: CancellationException) { pin.fill('\u0000'); throw cancelled }
                catch (_: Exception) { UnlockResult.Unavailable }
                when (result) {
                    is UnlockResult.Success -> {
                        if (!keepOpen) hide()
                        onVerified(pin.copyOf())
                    }
                    is UnlockResult.WrongPin -> {
                        view.shake(); view.reset()
                        if (result.waitMs > 0) wait(view, result.waitMs)
                        else { view.showMessage(text(R.string.lock_wrong_pin).format(result.attemptsLeft)); view.setInputEnabled(true) }
                    }
                    is UnlockResult.WaitRequired -> { view.shake(); view.reset(); wait(view, result.remainingMs) }
                    UnlockResult.Wiped -> { hide(); onWiped() }
                    UnlockResult.BiometricInvalid, UnlockResult.Unavailable -> {
                        view.shake(); view.reset(); view.showMessage(text(R.string.lock_unavailable)); view.setInputEnabled(true)
                    }
                }
                pin.fill('\u0000')
            }
        }
    }

    /**
     * Two entries of a new PIN. [accept] stores it (true) or fails (false); the screen closes on success. An easy PIN is
     * refused with the reason before it is asked a second time.
     */
    fun create(title: Int = R.string.lock_create_title, closeable: Boolean, accept: suspend (CharArray) -> Boolean, onClose: () -> Unit) {
        val view = screen()
        var first: CharArray? = null
        fun start() {
            first?.fill('\u0000'); first = null
            view.configure(text(title), text(R.string.lock_create_hint), closeable, false, null)
            view.reset(); view.setInputEnabled(true)
        }
        start()
        view.onClose = { first?.fill('\u0000'); hide(); onClose() }
        view.onComplete = { pin ->
            val earlier = first
            if (earlier == null) {
                val weakness = PinPolicy.weakness(pin)
                if (weakness != null) {
                    view.shake()
                    view.showMessage(text(when (weakness) {
                        PinWeakness.REPEATED -> R.string.lock_weak_repeated
                        PinWeakness.SEQUENCE -> R.string.lock_weak_sequence
                        else -> R.string.lock_weak_common
                    }))
                    pin.fill('\u0000')
                } else {
                    first = pin
                    view.configure(text(R.string.lock_confirm_title), null, closeable, false, null)
                }
            } else if (earlier.contentEquals(pin)) {
                view.setInputEnabled(false)
                view.showMessage(text(R.string.lock_checking), isError = false)
                activity.lifecycleScope.launch {
                    val stored = try { accept(pin) } catch (cancelled: CancellationException) { pin.fill('\u0000'); throw cancelled }
                    catch (_: Exception) { false }
                    pin.fill('\u0000'); earlier.fill('\u0000'); first = null
                    if (stored) hide() else { start(); view.showMessage(text(R.string.storage_error)) }
                }
            } else {
                view.shake(); pin.fill('\u0000')
                start(); view.showMessage(text(R.string.lock_mismatch))
            }
        }
    }
}
