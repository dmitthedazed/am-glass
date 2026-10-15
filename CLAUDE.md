# am-glass — liquid glass for Apple Music (Xposed module)

LSPosed/Vector module (`dev.dimka.amglass`) that gives Apple Music for Android
(`com.apple.android.music` 7.0.0-beta, versionCode 1611) iOS 26-style liquid glass, using
Kyant0's backdrop library (`io.github.kyant0:backdrop:2.0.1`, kmp branch of
github.com/Kyant0/AndroidLiquidGlass).

## Device / framework
- Phone: Realme RMX5060, Android 16 (SDK 36), density 476 (2.975x), 1264x2780. Root: KernelSU.
- Framework: Vector (JingMatrix LSPosed fork), legacy Xposed API 82.
  CLI: `adb shell su -c '/data/adb/modules/zygisk_vector/cli modules|scope ...'`.
  Module is enabled, scope = `com.apple.android.music/0`.
- Module log: `adb shell su -c 'grep -h AmGlass /data/adb/lspd/log/modules_*.log | tail'`.

## Build & install
```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk && adb shell am force-stop com.apple.android.music
```
AGP 9.3.2, Kotlin/Compose compiler 2.4.10, compileSdk 37, minSdk 33. Module resources use
package id 0x6e (`androidResources.additionalParameters`) so Compose view-tag ids never collide
with the app's 0x7f ids; `Entry.hookModuleResources` routes 0x6e string lookups to the module APK
(fixes accessibility-tree crashes).

## Architecture (app/src/main/java/dev/dimka/amglass)
- `Entry` — hooks. All app classes are R8-obfuscated; match by type or verified name.
- **Bars (dock + mini player)** — Apple Music uses Dimezis BlurView v3: `BlurTarget`
  (`blur_target`) records page content into a public `RenderNode`; `BlurView`s
  `bottom_nav_blur` / `mini_player_blur` blur it. We hook `BlurView.b(BlurTarget)`, replace its
  controller with a no-op Proxy, and add a module `ComposeView` inside the BlurView.
  - `BlurTargetBackdrop` — custom `Backdrop` drawing the BlurTarget RenderNode (screen-space
    offset, fills window color first — pages have no own background, otherwise text shows through).
  - `GlassInstaller` — private Lifecycle/SavedState owner + own Recomposer (app's AndroidX is a
    separate obfuscated copy). Clears the app's opaque "apple material" backgrounds
    (`bottom_nav_view`, `mini_player_content`), disables outline/child clipping (app re-enables
    `clipToOutline` — enforced off every pre-draw).
  - `NativeTabs` + `catalog/` — dock is the catalog's `LiquidBottomTabs` copied 1:1 (Apache-2.0,
    see `catalog/LICENSE-AndroidLiquidGlass.txt`). Native BottomNavigationView stays as invisible
    source of tabs/selection/clicks; its touches are forwarded to the ComposeView
    (`ViewGroup.dispatchTouchEvent` hook). Nav height forced to 64dp.
  - `AdaptiveGlass` — catalog `LiquidBottomTabs` look: vibrancy + blur 8dp + lens 24dp/24dp over
    container color (#121212 / #FAFAFA at 40%). Luminance probe (from `AdaptiveLuminanceGlassContent`)
    only flips content color black/white. Used by dock, mini player and header buttons.
- **Popup menu "⋯" (Library etc.)** — blurred by Haze (`dev.chrisbanes.haze`, obfuscated `nh.*`)
  inside the app's Compose. `HazeGlass` hooks `HazeEffectNode` (`nh.k.j(o1.G)`) and draws glass
  with platform RenderNode/RenderEffect + the library's lens AGSL
  (`com.kyant.backdrop.internal.ShadersKt.RoundedRectRefractionShaderString`, read by reflection),
  sampling the node's `HazeArea` content layer. Bindings are documented in the file header.
- **Header round buttons** — also Haze, but Haze gives them no areas: their source layer contains
  the buttons, sampling it recurses on RenderThread (SIGSEGV — tried, reverted). Currently drawn
  as glass material without refraction (`HazeGlass.drawMaterialOnly`). Real refraction would need
  an offscreen HardwareRenderer copy (1-frame lag).
- **Settings switches** — app's Material3 Switch (`w0.v5.a`, colors `w0.r5`, 16 longs). `SwitchGlass`
  injects marker colors (0xFF0A0B0x) and repaints them in BackgroundNode (`K.x.j`, color field `L`):
  iOS green track, white pill thumb that turns to glass while pressed (driven by Material's thumb
  growth 24dp → 28dp). Status: works; off-state thumb may still look slightly gray — verify.

## Reverse engineering
- REA MCP (`rea-agents@6.4.0`) Android provider needs
  `REA_JADX_MCP_JAR=~/.local/share/rea/android/jadx-headless-mcp-0.7.1-all.jar` (sha256
  6e5eacf5…ff75) + `JAVA_HOME`; set in both `~/.claude.json` and
  `~/.config/Claude/claude_desktop_config.json` (backups `*.bak-rea-*`). MCP tools need an app
  restart to pick it up; CLI works: `rea trace-android-references <apk> <Class> --format json`.
- Pull APK: `adb shell pm path com.apple.android.music`, then `jadx --no-res -d out base.apk`.

## Testing notes
- Coordinates (px): dock y≈2655, tab centers x≈184/408/632/856/1080; Library "⋯" (1143,247),
  menu "Settings" (830,715). Check foreground with
  `adb shell dumpsys activity activities | grep topResumedActivity` (not `dumpsys activity top`).
- Careful: taps on Settings can toggle real user preferences.

## Ideas / next
- Header buttons with real refraction (offscreen render).
- Mini player press "squish"; adaptive tint for popup menu.
