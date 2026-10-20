# Changelog

All release notes are maintained in this file. Use the version headings to link directly to a specific release.

## [v1.1.0](#v110)

### Added
- **Quick List import / export.** The Quick List tab has a new ⋮ menu. Export saves the list as `killora-quick-list-YYYYMMDD.json` through the system file picker. Import merges a file into the list: it only adds apps, never replaces or reorders, keeps apps that are not installed on this phone, skips invalid entries and can be undone. A plain text file with one package name per line also works.
- **Default tab setting.** Settings › Default tab chooses whether Killora opens on All apps (unchanged default) or Quick List. It applies when the app starts.
- **Language setting.** Settings › Language, built on Android's per-app language support (also listed in system Settings on Android 13+). Only English ships for now. See "Adding a language" in the README.
- **Version variables.** `APP_VERSION_NAME` and `APP_VERSION_CODE` in `gradle.properties` control the version and build number.

### Changed
- **Landscape layout** is now a real two-pane screen (status and buttons on the left, the full-height list on the right) instead of a squashed copy of portrait with 26dp buttons and 8sp text. The refresh button is back in landscape.
- **Tablets and wide windows:** content is capped to a width and centered, and the app lists use two columns where there is room.
- **Edge-to-edge:** screens now keep clear of the status bar, navigation bar, display cutout and keyboard. Status bar icons follow the theme.
- **Tap targets** are 40–48dp, and the main action buttons grow with the font size instead of clipping.
- **Closing overlay** scrolls and shrinks in landscape so the Stop button cannot be pushed off screen, and is width-capped on tablets.
- **Add apps dialog** keeps its list within 40% of the window height so it fits in landscape and split-screen.
- `OemAutostartHelper.skinName()` now takes a `Context`, so its fallback name is a translated string.
- Placeholder strings (`main_0`, `widget_text`, the `›` glyph and similar) are marked `translatable="false"`.

### Fixed
- Search and clear icons, and the Quick List "remove" button, were white and invisible in the Light theme.
- Rotating the device, or changing theme or language, reset the screen to All apps and cleared ticked apps and open search boxes. These are now kept.
- Settings had no insets handling, so on Android 15+ its header could sit under the status bar.
- **Deep force stop could miss the confirm button.** The confirm search also matched the App info page's own "Force stop" button, so a too-early second tap counted as the confirmation while the real dialog opened unanswered (seen with Zoom). Plain OK / Yes / Confirm buttons are now tried first, and a "Force stop"-labelled button is accepted only once the dialog is proven open (a Cancel button, or 700 ms elapsed).
- A confirmation dialog left open by an earlier attempt is now answered on retry instead of being searched around until timeout.
- The accessibility service now looks up Settings' "Force stop" label in the device language, so an in-app language choice can never make it search for the wrong label.
