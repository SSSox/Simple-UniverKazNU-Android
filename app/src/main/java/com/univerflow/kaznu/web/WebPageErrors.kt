package com.univerflow.kaznu.web

import android.content.Context
import com.univerflow.kaznu.R

internal fun httpErrorMessage(status: Int, fallback: Int = R.string.network_error) = when {
    status >= 500 -> R.string.server_error
    status == 403 -> R.string.access_error
    status == 404 -> R.string.page_missing
    else -> fallback
}

/** Codes help diagnose failures without exposing URLs, server bodies or session data. */
internal fun Context.webErrorText(messageId: Int, httpStatus: Int?): String {
    val description = getString(messageId)
    return if (httpStatus == null) description
    else getString(R.string.http_error_with_status, description, httpStatus)
}
