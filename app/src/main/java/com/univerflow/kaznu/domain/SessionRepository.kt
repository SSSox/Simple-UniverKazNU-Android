package com.univerflow.kaznu.domain

/** Plain Kotlin values: no Android storage or UI dependencies. */
class Credentials(val login: String, val password: String) {
    override fun toString() = "Credentials([redacted])"
}

enum class AppLanguage(val siteCode: String, val localeCode: String, val displayName: String) {
    RUSSIAN("ru", "ru", "Русский"), KAZAKH("kz", "kk", "Қазақша"), ENGLISH("en", "en", "English");

    companion object {
        fun fromCode(code: String?) = entries.firstOrNull { it.siteCode == code } ?: RUSSIAN
    }
}

/** The app's appearance: [SYSTEM] follows the phone's dark theme setting. */
enum class AppTheme(val code: String) {
    SYSTEM("system"), LIGHT("light"), DARK("dark");

    companion object {
        fun fromCode(code: String?) = entries.firstOrNull { it.code == code } ?: SYSTEM
    }
}

/** The page the app opens first: the site's news (default) or Кабинет, which loads much faster. */
enum class StartPage(val code: String) {
    NEWS("news"), BACHELOR("bachelor");

    companion object {
        fun fromCode(code: String?) = entries.firstOrNull { it.code == code } ?: NEWS
    }
}

/** Text size of the website, its documents and downloaded files, in percent of the WebView default. */
object TextZoom {
    const val MIN = 60
    const val MAX = 160
    const val DEFAULT = 100
    fun clamp(value: Int) = ((value + 5) / 10 * 10).coerceIn(MIN, MAX)
}

data class StoredSession(
    val credentials: Credentials? = null,
    val language: AppLanguage = AppLanguage.RUSSIAN,
    val paused: Boolean = false,
    val storageError: Boolean = false,
    val mobileLayout: Boolean = true,
    val textZoom: Int = TextZoom.DEFAULT,
    val animations: Boolean = true,
    val shortcuts: HomeShortcuts = HomeShortcuts(),
    /** With the lock on, [credentials] stay null until the PIN or the biometrics open the protected record. */
    val lock: LockSettings = LockSettings(),
    val theme: AppTheme = AppTheme.SYSTEM,
    /** Notifications read from the site's own marks; they belong to the signed-in account. */
    val notices: List<SiteNotice> = emptyList(),
    /** A previous process accepted logout or an account change before its WebView profile was erased. */
    val sessionClearPending: Boolean = false,
    val manualLogin: Boolean = false,
    val externalLinks: Boolean = false,
    val reminders: ReminderSettings = ReminderSettings(),
    val gradeAlerts: Boolean = false,
    val startPage: StartPage = StartPage.NEWS,
)

/** Persistence owns dispatching and serialization; callers never access the Keystore directly. */
interface SessionRepository {
    suspend fun load(): StoredSession
    suspend fun saveCredentials(credentials: Credentials)
    suspend fun deleteCredentials()
    suspend fun beginSessionClear()
    suspend fun finishSessionClear()
    suspend fun setManualLogin(enabled: Boolean)

    // App lock (PIN, optional biometrics). The defaults describe a repository without the lock.
    suspend fun enableLock(credentials: Credentials, pin: CharArray) { throw UnsupportedOperationException() }
    suspend fun unlockWithPin(pin: CharArray): UnlockResult = UnlockResult.Unavailable
    /** Checks the current PIN and sets the new one; the counters and the erase rule apply as for an unlock. */
    suspend fun changePin(oldPin: CharArray, newPin: CharArray): UnlockResult = UnlockResult.Unavailable
    /** Checks the PIN and returns the account to plain Keystore storage without a lock. */
    suspend fun disableLock(pin: CharArray): UnlockResult = UnlockResult.Unavailable
    suspend fun lockNow() {}
    suspend fun setAutoLockMs(milliseconds: Long) {}
    suspend fun markLockOfferHandled() {}
    /** The cipher for the biometric prompt that unlocks the protected account, or null when it cannot be used. */
    suspend fun biometricUnlockCipher(): javax.crypto.Cipher? = null
    suspend fun unlockWithBiometric(cipher: javax.crypto.Cipher): UnlockResult = UnlockResult.Unavailable
    suspend fun biometricEnrollCipher(): javax.crypto.Cipher = throw UnsupportedOperationException()
    suspend fun finishBiometric(cipher: javax.crypto.Cipher) { throw UnsupportedOperationException() }
    suspend fun removeBiometric() {}
    suspend fun setAutoLoginPaused(paused: Boolean)
    suspend fun setLanguage(language: AppLanguage)
    suspend fun setMobileLayout(enabled: Boolean)
    suspend fun setTextZoom(percent: Int)
    suspend fun setAnimations(enabled: Boolean)
    suspend fun setExternalLinks(enabled: Boolean)
    suspend fun setReminders(settings: ReminderSettings) {}
    suspend fun setGradeAlerts(enabled: Boolean) {}
    suspend fun setTheme(theme: AppTheme)
    suspend fun setStartPage(page: StartPage) {}
    suspend fun setShortcuts(shortcuts: HomeShortcuts)
    suspend fun setNotices(notices: List<SiteNotice>) {}
}
