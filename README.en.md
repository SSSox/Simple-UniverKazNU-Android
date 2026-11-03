[Русский](README.md) · [Қазақша](README.kz.md) · **English**

# Univer · KazNU

An unofficial Android app for the [Univer KazNU](https://univer.kaznu.kz/) student portal. It opens the site in a built-in WebView, signs in by itself with the saved data and makes the pages convenient on a phone. The app is not affiliated with Al-Farabi KazNU and has no server, analytics or ads of its own.

## Features

- **Sign-in without retyping.** The login and password are stored only on the phone, encrypted. When the site session ends, the app signs in again through the site's own form.
- **Mobile layout of the site.** Schedule, grades, assessment, UMKD and distance-course tables are shown as cards. The "Original" switch returns the site's own page unchanged.
- **Downloads and file viewer.** Course materials are saved to the phone's Downloads folder. PDF, Word (.docx, .doc, .rtf), Excel (.xlsx, .xls), PowerPoint (.pptx, .ppt), OpenDocument, web pages, images and text open inside the app. File text can be searched.
- **Site notifications.** The bell collects the site's marks, for example "Анкетирование студентов (2)" (student questionnaires). Questionnaires the student has already answered are not shown.
- **Moodle (СДО Moodle)** opens inside the app: sign-in through Univer, attaching files and photos to assignments.
- **PIN code and biometrics** (optional), Russian, Kazakh and English, light and dark theme, text size.

## Installation

Requirements:

- Android 7.0 (API 24) or later;
- an up-to-date Android System WebView or Chrome: the mobile layout scripts target Chromium 80+.

Steps:

1. Download `app-release.apk` from the **Releases** page of this repository.
2. Compare its SHA-256 checksum with the one published in the release description:
   ```powershell
   Get-FileHash .\app-release.apk -Algorithm SHA256   # Windows
   ```
   ```bash
   sha256sum app-release.apk                           # Linux, macOS: shasum -a 256
   ```
3. Open the file on the phone and allow installation from this source when Android asks.
4. On the first start enter your Univer login and password and tap "Save and sign in".

**Updating.** A new APK installs over the old one and keeps the data. A debug build (`app-debug.apk`) has to be uninstalled before installing the release: they have different signatures.

**Removing data.** "Settings" → "Sign out" clears the site's cookies, cache and history. "Delete saved credentials" also erases the encrypted login and password. Uninstalling the app removes everything.

## Security and privacy

- The login and password are encrypted with AES-GCM under an Android Keystore key and never go into backups. With a PIN code the data is additionally locked with a PIN-derived key; after 10 wrong attempts the saved sign-in is erased.
- The app connects only to `univer.kaznu.kz` and to Moodle (`dl.kaznu.kz`, `dl.kaznu.edu.kz`), over HTTPS only. Certificate checks are never turned off.
- Automatic actions (sign-in, the known read notice and the additional agreement) run only on the exact address `https://univer.kaznu.kz`. The app submits no other forms.

Details: the [privacy policy](PRIVACY.en.md) and the [security policy](SECURITY.en.md). The security policy also explains how to report a vulnerability and what to check in the code.

## Building from source

You need:

- [Android Studio](https://developer.android.com/studio) with its bundled JDK (`jbr`);
- Android SDK Platform 37 and Platform Tools (installed through the SDK Manager).

Gradle 9.8.0 is downloaded by the `gradlew` wrapper itself; JDK 25 for the Gradle daemon is picked automatically ([gradle/gradle-daemon-jvm.properties](gradle/gradle-daemon-jvm.properties)). Android Gradle Plugin 9.4.1, with Kotlin built in. All dependencies are pinned in [gradle/libs.versions.toml](gradle/libs.versions.toml): only AndroidX, Material Components and kotlinx-coroutines.

**In Android Studio:** File → Open → the project folder, wait for Gradle Sync, choose the `app` configuration and press Run.

**From the command line** (in the project root):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug
```

```bash
export JAVA_HOME="/opt/android-studio/jbr"   # path to the jbr of your installation
./gradlew :app:assembleDebug
```

Build outputs:

| Task | File | Signature |
|---|---|---|
| `:app:assembleDebug` | `app/build/outputs/apk/debug/app-debug.apk` | debug key |
| `:app:assembleRelease` | `app/build/outputs/apk/release/app-release.apk` | key from `keystore.properties`; without it the APK is unsigned |
| `:app:bundleRelease` | `app/build/outputs/bundle/release/app-release.aab` | the same, for Google Play |
| `:app:assembleQa` | `app/build/outputs/apk/qa/app-qa.apk` | release build with R8 on the debug key: installs over debug and keeps the data |

**Release signing.** Create a `keystore.properties` file next to `settings.gradle.kts` (it is in `.gitignore` and never goes into the repository):

```properties
storeFile=C:/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

The release is built with R8 (code and resource shrinking); the rules are in [app/src/main/keepRules](app/src/main/keepRules).

## Checks

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

Instrumented tests need a running emulator or phone. The tests work with local synthetic pages and send nothing to Univer:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
# a single class:
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.univerflow.kaznu.NoticesAndTablesQaTest"
```

The live-site TLS check is enabled only explicitly: the argument `-Pandroid.testInstrumentationRunnerArguments.univer.liveTls=true` for `NetworkSecurityConfigTest`. The large-presentation test is skipped if the `deck-photos.*` files are missing from `app/src/androidTest/assets/documents` (16 MB, not in the repository).

## How the code works

The app shows the real site: pages are neither copied nor replaced. The mobile layout is built on top of the site's DOM and is removed completely by the "Original" switch.

| Path | What is there |
|---|---|
| [MainActivity.kt](app/src/main/java/com/univerflow/kaznu/MainActivity.kt) | the site's WebView, "Back" navigation, linking the screen with the session and automation |
| [UniverViewModel.kt](app/src/main/java/com/univerflow/kaznu/UniverViewModel.kt) | app state (StateFlow), sign-out, account change, lock |
| [domain/](app/src/main/java/com/univerflow/kaznu/domain) | plain Kotlin without Android: rules for the PIN, notifications, downloads, home tiles |
| [data/](app/src/main/java/com/univerflow/kaznu/data) | storage: Keystore encryption ([CredentialVault.kt](app/src/main/java/com/univerflow/kaznu/data/CredentialVault.kt)), PIN ([LockVault.kt](app/src/main/java/com/univerflow/kaznu/data/LockVault.kt), [LockCrypto.kt](app/src/main/java/com/univerflow/kaznu/data/LockCrypto.kt)), settings |
| [web/](app/src/main/java/com/univerflow/kaznu/web) | address policy ([SitePolicy.kt](app/src/main/java/com/univerflow/kaznu/web/SitePolicy.kt)), WebView settings ([SecureWebSettings.kt](app/src/main/java/com/univerflow/kaznu/web/SecureWebSettings.kt)), sign-in automation and its limits ([AutomationController.kt](app/src/main/java/com/univerflow/kaznu/web/AutomationController.kt), [SessionGuard.kt](app/src/main/java/com/univerflow/kaznu/web/SessionGuard.kt)), mobile layout |
| [download/](app/src/main/java/com/univerflow/kaznu/download) | downloads to the phone's Downloads folder with a check of the server's response |
| [preview/](app/src/main/java/com/univerflow/kaznu/preview) | the app's own document readers: PDF, Word, Excel, PowerPoint, OpenDocument, HTML |
| [ui/](app/src/main/java/com/univerflow/kaznu/ui), [main/](app/src/main/java/com/univerflow/kaznu/main) | native screens: "Downloads", file viewer, PIN, settings, notifications |
| [assets/](app/src/main/assets) | JavaScript for the site's pages: sign-in ([univer-automation.js](app/src/main/assets/univer-automation.js)), mobile layout, notifications ([univer-notices.js](app/src/main/assets/univer-notices.js)) |
| [res/xml/network_security_config.xml](app/src/main/res/xml/network_security_config.xml) | trusted certificates: the system ones plus Sectigo R46 for `univer.kaznu.kz` only |
| [app/src/test](app/src/test), [app/src/androidTest](app/src/androidTest) | unit tests and emulator tests with synthetic pages |

Sign-in goes through the site's own form: the script fills the visible `#login_frm` fields and presses its button, so the site's password encryption and handlers are not bypassed. The number of attempts is limited; if the site rejected the password, the app stops and waits until it is corrected in the settings or "Retry" is tapped.

## Limitations

- If the site introduces a CAPTCHA or two-factor check, they have to be passed manually.
- If the site's HTML changes noticeably, the mobile layout or automatic sign-in may stop working until the app is updated.
- On a very old WebView (below Chromium 80) the site opens, but the mobile layout and automation may not work.

## License

Copyright (C) 2026 the Univer · KazNU authors.

The code is distributed under the [GNU General Public License](LICENSE), version 3 or, at your option, any later version (GPL-3.0-or-later). You may use, modify and distribute it, but a modified version of the app must be released under the same license together with its source code.

The name, logo and materials of Al-Farabi KazNU are not covered by the license and belong to the university.
