package com.univerflow.kaznu.ui

import android.os.SystemClock
import android.view.View
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
    // The views beside the pad that it hid from accessibility services, with the setting each had before.
    private val covered = ArrayList<Pair<View, Int>>()
    private var coveredParent: ViewGroup? = null

    val isShowing get() = screen?.isAttachedToWindow == true

    private fun screen(): PinScreen = screen?.takeIf { it.parent != null } ?: PinScreen(activity, text).also {
        screen = it
        activity.addContentView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        it.elevation = 100f
        coverSiblings(it)
    }

    /**
     * A covered view is still in the accessibility tree: without this, swiping past the last key reaches the toolbar,
     * drawer and page under the pad, and a double tap activates them. Views already hidden (the locked page) stay as they are.
     */
    private fun coverSiblings(pad: View) {
        val parent = pad.parent as? ViewGroup ?: return
        for (index in 0 until parent.childCount) parent.getChildAt(index).takeIf { it !== pad }?.let(::cover)
        // A message shown while the pad is up (a Snackbar that finds no closer parent) is added next to it later.
        coveredParent = parent
        parent.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View, child: View) { if (child !== pad) cover(child) }
            override fun onChildViewRemoved(parent: View, child: View) { covered.removeAll { it.first === child } }
        })
    }

    private fun cover(view: View) {
        if (view.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) return
        covered += view to view.importantForAccessibility
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    private fun uncoverSiblings() {
        coveredParent?.setOnHierarchyChangeListener(null)
        coveredParent = null
        covered.forEach { (view, previous) -> view.importantForAccessibility = previous }
        covered.clear()
    }

    /** The Back button: a closeable screen closes, the unlock screen sends the app to the background. */
    fun handleBack(): Boolean {
        val view = screen ?: return false
        if (view.isCloseable) view.onClose?.invoke() else activity.moveTaskToBack(true)
        return true
    }

    /** [animated]: the pad fades off the page it covered; closing the Activity removes it at once. */
    fun hide(animated: Boolean = false) {
        countdown?.cancel(); countdown = null
        // At once, not after the fade: the unlock screen shows the page again right after this returns.
        uncoverSiblings()
        val view = screen ?: return
        screen = null
        val remove = { (view.parent as? ViewGroup)?.removeView(view); Unit }
        if (animated) Motion.leave(view, remove) else remove()
    }

    private fun clock(milliseconds: Long): String {
        val seconds = ((milliseconds + 999) / 1000).coerceAtLeast(1)
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    private fun wrongPinTexts() = WrongPinTexts(text(R.string.lock_wrong_pin), text(R.string.lock_last_attempt), text(R.string.lock_wait))

    /**
     * Disables the keys and counts the wait down; the keys return when it is over. [attemptsLeft], when known, stays on
     * screen through the wait and after it, so the last attempt before the wipe is never a surprise.
     */
    private fun wait(view: PinScreen, milliseconds: Long, attemptsLeft: Int? = null) {
        countdown?.cancel()
        val deadline = SystemClock.elapsedRealtime() + milliseconds
        view.setInputEnabled(false)
        countdown = activity.lifecycleScope.launch {
            while (true) {
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) break
                view.showMessage(wrongPinTexts().duringWait(clock(left), attemptsLeft))
                delay(500)
            }
            view.showMessage(attemptsLeft?.let { wrongPinTexts().afterWrongPin(it) })
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
        view.onClose = { hide(animated = true); onClose() }
        view.onComplete = { pin ->
            activity.lifecycleScope.launch {
                view.setInputEnabled(false)
                view.showMessage(text(R.string.lock_checking), isError = false)
                val result = try { verify(pin) } catch (cancelled: CancellationException) { pin.fill('\u0000'); throw cancelled }
                catch (_: Exception) { UnlockResult.Unavailable }
                when (result) {
                    is UnlockResult.Success -> {
                        if (!keepOpen) { view.succeed(); hide(animated = true) }
                        onVerified(pin.copyOf())
                    }
                    is UnlockResult.WrongPin -> {
                        view.shake(); view.reset()
                        if (result.waitMs > 0) wait(view, result.waitMs, result.attemptsLeft)
                        else { view.showMessage(wrongPinTexts().afterWrongPin(result.attemptsLeft)); view.setInputEnabled(true) }
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
        view.onClose = { first?.fill('\u0000'); hide(animated = true); onClose() }
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
                    if (stored) { view.succeed(); hide(animated = true) } else { start(); view.showMessage(text(R.string.storage_error)) }
                }
            } else {
                view.shake(); pin.fill('\u0000')
                start(); view.showMessage(text(R.string.lock_mismatch))
            }
        }
    }
}

/**
 * What the pad says after a wrong PIN, from texts already in the app's language: [wrongPin] takes the attempts left,
 * [wait] the time left; [lastAttempt] warns that the next wrong PIN erases the saved account.
 */
internal class WrongPinTexts(val wrongPin: String, val lastAttempt: String, val wait: String) {
    fun afterWrongPin(attemptsLeft: Int): String = if (attemptsLeft == 1) lastAttempt else wrongPin.format(attemptsLeft)

    /** The countdown, with the attempts left under it when they are known. */
    fun duringWait(clock: String, attemptsLeft: Int?): String {
        val countdown = wait.format(clock)
        return if (attemptsLeft == null) countdown else countdown + "\n" + afterWrongPin(attemptsLeft)
    }
}
