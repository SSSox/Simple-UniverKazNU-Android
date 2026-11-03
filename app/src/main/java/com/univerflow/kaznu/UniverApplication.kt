package com.univerflow.kaznu

import android.app.Application
import com.univerflow.kaznu.data.AppPreferences
import com.univerflow.kaznu.domain.AppTheme
import com.univerflow.kaznu.ui.ThemeMode

/** Applies the saved theme before any screen is created, including a viewer opened from a download notification. */
class UniverApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // One small preferences read at process start: a later, asynchronous read would draw the first frame in the wrong theme.
        ThemeMode.apply(runCatching { AppPreferences(this).theme }.getOrDefault(AppTheme.SYSTEM))
    }
}
