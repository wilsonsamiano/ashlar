# Win10 Launcher

Windows 10 Mobile style home screen for Android. Metro tiles, an A–Z app list, and the classic Windows Phone accent colors. Static tiles only — no live tiles.

Beta for sideload. Package `com.wilson.win10launcher`, so it will not clash with another launcher.

[![Buy Me a Coffee](https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png)](https://buymeacoffee.com/wilsonsamiano)

## Download the beta

**[Win10Launcher-2.30.apk](https://github.com/wilsonsamiano/win10-launcher/releases/download/v2.30.0-beta/Win10Launcher-2.30.apk)** — version 2.30 (versionCode 41)

Source on `main` is **2.17** (versionCode 28). Same hinge, same exponential ease, drawn on the GPU render thread so a slow tablet keeps every frame. The APK linked above is still 2.16 until the next signed build. Install that build over this one. Tiles and settings stay.

1. Download the APK onto the tablet (Onn 8.1 and other Android 8+ devices).
2. Open it and allow install from that source if Android asks.
3. If an older Win10 Launcher is already installed, install this over it. Same signing key.
4. Set it as Home: **Settings → Apps → Default apps → Home app → Win10 Launcher**.

## What you get

- Start screen: half, small, medium, and wide tiles in the accent color
- **Edit** rearranges the layout: long-press and drag tiles, tap a tile to resize or unpin
- Clock tile shows the live time and the date. On a wide tile the time grows to fill the tile
- Settings: Dark, Light, or Auto. Auto is light from sunrise to sunset and dark after sunset. Save Start layout keeps a copy you can restore. There is no reset.
- Press a tile and it tilts toward your finger, then springs back
- Icons are sized to fill the tile, with the name still visible underneath
- Swipe left on Start, or tap **ALL APPS**: the screen slides (flat, no page turn)
- Tap a tile: it presses in, then the tiles turn together on the left edge of the screen. They stay separate, and the one you tapped leads. No zoom. The turn is a GPU texture spin, not a per-frame redraw
- Opening an app from the list, or Settings, still turns the whole page
- Back from All apps slides the other way. Back from Settings turns the page back. Back from an app unfolds the tiles
- Pin with the **+** tile, or long-press an app in the list
- Long-press a tile to resize or unpin
- A–Z app list with letter jump and search
- 20-color Windows Phone accent palette, dark/light theme, reset layout
- Settings → **Check for updates** installs the next version over this one

## Build

Android Studio, or the command line. JDK 17, Gradle 8.7, Android Gradle Plugin 8.5.2, compileSdk 34, minSdk 26.

```bash
./gradlew assembleDebug
```

The published release APK is already signed. To sign your own release build, point these at a keystore you keep offline:

```bash
export WIN10_KEYSTORE=/path/to/release.keystore
export WIN10_KEYSTORE_PASSWORD=...
export WIN10_KEY_ALIAS=win10   # optional
./gradlew assembleRelease
```

The keystore is not in this repository.

## License

[MIT](LICENSE) © 2026 Wilson Samiano

[Buy me a coffee](https://buymeacoffee.com/wilsonsamiano)
