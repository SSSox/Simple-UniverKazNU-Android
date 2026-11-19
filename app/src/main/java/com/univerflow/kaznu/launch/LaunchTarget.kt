package com.univerflow.kaznu.launch

import android.content.Context
import android.content.Intent
import com.univerflow.kaznu.MainActivity
import com.univerflow.kaznu.web.AppSection

/**
 * Where a launcher shortcut, the widget or a notification asks the main screen to go. The Intent carries only one of
 * these names, never an address: the main screen is exported for the launcher, so any app can send it this request,
 * and the worst it can do is open a section the student could open from the drawer. It still waits for the account
 * and the PIN.
 */
enum class LaunchTarget(val section: AppSection?) {
    SCHEDULE(AppSection.SCHEDULE), JOURNAL(AppSection.JOURNAL), MATERIALS(AppSection.MATERIALS), DOWNLOADS(null);

    /**
     * Brings the running main screen forward (onNewIntent) instead of opening a second one with a second WebView;
     * windows over it (Downloads, a link, a file) close first.
     */
    fun intent(context: Context): Intent = Intent(context, MainActivity::class.java)
        .setAction(ACTION)
        .putExtra(EXTRA, name)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    companion object {
        const val ACTION = "com.univerflow.kaznu.action.OPEN"
        const val EXTRA = "target"

        fun parse(action: String?, name: String?): LaunchTarget? =
            if (action != ACTION || name == null) null else entries.firstOrNull { it.name == name }

        /** Another app may send extras that fail to unparcel: they read as no request. */
        fun of(intent: Intent?): LaunchTarget? = intent?.let { runCatching { parse(it.action, it.getStringExtra(EXTRA)) }.getOrNull() }
    }
}
