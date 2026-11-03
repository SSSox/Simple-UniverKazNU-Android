package com.univerflow.kaznu.ui

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import com.univerflow.kaznu.domain.AppTheme

/** The app's light or dark theme through AppCompat's night mode; every resource and screen follows it. */
object ThemeMode {
    private fun nightMode(theme: AppTheme) = when (theme) {
        AppTheme.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        AppTheme.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        AppTheme.DARK -> AppCompatDelegate.MODE_NIGHT_YES
    }

    /** Applies [theme]; open screens are recreated by AppCompat only when the resulting mode changes. */
    fun apply(theme: AppTheme) {
        val mode = nightMode(theme)
        if (AppCompatDelegate.getDefaultNightMode() != mode) AppCompatDelegate.setDefaultNightMode(mode)
    }

    /** Whether [context] currently draws the dark theme (the chosen one, or the system's when it follows the system). */
    fun isDark(context: Context) =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
}
