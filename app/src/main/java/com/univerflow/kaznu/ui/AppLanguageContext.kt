package com.univerflow.kaznu.ui

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.view.ContextThemeWrapper
import com.univerflow.kaznu.R
import com.univerflow.kaznu.domain.AppLanguage
import java.util.Locale

/**
 * Resources of the app language, which can differ from the system locale. Built where it is shown and reused only
 * for the lifetime of one render pass; `ContextThemeWrapper.createConfigurationContext` is not free.
 */
fun AppLanguage.context(base: Context): Context = ContextThemeWrapper(base.createConfigurationContext(
    Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(localeCode)) }
), R.style.Theme_UniverKazNUSimple)
