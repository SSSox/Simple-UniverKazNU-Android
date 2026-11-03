package com.univerflow.kaznu.main

import android.content.res.ColorStateList
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.MenuItemCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.TextViewCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.univerflow.kaznu.AppState
import com.univerflow.kaznu.R
import com.univerflow.kaznu.ui.context
import com.univerflow.kaznu.databinding.ActivityMainBinding
import com.univerflow.kaznu.web.AppSection
import com.google.android.material.badge.BadgeDrawable
import com.google.android.material.badge.BadgeUtils
import com.google.android.material.badge.ExperimentalBadgeUtils

/**
 * The top app bar and navigation drawer: labels in the app language, the page heading, the current section and the
 * downloads badge. What a tap does stays with the screen ([onToolbar], [onNavigation]).
 */
class MainChrome(private val activity: AppCompatActivity, private val binding: ActivityMainBinding) {
    private var badge: BadgeDrawable? = null
    private var noticeBadge: BadgeDrawable? = null

    /** [onNavigation] returns whether the tapped item becomes the checked one. */
    fun configure(canOpenDrawer: () -> Boolean, onToolbar: (Int) -> Boolean, onNavigation: (Int) -> Boolean) {
        // NavigationView passes system insets to its header; the header keeps its own spacing below the status bar.
        val header = binding.navigation.getHeaderView(0)
        val headerTop = header.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(header) { view, insets ->
            view.updatePadding(top = headerTop + insets.getInsets(WindowInsetsCompat.Type.systemBars()).top)
            insets
        }
        binding.toolbar.setNavigationOnClickListener { if (canOpenDrawer()) binding.drawer.openDrawer(GravityCompat.START) }
        binding.toolbar.setOnMenuItemClickListener { item -> onToolbar(item.itemId) }
        binding.navigation.setNavigationItemSelectedListener { item ->
            binding.drawer.closeDrawer(GravityCompat.START)
            onNavigation(item.itemId)
        }
    }

