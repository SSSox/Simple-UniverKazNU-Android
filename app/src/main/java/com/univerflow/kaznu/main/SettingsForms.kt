package com.univerflow.kaznu.main

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.univerflow.kaznu.R
import com.univerflow.kaznu.AppState
import com.univerflow.kaznu.UniverViewModel
import com.univerflow.kaznu.web.LinkProfile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.domain.AppTheme
import com.univerflow.kaznu.ui.LanguageResources
import com.univerflow.kaznu.ui.ThemeMode
import com.univerflow.kaznu.ui.launchCatching
import com.univerflow.kaznu.databinding.SettingsSheetBinding
import com.univerflow.kaznu.ui.BiometricUi
import com.univerflow.kaznu.ui.Motion
import com.univerflow.kaznu.ui.missingBottomInset
import com.univerflow.kaznu.update.UpdatePhase
import com.univerflow.kaznu.update.UpdateState
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout

/**
 * One layout for the first-run sign-in form and the settings sheet. Labels follow the app language; the saved password
 * is never put back into a field.
 */
class SettingsForms(private val activity: AppCompatActivity, private val model: UniverViewModel, private val lock: LockFlow, private val host: Host) {
    interface Host : ScreenFeedback {
        val sessionChanging: Boolean
        fun save(form: SettingsSheetBinding, first: Boolean)
        /** Ends the session and removes the saved login (the one Sign out). */
        fun signOut()
        fun manualLogin(form: SettingsSheetBinding)
        fun openDownloads()
        fun setMobileLayout(enabled: Boolean, form: SettingsSheetBinding?)
        fun setTextZoom(percent: Int)
        val updateState: UpdateState
        val updateStatus: String
        fun checkUpdates()
        fun installUpdate()
        /** Asks for the notification permission where Android needs it (API 33+); [granted] hears the answer. */
        fun requestNotifications(granted: (Boolean) -> Unit)
    }

    /** Read once: [update] runs with every state change and every tick of an update's download while the sheet is open. */
    private val versionName by lazy(LazyThreadSafetyMode.NONE) {
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty()
    }
    private val languages = LanguageResources(activity)
    private var firstForm: SettingsSheetBinding? = null
    private var sheetForm: SettingsSheetBinding? = null
    private var sheet: BottomSheetDialog? = null

    /** The open page of the settings; a theme change recreates the screen and the sheet comes back on it. */
    var tab = 0

    val showing get() = sheet?.isShowing == true

    /** The first-run form in [container], created once and kept in step with the state. */
    fun showFirstRun(container: FrameLayout, storageError: Boolean) {
        val form = firstForm ?: make(first = true).also { form ->
            firstForm = form
            container.addView(form.root, FrameLayout.LayoutParams(firstRunWidth(), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL))
        }
        update(form, first = true)
        if (storageError) feedback(form, R.string.storage_error)
    }

    /** The screen turned without being recreated (MainActivity handles rotation): the form takes the new width. */
    fun resizeFirstRun() {
        val root = firstForm?.root ?: return
        val params = root.layoutParams ?: return
        val width = firstRunWidth()
        if (params.width != width) { params.width = width; root.layoutParams = params }
    }

