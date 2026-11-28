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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Surface driven by [PinFlowController]; implemented by [PinScreen] and by fakes in JVM unit tests. */
internal interface PinPad {
    val isCloseable: Boolean
    var onComplete: ((CharArray) -> Unit)?
    var onBiometric: (() -> Unit)?
    var onForgot: (() -> Unit)?
    var onClose: (() -> Unit)?

    fun configure(titleText: String, hint: String?, closeable: Boolean, biometric: Boolean, forgotText: String?)
    fun showMessage(text: String?, isError: Boolean = true)
    fun setInputEnabled(enabled: Boolean)
    fun setCloseEnabled(enabled: Boolean)
    fun reset()
    fun succeed()
    fun shake()
}

/**
 * Shows the PIN pad over the whole activity (above the drawer, the web page and every dialog's parent) and runs the
 * three PIN conversations: enter the PIN, and choose a new PIN twice. Text comes from [text] in the app's language.
 */
class LockUi(private val activity: AppCompatActivity, private val text: (Int) -> String) {
    private var screen: PinScreen? = null
    // The views beside the pad that it hid from accessibility services, with the setting each had before.
    private val covered = ArrayList<Pair<View, Int>>()
    private var coveredParent: ViewGroup? = null

    private val controller = PinFlowController(
        scope = activity.lifecycleScope,
        text = text,
        clock = SystemClock::elapsedRealtime,
        acquirePad = ::screen,
        padActive = { pad -> !activity.isDestroyed && !activity.isFinishing && screen === pad && (pad as? View)?.parent != null },
        releasePad = ::detachScreen,
        moveTaskToBack = { activity.moveTaskToBack(true) },
    )

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

    private fun detachScreen(animated: Boolean) {
        // At once, not after the fade: the unlock screen shows the page again right after this returns.
        uncoverSiblings()
        val view = screen ?: return
        screen = null
        view.onComplete = null
        view.onBiometric = null
        view.onForgot = null
        view.onClose = null
        view.setInputEnabled(false)
        view.setCloseEnabled(false)
        val remove = { (view.parent as? ViewGroup)?.removeView(view); Unit }
        if (animated) Motion.leave(view, remove) else remove()
    }

    /** The Back button: backgrounds the unlock screen (even during verification); closes a closeable screen unless it is verifying. */
    fun handleBack(): Boolean = controller.handleBack()

    /** [animated]: the pad fades off the page it covered; closing the Activity removes it at once. */
    fun hide(animated: Boolean = false) = controller.hide(animated)

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
    ) = controller.enter(title, hint, closeable, biometric, forgot, verify, onVerified, onBiometric, onForgot, onClose, onWiped, keepOpen)

    /**
     * Two entries of a new PIN. [accept] stores it (true) or fails (false); the screen closes on success and runs
     * [onAccepted] after closing. [onCleared] runs on every exit path (close, hide, re-entry, completion) so callers can
     * wipe any held PIN buffer. An easy PIN is refused with the reason before it is asked a second time.
     */
    fun create(
        title: Int = R.string.lock_create_title, closeable: Boolean,
        accept: suspend (CharArray) -> Boolean,
        onClose: () -> Unit,
        onAccepted: () -> Unit = {},
        onCleared: () -> Unit = {},
    ) = controller.create(title, closeable, accept, onClose, onAccepted, onCleared)
}

/**
 * State machine and coroutine orchestration for the PIN pad: cancels in-flight verify/accept jobs and lockout countdowns
 * on hide or re-entry, blocks close/Back while PBKDF2 runs, clears PIN buffers on every exit path, and propagates
 * [CancellationException]. Pure Kotlin so all invariants are verified in JVM unit tests.
 */
