
# Okdesk Mobile (Android)

Android WebView app for Okdesk and the Poscenter portals. The app's runtime
scripts are pinned copies in `app/src/main/assets`; Gradle does not read or copy
Tampermonkey files from the parent directory. Tampermonkey updates therefore do
not change this app. To change Android behavior, edit the app's own assets/code
and build a new app version.

## Build

1. Open this directory in Android Studio. Install Android SDK Platform 35 and
   Build Tools 35.0.0 when prompted. JDK 17 is required.
2. Build `app` or run `gradlew.bat assembleDebug` on Windows. The debug APK is
   written to `app/build/outputs/apk/debug/app-debug.apk`.
3. Install the debug APK on Android 10 or newer. It opens
   `https://shtrih-m.okdesk.ru/issues` and uses its own Okdesk login session.
4. Sign in to Okdesk in the app. On the first visit to Issues, enter your
   personal API token, verify it and select the working group. The mobile
   interface starts only after this setup succeeds. Import templates from the
   browser userscript's JSON export if needed. Tampermonkey storage is not
   automatically shared with the app.

## Behavior

- The mobile issue search keeps the latest 10 queries locally per portal for 7
  days. Enter, leaving a changed search field, or opening an issue from a search
  records the query. Selecting a recent issue number opens it directly; selecting
  text reapplies the list filter. Repeated queries move to the top without
  duplicates. Expired entries are removed when history is read. The Clear action
  removes search history without touching templates or login data.

- The mobile interface and API logic use the app-local `okdesk.user.js` asset.
- Only the exact `https://shtrih-m.okdesk.ru` origin receives the native message
  bridge and injected script. External links open in the system browser.
- The token is encrypted with an Android Keystore AES-GCM key before storage.
  App backup is disabled. The WebView login has its own cookies.
- Initial setup verifies access to the groups API and that the token belongs
  to an employee. It saves the selected group for the group tab and transfers.
- Android handles file selection, downloads, clipboard, and the hardware Back
  button. The native network helper is limited to public HTTPS GET requests of
  at most 2 MB, for the userscript's HTML attachment reader.
- The mobile header's PC button and the issue menu's `Открыть полную карточку`
  open the full Okdesk view. A `Мобильный вид` button at the bottom-right
  returns to the same mobile issue, removing `okdesk_desktop=1` if present.
- The side menu opens the Okdesk mobile list, the Optposcenter catalog with its
  app-local script asset, and FS Poscenter with its app-local script asset. Optposcenter credentials and script
  data are stored in the app's encrypted store; use the app bar gear to open
  catalog settings.
- The FS section blocks the Kaspersky `main.js` and `main.css` requests that
  caused the slow initial load, only while the active page is FS Poscenter.
- FS files have colored format badges and direct-link copy buttons. Supported
  preview types have an Open action, routed to the external browser on Android
  without replacing the catalog. Archives and executables remain download-only.
- App appearance defaults to dark and can be changed to light from the side
  menu. Android status and navigation bars are hidden for an immersive view;
  swipe from the system edge to temporarily reveal them.
- Android Back and edge-swipe close the drawer or current mobile sheet first,
  return from PC mode to the remembered mobile issue, and navigate within each
  internal section without handing control to the original Okdesk page.
- The API token is optional. Without it, the app keeps the normal Okdesk website
  inside the mobile shell and non-API script helpers. API-backed issue lists,
  comments, and status actions require a token; add one later from the side
  menu's API setup item.
- The Optposcenter section is presented as a focused mobile quick-search screen.
  Search results still use the app-local catalog script and its saved index.
  The search is restored after catalog-page navigation and remains available on
  the portal's login redirect for the catalog.
- Only Okdesk, Optposcenter, and FS Poscenter open inside the app. Other links
  continue to open in the system browser.

## Limitations and test checklist

This is an initial Android project, not a signed production release. Before
distribution, test on a real device: login/logout, API token save after app
restart, list and issue detail, open/private comments, status and assignee
actions, multiple file upload, attachment preview, download of signed URLs,
external links, Back, and both theme modes. Release signing must use a private
keystore outside this project. Never embed an API token in source or APK.
