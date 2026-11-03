package com.univerflow.kaznu.main

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.univerflow.kaznu.AppState
import com.univerflow.kaznu.R
import com.univerflow.kaznu.databinding.ItemNoticeBinding
import com.univerflow.kaznu.databinding.NoticesSheetBinding
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.domain.NoticeKind
import com.univerflow.kaznu.domain.Notices
import com.univerflow.kaznu.domain.SiteNotice
import com.univerflow.kaznu.ui.context
import com.univerflow.kaznu.ui.missingBottomInset

/**
 * The Notifications window: unread site marks. A tap opens the page that resolves one, the check mark marks it read;
 * either takes it out of the list. The list lives in the ViewModel; this sheet only draws it.
 */
class NoticesSheet(
    private val activity: AppCompatActivity,
    private val onOpen: (SiteNotice) -> Unit,
    private val onRead: (String) -> Unit,
    private val onReadAll: () -> Unit,
    private val onRefresh: () -> Unit,
) {
    private var dialog: BottomSheetDialog? = null
    private var sheet: NoticesSheetBinding? = null
    private var checking = false
    private var last: AppState? = null
    // The rows last drawn: a render with the same list and language keeps them (and the reader's focus on them).
    private var drawn: Pair<List<SiteNotice>, AppLanguage>? = null
    private val stopChecking = Runnable { checking = false; last?.let(::render) }
    val showing get() = dialog != null

    fun show(state: AppState) {
        if (showing) return
        val form = NoticesSheetBinding.inflate(LayoutInflater.from(state.language.context(activity)))
        val dialog = BottomSheetDialog(activity)
        this.dialog = dialog
        sheet = form
        ViewCompat.setOnApplyWindowInsetsListener(form.root) { view, insets ->
            val needed = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            val applied = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.paddingBottom ?: 0
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, missingBottomInset(needed, applied))
            insets
        }
        ViewCompat.setAccessibilityHeading(form.noticesTitle, true)
        form.noticesReadAll.setOnClickListener { onReadAll() }
        form.noticesRefresh.setOnClickListener {
            checking = true
            form.root.removeCallbacks(stopChecking)
            form.root.postDelayed(stopChecking, 6_000)
            last?.let(::render)
            onRefresh()
        }
        dialog.setContentView(form.root)
        dialog.setOnShowListener {
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
        }
        dialog.setOnDismissListener {
            form.root.removeCallbacks(stopChecking)
            checking = false
            if (this.dialog === dialog) { this.dialog = null; sheet = null; drawn = null }
        }
        render(state)
        dialog.show()
        ViewCompat.requestApplyInsets(form.root)
    }

    fun dismiss() { dialog?.dismiss() }

    fun render(state: AppState) {
        last = state
        val form = sheet ?: return
        val labels = state.language.context(activity)
        val notices = Notices.listed(state.notices)
        form.noticesTitle.text = labels.getString(R.string.notices)
        form.noticesRefresh.contentDescription = labels.getString(R.string.notices_refresh)
        form.noticesRefresh.isEnabled = !checking
        // Only the check in progress is announced; the list itself says the rest.
        form.noticesSummary.text = labels.getString(R.string.notices_checking)
        form.noticesSummary.isVisible = checking
        form.noticesReadAll.text = labels.getString(R.string.notices_read_all)
        form.noticesReadAll.isVisible = notices.isNotEmpty()
        form.noticesEmpty.isVisible = notices.isEmpty()
        form.noticesEmptyText.text = labels.getString(R.string.notices_empty)
        if (drawn == notices to state.language) return
        drawn = notices to state.language
        form.noticesList.removeAllViews()
        val inflater = LayoutInflater.from(labels)
        notices.forEach { notice ->
            val row = ItemNoticeBinding.inflate(inflater, form.noticesList, false)
            val blocked = notice.kind == NoticeKind.BLOCKED
            row.noticeIcon.setImageResource(if (blocked) R.drawable.ic_warning else R.drawable.ic_task)
            row.noticeIcon.backgroundTintList = ColorStateList.valueOf(MaterialColors.getColor(row.root,
                if (blocked) com.google.android.material.R.attr.colorErrorContainer else com.google.android.material.R.attr.colorPrimaryContainer))
            row.noticeIcon.imageTintList = ColorStateList.valueOf(MaterialColors.getColor(row.root,
                if (blocked) com.google.android.material.R.attr.colorOnErrorContainer else com.google.android.material.R.attr.colorOnPrimaryContainer))
            row.noticeTitle.text = if (blocked) notice.title else "${notice.title} (${notice.count})"
            val details = notice.details.joinToString("\n") { detail ->
                if (detail.until.isEmpty()) "• ${detail.title}" else "• " + labels.getString(R.string.notice_detail_until, detail.title, detail.until)
            }
            // A count shows what it counts (the questionnaires and their dates); a page message is its own text.
            row.noticeText.text = if (blocked) notice.text else details
            row.noticeText.isVisible = row.noticeText.text.isNotEmpty()
            row.noticeText.maxLines = 8
            row.noticeRead.contentDescription = labels.getString(R.string.notice_mark_read, notice.title)
            row.noticeRead.setOnClickListener { onRead(notice.key) }
            row.root.contentDescription = listOf(row.noticeTitle.text, row.noticeText.text).filter { it.isNotEmpty() }.joinToString(". ")
            row.root.setOnClickListener { dismiss(); onOpen(notice) }
            form.noticesList.addView(row.root)
        }
    }
}
