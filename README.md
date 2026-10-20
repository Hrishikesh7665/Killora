# Killora

**One tap. Zero bloat.**

Killora is a lightweight, native Android app designed to help you close apps running in the background with minimal effort. It keeps things simple: no ads, no unnecessary permissions, and no clutter.

Depending on your device and available privileges, Killora offers three ways to close apps:

- **Standard Close:** Requests Android to terminate background processes of other apps.
- **Root Close:** Uses root access, when available, for a more direct and thorough stopping mechanism.
- **Deep Force Stop:** Uses an Accessibility Service to interact with Android's native **Force stop** button in Settings. You can let Killora handle the process automatically or guide it manually, step by step.

## Features

- **App Management:** Scan installed apps and select which ones to close, with options to filter system apps and essential apps.
- **Quick List:** Keep your frequently closed apps in one convenient, persistent list.
- **RAM Usage Widget:** Check memory usage directly from your home screen.
- **Quick List backup:** Export your Quick List to a JSON file and import it again (Quick List tab, ⋮ menu). Importing only adds apps, and can be undone.
- **Default tab:** Choose whether Killora opens on All apps or Quick List (Settings).
- **Language:** Pick the app language in Settings. Only English ships today; see below to add more.
- **Responsive layout:** Phones, tablets and foldables in either orientation, including a two-pane landscape layout.
- **Theme Customization:** Choose Light, Dark, or AMOLED mode, or follow your system's theme.
- **One-Tap Actions:** Close your Quick List using a home-screen shortcut or widget.

See the [changelog](CHANGELOG.md) for the full release history, or jump directly to the [v1.1.0 release notes](CHANGELOG.md#v110).

## Project Structure

The project follows a standard Android application structure, with Kotlin source files organized by functionality.

```text
app/src/main/java/in/hrishikesh7665/killora/
├── MainActivity.kt
├── SettingsActivity.kt
├── QuickCloseActivity.kt
├── ForceCloseAccessibilityService.kt
├── OverlayService.kt
├── AppCloser.kt
├── ManualCloser.kt
├── RootUtils.kt
├── OemAutostartHelper.kt
├── QuickList.kt / QuickListBackup.kt
├── AppLanguage.kt
├── EdgeToEdge.kt
└── ...
```

| File | Responsibility |
|---|---|
| `MainActivity.kt` | Main screen, installed app list, and Quick List |
| `SettingsActivity.kt` | App preferences and customization |
| `QuickCloseActivity.kt` | Entry point for shortcuts and widgets |
| `ForceCloseAccessibilityService.kt` | Automates or assists with the Settings force-stop flow |
| `OverlayService.kt` | Displays progress while a closing operation is running |
| `AppCloser.kt` / `ManualCloser.kt` | Coordinates automatic and manual closing workflows |
| `RootUtils.kt` | Detects root availability and handles root commands |
| `OemAutostartHelper.kt` | Provides links to OEM-specific autostart and battery settings |
| `QuickList.kt` / `QuickListBackup.kt` | Saved Quick List, and its JSON import and export |
| `AppLanguage.kt` | In-app language choice and the list of supported languages |
| `EdgeToEdge.kt` | Window insets, maximum content width and list columns for any screen size |
| `HomeTab.kt` | The two main tabs; also the values of the Default tab setting |

## Adding a language

All user-facing text lives in `app/src/main/res/values/strings.xml`.

1. Copy it to `res/values-<code>/strings.xml` (for example `values-de`) and translate it. Leave strings marked `translatable="false"` out.
2. Add the code to `AppLanguage.supportedTags` and to `res/xml/locales_config.xml`.

The Language row in Settings then lists it automatically.

## Requirements

- **JDK:** 17
- **Android SDK:** `compileSdk 37`
- **Minimum Android version:** Android 7.0 (API 24)
- **Build system:** Gradle

## Building from Source

You can build Killora locally using Android Studio or the included Gradle wrapper.

1. Clone or download this repository.
2. Open the project in Android Studio and allow Gradle to sync.
3. Build the debug APK from the project root:

```bash
./gradlew assembleDebug
```

On Windows, use:

```bat
gradlew.bat assembleDebug
```

The generated debug APK will typically be available under `app/build/outputs/apk/debug/`.

## Releases

Download the latest version of Killora from the [GitHub Releases](../../releases/latest) page. See the [v1.1.0 release notes](CHANGELOG.md#v110) or browse the [complete changelog](CHANGELOG.md).

## Permissions and Access

Killora requests only the permissions and special access needed for its features.

| Permission or Access | Purpose |
|---|---|
| `KILL_BACKGROUND_PROCESSES` | Supports the standard background-process closing method |
| `FOREGROUND_SERVICE` | Supports foreground service functionality |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Declares the special-use foreground service, where required by the app's target SDK |
| `POST_NOTIFICATIONS` | Allows completion notifications when permitted by Android |
| `QUERY_ALL_PACKAGES` | Allows the app to discover installed applications for its core functionality |
| Accessibility Service | Optional access used for the Deep Force Stop feature |
| Root access | Optional access for devices with a working root setup |

Accessibility and root access are optional. You can use the app without enabling either, although some closing methods will not be available.

**A note on Android's limitations:** Android restricts how ordinary applications can terminate other apps. The standard method does not guarantee a complete force stop, and root-based behavior depends on the device and its configuration. Deep Force Stop relies on accessibility interactions with the system Settings interface, which may vary across Android versions and device manufacturers.

## Privacy

Killora is designed with simplicity and privacy in mind.

- No advertisements.
- No analytics or tracking.
- No network permissions requested.
- No unnecessary permissions by design.

App discovery and closing operations are intended to run locally on your device. Quick List import and export use Android's system file picker, so Killora needs no storage permission and only touches the file you choose.

## License

Killora is licensed under the **MIT License**. You are free to use, copy, modify, merge, publish, distribute, sublicense, and sell copies of the software, subject to the terms of the license.

See the [`LICENSE`](LICENSE) file for the complete license text.

Copyright (c) 2026 Killora contributors.

---

*Built to keep Android app management simple, lightweight, and in your control.*