    /** Native labels follow the app language, which can differ from the system locale. */
    fun localize(state: AppState) {
        val labels = state.language.context(activity)
        binding.toolbar.navigationContentDescription = labels.getString(R.string.navigation_menu)
        binding.toolbar.menu.apply {
            findItem(R.id.action_downloads).title = labels.getString(R.string.downloads)
            findItem(R.id.action_notifications).title = labels.getString(R.string.notices)
            findItem(R.id.action_refresh).title = labels.getString(R.string.refresh)
            findItem(R.id.action_reading).apply { title = labels.getString(R.string.mobile_layout); isChecked = state.mobileLayout }
            findItem(R.id.action_text_size).title = labels.getString(R.string.text_size)
            findItem(R.id.action_print).title = labels.getString(R.string.print)
            findItem(R.id.action_browser).title = labels.getString(R.string.browser)
            findItem(R.id.action_settings).title = labels.getString(R.string.settings)
        }
        binding.navigation.menu.apply {
            NAVIGATION_LABELS.forEach { (item, label) -> findItem(item).title = labels.getString(label) }
            findItem(R.id.nav_all_sections).isVisible = state.mobileLayout
        }
        val header = binding.navigation.getHeaderView(0)
        header.findViewById<TextView>(R.id.drawer_university).text = labels.getString(R.string.university)
        header.findViewById<TextView>(R.id.drawer_account).apply {
            text = labels.getString(if (state.paused) R.string.account_paused else R.string.account_saved)
            TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(activity.getColor(
                if (state.paused) R.color.on_warning_container else R.color.md_tertiary)))
        }
    }

    /**
     * The page heading in the app bar, with the section's own name as the fallback. Schedule and distance-course
     * pages head themselves with breadcrumbs ("Недели/ Список Уч. планов / …"); the section name reads better there.
     */
    fun heading(section: AppSection?, pageTitle: String?, loading: Boolean, text: (Int) -> String) {
        val fallback = when (section) {
            AppSection.SCHEDULE -> R.string.nav_schedule
            AppSection.JOURNAL -> R.string.nav_journal
            AppSection.MATERIALS -> R.string.nav_materials
            AppSection.ATTESTATION -> R.string.nav_attestation
            AppSection.DISTANCE -> R.string.nav_distance
            else -> R.string.app_name
        }
        binding.toolbar.title = if (section == AppSection.SCHEDULE || section == AppSection.DISTANCE) text(fallback)
            else pageTitle?.takeIf { it.isNotBlank() } ?: text(fallback)
        binding.toolbar.subtitle = if (loading) text(R.string.preparing_page) else null
        val item = when (section) {
            AppSection.HOME -> R.id.nav_home
            AppSection.SCHEDULE -> R.id.nav_schedule
            AppSection.JOURNAL -> R.id.nav_journal
            AppSection.MATERIALS -> R.id.nav_materials
            AppSection.ATTESTATION -> R.id.nav_attestation
            AppSection.DISTANCE -> R.id.nav_distance
            null -> null
        }
        if (item != null) binding.navigation.setCheckedItem(item)
        else binding.navigation.checkedItem?.isChecked = false
    }

    fun drawerUsable(usable: Boolean) {
        binding.drawer.setDrawerLockMode(if (usable) DrawerLayout.LOCK_MODE_UNLOCKED else DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
    }

    /** The number of running downloads on the app bar's Downloads action, also read out by screen readers. */
    @OptIn(markerClass = [ExperimentalBadgeUtils::class])
    fun downloads(active: Int, state: AppState) {
        val badge = badge ?: BadgeDrawable.create(activity).also {
            badge = it
            BadgeUtils.attachBadgeDrawable(it, binding.toolbar, R.id.action_downloads)
        }
        badge.isVisible = active > 0
        badge.number = active
        val labels = state.language.context(activity)
        binding.toolbar.menu.findItem(R.id.action_downloads)?.let { item ->
            MenuItemCompat.setContentDescription(item, if (active > 0) labels.getString(R.string.downloads_active, active) else labels.getString(R.string.downloads))
        }
    }

    /** Unread notifications on the app bar's bell and in the drawer, also read out by screen readers. */
    @OptIn(markerClass = [ExperimentalBadgeUtils::class])
    fun notices(unread: Int, state: AppState) {
        val badge = noticeBadge ?: BadgeDrawable.create(activity).also {
            noticeBadge = it
            BadgeUtils.attachBadgeDrawable(it, binding.toolbar, R.id.action_notifications)
        }
        badge.isVisible = unread > 0
        badge.number = unread
        val labels = state.language.context(activity)
        val label = if (unread > 0) labels.getString(R.string.notices_badge, unread) else labels.getString(R.string.notices)
        binding.toolbar.menu.findItem(R.id.action_notifications)?.let { MenuItemCompat.setContentDescription(it, label) }
        binding.navigation.menu.findItem(R.id.nav_notices)?.let { item ->
            item.title = if (unread > 0) "${labels.getString(R.string.notices)} ($unread)" else labels.getString(R.string.notices)
            MenuItemCompat.setContentDescription(item, label)
        }
    }

    private companion object {
        val NAVIGATION_LABELS = listOf(
            R.id.nav_home to R.string.nav_home, R.id.nav_schedule to R.string.nav_schedule, R.id.nav_journal to R.string.nav_journal,
            R.id.nav_materials to R.string.nav_materials, R.id.nav_attestation to R.string.nav_attestation, R.id.nav_distance to R.string.nav_distance,
            R.id.nav_moodle to R.string.nav_moodle, R.id.nav_group_portal to R.string.nav_group_portal, R.id.nav_group_app to R.string.nav_group_app,
            R.id.nav_downloads to R.string.downloads, R.id.nav_all_sections to R.string.nav_all_sections, R.id.nav_settings to R.string.settings,
            R.id.nav_browser to R.string.browser,
        )
    }
}
