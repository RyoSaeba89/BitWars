# BitWars on OUYA — technical port notes

This document explains how BitWars (an HTML5 / ImpactJS game) was packaged to
run on the **OUYA** (NVIDIA Tegra 3, Android 4.1.2 "Jelly Bean", API 16), the
problems that had to be solved, and how to reproduce the build.

---

## 1. The game

BitWars is built with **ImpactJS**, a commercial HTML5 game engine:

- Pure **Canvas 2D** rendering, **320×180** internal resolution scaled ×3.
- Pixel‑art assets, 60 fps target.
- **HTML5 Audio** for music and sound effects (`new Audio()` — confirmed by
  grepping the build: zero `AudioContext` / WebAudio usage).
- A `plugins/gamepad.js` that uses the **W3C Gamepad API**
  (`navigator.getGamepads()`) with an OUYA‑specific button map.

So the game was *designed* for the OUYA controller — it even bundles an
OUYA‑branded font (`media/font/ouya.png`) — but it was never shipped.

### Source base: the "engine" commit

Upstream's tip is missing `lib/impact/` — the proprietary ImpactJS engine —
because a late commit (`ad99fcd "no more engine"`) stripped it out, leaving only
the minified `release/game.min.js`.

For a clean, un‑minified, hackable base we build from the **last commit that
still contained the engine**:

```
2ab5a8a  "gitignore"      <-- parent of "no more engine"; full ImpactJS + final game code
ad99fcd  "no more engine" <-- engine removed here
```

The web payload bundled in the APK (`app/src/main/assets/www/`) is the engine
tree at `2ab5a8a` (preserved on the `ouya-engine` branch): `index.html` →
`lib/impact/impact.js` + `lib/game/main.js`, plus `media/`. All 40 ImpactJS
modules referenced by the
game resolve to real files, so it loads cleanly.

---

## 2. Why the stock WebView does not work

The OUYA runs **Android 4.1**, whose system WebView is the old **WebKit** one
(Chromium‑based WebView only arrived in Android 4.4 KitKat). That WebView:

- has **no Gamepad API** — `navigator.getGamepads` is `undefined`, so
  `gamepad.js` self‑disables (`if (!navigator.getGamepads) return;`) and the
  controller does nothing. Fatal for a controller‑only console game.
- has weak Canvas 2D performance and flaky HTML5 Audio.

## 3. The solution: embedded Crosswalk

