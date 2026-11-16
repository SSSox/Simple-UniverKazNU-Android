package com.univerflow.kaznu.ui

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.view.ContextThemeWrapper
import com.univerflow.kaznu.R
import com.univerflow.kaznu.domain.AppLanguage
import java.util.Locale

/** Resources of the app language, which can differ from the system locale. `createConfigurationContext` is not free. */
fun AppLanguage.context(base: Context): Context = ContextThemeWrapper(base.createConfigurationContext(
    Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(localeCode)) }
), R.style.Theme_UniverKazNUSimple)

/** The language context of one screen, built again only when the language changes: a render asks for dozens of strings. */
class LanguageResources(private val base: Context) {
    private var current: Pair<AppLanguage, Context>? = null

    fun of(language: AppLanguage): Context =
        current?.takeIf { it.first == language }?.second ?: language.context(base).also { current = language to it }
}
