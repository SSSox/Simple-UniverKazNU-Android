package com.univerflow.kaznu.web

import android.webkit.WebResourceRequest

/** A real tap on the observed site's Logout link asks the same as the app's Sign out, and the site is not left alone. */
class LogoutNavigation(private val logout: () -> Unit) {
    fun onNavigation(request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame || !request.hasGesture() || !request.method.equals("GET", true) ||
            !SitePolicy.logout(request.url.toString())) return false
        logout()
        return true
    }
}
