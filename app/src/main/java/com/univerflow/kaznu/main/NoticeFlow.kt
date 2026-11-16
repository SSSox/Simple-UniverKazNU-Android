package com.univerflow.kaznu.main

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.univerflow.kaznu.AppState
import com.univerflow.kaznu.R
import com.univerflow.kaznu.UniverViewModel
import com.univerflow.kaznu.databinding.ActivityMainBinding
import com.univerflow.kaznu.domain.SiteNotice
import com.univerflow.kaznu.ui.attempt
import com.univerflow.kaznu.ui.launchCatching
import com.univerflow.kaznu.web.AutomationStatus
import com.univerflow.kaznu.web.ChannelGuard
import com.univerflow.kaznu.web.NoticeBridge
import com.univerflow.kaznu.web.SitePolicy
import kotlinx.coroutines.launch

/**
 * Notifications on the main screen: the signed-in page is read for the site's own marks after each page and on a
 * timer while the screen is in front, new ones are announced, and the Notifications window lists them.
 * The list itself lives in the ViewModel.
 */
class NoticeFlow(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val model: UniverViewModel,
    private val host: Host,
) {
    interface Host : ScreenFeedback {
        val sessionChanging: Boolean
        val sessionEpoch: Int
        /** The key of the main page's message channels. */
        val channel: ChannelGuard
        val rendererUnavailable: Boolean
        /** The document or service window is over the page. */
        val documentOpen: Boolean
        val automationStatus: AutomationStatus
        /** Whether the screen is resumed: the periodic check is posted only then. */
        val inForeground: Boolean
        /** The app-language resources, for texts with arguments. */
        fun labels(): Context
        /** Opens the page that resolves a notice; the notice is already marked read. */
        fun openNoticePage(url: String)
    }

    /** While the screen is in front, the site is read for notifications on this interval even without navigation. */
    private val ticker: Runnable = Runnable { scan(); binding.root.postDelayed(ticker, CHECK_MS) }
    // The site's own marks (a menu count, a "complete the questionnaire first" message) become notifications.
    private val bridge = NoticeBridge(binding.webView, host.channel, CHECK_MS) { scan, session ->
        val epoch = host.sessionEpoch
        activity.lifecycleScope.launch {
            val fresh = attempt(emptyList()) { model.applyNoticeScan(scan, session) }
            if (fresh.isNotEmpty() && epoch == host.sessionEpoch && !sheet.showing && !activity.isDestroyed) announce(fresh)
        }
    }
    private val sheet = NoticesSheet(activity, onOpen = ::open,
        onRead = { key -> change { model.markNoticeRead(key) } },
        onReadAll = { change { model.markAllNoticesRead() } },
        onRefresh = { scan(refresh = true) })

    /** Reads the main page for notifications once it is a signed-in page, not the login form or the read notice. */
    fun scan(refresh: Boolean = false) {
        val state = model.state.value
        if (host.rendererUnavailable || host.sessionChanging || host.documentOpen || !state.canBrowse || state.paused ||
            state.locked || host.automationStatus != AutomationStatus.IDLE) return
        val url = binding.webView.url
        if (!SitePolicy.trusted(url) || SitePolicy.loginPage(url) || SitePolicy.readNotice(url)) return
        bridge.scan(refresh, model.noticeSession)
    }

    fun show() {
        val state = model.state.value
        if (!state.canBrowse || state.locked || host.sessionChanging || sheet.showing) return
        sheet.show(state)
    }

    fun render(state: AppState) = sheet.render(state)

    /** (Re)starts the periodic notice check; it stops while the app is paused, locked or a page is failed. */
    fun startChecks() {
        binding.root.removeCallbacks(ticker)
        if (host.inForeground) binding.root.postDelayed(ticker, CHECK_MS)
    }

    fun stopChecks() { binding.root.removeCallbacks(ticker) }

    fun dismiss() = sheet.dismiss()

    /** The session ended: the window closes and the page reader starts over with the next session. */
    fun reset() {
        sheet.dismiss()
        bridge.reset()
    }

    /** The website's renderer is gone: nothing more is read from it. */
    fun rendererGone() = bridge.close()

    /** The screen is destroyed. */
    fun close() {
        bridge.close()
        sheet.dismiss()
    }

    private fun change(block: suspend () -> Unit) { activity.launchCatching({ host.message(R.string.storage_error) }, block) }

    private fun announce(fresh: List<SiteNotice>) {
        val labels = host.labels()
        val text = if (fresh.size == 1) labels.getString(R.string.notices_new_one, fresh[0].title)
            else labels.getString(R.string.notices_new_many, fresh.size)
        Snackbar.make(binding.content, text, Snackbar.LENGTH_LONG).setAction(labels.getString(R.string.notices_show)) { show() }.show()
    }

    /** Opens the page that resolves [notice] (the questionnaire list, the section with the message) and marks it read. */
    private fun open(notice: SiteNotice) {
        if (host.sessionChanging || !SitePolicy.trusted(notice.url)) return
        change { model.markNoticeRead(notice.key) }
        host.openNoticePage(notice.url)
    }

    private companion object {
        /** The notification check: the open page, and the sections when [NoticeBridge] last fetched them this long ago. */
        const val CHECK_MS = 10 * 60_000L
    }
}
