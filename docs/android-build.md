# Android build

Decision 45. An optional tool: Kotlin and Java Android apps build on the phone, from a terminal or a Claude session. No Developer options. Measurements and how it was worked out: `docs/android-tools-spike.md`.

## Install and remove

Settings → Tools → Android build. Nothing is bundled and first-run setup never installs it. About 390 MB to download, 880 MB on the phone. The first build of a project downloads about 260 MB more and uses about 800 MB in `~/.gradle`.

Install opens Google's SDK license, read live from Google's manifest. **Accept and install** starts the install; Decline does nothing.

Both go through `setup/pocketrocket-setup.sh`:

- `--only android-build` installs Java 17, then `gradle`, `aapt2`, `aapt`, `aidl` and `android-tools` from the Termux repos. It downloads platform 37.0 and build-tools 36.0.0 from Google, checks them against the sha1 in Google's manifest, and writes the license file and each package's `package.xml`. Needs `POCKETROCKET_SDK_LICENSE_HASH`, which the app sets only after Accept. Retry resumes.
- `--only android-build-remove` stops Gradle and Kotlin daemons, then removes the SDK, the profile file, our block in `gradle.properties` and what Gradle downloaded. It removes the packages the install brought in and no others. A package something else now needs is kept and named in the log. `gradle.properties` lines of your own and `~/.gradle/init.d` stay. `~/.android` (debug keystore, adb key) stays too.

## How builds find it

| Setting | Where |
|---|---|
| `ANDROID_HOME`, `JAVA_HOME` | `$PREFIX/etc/profile.d/pocketrocket-android-build.sh` for new terminals; `AndroidBuild.environment()` for new Claude sessions |
| `android.aapt2FromMavenOverride` | A marked block in `~/.gradle/gradle.properties` |
| `org.gradle.daemon.idletimeout=600000` | Same block. A daemon holds over 1 GB, and the default keeps it for 3 hours |

`build-tools/36.0.0/{aapt,aapt2,aidl}` and `platform-tools/{adb,fastboot}` are symlinks to Termux's arm64 builds.

The tool counts as installed when `$ANDROID_HOME/.pocketrocket-installed` exists; the script writes it last.

## Limits

- Native code needs the Android NDK tile, below. No RenderScript.
- No emulator on the phone. Device testing is a separate tile (`docs/device-testing.md`), not built yet.
- A project on other SDK versions gets them from the Gradle plugin, x86_64 tools included. Only `aapt2` is overridden there, so AIDL needs build-tools 36.0.0.
- Google's manifest changes are not tracked. Moving to a newer platform means updating the versions and sha1s in the script.

## Android NDK tile

Decision 50. A separate optional tile on top of this tool: `--only android-ndk` and `android-ndk-remove`. 96 MB download, 608 MB on disk, 14 packages, 51 s through the tile on the emulator. Why it is built this way: `docs/android-ndk-spike.md`.

| What | Where |
|---|---|
| clang, cmake, ninja, ndk-sysroot | From Termux. `~/.pocketrocket/android-ndk.packages` records what the install brought in, so uninstall purges only that |
| The shim | `$ANDROID_HOME/ndk-shim`: symlinks, a linker wrapper that drops Termux's rpath, the toolchain file |
| NDK versions | `$ANDROID_HOME/ndk/<version>`: a real `source.properties` claiming that revision, the rest symlinks to the one shim. The list is `ANDROID_NDK_VERSIONS` in the setup script; the page reads the folders |
| CMake | `$ANDROID_HOME/cmake/3.22.1`, the plugin's default, pointing at Termux's CMake |
| Gradle hook | `~/.gradle/init.d/pocketrocket-android-ndk.gradle` |

Every version is the same Termux compiler: a pin is accepted, it does not get that real NDK. The versions are the plugin defaults read from its jars: 25.1.8937393 (AGP 8.0-8.3), 26.1.10909125 (to 8.6), 27.0.12077973 (to 8.13), 28.2.13676358 (9.0 on).

The Gradle hook does two things:

- **A version not listed stops the build in seconds, with a message listing what is accepted.** Left alone, the plugin accepts the license file, downloads Google's x86_64 NDK (2.4 GB on disk, about 3 minutes on the emulator) and then fails because it cannot run. Gradle runs the plugin's hook even after ours throws, so ours moves `ndkVersion` to a served folder first; a throw alone does not stop the download.
- **A project that names no STL gets `-DANDROID_STL=c++_shared`.** The toolchain's own default is not enough: the plugin packs `libc++_shared.so` only when the argument names it, and the app would build and then fail to load.

Uninstalling the Android tool removes the NDK tool first; its confirm sheet says so. The NDK page offers no Install without the Android tool.

Limits: arm64 only; `c++_static` stops with a message; `ndk-build` stops with a message; linking uses the phone's system libraries, so an app can link something an older phone lacks with no build error; `pkg upgrade` moves the compiler. Termux's Gradle (9.8) cannot run AGP 8.13 ("relies on ... a Gradle internal API that was removed in Gradle 9.6.0"), so such a project needs its own `./gradlew`. Build Play releases with Google's real NDK.

An Android tool with no NDK tile has no hook: a native project there still triggers the 2.4 GB download.

## Code

`com.pocketrocket.androidbuild`: `AndroidBuild` (state and running the script), `AndroidBuildTool` (facts and sizes), `SdkLicense` (reading the license, its hash). `ui/tools/AndroidBuildToolPage.kt`: the page and license sheet. `setup/ToolScript.kt` runs any tool's `--only` option, shared with Chromium. Test with the stub: `-Ppocketrocket.setupStub`, and `touch ~/.pocketrocket/stub-android-build-fail-once` to fail once.
