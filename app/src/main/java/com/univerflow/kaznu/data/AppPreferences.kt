package com.univerflow.kaznu.data

import android.annotation.SuppressLint
import android.content.Context
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.domain.AppTheme
import com.univerflow.kaznu.domain.HomeShortcuts
import com.univerflow.kaznu.domain.NoticeDetail
import com.univerflow.kaznu.domain.NoticeKind
import com.univerflow.kaznu.domain.Notices
import com.univerflow.kaznu.domain.PinnedLink
import com.univerflow.kaznu.domain.ReminderSettings
import com.univerflow.kaznu.domain.SiteNotice
import com.univerflow.kaznu.domain.Shortcuts
import com.univerflow.kaznu.domain.TextZoom
import org.json.JSONArray
import org.json.JSONObject

// The boolean result of commit() is intentional: report failed persistence instead of assuming success.
@SuppressLint("UseKtx")
class AppPreferences(context: Context, name: String = "univer.preferences") {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    var language: AppLanguage
        get() = AppLanguage.fromCode(preferences.getString("language", "ru"))
        set(value) { check(preferences.edit().putString("language", value.siteCode).commit()) }
    var autoLoginPaused: Boolean
        get() = preferences.getBoolean("autologin_paused", false)
        set(value) { check(preferences.edit().putBoolean("autologin_paused", value).commit()) }
    val sessionClearPending: Boolean
        get() = preferences.getBoolean("session_clear_pending", false)
    var manualLogin: Boolean
        get() = preferences.getBoolean("manual_login", false)
        set(value) {
            val write = preferences.edit().putBoolean("manual_login", value)
            if (value) write.putBoolean("autologin_paused", false)
            check(write.commit())
        }

    fun accountSaved() {
        check(preferences.edit().putBoolean("autologin_paused", false).putBoolean("manual_login", false).commit())
    }

    fun beginSessionClear() {
        check(preferences.edit().putBoolean("autologin_paused", true).putBoolean("session_clear_pending", true).putBoolean("manual_login", false).commit())
    }

