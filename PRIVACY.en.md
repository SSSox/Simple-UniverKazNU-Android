[Русский](PRIVACY.md) · [Қазақша](PRIVACY.kz.md) · **English**

# Privacy policy · Univer · KazNU (Android)

Effective from version **2.0** (29 October 2026).

The app is an unofficial client of the [Univer KazNU](https://univer.kaznu.kz/) website: it opens the site in a built-in WebView, keeps you signed in and makes the pages convenient on a phone. The app is not affiliated with Al-Farabi KazNU and has no server of its own.

## What data is processed

| Data | Where it is stored | Where it is sent |
|---|---|---|
| Univer login and password | Only on the phone, in a file encrypted with AES-GCM under an Android Keystore key (and a PIN-derived key when the PIN code is on) | Only to `https://univer.kaznu.kz`, into the site's own sign-in form |
| Cookies and site data (session, page settings; the schedule and grades for the Bachelor tab, for up to 10 minutes) | The app's WebView storage | Only to the Univer site and KazNU Moodle (`dl.kaznu.kz`, `dl.kaznu.edu.kz`) when you sign in through Univer |
| Cookies and sign-ins of sites opened from links | A separate WebView profile of the app | Only to the sites whose links you tapped; Univer and Moodle do not get them |
| Downloaded files | The phone's Downloads folder and the app's downloads list | Nowhere |
| Grade report (PDF or PNG) | The app's temporary folder; deleted on sign-out, and once older than an hour at the next app start or new report | Only to the app you pick in the Share sheet, or to the print service |
| The saved week of the schedule (courses, times, rooms, teachers) | A file in the app's folder, not backed up; deleted on sign-out | Nowhere. The "Offline schedule" screen, the "Today’s classes" widget and class reminders show it; with a PIN the widget and reminders do not name the course |
| The journal goal and the "new" marks | The app's WebView storage | Nowhere |
| Journal totals for new-grade notifications (only while they are on) | A file in the app's folder, not backed up; deleted when the notifications are turned off and on sign-out | Nowhere. The check itself sends one GET of the journal to `https://univer.kaznu.kz` with the current session's cookies, without a password and without forms. If the site renews the session cookie in its answer, the app keeps it as a browser would, so a check can extend the session on the site |
| An "Add to calendar" event (title, time, room) | The app does not keep it | To the calendar app the button opens. From then on the calendar keeps the event and may sync it with its own account |
| Settings (language, text size, tiles, PIN code) | The app's settings on the phone | Nowhere |
| Update check (the version found, the check time) | The app's settings on the phone | A plain request without a login or cookies goes to `github.com`: GitHub sees the IP address and the app's name |

- The app does **not** collect analytics, show ads, use third-party tracking SDKs or send data to the developer.
- The Univer site puts Google Analytics on its pages. The app does not load it: in the site window and in the documents and Moodle window, requests to Google Analytics and Google Tag Manager never reach the network, so Google Analytics does not receive which pages you open. The links window does not block them. Pages may still include other third-party services themselves; the app does not add them. Third-party cookies are disabled in the WebView.
- Distance-course task information is read from the same Univer pages you would open manually and cached for 5 minutes in the site tab only.
- The update file is downloaded only after you tap "Install", is kept in the app's temporary folder and is deleted after installation.
- Cloud backup of app data and transfer to another device are disabled.

## Deleting data

- "Sign out" deletes the WebView cookies, storage and cache, including the link window's data, grade reports, the saved schedule and journal totals, the encrypted login and password and the PIN; reminders and background grade checks stop. Language and other settings stay.
- "Delete data and sign in again" on the PIN screen (if the PIN is forgotten) deletes the same.
- Uninstalling the app deletes all of its data; downloaded files stay in Downloads.

## Permissions

Internet and network state are needed for the site to work, notifications for download progress, class reminders and new-grade messages, a foreground service for downloads, starting after the phone restarts to bring back reminders, the widget and grade checks, storage write access only on Android 7–9 to save files to Downloads; the camera is opened by the system camera app only when you choose "Take photo" for an attachment; installing apps is needed only to update Univer itself, and you allow it separately.

Questions and data requests: through the app's publication page or the developer contacts listed there.
