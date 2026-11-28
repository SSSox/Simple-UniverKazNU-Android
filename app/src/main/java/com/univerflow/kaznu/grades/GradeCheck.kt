package com.univerflow.kaznu.grades

import android.content.Context
import android.webkit.WebSettings
import com.univerflow.kaznu.data.AppPreferences
import com.univerflow.kaznu.data.GradeStore
import com.univerflow.kaznu.domain.GradeChange
import com.univerflow.kaznu.domain.GradeTotals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One background look at the journal, only while the site's session lasts: no sign-in is ever made here. The first
 * look at a term (or in another site language) only keeps the base; later ones report what is new or changed since it.
 * A page the student opened meanwhile, or a sign-out, wins over this look. The parts are passed in so a test can use
 * its own page, store and notification.
 */
class GradeCheck(
    private val context: Context,
    private val store: GradeStore = GradeStore.of(context),
    private val allowed: suspend () -> Boolean = { withContext(Dispatchers.IO) { GradeAlerts.allowed(context) } },
    /** Gets the journal; a renewed session cookie is kept only while `current()` says no sign-out came meanwhile. */
    private val fetch: suspend (current: () -> Boolean) -> JournalPage = { current ->
        withContext(Dispatchers.IO) { JournalFetch.get(WebSettings.getDefaultUserAgent(context), current = current) }
    },
    private val read: suspend (String) -> GradeTotals? = { JournalReader(context).read(it) },
    /** The app's language names the base, as on the page: the site's own lang attribute is not relied on. */
    private val language: () -> String = { AppPreferences(context).language.siteCode },
    private val notify: (List<GradeChange>) -> Unit = { GradeAlerts.notify(context, it) },
    private val withdraw: () -> Unit = { GradeAlerts.withdraw(context) },
    private val signedOut: () -> Unit = { GradeAlerts.signedOut(context) },
) {
    enum class Outcome { SKIPPED, SIGNED_OUT, FAILED, BASED, UNCHANGED, NOTIFIED }

    suspend fun run(): Outcome {
        // Taken first: an erase or a page write from here on wins over this look.
        val since = store.generation
        val version = store.version
        if (!allowed()) return Outcome.SKIPPED
        val page = fetch { store.generation == since }
        if (page is JournalPage.SignedOut) { signedOut(); return Outcome.SIGNED_OUT }
        if (page !is JournalPage.Found) return Outcome.FAILED
        val found = read(page.html) ?: return Outcome.FAILED
        return withContext(Dispatchers.IO) {
            val totals = found.copy(language = language())
            val base = store.baseSnapshot(totals)
            val changes = totals.changesFrom(base)
            val kept = store.keep(totals, since, after = version)
            when {
                !kept -> if (store.generation == since && store.version == version) Outcome.FAILED else Outcome.SKIPPED
                base == null -> Outcome.BASED
                changes.isEmpty() -> Outcome.UNCHANGED
                else -> {
                    notify(changes)
                    // A sign-out between the write and the notification removes what it said.
                    if (store.generation != since) { withdraw(); Outcome.SKIPPED } else Outcome.NOTIFIED
                }
            }
        }
    }

    internal companion object {
        /** Tracking is on and nothing else holds the account: not paused, not being cleared. */
        fun allowedBy(preferences: AppPreferences) =
            preferences.gradeAlerts && !preferences.autoLoginPaused && !preferences.sessionClearPending
    }
}
