Demo: https://jp-fernando.github.io/crochetApp/

# Crochet Pattern Guide

Crochet Pattern Guide is now structured as an Android app backed by the existing offline web UI.

The web app in the repository root remains the source of truth:

- `index.html` contains the full interface, parser, IndexedDB logic, and embedded PDF.js bundle
- `manifest.json` and `sw.js` are still used for the browser/PWA version
- `app/` contains a native Android wrapper built around `WebView`

## What changed

The Android wrapper does not rewrite the app in native UI. It packages the current web app inside a secure local `WebView` and adds the Android-specific pieces the browser version relied on implicitly:

- local asset hosting through `WebViewAssetLoader`
- Android file picker support for importing PDF patterns
- DOM storage / IndexedDB enabled inside the `WebView`
- a JavaScript bridge (`AndroidEmail`) plus a `FileProvider` so a saved pattern can be sent by email as a PDF attachment
- build-time sync of `index.html`, `manifest.json`, and `sw.js` into the APK assets

That keeps one codebase for the crochet reader while making it installable as a standard Android app.

## Project structure

```text
.
├── index.html                  # Main web app, parser, viewer, IndexedDB logic
├── manifest.json               # PWA manifest for browser installs
├── sw.js                       # Service worker for browser offline mode
├── app/
│   ├── build.gradle            # Android module config
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/crochetpatternguide/app/MainActivity.java
│       └── res/                # Android theme + launcher icon
├── build.gradle                # Root Android build config
├── settings.gradle             # Android project settings
└── gradle.properties
```

## How the Android wrapper works

`MainActivity` loads:

```text
https://appassets.androidplatform.net/assets/index.html
```

Android maps that URL to the bundled app assets with `WebViewAssetLoader`. During each Android build, the `syncWebAssets` task copies these files from the repo root into the generated asset directory:

- `index.html`
- `manifest.json`
- `sw.js`

This means changes to the web app are automatically picked up by the Android app at build time. There is no second copy of the UI to maintain.

## Sending a pattern by email

Each project card has an envelope button. It asks for the recipient address (the last one used is remembered) and then:

- **Android app:** the PDF is passed from IndexedDB to the native side in base64 chunks, written to `cache/shared_pdfs/`, and exposed through `FileProvider`. The app looks for installed email apps (those that handle `mailto:` and accept a PDF) and opens the compose screen with the recipient, subject, notes and attachment filled in. If several email apps exist the user picks one; if none exist a generic share sheet is shown instead. If no app accepts PDFs, an error appears and the user can retry. The email itself is sent by the user's mail app, so no account or server is configured in this app.
- **Browser/PWA:** uses the Web Share API with the PDF file when available (the recipient must be chosen in the email app); otherwise it downloads the PDF and opens a `mailto:` link with the recipient so the file can be attached manually.

The manifest declares `<queries>` for `mailto:` and PDF sharing (Android 11+ package visibility). Each email app is targeted through an activity that accepts PDF attachments. A local app picker avoids Android's limit on additional chooser entries. Cancelling this picker keeps the email form open, with its recipient, ready to retry. The picker is dismissed when its Activity is destroyed, including configuration changes; the recreated page starts afresh.

Each transfer has a separate URI, so another send cannot overwrite an earlier attachment. The cache keeps at most **three transfers**, including one being prepared, and removes entries older than seven days when preparing another email. There is no per-file size limit; three copies of a 17 MB pattern use at most about 51 MB. Older draft attachments may become unavailable after eviction, and Android can also reclaim cache storage, so this is not permanent storage for drafts. If cleanup fails, preparing another transfer fails rather than exceeding the count limit.

### Email checks

Run the JavaScript regression checks with `node tests/email.test.cjs`, build with `./gradlew assembleDebug`, and run Android checks with `./gradlew :app:lintDebug`.

Run the cache regression checks without an Android device:

```bash
javac -d /tmp/crochet-cache-tests app/src/main/java/com/crochetpatternguide/app/SharedPdfCache.java tests/SharedPdfCacheTest.java
java -cp /tmp/crochet-cache-tests com.crochetpatternguide.app.SharedPdfCacheTest
```

On an Android phone, verify these cases before release:

- Import a PDF, add notes, tap the envelope, enter an address, and check the recipient, subject, notes and readable attachment in the email app. Send it and open the received PDF.
- Share a large PDF, cancel, and retry. Prepare a second email with the same pattern name and check that the first attachment still opens.
- With multiple email apps, check that all are listed and that cancelling the picker keeps the email form open and permits retrying. Change dark mode or font size with the picker open and check that no orphaned window remains after the Activity is recreated.
- With no email app, check the fallback share sheet; with no PDF-sharing apps, check that an error appears and retry remains available.

## Building the Android app

### Option 1: Android Studio

1. Open this folder as a project in Android Studio.
2. Let Android Studio install the Android SDK components it asks for.
3. Build or run the `app` module on a device or emulator.

### Option 2: local Gradle install

This repository currently does not include a Gradle wrapper. If you already have a compatible Gradle setup locally, build from the project root with:

```bash
gradle :app:assembleDebug
```

The resulting debug APK will be generated under:

```text
app/build/outputs/apk/debug/
```

## Running the browser version

You can still use the original browser/PWA version directly:

- open `index.html` in a browser
- or serve the folder locally and install it as a PWA

The browser version and Android version share the same UI and behavior, but their stored data is separate:

- browser/PWA data lives in the browser's IndexedDB
- Android app data lives inside the Android WebView storage sandbox

## Current limitations

- The Android wrapper depends on the system `WebView`, so behavior can vary slightly by Android version.
- Imported PDFs are stored inside WebView-managed storage, not in a user-visible project folder.
- Because there is no Gradle wrapper in the repo yet, first build is easiest from Android Studio.

## Notes for future work

- Add a Gradle wrapper so the project builds without a preinstalled Gradle.
- Generate proper adaptive launcher icons instead of using a single vector drawable.
- Add release signing configuration and Play Store metadata if you intend to publish it.
- Add a "back to top" button to the PDF steps viewer, so users can jump back to step 1 without repeatedly clicking "back".
- Add double-tap on a PDF step to jump to that step. Single tap was previously disabled for this because it collided with pinch-to-zoom gestures; double tap avoids that ambiguity.
