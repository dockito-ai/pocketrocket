# Android tools spike

31 Oct 2026. Decision 45. Can Android apps be built and tested on the phone, inside PocketRocket? Yes to both on the emulator. Nothing here has run on Shankar's phone yet.

Setup: arm64 API 36 emulator, headless, a copy of `PocketRocket_API36_arm64` with 6 GB RAM, 6 cores and a 16 GB data partition. Commands ran in a real PocketRocket terminal, typed into it, so the app's process rules applied. Sources: `docs/android-tools-spike/hello` (Kotlin, Compose, AGP 9.5.0, Gradle 9.8.2) and `docs/android-tools-spike/vdisplay`.

## Build: passed

### Numbers

| What | Measured | Note |
|---|---|---|
| Download from the tile | 387 MB | 262 MB from Termux (48 packages), 125 MB from Google |
| Disk from the tile | 876 MB | `$PREFIX` 920 → 1796 MB. SDK is 356 MB of it |
| Install time | 86 s | Emulator network |
| Uninstall | 5 s, back to 920 MB and the same 157 packages | No package list difference, also after a second install |
| First Compose build, cold | 164, 167 and 271 s | The 271 s run followed a reboot, with another agent's emulator busy |
| First build also downloads | 258 MB | `~/.gradle/caches/modules-2` |
| First build disk | 796 MB in `~/.gradle` | |
| Second build, nothing changed | 1 s | Daemon running |
| One line changed | 1-2 s | Daemon running |
| Clean rebuild, warm | 4 s | Daemon running |
| Release with R8 and AIDL | 46 s, first time | 1.2 MB APK |
| Debug APK | 11.5 MB | |
| Peak RAM, debug build | 2.9-3.4 GB | Sum of RSS for the app's user, so shared pages count twice |
| Peak RAM, release build | 3.6 GB | Same method |
| Lowest free memory, 6 GB emulator | 0.85-1.5 GB | `MemAvailable` |
| Peak processes for the app's user | 12 | Android's limit is 32 |
| Killed by the process limit | None | logcat has only normal exits |

**The emulator flatters these times.** It runs on a Mac's fast cores with fast storage and a good network. Expect a phone to be several times slower, and it may throttle in a long build. The RAM figures depend on the 2 GB Gradle heap in `hello/gradle.properties`; the build also passed on Gradle's default heap. The process count covers one build with no Claude session running, so the phone's margin will be smaller.

### What the tool does differently from the first plan

- **`sdkmanager` no longer runs here.** Current cmdline-tools (16111833) hand `sdkmanager` over to an `android` binary that is x86_64 and fails. Older versions are pure Java, but pinning a deprecated tool is a dead end and costs 172 MB. The setup script downloads Google's zips instead, checks them against the sha1 in Google's manifest, and writes the `package.xml` that `sdkmanager` would write.
- **A plain unzip is not enough.** Without `package.xml` the Gradle plugin treats the package as missing and tries to install it. This is the risky part of the design: the file format is Google's and could change, though it has been stable for years.
- **Platform 37.0, not 36.** Today's Compose BOM (2026.09.00) refuses to build below `compileSdk` 37. AGP 9.5 wants build-tools 36.0.0, which is what the tool installs.
- **`apksigner` is left out of the tile.** Termux's package depends on `openjdk-21`, which adds about 100 MB download and 220 MB disk and makes `java` on PATH version 21 beside `JAVA_HOME` 17. The Gradle plugin signs without it. `pkg install apksigner` still works.
- **Java 17 is installed first.** `gradle` accepts any JDK, and apt would pick 21 otherwise.

### x86_64 binaries

Google's build-tools for Linux are x86_64 and cannot run here. `aapt2` has to be replaced through `android.aapt2FromMavenOverride`, because the plugin fetches its own from Maven otherwise.

| Binary | Result |
|---|---|
| `aapt2`, `aapt`, `aidl` | Symlinked to Termux's arm64 builds. AIDL build tested |
| `platform-tools/adb`, `fastboot` | Symlinked to Termux's `android-tools`, with a `package.xml` so the plugin does not fetch x86_64 ones |
| `zipalign`, `dexdump`, `split-select`, `bcc_compat`, `llvm-rs-cc`, `lld` | Left as x86_64 and unusable. No Termux package for the first three. A normal debug or release build did not call them |
| `d8`, `apksigner` scripts | Java, so they should run; not run here |

Not supported: the NDK, RenderScript, and anything else that calls those binaries.

A project that wants other SDK versions gets them from the plugin, which downloads them itself in pure Java because the license file is there. Tested with AGP 8.13, Gradle wrapper 8.13 and `compileSdk` 36: it fetched platform 36 and build-tools 35 and built. **Those auto-installed build-tools keep x86_64 `aidl`**, so AIDL needs build-tools 36.0.0 or a manual symlink. They also add disk: the SDK went from 356 to 705 MB, and `~/.gradle` reached 2.2 GB with two wrappers.