[Crosswalk](https://crosswalk-project.org/) is Intel's embeddable Chromium
runtime. We use it in **embedded mode**: the Chromium runtime is compiled into
the app, producing a single self‑contained APK (no separate "runtime" install).

- **Version:** `xwalk_core_library-23.53.589.4` → **Chromium 53**.
- **Android support:** the AAR declares `minSdkVersion 15`, and the official
  2016 Crosswalk 23 ARM build declares `minSdk 16 / target 21`. The
  chromium‑crosswalk fork kept Jelly Bean (4.1) support well past the point
  upstream Chrome dropped it (~Chrome 51). So Chromium 53 runs on the OUYA.
- This is **newer than the Chromium 44 (Crosswalk 15)** that the OUYA homebrew
  community typically used — Chromium 53 has a faster Canvas 2D path and more
  complete web APIs, and it still installs and runs on 4.1. (If a future,
  heavier HTML5 game ever misbehaves on the Tegra 3 with Crosswalk 23, the
  fallback is Crosswalk 15.44.x / Chromium 44.)

### Crosswalk AAR provenance

`app/libs/xwalk_core_library-23.53.589.4.aar` (≈48 MB) is the Crosswalk core
library repackaged as an Android AAR. It originates from the official Crosswalk
distribution (`download.01.org/crosswalk/.../maven2/org/xwalk/xwalk_core_library/`).

Notes for anyone auditing it:

- The AAR's top‑level `classes.jar` is a 681‑byte `BuildConfig` stub — that is
  **normal**. The real Java API (`org.xwalk.core.XWalkView`, `XWalkActivity`,
  `XWalkInitializer`, …) lives in the AAR's inner `libs/xwalk_core_library.jar`
  (3.78 MB), which Gradle puts on the classpath automatically.
- It ships native libs for both `armeabi-v7a` (38 MB) and `x86` (58 MB); we
  filter to `armeabi-v7a` only (see below) so the OUYA APK stays ~40 MB.
- Equivalent official forms: `crosswalk-webview-23.53.589.4-arm.zip` (the Ant
  library project — same `.jar` + `.so` + `res`) and
  `crosswalk-apks-23.53.589.4-arm.zip` (shared‑mode runtime APK + a HelloWorld
  sample — *not* used here; shared mode needs two installs).

---

## 4. Project layout

```
ouya/
├─ build.gradle              # AGP 7.0.2
├─ settings.gradle
├─ gradle.properties
├─ gradlew[.bat] + gradle/   # Gradle 7.0.2 wrapper
└─ app/
   ├─ build.gradle           # minSdk16/target21, abiFilter armeabi-v7a, flatDir AAR
   ├─ libs/
   │  └─ xwalk_core_library-23.53.589.4.aar
   └─ src/main/
      ├─ AndroidManifest.xml
      ├─ java/in/p1x/bitwars/MainActivity.java
      ├─ res/{values,mipmap-*,drawable-xhdpi}/   # strings, launcher icon, OUYA tile
      └─ assets/www/          # the ImpactJS game (engine commit 2ab5a8a)
```

### `MainActivity`

`MainActivity extends org.xwalk.core.XWalkActivity`. In embedded mode
`onXWalkReady()` fires as soon as the bundled runtime initialises; there we:

```java
XWalkPreferences.setValue(XWalkPreferences.ANIMATABLE_XWALK_VIEW, false); // SurfaceView = best Tegra perf
XWalkPreferences.setValue(XWalkPreferences.REMOTE_DEBUGGING, true);       // chrome://inspect over adb
XWalkView v = new XWalkView(this, this);
setContentView(v);
v.load("file:///android_asset/www/index.html", null);
```

Plus immersive fullscreen, `FLAG_KEEP_SCREEN_ON`, and `onResume/onPause/
onDestroy` forwarding to the XWalkView.

### Manifest — OUYA specifics

- `tv.ouya.intent.category.GAME` **and** `android.intent.category.LEANBACK_LAUNCHER`
  in the launcher intent‑filter → the side‑loaded APK shows up in the OUYA
  *"PLAY"* games menu.
- `<meta-data android:name="tv.ouya.icon" android:resource="@drawable/ouya_icon"/>`
  → the 732×412 tile for the games menu.
- `android:screenOrientation="landscape"`, `Theme.NoTitleBar.Fullscreen`,
  `extractNativeLibs="true"` (Crosswalk `dlopen`s `libxwalkcore.so`).
- `INTERNET` permission is required even though content is local.

### `app/build.gradle` highlights

```gradle
repositories { flatDir { dirs 'libs' } }              // consume the local AAR
android {
  compileSdkVersion 30
  defaultConfig {
    applicationId 'in.p1x.bitwars'
    minSdkVersion 16; targetSdkVersion 21
    ndk { abiFilters 'armeabi-v7a' }                  // drop the 58 MB x86 .so
  }
  lintOptions { abortOnError false }                  // legacy AAR trips lint
}
dependencies { implementation(name: 'xwalk_core_library-23.53.589.4', ext: 'aar') }
```

---

## 5. Build toolchain

| Tool | Version |
|---|---|
| Android Gradle Plugin | 7.0.2 |
| Gradle | 7.0.2 |
| JDK | 11 |
| compileSdk / build‑tools | 30 / 30.0.3 |
| NDK | **none** — Crosswalk's `.so` is prebuilt; we only package it |

```sh
export JAVA_HOME=/path/to/jdk-11
cd ouya
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk  (~39 MB, armeabi-v7a)
```

### Gotcha: `local.properties` escaping

On Windows, write the SDK path with **forward slashes**:

```
sdk.dir=C:/Users/you/AppData/Local/Android/Sdk
```

Backslashes are escape characters in a `.properties` file (`\U`, `\A`, …) and
silently corrupt the path, which surfaces as a cryptic
`java.io.IOException: The filename, directory name, or volume label syntax is
incorrect` during `:app:compileDebugJavaWithJavac`.

---

## 6. On‑device validation (real OUYA Console, Android 4.1.2)

- Installs and boots from the OUYA games menu.
- **Title screen renders perfectly** (logo, soldiers, credits) — see
  `docs/title.png`.
- Advancing past the intro generates a map and enters a match: the HUD (BLUE /
  RED teams, flags, timers, resources), the turn system, and the in‑game menu
  (RESUME / SURRENDER / RESTART MAP / GENERATE NEW MAP / SOUND) all render and
  work.
- **Controller works** through Crosswalk's Gamepad API (validated with a
  physical OUYA controller).
- **Audio works** (music + SFX).

### Harmless log noise

Crosswalk probes for Android APIs that don't exist on 4.1, so `logcat` is full
of `dalvikvm: Could not find method …` and
`Link of class 'org.chromium.net.NetworkChangeNotifierAutoDetect$MyNetworkCallback' failed`.
These are expected — Crosswalk degrades gracefully. You will also see
`cr_MediaResource: File does not exist / Unable to configure metadata extractor`
from the native MediaPlayer probe; Chromium's own media pipeline still plays the
audio, so it can be ignored.

### Note: OUYA `HypervisorService` watchdog

Firing several `adb shell input keyevent` events in quick succession can trip
the OUYA's `HypervisorService: Application timed out` watchdog and force‑stop
the app (e.g. SPACE → "next turn" kicks off a blocking AI turn). This is an
artifact of injecting synthetic key bursts faster than a human; normal gamepad
play does not hit it. Also note `adb` can only inject **key events** (the game's
keyboard binds), not Gamepad‑API state — the controller has to be validated with
a real pad.

---

## 7. Reproduce from scratch

1. The web payload is already committed under
   `ouya/app/src/main/assets/www/`. To regenerate it from scratch, copy the
   full engine tree (incl. `lib/impact/`) preserved on the `ouya-engine`
   branch under `sources/`:
   ```sh
   git checkout ouya-engine -- sources
   cp -r sources/{index.html,favicon.png,lib,media} ouya/app/src/main/assets/www/
   ```
2. Put `xwalk_core_library-23.53.589.4.aar` in `ouya/app/libs/`.
3. `./gradlew assembleDebug` (JDK 11).
4. `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
