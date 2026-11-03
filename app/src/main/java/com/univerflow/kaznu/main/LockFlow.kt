package com.univerflow.kaznu.main

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.univerflow.kaznu.AppState
import com.univerflow.kaznu.R
import com.univerflow.kaznu.UniverViewModel
import com.univerflow.kaznu.domain.UnlockResult
import com.univerflow.kaznu.ui.BiometricUi
import com.univerflow.kaznu.ui.LockUi
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The app lock on the main screen: the unlock screen over the page, the one-time PIN offer, setting,
 * changing and turning off the PIN, biometric enrolment and unlock, and the window's screenshot protection.
 */
class LockFlow(private val activity: AppCompatActivity, private val model: UniverViewModel, private val lockUi: LockUi, private val host: Host) {
    interface Host : ScreenFeedback {
        val sessionChanging: Boolean
        /** A signed-in service (Moodle) is open: its pages are always kept out of screenshots. */
        val serviceOpen: Boolean
        /** Hides the page from view and accessibility while the account is locked, and pauses automation; or shows it again. */
        fun coverPage(covered: Boolean)
        fun dismissDialogs()
        /** "Forgot the PIN": erase the account here; signing in again is the only way back. */
        fun eraseAccount()
    }

    /** Whether the activity is resumed; with a PIN the window is secure whenever it is not. */
    var inForeground = false
    // The PIN offer is made once per run; a rotation with the dialog open does not make it twice.
    private var offerShown = false
    private var biometricPrompting = false
    // The biometric prompt opens by itself once per lock; after that it is the student's choice.
    private var biometricAutoShown = false
    // Set while the unlock screen is up: whether it offers the biometric key.
    private var unlockBiometric: Boolean? = null

    val showing get() = lockUi.isShowing

    fun restore(saved: Bundle?) { offerShown = saved?.getBoolean(OFFER_SHOWN) ?: false }

    fun save(outState: Bundle) { outState.putBoolean(OFFER_SHOWN, offerShown) }

    /** The window is secure (no screenshots, no recent-apps picture) wherever an account or a PIN could show. */
    fun forceSecure() = lockUi.isShowing || (model.state.value.lock.enabled && !inForeground)