### SDK license

Accepting means writing the hash `24333f8a…` to `$ANDROID_HOME/licenses/android-sdk-license`, the same file `sdkmanager --licenses` writes. Without that file the plugin refuses to download anything, though packages already installed work. The tool page fetches the current text from Google's manifest and shows it; **Accept and install** is the only way an install starts, and the script refuses to fetch the SDK without the hash. Uninstall deletes the file, so a new install asks again.

The hash cannot be derived from the published text with the obvious formulas (sha1 with several whitespace variants did not match), so it is a constant, tested against today's manifest. If Google publishes new terms, the user reads the new text, installed packages keep working, and the plugin refuses only new downloads until the hash is updated.

### Other findings

- `./gradlew` works, with its own Gradle distribution. On this network Java timed out on one GitHub asset IP (`185.199.109.133`) that curl skipped past, so `gradle wrapper` validation and `./gradlew` first downloads failed; the wrapper test used a zip fetched by curl. Not Android-specific, but a slow or partly blocked network will show it.
- Terminals opened before the install lack the environment. The page says new ones pick it up.
- **First-run setup, unrelated to this work:** 2 of 3 fresh installs stopped at "Installing base packages" with exit code 127, right after the upgrade of the bundled packages. Retry succeeded each time. The cause is not established; one guess is the progress loop calling a coreutils command while coreutils is being replaced.

## Device testing: passed on the emulator

| Check | Result |
|---|---|
| `adb pair` to localhost from the app's shell | Passed. Started before opening the pairing dialog, then given the code |
| `adb connect` | Passed with `127.0.0.1:<port>`. `localhost` failed on the first try, right after pairing, and worked on the retry; use `127.0.0.1` |
| `adb shell id` | `uid=2000(shell)` |
| `adb install` with no tap | Passed, 107 ms, app already built |
| Create a hidden virtual display | Passed, but **there is no shell command for it**. `vdisplay/VDisplay.java` is a small helper run with `app_process` as the shell user, backed by an ImageReader |
| `am start --display N` | Passed. The app ran on the virtual display |
| Visible screen stays put | Passed. Display 0 kept the terminal, confirmed by `dumpsys` and a screenshot |
| `screencap -d` | Passed only with SurfaceFlinger's id from `dumpsys SurfaceFlinger --display-id`. The display's own id fails with "not valid" |
| Screenshot from the helper | Passed. Reads the ImageReader and writes a PNG, needs no id lookup |
| `uiautomator dump` | Default dumps only the focused display. `--windows` dumps every display, grouped by `<display id>`, so that form is needed. `-d N` is ignored |
| `input -d N tap` | Passed, including while focus was on display 0. The app logged each tap |
| `logcat` | Passed |
| Removing the display | Passed. `DESTROY_CONTENT_ON_REMOVAL`, and none left afterwards |

**Side effect:** `am start --display` moves system focus to the virtual display (`mCurrentFocus` is the test app). The visible app stays on screen but may stop receiving hardware key events until it is touched. A real tap on display 0 brings focus back. Needs checking on the phone with the keyboard open.

**Not tested on the emulator:** the screen really turning off (the power key left it `Awake`); wireless debugging dropping when Wi-Fi changes; whether the pairing dialog survives switching to another app. The emulator's adbd also listens on port 5555, so the inner `adb` listed an `emulator-5554` device automatically; a phone will not.

The helper was compiled on the Mac. Device testing would ship it inside the APK as our own code, or build it on the phone with `d8`.

### What Shankar must check on the Xiaomi

1. **Wireless debugging needs Wi-Fi.** Does it turn on without Wi-Fi, for example on a hotspot, and does it stay on when the screen locks?
2. **Pairing.** Open Settings, then the pairing dialog, then PocketRocket in split screen or a floating window. Does the dialog stay open long enough? HyperOS may close it, or offer a notification instead.
3. **"USB debugging (Security settings)".** MIUI and HyperOS require this toggle for `input` and sometimes for `pm install`, and it needs a Mi account and a SIM. Without it, expect `INJECT_EVENTS` errors on `input`.
4. **"Install via USB".** MIUI's separate toggle. Without it `pm install` may fail or show a prompt, which would break the no-tap install.
5. **Virtual display.** Does the helper create it? Does `am start --display` stay there, or does HyperOS move the app to the main screen? Does anything appear on the visible screen?
6. **`screencap -d` and `uiautomator dump --windows`** on the virtual display.
7. **Battery saver.** Does HyperOS kill the shell helper or the adb connection when the screen is off? Does the system disconnect wireless debugging after a while?
8. **Real build numbers.** Time, heat, and whether anything dies with Claude also running. `logcat | grep -i phantom` shows the process limit at work.
