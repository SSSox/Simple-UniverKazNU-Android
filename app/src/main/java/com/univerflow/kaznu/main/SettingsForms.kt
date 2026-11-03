package com.univerflow.kaznu.main

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.univerflow.kaznu.R
import com.univerflow.kaznu.UniverViewModel
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.domain.AppTheme
import com.univerflow.kaznu.ui.ThemeMode
import com.univerflow.kaznu.ui.context
import com.univerflow.kaznu.databinding.SettingsSheetBinding
import com.univerflow.kaznu.ui.BiometricUi
import com.univerflow.kaznu.ui.missingBottomInset
import com.univerflow.kaznu.update.UpdatePhase
import com.univerflow.kaznu.update.UpdateState
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * One layout for the first-run sign-in form and the settings sheet. Labels follow the app language; the saved password
 * is never put back into a field.
 */
class SettingsForms(private val activity: AppCompatActivity, private val model: UniverViewModel, private val lock: LockFlow, private val host: Host) {
    interface Host : ScreenFeedback {
        val sessionChanging: Boolean
        fun save(form: SettingsSheetBinding, first: Boolean)
        fun endSession(delete: Boolean)
        fun openDownloads()
        fun setMobileLayout(enabled: Boolean, form: SettingsSheetBinding?)
        fun setTextZoom(percent: Int)
        val updateState: UpdateState
        val updateStatus: String
        fun checkUpdates()
        fun installUpdate()
    }

    private var firstForm: SettingsSheetBinding? = null
    private var sheetForm: SettingsSheetBinding? = null
    private var sheet: BottomSheetDialog? = null

    val showing get() = sheet?.isShowing == true

    /** The first-run form in [container], created once and kept in step with the state. */
    fun showFirstRun(container: FrameLayout, storageError: Boolean) {
        val form = firstForm ?: make(first = true).also { form ->
            firstForm = form
            val wide = activity.resources.configuration.screenWidthDp >= 600
            container.addView(form.root, FrameLayout.LayoutParams(
                if (wide) (560 * activity.resources.displayMetrics.density).toInt() else FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL))
        }
        update(form, first = true)
        if (storageError) feedback(form, R.string.storage_error)
    }

    fun closeFirstRun(container: FrameLayout) {
        firstForm?.password?.text?.clear()
        firstForm = null
        container.removeAllViews()
    }

    fun isFirst(form: SettingsSheetBinding) = form === firstForm

    /** Re-labels the open sheet after a state change. */
    fun refresh() { sheetForm?.let { update(it, first = false) } }

    fun dismiss() { sheet?.dismiss() }

    /** A failure of the open sheet's last action, shown inside it. */
    fun sheetError(id: Int) { sheetForm?.let { feedback(it, id) } }