    fun applyWindowSecurity() {
        val state = model.state.value
        val secure = !state.hasCredentials || host.serviceOpen || forceSecure()
        if (secure) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 33) activity.setRecentsScreenshotEnabled(!state.lock.enabled)
    }

    fun showUnlockScreen(state: AppState) {
        host.dismissDialogs()
        host.coverPage(true)
        val biometric = state.lock.biometric && BiometricUi.available(activity)
        if (lockUi.isShowing && unlockBiometric == biometric) { applyWindowSecurity(); autoPromptBiometric(); return }
        unlockBiometric = biometric
        lockUi.enter(R.string.lock_unlock_title, R.string.lock_unlock_hint, closeable = false, biometric = biometric, forgot = true,
            verify = { model.unlockWithPin(it) }, onVerified = { it.fill('\u0000') },
            onBiometric = ::promptBiometricUnlock, onForgot = ::confirmForgottenPin, onWiped = { host.message(R.string.lock_wiped) })
        applyWindowSecurity()
        autoPromptBiometric()
    }

    fun releaseUnlockScreen() {
        if (unlockBiometric == null) return
        unlockBiometric = null
        biometricAutoShown = false
        lockUi.hide()
        host.coverPage(false)
        applyWindowSecurity()
    }

    fun autoPromptBiometric() {
        if (unlockBiometric != true || biometricAutoShown || !inForeground || !model.state.value.locked) return
        biometricAutoShown = true
        activity.window.decorView.post { promptBiometricUnlock() }
    }

    private fun promptBiometricUnlock() {
        if (biometricPrompting || !model.state.value.locked) return
        biometricPrompting = true
        activity.lifecycleScope.launch {
            val cipher = try { model.biometricUnlockCipher() }
            catch (cancelled: CancellationException) { biometricPrompting = false; throw cancelled }
            catch (_: Exception) { null }
            if (cipher == null) { biometricPrompting = false; host.message(R.string.bio_changed); return@launch }
            BiometricUi.prompt(activity, cipher, host.text(R.string.bio_prompt_title), host.text(R.string.bio_prompt_subtitle), host.text(R.string.bio_use_pin),
                onSuccess = { opened ->
                    activity.lifecycleScope.launch {
                        val result = try { model.unlockWithBiometric(opened) } catch (cancelled: CancellationException) { biometricPrompting = false; throw cancelled }
                        catch (_: Exception) { UnlockResult.Unavailable }
                        biometricPrompting = false
                        if (result is UnlockResult.BiometricInvalid) host.message(R.string.bio_changed)
                        else if (result !is UnlockResult.Success) host.message(R.string.lock_unavailable)
                    }
                },
                onFailure = { biometricPrompting = false })
        }
    }

    /** "Forgot the PIN": the only way back is to erase the account here and sign in again, which keeps the PIN unguessable. */
    private fun confirmForgottenPin() {
        host.track(MaterialAlertDialogBuilder(activity).setIcon(R.drawable.ic_delete)
            .setTitle(host.text(R.string.lock_forgot_title)).setMessage(host.text(R.string.lock_forgot_message))
            .setNegativeButton(host.text(R.string.cancel), null)
            .setPositiveButton(host.text(R.string.lock_forgot_action)) { _, _ -> host.eraseAccount() }.show())
    }

    /** Offers the PIN once to anyone who has an account and has not answered yet (new sign-ins and existing users alike). */
    fun maybeOfferPin() {
        val early = model.state.value
        if (offerShown || !early.ready || !early.hasCredentials || early.lock.enabled || early.lock.offerHandled) return
        activity.window.decorView.postDelayed({
            val state = model.state.value
            if (offerShown || activity.isFinishing || activity.isDestroyed || !state.ready || state.locked || !state.hasCredentials || state.paused ||
                state.lock.enabled || state.lock.offerHandled || model.credentials == null || host.sessionChanging || lockUi.isShowing) return@postDelayed
            offerShown = true
            host.track(MaterialAlertDialogBuilder(activity).setIcon(R.drawable.ic_lock)
                .setTitle(host.text(R.string.offer_pin_title)).setMessage(host.text(R.string.offer_pin_message)).setCancelable(false)
                .setPositiveButton(host.text(R.string.offer_pin_set)) { _, _ -> answerPinOffer(); startPinSetup() }
                .setNegativeButton(host.text(R.string.offer_pin_later)) { _, _ -> answerPinOffer() }.show())
        }, 900)
    }

    private fun answerPinOffer() {
        activity.lifecycleScope.launch {
            try { model.markLockOfferHandled() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
        }
    }

    /** New PIN twice, then (when the phone has biometrics) the offer to sign in faster with them. */
    fun startPinSetup() {
        if (model.credentials == null || model.state.value.lock.enabled) return
        lockUi.create(closeable = true, accept = { pin ->
            try {
                model.enableLock(pin)
                // The PIN screen closes right after this returns; the message and the biometric offer follow it.
                activity.window.decorView.post { host.message(R.string.pin_enabled_msg); offerBiometric(); applyWindowSecurity() }
                true
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
        }, onClose = { applyWindowSecurity() })
        applyWindowSecurity()
    }

    private fun offerBiometric() {
        if (!BiometricUi.available(activity) || model.state.value.lock.biometric) return
        host.track(MaterialAlertDialogBuilder(activity).setIcon(R.drawable.ic_fingerprint)
            .setTitle(host.text(R.string.offer_bio_title)).setMessage(host.text(R.string.offer_bio_message)).setCancelable(false)
            .setPositiveButton(host.text(R.string.offer_bio_enable)) { _, _ -> enrollBiometric {} }
            .setNegativeButton(host.text(R.string.offer_bio_later), null).show())
    }

    /** Asks for the fingerprint or face that will open the PIN-protected key; [done] runs whatever the outcome. */
    fun enrollBiometric(done: () -> Unit) {
        activity.lifecycleScope.launch {
            val cipher = try { model.biometricEnrollCipher() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { host.message(R.string.bio_unavailable); done(); return@launch }
            BiometricUi.prompt(activity, cipher, host.text(R.string.bio_prompt_title), host.text(R.string.bio_prompt_enroll_subtitle), host.text(R.string.cancel),
                onSuccess = { ready ->
                    activity.lifecycleScope.launch {
                        try { model.finishBiometric(ready); host.message(R.string.bio_enabled_msg) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { host.message(R.string.storage_error) }
                        done()
                    }
                },
                onFailure = { done() })
        }
    }

    fun removeBiometric() {
        activity.lifecycleScope.launch {
            try { model.removeBiometric() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { host.message(R.string.storage_error) }
        }
    }

    /** Current PIN, then a new one twice. Nothing changes until the new PIN is stored. */
    fun changePin() {
        lockUi.enter(R.string.lock_verify_title, null, closeable = true, biometric = false, forgot = false,
            verify = { model.unlockWithPin(it) }, keepOpen = true, onWiped = { host.message(R.string.lock_wiped) },
            onVerified = { old ->
                lockUi.create(R.string.lock_new_title, closeable = true, accept = { new ->
                    try {
                        when (model.changePin(old, new)) {
                            is UnlockResult.Success -> { host.message(R.string.pin_changed_msg); true }
                            UnlockResult.Wiped -> { host.message(R.string.lock_wiped); true }
                            else -> false
                        }
                    } finally { old.fill('\u0000') }
                }, onClose = { old.fill('\u0000'); applyWindowSecurity() })
            }, onClose = { applyWindowSecurity() })
        applyWindowSecurity()
    }

    /** Turning the PIN off asks for it first: a borrowed phone cannot simply remove the protection. */
    fun disablePin() {
        lockUi.enter(R.string.security_disable_title, R.string.security_disable_message, closeable = true, biometric = false, forgot = false,
            verify = { model.disableLock(it) }, onWiped = { host.message(R.string.lock_wiped) },
            onVerified = { it.fill('\u0000'); host.message(R.string.pin_disabled_msg); applyWindowSecurity() }, onClose = { applyWindowSecurity() })
        applyWindowSecurity()
    }

    private companion object { const val OFFER_SHOWN = "pin_offer_shown" }
}