    // A centred 560 dp column on a wide screen, the whole width on a phone.
    private fun firstRunWidth() = if (activity.resources.configuration.screenWidthDp >= 600)
        (560 * activity.resources.displayMetrics.density).toInt() else FrameLayout.LayoutParams.MATCH_PARENT

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
            tab = 0
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
        val form = SettingsSheetBinding.inflate(LayoutInflater.from(languages.of(model.state.value.language)))
        bindAccount(form, first)
        // update() sets the switches and the language; their listeners come after it, so filling them is no user change.
        update(form, first)
        form.save.setOnClickListener { host.save(form, first) }
        form.manualLogin.setOnClickListener { host.manualLogin(form) }
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
        form.settingsTabs.isVisible = !first
        if (first) form.tabApp.isVisible = false else bindTabs(form)
        form.editAccount.setOnClickListener {
            toggle(form, form.credentials)
            if (form.credentials.isVisible) form.login.requestFocus()
        }
        form.moreOptions.setOnClickListener { toggle(form, form.preferences) }
        form.closeSettings.setOnClickListener { dismiss() }
        form.downloads.setOnClickListener { dismiss(); host.openDownloads() }
        form.login.doAfterTextChanged { form.loginLayout.error = null }
        form.password.doAfterTextChanged { form.passwordLayout.error = null }
        // The saved password is never put back into a field: the show-password toggle would reveal it to whoever holds the phone.
        if (!first) form.login.setText(model.credentials?.login.orEmpty())
        form.credentialsNote.isVisible = true
    }

    /** Account, language and view, the app: one page at a time. */
    private fun bindTabs(form: SettingsSheetBinding) {
        listOf(R.string.tab_account, R.string.tab_view, R.string.tab_app).forEach { form.settingsTabs.addTab(form.settingsTabs.newTab().setText(host.text(it)), false) }
        form.settingsTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(selected: TabLayout.Tab) { tab = selected.position; showTab(form) }
            override fun onTabUnselected(selected: TabLayout.Tab) = Unit
            override fun onTabReselected(selected: TabLayout.Tab) = Unit
        })
        form.settingsTabs.getTabAt(tab)?.select()
        showTab(form)
    }

    private fun showTab(form: SettingsSheetBinding) {
        form.tabAccount.isVisible = tab == 0
        form.tabView.isVisible = tab == 1
        form.tabApp.isVisible = tab == 2
    }

    /** A section opens with the rows below it moving aside, not jumping. */
    private fun toggle(form: SettingsSheetBinding, section: View) {
        Motion.layout(form.root.getChildAt(0) as ViewGroup)
        section.isVisible = !section.isVisible
    }

    /** Language, theme, reading layout, animations and text size. */
    private fun bindPreferences(form: SettingsSheetBinding) {
        form.languages.setOnCheckedChangeListener { _, checked ->
            val language = when (checked) {
                R.id.kazakh -> AppLanguage.KAZAKH
                R.id.english -> AppLanguage.ENGLISH
                else -> AppLanguage.RUSSIAN
            }
            if (language != model.state.value.language && !host.sessionChanging)
                activity.launchCatching({ feedback(form, R.string.storage_error) }) { model.setLanguage(language) }
        }
        themeChoices(form).forEach { (card, theme) -> card.setOnClickListener { setTheme(theme, form) } }
        form.mobileLayout.setOnCheckedChangeListener { _, checked -> host.setMobileLayout(checked, form) }
        form.animations.setOnCheckedChangeListener { _, checked -> setAnimations(checked, form) }
        form.externalLinks.setOnCheckedChangeListener { _, checked -> setExternalLinks(checked, form) }
        form.classReminders.setOnCheckedChangeListener { _, checked -> setReminders(checked, null, form) }
        form.reminderLead.addOnButtonCheckedListener { _, id, checked -> if (checked) leadOf(form, id)?.let { setReminders(null, it, form) } }
        form.linkDataClear.setOnClickListener { clearLinkData(form) }
        form.textSize.setLabelFormatter { activity.getString(R.string.percent_value, it.toInt()) }
        form.textSize.addOnChangeListener { _, value, fromUser ->
            form.textSizeValue.text = activity.getString(R.string.percent_value, value.toInt())
            if (fromUser) host.setTextZoom(value.toInt())
        }
    }

    /** Sign out, PIN and biometrics. */
    private fun bindSecurity(form: SettingsSheetBinding) {
        form.logout.setOnClickListener { confirmSignOut() }
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
    }

    /**
     * Sign out from Settings or from the site's own Logout link. There is one kind: a sign-out that kept the login for a
     * one-tap return protected nothing (anyone holding the phone could tap it) and left a banner over the site.
     */
    fun confirmSignOut() {
        if (host.sessionChanging) return
        MaterialAlertDialogBuilder(activity)
            .setTitle(host.text(R.string.logout_confirm_title)).setMessage(host.text(R.string.logout_confirm_message))
            .setNegativeButton(host.text(R.string.cancel), null)
            .setPositiveButton(host.text(R.string.logout_action)) { _, _ -> host.signOut() }.show().also(host::track)
    }

    private fun themeChoices(form: SettingsSheetBinding) =
        listOf(form.themeSystem to AppTheme.SYSTEM, form.themeLight to AppTheme.LIGHT, form.themeDark to AppTheme.DARK)

    private fun leads(form: SettingsSheetBinding) =
        listOf(form.reminderLead5 to 5, form.reminderLead10 to 10, form.reminderLead15 to 15, form.reminderLead30 to 30)

    private fun leadOf(form: SettingsSheetBinding, id: Int) = leads(form).firstOrNull { it.first.id == id }?.second

    /**
     * Turning reminders on asks Android for notifications first; a refusal leaves them off and says where to allow them.
     * Only a real change is saved: the form being brought in step with the state calls here too.
     */
    private fun setReminders(enabled: Boolean?, lead: Int?, form: SettingsSheetBinding) {
        val current = model.state.value.reminders
        val next = current.copy(enabled = enabled ?: current.enabled, lead = lead ?: current.lead)
        if (next == current || host.sessionChanging) return
        val save = { activity.launchCatching({ feedback(form, R.string.storage_error); update(form, isFirst(form)) }) { model.setReminders(next) } }
        if (next.enabled && !current.enabled) host.requestNotifications { granted ->
            if (granted) save() else { feedback(form, R.string.notifications_denied); update(form, isFirst(form)) }
        } else save()
    }

    private fun updateReminders(form: SettingsSheetBinding, state: AppState) {
        form.remindersLabel.text = host.text(R.string.reminders_section)
        form.classReminders.text = host.text(R.string.class_reminders)
        if (form.classReminders.isChecked != state.reminders.enabled) form.classReminders.isChecked = state.reminders.enabled
        form.classRemindersHint.text = host.text(if (state.reminders.enabled) R.string.class_reminders_on else R.string.class_reminders_hint)
        form.reminderLead.isVisible = state.reminders.enabled
        leads(form).forEach { (button, minutes) ->
            button.text = host.text(R.string.reminder_lead).format(minutes)
            button.contentDescription = host.text(R.string.reminder_lead_description).format(minutes)
        }
        leads(form).firstOrNull { it.second == state.reminders.lead }?.first?.id?.let { if (form.reminderLead.checkedButtonId != it) form.reminderLead.check(it) }
    }

    private fun setExternalLinks(enabled: Boolean, form: SettingsSheetBinding) {
        if (enabled == model.state.value.externalLinks || !LinkProfile.available()) return
        activity.launchCatching({ feedback(form, R.string.storage_error); update(form, isFirst(form)) }) { model.setExternalLinks(enabled) }
    }

    /** Success is reported only once WebView confirmed the link window's data gone; Univer's own session stays. */
    private fun clearLinkData(form: SettingsSheetBinding) {
        form.linkDataClear.isEnabled = false
        activity.lifecycleScope.launch {
            val cleared = try { LinkProfile.clear() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
            form.linkDataClear.isEnabled = true
            form.linkDataHint.tag = if (cleared) R.string.link_data_cleared else R.string.link_data_clear_failed
            form.linkDataHint.text = host.text(form.linkDataHint.tag as Int)
        }
    }

    private fun setAnimations(enabled: Boolean, form: SettingsSheetBinding) {
        if (enabled == model.state.value.animations) return
        activity.launchCatching({ feedback(form, R.string.storage_error); update(form, isFirst(form)) }) { model.setAnimations(enabled) }
    }

    /** Saved first, then applied: AppCompat recreates the open screens in the new theme (the sheet reopens with them). */
    private fun setTheme(theme: AppTheme, form: SettingsSheetBinding) {
        if (theme == model.state.value.theme) return
        activity.launchCatching({ feedback(form, R.string.storage_error); update(form, isFirst(form)) }) {
            model.setTheme(theme); ThemeMode.apply(theme)
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
        form.accountStatus.text = text(if (state.manualLogin) R.string.account_manual else if (state.paused) R.string.account_paused else R.string.account_saved)
        form.editAccount.isVisible = !first
        form.editAccount.text = text(R.string.edit_account)
        form.moreOptions.isVisible = first
        form.moreOptions.text = text(R.string.more_options)
        form.downloads.isVisible = !first
        form.downloads.text = text(R.string.downloads)
        form.title.text = text(if (first) R.string.welcome else R.string.settings)
        form.subtitle.text = text(R.string.welcome_hint)
        // Explanatory text is only part of the first-run screen; settings show controls alone.
        form.subtitle.isVisible = first
        form.credentialsNote.text = text(if (first) R.string.secure_hint else R.string.password_not_shown)
        form.loginLayout.hint = text(R.string.login)
        form.passwordLayout.hint = text(R.string.password)
        form.passwordLayout.endIconContentDescription = text(R.string.show_password)
        form.languageLabel.text = text(R.string.language)
        form.themeLabel.text = text(R.string.theme)
        form.languageHint.text = text(R.string.language_hint)
        form.themeSystemLabel.text = text(R.string.theme_system)
        form.themeLightLabel.text = text(R.string.theme_light)
        form.themeDarkLabel.text = text(R.string.theme_dark)
        themeChoices(form).forEach { (card, theme) -> card.isChecked = theme == state.theme }
        form.themeSystem.contentDescription = form.themeSystemLabel.text
        form.themeLight.contentDescription = form.themeLightLabel.text
        form.themeDark.contentDescription = form.themeDarkLabel.text
        form.save.text = text(if (first) R.string.save_enter else R.string.save)
        form.manualLogin.text = text(R.string.manual_login)
        form.manualLogin.isVisible = first
        form.manualLogin.isEnabled = !host.sessionChanging
        form.logoutTitle.text = text(R.string.logout)
        form.logoutHint.text = text(R.string.logout_hint)
        form.logout.text = text(R.string.logout_action)
        form.about.text = languages.of(state.language).getString(R.string.about, versionName)
        (0 until form.settingsTabs.tabCount).forEach { index ->
            form.settingsTabs.getTabAt(index)?.text = text(listOf(R.string.tab_account, R.string.tab_view, R.string.tab_app)[index])
        }
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
        updateLinks(form, state)
        updateReminders(form, state)
        (form.feedback.tag as? Int)?.let { form.feedback.text = text(it) }
        form.accountActions.isVisible = !first
        val selected = when (state.language) {
            AppLanguage.RUSSIAN -> R.id.russian
            AppLanguage.KAZAKH -> R.id.kazakh
            AppLanguage.ENGLISH -> R.id.english
        }
        if (form.languages.checkedRadioButtonId != selected) form.languages.check(selected)
        updateSecurity(form, first)
        updateApp(form, first)
    }

    /** Without separate WebView profiles links can only go to the browser: the switch shows that and the data row goes. */
    private fun updateLinks(form: SettingsSheetBinding, state: AppState) {
        val available = LinkProfile.available()
        form.linksLabel.text = host.text(R.string.links_section)
        form.externalLinks.text = host.text(R.string.external_links)
        form.externalLinks.isEnabled = available
        val external = state.externalLinks || !available
        if (form.externalLinks.isChecked != external) form.externalLinks.isChecked = external
        form.externalLinksHint.text = host.text(if (available) R.string.external_links_hint else R.string.external_links_unavailable)
        form.linkDataDivider.isVisible = available
        form.linkDataRow.isVisible = available
        form.linkDataTitle.text = host.text(R.string.link_data)
        form.linkDataHint.text = host.text(form.linkDataHint.tag as? Int ?: R.string.link_data_hint)
        form.linkDataClear.text = host.text(R.string.link_data_clear)
    }

    /** The update check always runs; this section stays even for a version whose dialog was turned off. */
    private fun updateApp(form: SettingsSheetBinding, first: Boolean) {
        form.updateSection.isVisible = !first
        if (first) return
        val update = host.updateState
        val idle = update.phase == UpdatePhase.IDLE
        form.updateLabel.text = host.text(R.string.update_section)
        // Download percentages tick many times a second; only a settled status is worth animating.
        if (idle) Motion.text(form.updateStatus, host.updateStatus) else form.updateStatus.text = host.updateStatus
        form.updateInstall.text = host.text(R.string.update_install)
        form.updateInstall.isVisible = update.available != null
        form.updateInstall.isEnabled = idle
        form.updateCheck.text = host.text(R.string.update_check)
        form.updateCheck.isEnabled = idle
    }

    private fun updateSecurity(form: SettingsSheetBinding, first: Boolean) {
        form.securitySection.isVisible = !first && model.state.value.hasCredentials
        if (first || !model.state.value.hasCredentials) return
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
