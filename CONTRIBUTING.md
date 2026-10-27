# Contributing

## Build and try

```sh
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am force-stop com.apple.android.music
```

Needs JDK 21 and a rooted phone with LSPosed and Apple Music 7.0.0-beta (versionCode 1611).

Module logs: `adb shell su -c 'grep -h AmGlass /data/adb/lspd/log/modules_*.log | tail'`.

## Rules of thumb

- Apple Music is obfuscated. Find classes by types and member signatures, not by short names alone, and log clearly when something isn't found.
- A hook must never crash Apple Music. If something is missing, skip the glass and leave the stock UI.
- Code in `catalog/` comes from Kyant0/AndroidLiquidGlass under Apache-2.0 (see `LICENSE-AndroidLiquidGlass.txt` there).

## Pull requests

Keep them small. Mention your phone, Android version and framework, and add a screenshot if the change is visual.
