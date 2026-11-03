[Русский](PRIVACY.md) · [Қазақша](PRIVACY.kz.md) · **English**

# Privacy policy · Univer · KazNU (Android)

Effective from version **2.0** (29 October 2026).

The app is an unofficial client of the [Univer KazNU](https://univer.kaznu.kz/) website: it opens the site in a built-in WebView, keeps you signed in and makes the pages convenient on a phone. The app is not affiliated with Al-Farabi KazNU and has no server of its own.

## What data is processed

| Data | Where it is stored | Where it is sent |
|---|---|---|
| Univer login and password | Only on the phone, in a file encrypted with AES-GCM under an Android Keystore key (and a PIN-derived key when the PIN code is on) | Only to `https://univer.kaznu.kz`, into the site's own sign-in form |
| Cookies and site data (session, page settings) | The app's WebView storage | Only to the Univer site and KazNU Moodle (`dl.kaznu.kz`, `dl.kaznu.edu.kz`) when you sign in through Univer |
| Downloaded files | The phone's Downloads folder and the app's downloads list | Nowhere |
| Settings (language, text size, tiles, PIN code) | The app's settings on the phone | Nowhere |

- The app does **not** collect analytics, show ads, use third-party tracking SDKs or send data to the developer.
- Univer pages may include third-party services themselves (for example Google Analytics on the site). This is the site's behaviour; the app does not add it. Third-party cookies are disabled in the WebView.
- Distance-course task information is read from the same Univer pages you would open manually and cached for 5 minutes in the site tab only.
- Cloud backup of app data and transfer to another device are disabled.

## Deleting data

- "Sign out" deletes the WebView cookies, storage and cache; the encrypted login and password stay for signing in again.
- "Delete saved credentials" deletes the encrypted record and its key.
- Uninstalling the app deletes all of its data; downloaded files stay in Downloads.

## Permissions

Internet and network state are needed for the site to work, notifications and a foreground service for download progress, storage write access only on Android 7–9 to save files to Downloads; the camera is opened by the system camera app only when you choose "Take photo" for an attachment.

Questions and data requests: through the app's publication page or the developer contacts listed there.
