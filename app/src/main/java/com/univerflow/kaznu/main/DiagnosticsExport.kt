package com.univerflow.kaznu.main

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import androidx.webkit.WebViewCompat
import com.univerflow.kaznu.AppState
import com.univerflow.kaznu.domain.Credentials
import com.univerflow.kaznu.domain.DiagnosticFile
import com.univerflow.kaznu.download.PublicDownloadStore
import com.univerflow.kaznu.web.LinkProfile
import java.util.Date
import java.util.Locale

/** Collects the non-personal facts for [DiagnosticFile] and saves the text to the phone's public Downloads. */
object DiagnosticsExport {
    /** Facts only: settings as words and numbers, flags as yes/no. Never the login, password, PIN, cookies, addresses or grades. */
    fun facts(context: Context, state: AppState): List<Pair<String, String>> {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val webView = runCatching { WebViewCompat.getCurrentWebViewPackage(context) }.getOrNull()
        val metrics = context.resources.displayMetrics
        fun yesNo(value: Boolean) = if (value) "yes" else "no"
        return listOf(
            "App version" to (info?.versionName ?: "unknown"),
            "App version code" to (info?.let { PackageInfoCompat.getLongVersionCode(it).toString() } ?: "unknown"),
            "Build type" to if (debuggable) "debug" else "release",
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "WebView" to (webView?.let { "${it.packageName} ${it.versionName}" } ?: "unknown"),
            "Screen" to "${metrics.widthPixels}x${metrics.heightPixels} px, density ${metrics.densityDpi} dpi",
            "System locale" to Locale.getDefault().toLanguageTag(),
            "Language" to state.language.siteCode,
            "Theme" to state.theme.code,
            "Text size" to "${state.textZoom}%",
            "Mobile layout" to yesNo(state.mobileLayout),
            "Animations" to yesNo(state.animations),
            "Start page" to state.startPage.code,
            "Links in external browser" to yesNo(state.externalLinks || !LinkProfile.available()),
            "Class reminders" to if (state.reminders.enabled) "on, ${state.reminders.lead} min" else "off",
            "Grade alerts" to yesNo(state.gradeAlerts),
            "PIN lock" to yesNo(state.lock.enabled),
            "Biometric unlock" to yesNo(state.lock.biometric),
            "Saved credentials" to yesNo(state.hasCredentials),
            "Manual login" to yesNo(state.manualLogin),
            "Paused" to yesNo(state.paused),
            "Storage error" to yesNo(state.storageError))
    }

    /** Writes the file; returns its name as saved. Call on an IO dispatcher. No overwrite: a taken name gets a counter. */
    fun save(context: Context, state: AppState, credentials: Credentials?, now: Date = Date()): String {
        val secrets = listOfNotNull(credentials?.login, credentials?.password)
        val text = DiagnosticFile.build(facts(context, state), secrets, now)
        val pending = PublicDownloadStore(context).create(DiagnosticFile.fileName(now), "text/plain")
        pending.use {
            it.output.write(text.toByteArray(Charsets.UTF_8))
            it.publish()
        }
        return DiagnosticFile.fileName(now)
    }
}
