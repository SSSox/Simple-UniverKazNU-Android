package com.univerflow.kaznu.main

import android.content.res.ColorStateList
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.MenuItemCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.updatePadding
import androidx.core.widget.TextViewCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.univerflow.kaznu.AppState
import com.univerflow.kaznu.R
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.ui.LanguageResources
import com.univerflow.kaznu.ui.Motion
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
    private val languages = LanguageResources(activity)
    /**
     * What the menus last showed. A menu title set to the same text still rebuilds the app bar's actions and rebinds the
     * whole drawer list, and the page's automation reports its status every probe (600 ms, later 2 s): without these the
     * idle screen would redraw itself all the time.
     */
    private var localized: Triple<AppLanguage, Boolean, Boolean>? = null
    private var shownDownloads: Pair<Int, AppLanguage>? = null
    private var shownNotices: Pair<Int, AppLanguage>? = null

    /** [onNavigation] returns whether the tapped item becomes the checked one. */
    fun configure(canOpenDrawer: () -> Boolean, onToolbar: (Int) -> Boolean, onNavigation: (Int) -> Boolean) {
        // NavigationView passes system insets to its header; the header keeps its own spacing below the status bar.
        val header = binding.navigation.getHeaderView(0)
        val headerTop = header.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(header) { view, insets ->
            view.updatePadding(top = headerTop + insets.getInsets(WindowInsetsCompat.Type.systemBars()).top)
            insets
        }
        // The list just fits a tall phone: its scrollbar would flash along the whole drawer each time it opens.
        binding.navigation.getChildAt(0).isVerticalScrollBarEnabled = false
        binding.toolbar.setNavigationOnClickListener { if (canOpenDrawer()) binding.drawer.openDrawer(GravityCompat.START) }
        binding.toolbar.setOnMenuItemClickListener { item -> onToolbar(item.itemId) }
        binding.navigation.setNavigationItemSelectedListener { item ->
            binding.drawer.closeDrawer(GravityCompat.START)
            onNavigation(item.itemId)
        }
    }

    /** Native labels follow the app language, which can differ from the system locale. */
    fun localize(state: AppState) {
        val shown = Triple(state.language, state.mobileLayout, state.paused)
        if (shown == localized) return
        localized = shown
        val labels = languages.of(state.language)
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
        // The downloads count is read out in the app language too; it otherwise changes only with the list.
        shownDownloads?.let { (active, _) -> downloads(active, state) }
    }

    /**
     * The section's short name in the app bar: the page repeats its full heading below it, and site headings are too
     * long for the bar. Other pages use their heading; while one loads, the bar keeps its title and the progress line
     * under the bar shows the wait, so nothing in the bar jumps on a navigation.
     */
    fun heading(section: AppSection?, pageTitle: String?, loading: Boolean, text: (Int) -> String) {
        val name = when (section) {
            AppSection.SCHEDULE -> R.string.nav_schedule
            AppSection.JOURNAL -> R.string.nav_journal
            AppSection.MATERIALS -> R.string.nav_materials
            AppSection.ATTESTATION -> R.string.nav_attestation
            AppSection.DISTANCE -> R.string.nav_distance
            else -> null
        }
        showTitle(name?.let(text) ?: pageTitle?.takeIf { it.isNotBlank() }
            ?: binding.toolbar.title?.takeIf { loading && it.isNotBlank() } ?: text(R.string.app_name))
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

    private fun showTitle(title: CharSequence) {
        if (binding.toolbar.title?.toString() == title.toString()) return
        binding.toolbar.title = title
        binding.toolbar.children.firstOrNull { (it as? TextView)?.text == title }?.let(Motion::arrive)
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
        if (shownDownloads == active to state.language) return
        shownDownloads = active to state.language
        badge.isVisible = active > 0
        badge.number = active
        val labels = languages.of(state.language)
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
        if (shownNotices == unread to state.language) return
        shownNotices = unread to state.language
        badge.isVisible = unread > 0
        badge.number = unread
        val labels = languages.of(state.language)
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
