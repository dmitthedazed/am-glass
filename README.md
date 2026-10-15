<p align="center">
  <img src="docs/icon.png" width="128" alt="AM Liquid Glass icon">
</p>

<h1 align="center">AM Liquid Glass</h1>

<p align="center">
  An LSPosed / Xposed module that gives <b>Apple Music for Android</b> iOS 26-style liquid glass.
</p>

<p align="center">
  <img src="docs/dock-normal.jpg" width="640" alt="Glass dock and mini player"><br>
  <sub>Resting</sub>
</p>
<p align="center">
  <img src="docs/dock-pressed.jpg" width="640" alt="Pressed dock: the selection turns into a refracting lens"><br>
  <sub>Pressed: the dock grows and the selection turns into a lens</sub>
</p>

## What it changes

- **Tab bar (dock).** A floating glass capsule with vibrancy, blur and edge refraction, copied from
  the `LiquidBottomTabs` demo of [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass).
  Press and drag a tab: the selection pill becomes a lens with chromatic aberration.
- **Mini player.** The same glass capsule. Its text and buttons switch between black and white
  depending on how bright the content under it is.
- **Header buttons and the "⋯" menu.** Glass instead of the stock blur.
- **Settings switches.** iOS-style green track and a white pill thumb that turns to glass while pressed.

Everything is drawn with Kyant0's [backdrop](https://github.com/Kyant0/AndroidLiquidGlass) library
(`io.github.kyant0:backdrop:2.0.1`) on top of the content Apple Music already renders for its own blur.

## Requirements

| | |
|---|---|
| Apple Music | `com.apple.android.music` **7.0.0-beta** (versionCode 1611) |
| Android | 13+ (API 33), tested on Android 16 |
| Framework | LSPosed / Vector or another Xposed API 82 framework |
| Root | Magisk, KernelSU or APatch |

Apple Music is R8-obfuscated, and the module finds classes by type and member names of this exact
build. Other versions will most likely not get the glass. The module was tested on a single device
(Realme RMX5060), so treat it as experimental.

## Install

1. Download `am-glass-*.apk` from [Releases](../../releases) and install it.
2. Enable **AM Liquid Glass** in LSPosed and set the scope to **Apple Music** (`com.apple.android.music`).
3. Force-stop Apple Music and open it again.

The release APK is signed with a debug key, so to update it you may need to uninstall the old build first.

## How it works

- Apple Music draws its floating bars with Dimezis **BlurView**: a `BlurTarget` records the page into
  a `RenderNode` and each `BlurView` blurs it. The module hooks `BlurView`, swaps its blur controller
  for a no-op and puts its own `ComposeView` inside, sampling the same `RenderNode` as a `Backdrop`.
- The stock `BottomNavigationView` stays as an invisible source of tabs, selection and clicks, and
  its touches are forwarded to the glass dock.
- The module ships its own Compose runtime with a private lifecycle and recomposer, because Apple
  Music's AndroidX is a separate obfuscated copy. Module resources use package id `0x6e`, so their
  IDs never collide with the app's `0x7f` ones.
- The "⋯" menu is blurred by Haze inside the app's own Compose. The module hooks Haze's effect node
  and draws glass with platform `RenderEffect`s and the library's lens shader.

Source lives in [`app/src/main/java/dev/dimka/amglass`](app/src/main/java/dev/dimka/amglass).

## Build

```bash
JAVA_HOME=/path/to/jdk-21 ./gradlew :app:assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`.
AGP 9.3, Kotlin 2.4, compileSdk 37, minSdk 33.

## Credits

- [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass): the backdrop library,
  and the catalog's `LiquidBottomTabs` and its helpers, copied into
  [`catalog/`](app/src/main/java/dev/dimka/amglass/catalog) under Apache-2.0.

Not affiliated with Apple. Apple Music is a trademark of Apple Inc.

## License

[Apache-2.0](LICENSE)
