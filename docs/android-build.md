# Android build

Decision 45. An optional tool: Kotlin and Java Android apps build on the phone, from a terminal or a Claude session. No Developer options. Measurements and how it was worked out: `docs/android-tools-spike.md`.

## Install and remove

Settings → Tools → Android build. Nothing is bundled and first-run setup never installs it. About 390 MB to download, 880 MB on the phone. The first build of a project downloads about 260 MB more and uses about 800 MB in `~/.gradle`.

Install opens Google's SDK license, read live from Google's manifest. **Accept and install** starts the install; Decline does nothing.

Both go through `setup/pocketrocket-setup.sh`:

- `--only android-build` installs Java 17, then `gradle`, `aapt2`, `aapt`, `aidl` and `android-tools` from the Termux repos. It downloads platform 37.0 and build-tools 36.0.0 from Google, checks them against the sha1 in Google's manifest, and writes the license file and each package's `package.xml`. Needs `POCKETROCKET_SDK_LICENSE_HASH`, which the app sets only after Accept. Retry resumes.
- `--only android-build-remove` stops Gradle and Kotlin daemons, then removes the Android NDK, the SDK, the profile file, our block in `gradle.properties`, our Gradle hook and what Gradle downloaded. It removes the packages the install brought in and no others. A package something else now needs is kept and named in the log. `gradle.properties` lines of your own and your own `~/.gradle/init.d` scripts stay. `~/.android` (debug keystore, adb key) stays too.

## How builds find it

| Setting | Where |
|---|---|
| `ANDROID_HOME`, `JAVA_HOME` | `$PREFIX/etc/profile.d/pocketrocket-android-build.sh` for new terminals; `AndroidBuild.environment()` for new Claude sessions |
| `android.aapt2FromMavenOverride` | A marked block in `~/.gradle/gradle.properties` |
| `org.gradle.daemon.idletimeout=600000` | Same block. A daemon holds over 1 GB, and the default keeps it for 3 hours |
| The NDK hook | `~/.gradle/init.d/pocketrocket-android-ndk.gradle` (below): with no NDK, a native project's C and C++ tasks stop with a message instead of downloading Google's NDK |

`build-tools/36.0.0/{aapt,aapt2,aidl}` and `platform-tools/{adb,fastboot}` are symlinks to Termux's arm64 builds.

The tile reads what is on the phone each time Tools opens, whoever set it up; no flag is saved at install (`AndroidBuildTool.presence`). It shows Installed while `$ANDROID_HOME` exists, and it works when `openjdk-17`, `gradle`, `aapt2`, `aapt` and `aidl` are installed (dpkg), the SDK has a platform with `android.jar` and `package.xml`, build tools with `package.xml` and `licenses/android-sdk-license`, and `~/.gradle/gradle.properties` has the aapt2 setting. When something is missing (a `pkg uninstall gradle` in a terminal, or an install that stopped) the tile reads "Installed · Gradle missing" and the page offers Repair, which runs the install again (license sheet first), beside Uninstall. Deleting `$ANDROID_HOME` makes it Not installed. Uninstall removes the SDK, the Gradle setting and downloads whoever made them, and the packages listed in `~/.pocketrocket/android-build.packages` except what other packages need; Java or Gradle installed by hand, with no record, stay. The NDK install checks the same SDK pieces (`android_sdk_ready`). Installs from before this wrote `.pocketrocket-installed`; it is no longer read or written.

## Limits

- Native code needs the Android NDK tile, below; without it a native project's C and C++ tasks stop with a message, and its Kotlin, Java and unit tests still build. No RenderScript.
- No emulator on the phone. Device testing is a separate tile (`docs/device-testing.md`), not built yet.
- A project on other SDK versions gets them from the Gradle plugin, x86_64 tools included. Only `aapt2` is overridden there, so AIDL needs build-tools 36.0.0.
- Google's manifest changes are not tracked. Moving to a newer platform means updating the versions and sha1s in the script.