internal class PinFlowController(
    private val scope: CoroutineScope,
    private val text: (Int) -> String,
    private val clock: () -> Long,
    private val acquirePad: () -> PinPad,
    private val padActive: (PinPad) -> Boolean,
    private val releasePad: (animated: Boolean) -> Unit,
    private val moveTaskToBack: () -> Unit,
) {
    private var pad: PinPad? = null
    private var work: Job? = null
    private var countdown: Job? = null
    private var first: CharArray? = null
    private var activeCleared: (() -> Unit)? = null
    private var busy = false
    private var active = false
    private var session = 0L

    private fun clearFirst() {
        first?.fill('\u0000')
        first = null
    }

    private fun runCleared() {
        val cleanup = activeCleared
        activeCleared = null
        cleanup?.invoke()
    }

    private fun cancelActiveWork() {
        countdown?.cancel()
        countdown = null
        work?.cancel()
        work = null
        busy = false
        clearFirst()
        runCleared()
    }

    private fun beginSession(onCleared: (() -> Unit)? = null): Pair<PinPad, Long> {
        session++
        cancelActiveWork()
        activeCleared = onCleared
        active = true
        val view = acquirePad()
        pad = view
        return view to session
    }

    fun handleBack(): Boolean {
        val view = pad?.takeIf { active } ?: return false
        // The unlock screen cannot be closed, so Back still leaves the app there, even mid-verification.
        if (!view.isCloseable) { moveTaskToBack(); return true }
        if (busy) return true
        view.onClose?.invoke()
        return true
    }

    fun hide(animated: Boolean = false) {
        session++
        active = false
        cancelActiveWork()
        pad = null
        releasePad(animated)
    }

    private fun clockText(milliseconds: Long): String {
        val seconds = ((milliseconds + 999) / 1000).coerceAtLeast(1)
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    private fun wrongPinTexts() = WrongPinTexts(text(R.string.lock_wrong_pin), text(R.string.lock_last_attempt), text(R.string.lock_wait))

    /**
     * Disables the keys and counts the wait down; the keys return when it is over. [attemptsLeft], when known, stays on
     * screen through the wait and after it, so the last attempt before the wipe is never a surprise.
     */
    private fun wait(view: PinPad, mySession: Long, milliseconds: Long, attemptsLeft: Int? = null) {
        countdown?.cancel()
        countdown = null
        if (session != mySession || !active || !padActive(view) || !scope.isActive) return
        val deadline = clock() + milliseconds
        view.setInputEnabled(false)
        val timer = scope.launch(start = CoroutineStart.LAZY) {
            val current = coroutineContext[Job]
            while (true) {
                if (session != mySession || !active || !padActive(view)) return@launch
                val left = deadline - clock()
                if (left <= 0) break
                view.showMessage(wrongPinTexts().duringWait(clockText(left), attemptsLeft), isError = true)
                delay(500)
            }
            if (session != mySession || !active || !padActive(view)) return@launch
            view.showMessage(attemptsLeft?.let { wrongPinTexts().afterWrongPin(it) }, isError = true)
            view.setInputEnabled(true)
            if (countdown === current) countdown = null
        }
        countdown = timer
        timer.start()
    }

    fun enter(
        title: Int, hint: Int?, closeable: Boolean, biometric: Boolean, forgot: Boolean,
        verify: suspend (CharArray) -> UnlockResult,
        onVerified: (CharArray) -> Unit,
        onBiometric: () -> Unit = {}, onForgot: () -> Unit = {}, onClose: () -> Unit = {}, onWiped: () -> Unit = {},
        keepOpen: Boolean = false,
    ) {
        val (view, mySession) = beginSession()
        view.configure(text(title), hint?.let(text), closeable, biometric, if (forgot) text(R.string.lock_forgot) else null)
        view.reset()
        view.setInputEnabled(true)
        view.setCloseEnabled(true)
        view.onBiometric = { if (!busy && session == mySession && active) onBiometric() }
        view.onForgot = { if (!busy && session == mySession && active) onForgot() }
        view.onClose = { if (!busy && session == mySession && active) { hide(animated = true); onClose() } }
        view.onComplete = { pin ->
            if (busy || session != mySession || !active || !padActive(view) || !scope.isActive) {
                pin.fill('\u0000')
            } else {
                busy = true
                view.setInputEnabled(false)
                view.setCloseEnabled(false)
                view.showMessage(text(R.string.lock_checking), isError = false)
                val job = scope.launch(start = CoroutineStart.LAZY) {
                    val current = coroutineContext[Job]
                    var verifiedCopy: CharArray? = null
                    try {
                        val result = try { verify(pin) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { UnlockResult.Unavailable }
                        if (session != mySession || !active || !padActive(view)) return@launch
                        // Clear work/busy before callbacks so a synchronous hide() or follow-up create() does not self-cancel.
                        if (work === current) work = null
                        busy = false
                        when (result) {
                            is UnlockResult.Success -> {
                                verifiedCopy = pin.copyOf()
                                pin.fill('\u0000')
                                if (!keepOpen) { view.succeed(); hide(animated = true) }
                                val copy = verifiedCopy
                                verifiedCopy = null
                                onVerified(copy)
                            }
                            is UnlockResult.WrongPin -> {
                                view.setCloseEnabled(true)
                                view.shake(); view.reset()
                                if (result.waitMs > 0) wait(view, mySession, result.waitMs, result.attemptsLeft)
                                else { view.showMessage(wrongPinTexts().afterWrongPin(result.attemptsLeft)); view.setInputEnabled(true) }
                            }
                            is UnlockResult.WaitRequired -> {
                                view.setCloseEnabled(true)
                                view.shake(); view.reset()
                                wait(view, mySession, result.remainingMs)
                            }
                            UnlockResult.Wiped -> { hide(); onWiped() }
                            UnlockResult.BiometricInvalid, UnlockResult.Unavailable -> {
                                view.setCloseEnabled(true)
                                view.shake(); view.reset(); view.showMessage(text(R.string.lock_unavailable)); view.setInputEnabled(true)
                            }
                        }
                    } finally {
                        pin.fill('\u0000')
                        verifiedCopy?.fill('\u0000')
                        if (work === current) { work = null; busy = false }
                    }
                }
                work = job
                job.invokeOnCompletion { pin.fill('\u0000') }
                if (!job.start() && !job.isActive && work === job) {
                    work = null
                    busy = false
                }
            }
        }
    }

    fun create(
        title: Int = R.string.lock_create_title, closeable: Boolean,
        accept: suspend (CharArray) -> Boolean,
        onClose: () -> Unit,
        onAccepted: () -> Unit = {},
        onCleared: () -> Unit = {},
    ) {
        val (view, mySession) = beginSession(onCleared)
        fun start() {
            clearFirst()
            view.configure(text(title), text(R.string.lock_create_hint), closeable, false, null)
            view.reset()
            view.setInputEnabled(true)
            view.setCloseEnabled(true)
        }
        start()
        view.onBiometric = null
        view.onForgot = null
        view.onClose = { if (!busy && session == mySession && active) { hide(animated = true); onClose() } }
        view.onComplete = { pin ->
            if (busy || session != mySession || !active || !padActive(view)) {
                pin.fill('\u0000')
            } else {
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
                    first = null
                    if (!scope.isActive) {
                        pin.fill('\u0000')
                        earlier.fill('\u0000')
                    } else {
                        busy = true
                        view.setInputEnabled(false)
                        view.setCloseEnabled(false)
                        view.showMessage(text(R.string.lock_checking), isError = false)
                        val job = scope.launch(start = CoroutineStart.LAZY) {
                            val current = coroutineContext[Job]
                            try {
                                val stored = try { accept(pin) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { false }
                                pin.fill('\u0000'); earlier.fill('\u0000')
                                if (session != mySession || !active || !padActive(view)) return@launch
                                if (work === current) work = null
                                busy = false
                                if (stored) {
                                    view.succeed()
                                    hide(animated = true)
                                    onAccepted()
                                } else {
                                    start()
                                    view.showMessage(text(R.string.storage_error))
                                }
                            } finally {
                                pin.fill('\u0000')
                                earlier.fill('\u0000')
                                if (work === current) { work = null; busy = false }
                            }
                        }
                        work = job
                        job.invokeOnCompletion {
                            pin.fill('\u0000')
                            earlier.fill('\u0000')
                        }
                        if (!job.start() && !job.isActive && work === job) {
                            work = null
                            busy = false
                        }
                    }
                } else {
                    view.shake(); pin.fill('\u0000')
                    start(); view.showMessage(text(R.string.lock_mismatch))
                }
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
