# Killora

**One tap. Zero bloat.**

Killora is a native Android app that closes background apps on demand — no bloat, no
ads, no unnecessary permissions. It supports three closing strategies depending on what
the device allows:

- **Standard close** — requests Android to end other apps' background processes.
- **Root close** — uses root (if available) for a more thorough stop.
- **Deep force stop** — drives the real Settings "Force stop" button via an
  Accessibility Service, either automatically or step-by-step in manual mode.

## Features

- Scan and select installed apps (with system-app and essential-app filtering)
- A persistent "Quick List" of apps you close often
- Home-screen RAM usage widget
- Light, Dark, and AMOLED themes (plus "follow system")
- App shortcut and widget action for one-tap closing of the Quick List

## Project structure

```
app/src/main/java/in/hrishikesh7665/killora/
├── MainActivity.kt                    # Home screen: app list + Quick List
├── SettingsActivity.kt                # Preferences screen
├── QuickCloseActivity.kt              # Headless entry point for shortcuts/widgets
├── ForceCloseAccessibilityService.kt  # Drives the Settings "Force stop" flow
├── OverlayService.kt                  # Progress overlay shown while closing
├── AppCloser.kt / ManualCloser.kt     # Closing orchestration (automatic/manual)
├── RootUtils.kt                       # Root detection and su invocation
├── OemAutostartHelper.kt              # OEM-specific autostart/battery settings links
└── ...                                # Adapters, prefs, theming, and small helpers
```

## Building

This is a standard Gradle-based Android project.

```bash
./gradlew assembleDebug
```

Requirements:
- JDK 17
- Android SDK (compileSdk 37, minSdk 24)

Open the project in Android Studio and let it sync, or build from the command line with
the wrapper above.

## Permissions

| Permission | Why |
|---|---|
| `KILL_BACKGROUND_PROCESSES` | Default, standard close method |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` | Keeps the progress overlay running during a close |
| `POST_NOTIFICATIONS` | Lets the app notify when a run finishes while backgrounded |
| `QUERY_ALL_PACKAGES` | Needed to list and close every installed app — the app's core purpose |
| Accessibility Service (opt-in, in system Settings) | Powers the optional "deep force stop" |
| Root (opt-in, if the device is rooted) | Alternative to the Accessibility path |

No analytics, ads, or network permissions are requested.

## License

Add a license of your choice here before publishing (e.g. MIT, Apache-2.0, GPL-3.0).