## C and C++ tile

Decision 96. An optional tile for any C or C++ project: `--only c-cpp` and `c-cpp-remove`. Termux's `clang`, `lld`, `llvm`, `cmake`, `ninja`, `make`, `pkg-config` and `lldb`: about 105 MB to download and 630 MB on disk on a phone that has none of them (apt's figures). It is a package tool like Python and Bun (decisions 92 and 93): the tile reads dpkg, Installed while `clang` is there and working when all eight are ("Installed · lldb missing" with Repair otherwise), whoever installed them. Uninstall removes the Android NDK first (its confirm sheet says so), then those packages and what the install brought in (`~/.pocketrocket/c-cpp.packages`), except what other packages need: Rust needs `clang`, so it stays while Rust is installed.

## Android NDK tile

Decisions 50 and 96. A separate optional tile on top of the Android tool and C and C++: `--only android-ndk` and `android-ndk-remove`. Install needs the Android tool and brings in C and C++ when it isn't there. On top of C and C++ it downloads about 74 MB (ndk-multilib 73 MB, ndk-build 1.2 MB) and takes about 140 MB. Why it is built this way: `docs/android-ndk-spike.md` and `docs/android-ndk-gaps-spike.md`.

| What | Where |
|---|---|
| The shim | `$ANDROID_HOME/ndk-shim`, built by `setup/android-ndk.sh` beside a temp copy and moved in at the end. Claims NDK 28.2.13676358 |
| ndk-build | Google's `ndk-build` and `build/` makefiles from AOSP `platform/ndk`, tag `ndk-r28c` (commit `805836ef…`), fetched with a shallow git fetch and refused unless `build/`'s tree is `dd78e5f2…`. Text only, Apache 2.0. The shim writes the `.mk` and `.cmake` files the NDK's own build makes from `meta/`, and lets the host be `aarch64` |
| Other ABIs, `c++_static` | Termux's `ndk-multilib`, downloaded with `apt-get download` (apt checks it against the signed index) and unpacked, never installed: per ABI, the crt objects, `libunwind`, `libatomic`, `libc++_shared.so`, `libc++_static.a`, `libc++abi.a`, and the compiler-rt builtins. Its version is in `ndk-shim/.ndk-multilib-version` |
| System libraries | Link-only stubs for every ABI, made at install from the phone's own `/system/lib64` libraries (same names, default versions and soname; private and platform versions left out). A 32-bit ABI's own names (`dl_unwind_find_exidx` on armeabi-v7a) come from ndk-multilib's API 24 stub for it |
| clang | `toolchains/llvm/prebuilt/linux-x86_64/bin/clang{,++}` are wrappers: the shim's sysroot (unless the caller passes one), the shim's resource dir (Termux's headers and runtimes through `lib/clang/latest`, plus the cross builtins), and a linker wrapper that drops Termux's rpath |
| CMake toolchain | `build/cmake/android.toolchain.cmake` is ours (`setup/android-ndk.toolchain.cmake`): four ABIs, every `ANDROID_STL`, `c++_static` by default as in Google's. Google's needs Google's NDK and stays beside it as `google-android.toolchain.cmake` |
| NDK versions | `$ANDROID_HOME/ndk/<version>`: a real `source.properties` claiming that revision, the rest links to the shim, for builds that look the NDK up by version without the Gradle hook. The list is `ANDROID_NDK_VERSIONS` in the setup script |
| CMake | `$ANDROID_HOME/cmake/3.22.1`, the plugin's default, pointing at Termux's CMake |

### The Gradle hook

`~/.gradle/init.d/pocketrocket-android-ndk.gradle`, from `setup/pocketrocket-android-ndk.gradle`. The Android tool writes it (and removes it on uninstall), and the NDK tool writes the same file; removing the NDK leaves it while the Android tool is installed. It never throws while Gradle configures a project:

- A project with CMake or ndk-build and no `ndkPath` of its own gets `ndkPath` set to the shim and `ndkVersion` to the shim's revision, whatever it pinned, with a one-line note. So the plugin never downloads Google's x86_64 NDK (2.4 GB) and a pin is not an error.
- ABIs the shim can't build (riscv64) are dropped from `ndk.abiFilters` and `splits.abi`, with a warning.
- With no NDK installed it points at `$ANDROID_HOME/ndk-placeholder` (only `source.properties` and `meta/`), and only the native tasks (`configure*`, `build*` for CMake or ndk-build, `externalNativeBuild*`, `generateJsonModel*`) fail, when they run: "has C/C++ code, and the Android NDK add-on is not installed … Kotlin and Java tasks still work". `compileDebugKotlin`, unit tests and lint run.

`-Ppocketrocket.ndk=<folder>` points it at another NDK folder, `-Ppocketrocket.ndk=none` acts as if the NDK were not installed. An init script given with `-I` runs before `init.d` and sets `ndkPath` first, and the installed one then steps aside.

The NDK tile reads the phone (decision 93, `AndroidNdkTool.presence`): Installed while `$ANDROID_HOME/ndk-shim` exists; working when `clang`, `lld`, `cmake`, `ninja`, `make` and `ndk-sysroot` are installed, the shim has `ndk-build` and its toolchain file, a version folder under `ndk/` has its `source.properties`, and `~/.gradle/init.d/pocketrocket-android-ndk.gradle` is there. Otherwise "Installed · clang missing" (for example) with Repair and Uninstall. An install from before decision 96 has no `ndk-build` in the shim, so it reads "Installed · ndk-build missing"; Repair runs the install again, which brings in C and C++ and moves the compilers the old install recorded in `~/.pocketrocket/android-ndk.packages` to C and C++'s list. A phone whose Android tool was installed before decision 96 gets the hook the next time the NDK is installed or the Android tool is repaired or reinstalled.

Uninstalling the Android tool removes the NDK first; its confirm sheet says so. Uninstalling C and C++ does the same. The NDK page offers no Install without the Android tool.

Limits: only arm64 output runs on the phone, the other ABIs are built but untested here; every NDK version is the same Termux compiler, which moves with `pkg upgrade`; the stubs are this phone's libraries, so a symbol newer than a project's `minSdk` links without an error (Google's NDK has stubs per API level); headers are Termux's, a few of which carry Termux paths (`_PATH_BSHELL`); no sanitizers for the other ABIs. Termux's Gradle (9.8) cannot run AGP 8.13 ("relies on ... a Gradle internal API that was removed in Gradle 9.6.0"), so such a project needs its own `./gradlew`. Build Play releases with Google's real NDK.

## Code

`com.pocketrocket.androidbuild`: `AndroidBuild` (state and running the script), `AndroidBuildTool` (facts and sizes), `SdkLicense` (reading the license, its hash), `AndroidNdk` and `AndroidNdkTool`. `com.pocketrocket.cpp`: `Cpp` and `CppTool`. All three run through `setup/ScriptTool.kt` (one `--only <id>` and `<id>-remove` pair, a marker on disk). `ui/tools/AndroidNdkToolPage.kt` and `CppToolPage.kt`. The shell side: `setup/pocketrocket-setup.sh` (steps) and `setup/android-ndk.sh` (the shim; it also runs alone, `android-ndk.sh shim <out> <ndk-build> <multilib-usr>`, for building one in a scratch folder). `POCKETROCKET_ANDROID_SDK_DIR` points the script at another SDK folder for tests. `ui/tools/AndroidBuildToolPage.kt`: the page and license sheet. `setup/ToolScript.kt` runs any tool's `--only` option, shared with Chromium. Test with the stub: `-Ppocketrocket.setupStub`, and `touch ~/.pocketrocket/stub-android-build-fail-once` to fail once.
