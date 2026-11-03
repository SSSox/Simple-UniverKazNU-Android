[Русский](SECURITY.md) · [Қазақша](SECURITY.kz.md) · **English**

# Security policy · Univer · KazNU (Android)

## Supported versions

Security fixes are released only for the latest version on the Releases page (currently 2.3.1).

## Reporting a vulnerability

- Send a private report: the repository's **Security** tab → **Report a vulnerability**. Do not post details in public issues.
- Describe the app and Android versions, the steps to reproduce and what exactly can be obtained or broken.
- Do not send real passwords, cookies or personal data; made-up values are enough for an example.
- Do not test vulnerabilities on other people's accounts and do not put load on the university site: it does not belong to the app's author.

## How the protection works

**Data storage.**
- The login and password are encrypted with AES-256-GCM under an Android Keystore key; the key never leaves the protected storage.
- With a PIN code (6 digits) the data is additionally locked with a PIN-derived key (PBKDF2-HMAC-SHA256, 210,000 iterations; 60,000 on Android 7, where the system lacks this algorithm) and an outer Keystore key. Guessing it off the phone is therefore impossible; on the phone delays grow after 4 free attempts, and the 10th wrong attempt erases the saved sign-in.
- Backup and data transfer are disabled (`allowBackup="false"`, the `data_extraction_rules` and `backup_rules` rules).
- The password and PIN screens, and the whole app when the PIN is on, are hidden from screenshots and the recent apps list (`FLAG_SECURE`).

**Network.**
- The app connects only to `https://univer.kaznu.kz` and to Moodle (`dl.kaznu.kz`, `dl.kaznu.edu.kz`). For updates: `github.com` and `*.githubusercontent.com`, without a login or cookies. HTTP is disabled (`usesCleartextTraffic="false"`).
- Certificates are checked by the system. In addition, only the public Sectigo R46 root certificate is trusted, and only for `univer.kaznu.kz` (old Android 7–14 do not know it). Certificate Transparency checking is on for Android 17. Certificate and hostname checks are never turned off.
- External links (browser, YouTube) open in other apps; the WebView does not load them.

**WebView.**
- File access and mixed HTTP content are off, third-party cookies are blocked.
- `addJavascriptInterface` is not used. A page passes the app only size-limited data through a `WebMessageListener` that accepts messages only from `https://univer.kaznu.kz`.
- WebView debugging is enabled only in the debug build.

**Automation.** The automation scripts in `app/src/main/assets` run only on the exact address `https://univer.kaznu.kz`. They fill the site's sign-in form and confirm two known dialogs: the read notice and the additional agreement. The app sends no other forms, messages, grades or answers. The number of sign-in attempts is limited ([SessionGuard.kt](app/src/main/java/com/univerflow/kaznu/web/SessionGuard.kt)). On Moodle pages the app only checks whether the session has ended (and then opens Moodle again through the Univer sign-in) and adds phone styles; no password is typed into Moodle.

**Downloads and viewer.**
- A file is saved only if the response came from Univer or Moodle; a sign-in page instead of a file, a 213 response and a redirect to another site are rejected.
- Documents are opened by the app's own readers in a WebView with a separate internal address where JavaScript, file access and network are off. File sizes and structure are checked; a damaged file is not opened.

**Updates.** At sign-in, at most once every 6 hours, the app asks GitHub for the number of the latest release (`domain/Updates.kt`, `update/`). The check cannot be turned off, but nothing is installed until you tap "Install". The file comes only from `github.com` and `*.githubusercontent.com` over HTTPS, and every redirect is checked. Before installing, the app compares the SHA-256 from the release file, the package name, a newer version and the signature: the APK must be signed with the same certificate as the installed app, otherwise it is deleted. The Android system installer installs the file.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | open the Univer site |
| `ACCESS_NETWORK_STATE` | a clear message when there is no network |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | downloads continue when the app is minimised |
| `POST_NOTIFICATIONS` | download progress notification |
| `WRITE_EXTERNAL_STORAGE` (Android 9 and older only) | saving to the Downloads folder on old versions |
| `REQUEST_INSTALL_PACKAGES` | installing an update of the app itself after you tap "Install" |
| `UPDATE_PACKAGES_WITHOUT_USER_ACTION` | on Android 12+ an update can install without a second confirmation once the app has updated itself |

Camera, contacts, location, microphone and SMS are not requested. Photos for assignments are taken by the system camera app.

## Checking the code yourself

- The address list: `SitePolicy.kt`, `SingleSignOnPolicy.kt`, `Updates.kt` and [network_security_config.xml](app/src/main/res/xml/network_security_config.xml). Besides them, the code has only the file viewer's internal address `univer-files.local` (it never goes to the network) and a YouTube link for embedded videos, which opens in another app.
- Dependencies: [gradle/libs.versions.toml](gradle/libs.versions.toml), only AndroidX, Material Components and kotlinx-coroutines. There is no analytics, advertising or crash reporting. The app runs no code from the network: a new version installs only as an ordinary APK through the Android installer.
- Where the password is handled: `data/CredentialVault.kt`, `data/LockVault.kt`, `data/LockCrypto.kt`, `assets/univer-automation.js`.
- The APK signature can be checked with `apksigner verify --print-certs Simple-UniverKazNU.apk` (from Android SDK Build Tools). The release certificate is `CN=SOX Team, O=SOX Team, C=KZ`, SHA-256 `0aa54a2d4714db17bc71b9a0a4d4bc7818cb57d91f10685200416bd2094732b9`; signed with schemes v2 and v3.

## Known limitations

- On a rooted phone or with malware that has administrator rights, the storage protection can be bypassed.
- The debug build (`app-debug.apk`) allows WebView debugging; for everyday use install the release.
- On Android 7 the PIN key is derived with fewer iterations (60,000).