    fun show() {
        if (showing || host.sessionChanging) return
        val dialog = BottomSheetDialog(activity)
        val form = make(first = false)
        sheetForm = form
        sheet = dialog
        // The sheet has its own window: the main screen's inset listener cannot protect its last actions.
        // Material pads the sheet container by the same insets first, so only the remainder is added here.
        ViewCompat.setOnApplyWindowInsetsListener(form.root) { view, insets ->
            val needed = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()).bottom
            val applied = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.paddingBottom ?: 0
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, missingBottomInset(needed, applied))
            insets
        }
        dialog.setContentView(form.root)
        dialog.setOnShowListener {
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
        }
        dialog.setOnDismissListener {
            form.password.text?.clear()
            sheetForm = null
            sheet = null
        }
        dialog.show()
        ViewCompat.requestApplyInsets(form.root)
    }

    fun feedback(form: SettingsSheetBinding, id: Int) {
        form.credentials.isVisible = true
        form.feedback.tag = id
        form.feedback.text = host.text(id)
        form.feedback.isVisible = true
    }

    private fun make(first: Boolean): SettingsSheetBinding {
        val form = SettingsSheetBinding.inflate(LayoutInflater.from(model.state.value.language.context(activity)))
        bindAccount(form, first)
        // update() sets the switches and the language; their listeners come after it, so filling them is no user change.
        update(form, first)
        form.save.setOnClickListener { host.save(form, first) }
        form.password.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE && form.save.isEnabled) { host.save(form, first); true } else false
        }
        bindPreferences(form)
        bindSecurity(form)
        form.updateCheck.setOnClickListener { host.checkUpdates() }
        form.updateInstall.setOnClickListener { host.installUpdate() }
        return form
    }

    /** Login and password, the account summary and the sheet's own navigation. */
    private fun bindAccount(form: SettingsSheetBinding, first: Boolean) {
        form.credentials.isVisible = first
        form.preferences.isVisible = !first
        form.editAccount.setOnClickListener {
            form.credentials.isVisible = !form.credentials.isVisible
            if (form.credentials.isVisible) form.login.requestFocus()
        }
        form.moreOptions.setOnClickListener { form.preferences.isVisible = !form.preferences.isVisible }
        form.closeSettings.setOnClickListener { dismiss() }
        form.downloads.setOnClickListener { dismiss(); host.openDownloads() }
        form.login.doAfterTextChanged { form.loginLayout.error = null }
        form.password.doAfterTextChanged { form.passwordLayout.error = null }
        // The saved password is never put back into a field: the show-password toggle would reveal it to whoever holds the phone.
        if (!first) form.login.setText(model.credentials?.login.orEmpty())
        form.credentialsNote.isVisible = !first
    }

    /** Language, theme, reading layout, animations and text size. */
    private fun bindPreferences(form: SettingsSheetBinding) {
        form.languages.setOnCheckedChangeListener { _, checked ->
            val language = when (checked) {
                R.id.kazakh -> AppLanguage.KAZAKH
                R.id.english -> AppLanguage.ENGLISH
                else -> AppLanguage.RUSSIAN
            }
            if (language != model.state.value.language && !host.sessionChanging) activity.lifecycleScope.launch {
                try { model.setLanguage(language) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { feedback(form, R.string.storage_error) }
            }
        }
        form.themes.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            setTheme(when (id) { R.id.theme_light -> AppTheme.LIGHT; R.id.theme_dark -> AppTheme.DARK; else -> AppTheme.SYSTEM }, form)
        }
        form.mobileLayout.setOnCheckedChangeListener { _, checked -> host.setMobileLayout(checked, form) }
        form.animations.setOnCheckedChangeListener { _, checked -> setAnimations(checked, form) }
        form.textSize.setLabelFormatter { activity.getString(R.string.percent_value, it.toInt()) }
        form.textSize.addOnChangeListener { _, value, fromUser ->
            form.textSizeValue.text = activity.getString(R.string.percent_value, value.toInt())
            if (fromUser) host.setTextZoom(value.toInt())
        }
    }

    /** Log out, PIN and biometrics, and removing the saved credentials. */
    private fun bindSecurity(form: SettingsSheetBinding) {
        form.logout.setOnClickListener { host.endSession(false) }
        form.securityPinAction.setOnClickListener {
            dismiss()
            if (model.state.value.lock.enabled) lock.changePin() else lock.startPinSetup()
        }
        form.securityPinDisable.setOnClickListener { dismiss(); lock.disablePin() }
        form.securityBiometric.setOnCheckedChangeListener { _, checked ->
            if (form.securityBiometric.tag != null) return@setOnCheckedChangeListener
            when {
                !checked -> lock.removeBiometric()
                !BiometricUi.available(activity) -> { host.message(R.string.bio_unavailable); updateSecurity(form, first = false) }
                else -> lock.enrollBiometric { updateSecurity(form, first = false) }
            }
        }
        form.delete.setOnClickListener {
            MaterialAlertDialogBuilder(activity)
                .setIcon(R.drawable.ic_delete)
                .setTitle(host.text(R.string.delete_title)).setMessage(host.text(R.string.delete_message))
                .setNegativeButton(host.text(R.string.cancel), null)
                .setPositiveButton(host.text(R.string.delete_action)) { _, _ -> host.endSession(true) }.show().also(host::track)
        }
    }

    private fun setAnimations(enabled: Boolean, form: SettingsSheetBinding) {
        if (enabled == model.state.value.animations) return
        activity.lifecycleScope.launch {
            try { model.setAnimations(enabled) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { feedback(form, R.string.storage_error); update(form, isFirst(form)) }
        }
    }

    /** Saved first, then applied: AppCompat recreates the open screens in the new theme (the sheet reopens with them). */
    private fun setTheme(theme: AppTheme, form: SettingsSheetBinding) {
        if (theme == model.state.value.theme) return
        activity.lifecycleScope.launch {
            try { model.setTheme(theme); ThemeMode.apply(theme) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { feedback(form, R.string.storage_error); update(form, isFirst(form)) }
        }
    }

    fun update(form: SettingsSheetBinding, first: Boolean) {
        val state = model.state.value
        val text = host::text
        form.handle.isVisible = !first
        form.brandBadge.isVisible = first
        form.closeSettings.isVisible = !first
        form.closeSettings.text = text(R.string.done)
        form.accountSummary.isVisible = !first
        form.accountTitle.text = text(R.string.account)
        form.accountStatus.text = text(if (state.paused) R.string.account_paused else R.string.account_saved)
        form.editAccount.isVisible = !first
        form.editAccount.text = text(R.string.edit_account)
        form.moreOptions.isVisible = first
        form.moreOptions.text = text(R.string.more_options)
        form.downloads.isVisible = !first
        form.downloads.text = text(R.string.downloads)
        form.title.text = text(if (first) R.string.welcome else R.string.settings)
        form.subtitle.text = if (first) text(R.string.welcome_hint) + "\n\n" + text(R.string.secure_hint) else text(R.string.secure_hint)
        // Explanatory text is only part of the first-run screen; settings show controls alone.
        form.subtitle.isVisible = first
        form.loginLayout.hint = text(R.string.login)
        form.passwordLayout.hint = text(R.string.password)
        form.passwordLayout.endIconContentDescription = text(R.string.show_password)
        form.languageLabel.text = text(R.string.language)
        form.viewLabel.text = text(R.string.view_section)
        form.themeLabel.text = text(R.string.theme)
        form.themeSystem.text = text(R.string.theme_system)
        form.themeLight.text = text(R.string.theme_light)
        form.themeDark.text = text(R.string.theme_dark)
        form.themeHint.text = text(R.string.theme_hint)
        val theme = when (state.theme) { AppTheme.SYSTEM -> R.id.theme_system; AppTheme.LIGHT -> R.id.theme_light; AppTheme.DARK -> R.id.theme_dark }
        if (form.themes.checkedButtonId != theme) form.themes.check(theme)
        form.save.text = text(if (first) R.string.save_enter else R.string.save)
        form.logout.text = text(R.string.logout)
        form.delete.text = text(R.string.delete_credentials)
        form.mobileLayout.text = text(R.string.mobile_layout)
        form.mobileHint.text = text(R.string.mobile_hint)
        form.textSizeLabel.text = text(R.string.text_size)
        form.textSize.contentDescription = text(R.string.text_size)
        if (form.textSize.value.toInt() != state.textZoom) form.textSize.value = state.textZoom.toFloat()
        form.textSizeValue.text = activity.getString(R.string.percent_value, state.textZoom)
        if (form.mobileLayout.isChecked != state.mobileLayout) form.mobileLayout.isChecked = state.mobileLayout
        form.animations.text = text(R.string.animations)
        form.animationsHint.text = text(R.string.animations_hint)
        if (form.animations.isChecked != state.animations) form.animations.isChecked = state.animations
        (form.feedback.tag as? Int)?.let { form.feedback.text = text(it) }
        form.logout.isVisible = !first
        form.delete.isVisible = !first
        val selected = when (state.language) {
            AppLanguage.RUSSIAN -> R.id.russian
            AppLanguage.KAZAKH -> R.id.kazakh
            AppLanguage.ENGLISH -> R.id.english
        }
        if (form.languages.checkedRadioButtonId != selected) form.languages.check(selected)
        updateSecurity(form, first)
        updateApp(form, first)
    }

    /** The update check always runs; this section stays even for a version whose dialog was turned off. */
    private fun updateApp(form: SettingsSheetBinding, first: Boolean) {
        form.updateSection.isVisible = !first
        if (first) return
        val update = host.updateState
        val idle = update.phase == UpdatePhase.IDLE
        form.updateLabel.text = host.text(R.string.update_section)
        form.updateStatus.text = host.updateStatus
        form.updateInstall.text = host.text(R.string.update_install)
        form.updateInstall.isVisible = update.available != null
        form.updateInstall.isEnabled = idle
        form.updateCheck.text = host.text(R.string.update_check)
        form.updateCheck.isEnabled = idle
    }

    private fun updateSecurity(form: SettingsSheetBinding, first: Boolean) {
        form.securitySection.isVisible = !first
        if (first) return
        val lockState = model.state.value.lock
        form.securityLabel.text = host.text(R.string.security_title)
        form.securityStatus.text = host.text(if (lockState.enabled) R.string.security_status_on else R.string.security_status_off)
        form.securityPinAction.text = host.text(if (lockState.enabled) R.string.security_pin_change else R.string.security_pin_set)
        form.securityPinDisable.isVisible = lockState.enabled
        form.securityPinDisable.text = host.text(R.string.security_pin_disable)
        form.securityBiometric.isVisible = lockState.enabled
        form.securityBiometric.text = host.text(R.string.security_biometric)
        form.securityBiometric.isEnabled = BiometricUi.available(activity) || lockState.biometric
        // A change made here is not the student's tap: the switch listener ignores it.
        form.securityBiometric.tag = "sync"
        form.securityBiometric.isChecked = lockState.biometric
        form.securityBiometric.tag = null
    }
}