    fun finishSessionClear() {
        check(preferences.edit().putBoolean("session_clear_pending", false).commit())
    }
    var mobileLayout: Boolean
        get() = preferences.getBoolean("mobile_layout", true)
        set(value) { check(preferences.edit().putBoolean("mobile_layout", value).commit()) }
    var textZoom: Int
        get() = TextZoom.clamp(preferences.getInt("text_zoom", TextZoom.DEFAULT))
        set(value) { check(preferences.edit().putInt("text_zoom", TextZoom.clamp(value)).commit()) }
    /** The app's own motion switch; the system "remove animations" setting is honoured separately. */
    var animations: Boolean
        get() = preferences.getBoolean("animations", true)
        set(value) { check(preferences.edit().putBoolean("animations", value).commit()) }
    /** Links to other sites go to the system browser instead of the app's own link window. */
    var externalLinks: Boolean
        get() = preferences.getBoolean("external_links", false)
        set(value) { check(preferences.edit().putBoolean("external_links", value).commit()) }
    /** Read by the alarm and boot receivers too, so it needs no account and no PIN. */
    var reminders: ReminderSettings
        get() = ReminderSettings(preferences.getBoolean("class_reminders", false), ReminderSettings.lead(preferences.getInt("reminder_lead", ReminderSettings.DEFAULT_LEAD)))
        set(value) { check(preferences.edit().putBoolean("class_reminders", value.enabled).putInt("reminder_lead", ReminderSettings.lead(value.lead)).commit()) }
    /** The file viewer's "A4 page width": Word and web pages stop widening at an A4 sheet when zoomed out. */
    var previewPageA4: Boolean
        get() = preferences.getBoolean("preview_page_a4", true)
        set(value) { check(preferences.edit().putBoolean("preview_page_a4", value).commit()) }
    /** Read once at process start, before the first screen, so every screen opens in the chosen theme. */
    var theme: AppTheme
        get() = AppTheme.fromCode(preferences.getString("theme", AppTheme.SYSTEM.code))
        set(value) { check(preferences.edit().putString("theme", value.code).commit()) }
    /** The last update check: the newer version it found, and when. Not account data, so kept through logout. */
    var updateVersion: String?
        get() = preferences.getString("update_version", null)
        set(value) { check(preferences.edit().putString("update_version", value).commit()) }
    var updateChecked: Long
        get() = preferences.getLong("update_checked", 0)
        set(value) { check(preferences.edit().putLong("update_checked", value).commit()) }
    /** The version whose dialog the student turned off. */
    var updateSkipped: String?
        get() = preferences.getString("update_skipped", null)
        set(value) { check(preferences.edit().putString("update_skipped", value).commit()) }
    /** An install is waiting for the student to allow installing from this app. */
    var updateResume: Boolean
        get() = preferences.getBoolean("update_resume", false)
        set(value) { check(preferences.edit().putBoolean("update_resume", value).commit()) }
    /** Pinned home links and tile order: plain validated data, kept through logout. */
    var shortcuts: HomeShortcuts
        get() = runCatching {
            val pins = JSONArray(preferences.getString("home_pins", "[]")).let { array ->
                (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.let { PinnedLink(it.optString("title"), it.optString("url")) }
                }
            }
            val order = JSONArray(preferences.getString("home_tile_order", "[]")).let { array ->
                (0 until array.length()).map { array.optString(it) }
            }
            val hidden = JSONArray(preferences.getString("home_hidden_tiles", "[]")).let { array ->
                (0 until array.length()).map { array.optString(it) }
            }
            HomeShortcuts(Shortcuts.normalize(pins), Shortcuts.normalizeOrder(order), Shortcuts.normalizeHidden(hidden))
        }.getOrDefault(HomeShortcuts())
        set(value) {
            val pins = JSONArray().apply {
                Shortcuts.normalize(value.pins).forEach { put(JSONObject().put("title", it.title).put("url", it.url)) }
            }
            val order = JSONArray().apply { Shortcuts.normalizeOrder(value.order).forEach { put(it) } }
            val hidden = JSONArray().apply { Shortcuts.normalizeHidden(value.hidden).forEach { put(it) } }
            check(preferences.edit().putString("home_pins", pins.toString()).putString("home_tile_order", order.toString())
                .putString("home_hidden_tiles", hidden.toString()).commit())
        }
    /** The Notifications window: plain data the site showed (titles, counts, messages), never credentials. */
    var notices: List<SiteNotice>
        get() = runCatching {
            JSONArray(preferences.getString("site_notices", "[]")).let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val kind = NoticeKind.fromCode(item.optString("kind")) ?: return@mapNotNull null
                    val details = item.optJSONArray("details")?.let { list ->
                        (0 until minOf(list.length(), Notices.MAX_DETAILS)).mapNotNull { at ->
                            list.optJSONObject(at)?.let { NoticeDetail(it.optString("title"), it.optString("until")) }
                        }
                    }.orEmpty()
                    SiteNotice(item.optString("key").ifEmpty { return@mapNotNull null }, kind, item.optString("title"),
                        item.optString("url").ifEmpty { return@mapNotNull null }, item.optInt("count"), item.optString("text"),
                        item.optString("page"), item.optLong("first_seen"), item.optBoolean("read"), details, item.optInt("open", -1), item.optBoolean("menu"))
                }
            }.let(Notices::ordered)
        }.getOrDefault(emptyList())
        set(value) {
            val array = JSONArray().apply {
                Notices.ordered(value).forEach {
                    put(JSONObject().put("key", it.key).put("kind", it.kind.code).put("title", it.title).put("url", it.url)
                        .put("count", it.count).put("text", it.text).put("page", it.page).put("first_seen", it.firstSeen).put("read", it.read).put("open", it.open).put("menu", it.menu)
                        .put("details", JSONArray().apply { it.details.forEach { detail -> put(JSONObject().put("title", detail.title).put("until", detail.until)) } }))
                }
            }
            check(preferences.edit().putString("site_notices", array.toString()).commit())
        }
}
