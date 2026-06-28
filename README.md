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
