package com.univerflow.kaznu.main

import android.content.res.ColorStateList
import androidx.core.view.isVisible
import com.univerflow.kaznu.R
import com.univerflow.kaznu.databinding.ActivityMainBinding
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.ui.Motion
import com.univerflow.kaznu.web.AutomationStatus

/**
 * The strip under the app bar: a paused session (information) or an automation step that failed (it needs the
 * student), with Sign in or Retry. What the strip does on a tap stays with the screen.
 */
class StatusBanner(private val binding: ActivityMainBinding) {
    private data class Shown(val message: Int?, val paused: Boolean, val action: Boolean, val language: AppLanguage)
    private var shown: Shown? = null

    fun hide() { binding.statusBar.isVisible = false }

    /** The automation reports its status on every probe; the strip is redrawn only when what it says changes. */
    fun render(paused: Boolean, status: AutomationStatus, language: AppLanguage, text: (Int) -> String) {
        val id = message(paused, status)
        Motion.show(binding.statusBar, id != null, rise = true)
        // Nothing to retry when the WebView cannot sign in by itself: the form below is the student's to fill.
        val unavailable = !paused && status == AutomationStatus.SIGN_IN_UNAVAILABLE
        val next = Shown(id, paused, (status != AutomationStatus.SIGNING_IN && !unavailable) || paused, language)
        if (next == shown) return
        shown = next
        if (id != null) binding.statusText.text = text(id)
        val context = binding.root.context
        // Information (a pause, sign-in left to the student) or a warning (a step that failed).
        val info = paused || unavailable
        val container = if (info) R.color.md_secondary_container else R.color.warning_container
        val content = context.getColor(if (info) R.color.md_on_secondary_container else R.color.on_warning_container)
        binding.statusBar.backgroundTintList = ColorStateList.valueOf(context.getColor(container))
        binding.statusText.setTextColor(content)
        binding.statusIcon.setImageResource(if (info) R.drawable.ic_info else R.drawable.ic_warning)
        binding.statusIcon.imageTintList = ColorStateList.valueOf(content)
        binding.statusAction.isVisible = next.action
        binding.statusAction.text = text(if (paused) R.string.sign_in else R.string.retry)
    }

    companion object {
        /** What the strip says: nothing while the automation works or rests, the reason when it stopped. */
        fun message(paused: Boolean, status: AutomationStatus): Int? = when {
            paused -> R.string.paused
            status == AutomationStatus.LOGIN_FAILED -> R.string.login_failed
            status == AutomationStatus.NOTICE_FAILED -> R.string.notice_failed
            status == AutomationStatus.AGREEMENT_FAILED -> R.string.agreement_failed
            status == AutomationStatus.LANGUAGE_FAILED -> R.string.language_failed
            status == AutomationStatus.ELEMENTS_MISSING -> R.string.elements_missing
            status == AutomationStatus.SIGN_IN_UNAVAILABLE -> R.string.sign_in_unavailable
            else -> null
        }
    }
}
