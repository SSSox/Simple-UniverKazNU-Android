package com.univerflow.kaznu.launch

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.univerflow.kaznu.R
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.ui.context

/**
 * The launcher icon's long-press list: Schedule, Grades, Materials and Downloads, named in the app's own language
 * (which may differ from the phone's, so these are dynamic shortcuts, not a static XML list). Without an account
 * there is nothing to open and the list goes; a shortcut pinned to the home screen stays and opens the sign-in form.
 */
object AppShortcuts {
    private class Shown(val target: LaunchTarget, val label: Int, val icon: Int)

    private val SHOWN = listOf(
        Shown(LaunchTarget.SCHEDULE, R.string.nav_schedule, R.drawable.ic_shortcut_schedule),
        Shown(LaunchTarget.JOURNAL, R.string.nav_journal, R.drawable.ic_shortcut_journal),
        Shown(LaunchTarget.MATERIALS, R.string.shortcut_materials, R.drawable.ic_shortcut_materials),
        Shown(LaunchTarget.DOWNLOADS, R.string.downloads, R.drawable.ic_shortcut_downloads),
    )

    /** A binder call to the launcher's service: run it off the main thread. */
    fun update(context: Context, language: AppLanguage?) {
        runCatching {
            if (language == null) { ShortcutManagerCompat.removeAllDynamicShortcuts(context); return }
            val words = language.context(context)
            ShortcutManagerCompat.setDynamicShortcuts(context, SHOWN.mapIndexed { rank, shown ->
                val label = words.getString(shown.label)
                ShortcutInfoCompat.Builder(context, shown.target.name.lowercase())
                    .setShortLabel(label)
                    .setLongLabel(label)
                    .setIcon(IconCompat.createWithResource(context, shown.icon))
                    .setIntent(shown.target.intent(context))
                    .setRank(rank)
                    .build()
            })
        }
    }

    /** The student opened [target] (from a shortcut, the widget or a notification): the launcher ranks its shortcuts by this. */
    fun used(context: Context, target: LaunchTarget) {
        runCatching { ShortcutManagerCompat.reportShortcutUsed(context, target.name.lowercase()) }
    }
}
