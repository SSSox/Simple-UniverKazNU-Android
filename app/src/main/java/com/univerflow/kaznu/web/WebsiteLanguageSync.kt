package com.univerflow.kaznu.web

import android.webkit.WebResourceRequest
import com.univerflow.kaznu.UniverViewModel
import com.univerflow.kaznu.domain.AppLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/** Adopt a user's flag before navigation; leave the website's original GET and cookies intact. */
class WebsiteLanguageSync(private val model: UniverViewModel, private val scope: CoroutineScope,
    private val onSelected: (AppLanguage) -> Unit, private val onFailure: () -> Unit) {
    fun onNavigation(request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame || !request.hasGesture() || request.method != "GET" || !model.state.value.ready) return false
        val language = SitePolicy.languageChange(request.url.toString()) ?: return false
        if (language != model.state.value.language) {
            onSelected(language)
            // State changes on the UI thread before the original flag navigation proceeds.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { model.setLanguage(language) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { onFailure() }
            }
        }
        return true
    }
}
