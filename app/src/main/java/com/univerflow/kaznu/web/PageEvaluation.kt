package com.univerflow.kaznu.web

import android.webkit.WebView
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * [value] as a JavaScript string literal for a script built here. The line and paragraph separators are escaped as
 * well: an engine before ES2019 ends a literal on them, and a login, a password or a page address may contain them.
 */
fun jsString(value: String): String = JSONObject.quote(value).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

/**
 * Runs [script] in the page and returns its result as WebView reports it: JSON text, "null" for nothing. A page
 * replaced meanwhile still answers (with "null"), so the caller always resumes.
 */
suspend fun WebView.evaluate(script: String): String? = suspendCancellableCoroutine { continuation ->
    evaluateJavascript(script) { result -> if (continuation.isActive) continuation.resume(result) }
}
