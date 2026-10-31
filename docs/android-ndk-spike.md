# Android NDK spike

31 Oct 2026. Can an Android app with C/C++ code be built inside PocketRocket on arm64, with Gradle's normal `externalNativeBuild` flow? **Yes, for arm64-v8a, with limits.** Nothing here has run on Shankar's phone. Shipped as an optional tile by decision 50; `docs/android-build.md` has what was built.

Setup: `PR_AndroidTools`, arm64 API 36 emulator, headless, 6 GB RAM. Every build ran in a real PocketRocket terminal, typed into it. AGP 9.5.0, Gradle 9.8.2, Java 17. The Android tool was installed with the branch's `--only android-build`, not through the tile. Sources: `docs/android-tools-spike/hello-ndk`.

## Result

Route 1 (Termux's clang behind a folder laid out as the NDK) passed (a) to (d). Route 2 (an unofficial NDK build) was not tried, as instructed, so its source and maintainer were not assessed.

| Criterion | Result | Evidence |
|---|---|---|
| (a) Compose app, JNI C++, CMake, `gradle assembleDebug`, shows the result | Passed | Text in the app's UI, same content as `hello-ndk/release-minsdk24.png` |
| (b) `string`, `vector`, exceptions, working in the installed APK | Passed, with `c++_shared` only | UI shows the sorted list and "exception caught: empty vector" |
| (c) Release, `minSdk` 24, `compileSdk` 37, builds and runs | Passed on API 36 | R8 on, 2.6 MB APK. **Not run on an old Android**, so `minSdk` 24 is checked from the binary, not proven |
| (d) Third-party C library | Passed | tinyexpr through FetchContent, pinned commit and SHA256. A prebuilt library was not tried |

## Is the output a correct normal app?

Checked on the `.so` files taken from the debug and release APKs:

| Check | Result |
|---|---|
| `DT_NEEDED`, `libhellondk.so` | `liblog`, `libm`, `libc++_shared`, `libdl`, `libc` |
| `DT_NEEDED`, `libc++_shared.so` | `libc`, `libm`, `libdl` |
| `RUNPATH` / `RPATH` | None, after the fix below |
| Strings containing `data/data` | 0 |
| Symbol versions needed | `LIBC` only, the base version every release has |
| LOAD alignment | 0x4000, so ready for 16 KB pages |
| Runs as a normal app | Yes, its own uid, with `libc++_shared.so` inside the APK |

Three things differ from what we expected:

1. **Termux's clang always writes a `RUNPATH` into `/data/data/app.pocket/...`.** No flag removes it. The shim's linker wrapper (`bin/ld-no-rpath`) drops the argument. Without it, every library carries our private path.
2. **`ndk-sysroot` has no stub libraries.** `libc.so`, `libm.so`, `liblog.so` are not in it; clang links against the phone's real `/system/lib64`. So the link step depends on the phone, and private symbols are visible to the linker, which Google's stubs prevent. The headers still gate by API level, and this build needed only `LIBC`, but a project could link something an older phone lacks and get no build error.
3. **No static libc++.** Termux has `libc++_shared.so` only. The plugin's default, `c++_static`, cannot link. The toolchain file stops with a message naming the one-line fix.

The shim keeps Termux's libraries out: its `usr/lib` holds only the crt objects, libc++ and links to system libraries. Headers are not filtered: `$PREFIX/include` holds every installed package's headers, so a project can compile against one and then fail at link. Not tested.

## Numbers

**The emulator flatters these times.** It runs on a Mac's fast cores and storage. Expect a phone to be several times slower. The sample is tiny (one C++ file, one C file), so native times say little about a large project. These are on top of the Android tool's own cost.

| What | Measured | Note |
|---|---|---|
| Extra download | 96.4 MB | apt's figure; 14 packages |
| Extra disk | 607 MB | `$PREFIX` 1795 → 2402 MB. Not the 270 MB first estimated: `clang` 187, `libllvm` 170, `cmake` 56, `libcompiler-rt` 34, `ndk-sysroot` 28, `lld` 20 |
| Package install | 7 s, after the download | Emulator network |
| Shim folder | 332 KB | Symlinks and small text files |
| `.cxx` per project | 2 MB | |
| Native rebuild from nothing, daemon running | 5 s | Whole `assembleDebug`, CMake configure and FetchContent included |
| One `.cpp` line changed | 1 s | CMake tasks ran; the change showed in the app ("sorted now:") |
| Nothing changed | 1 s | |
| One heavy C++ file (`regex`, `future`, `iostream`), `-O2` | 1.5 s | Compile only |
| Clean app build, no daemon, caches full | 156 s | Mostly Kotlin and Compose |
| First release build | 80 s | |
| First build on empty caches | About 240 s | **Not one clean run**: 77 + 138 + 25 s across two failures while the shim was being fixed |
| Gradle caches after the build | 796 MB | Same size as the Kotlin-only tool |
| Debug APK | 12.9 MB | `libc++_shared.so` adds 1.4 MB |
| Release APK | 2.6 MB | |
| Peak RAM, debug | 2.9 GB | Sum of RSS for the app's user, so shared pages count twice |
| Peak RAM, release | 3.1 GB | Same method |
| Lowest free memory | 1.3 GB | `MemAvailable`, 6 GB emulator |
| Peak processes for the app's user | 14 | Android's limit is 32 |
| Killed by the process limit | None seen | 33 phantom-process lines in logcat were normal exits. The buffer may have rotated, so this is not proof |

Compiling added little RAM over the Kotlin build, because the C++ here is small. A large project runs one clang per core, so expect more RAM and more processes than this.

## Limits

| Question | Answer |
|---|---|
| `armeabi-v7a`, `x86_64` | **No.** Gradle stops: "[CXX1201] ABIs [x86_64] are not supported for platform. Supported ABIs are [arm64-v8a]". Clang can compile for them (an x86_64 `.o` built), but linking fails: no crt objects, libunwind or compiler-rt for those targets |
| Project with no `abiFilters` | Builds arm64 only and raises no error, so an APK meant to be universal quietly is not |
| Project that pins `ndkVersion` | Fails unless it equals the shim's revision: "[CXX1104] NDK ... had version [29.0.14206865] which disagrees with android.ndkVersion [28.2.13676358]" |
| Project with no `ndkVersion` | AGP 9.5 expects 28.2.13676358, so the shim claims that. Other AGP versions expect other revisions. Not tested |
| `ANDROID_STL` unset or `c++_static` | Fails with our message. Needs `-DANDROID_STL=c++_shared` in the project |
| `ndk-build` / `Android.mk` | **Not tested, and it cannot work as is.** It needs Google's `ndk-build` script and `build/core` makefiles, which are not in any Termux package. Wrong to call cheap |
| `ndk.dir` | Works, with warning CXX5106 that it will be removed. `android.ndkPath` is the lasting route but goes in each project's Gradle file |
| Reported NDK version | Not true. The shim says r28, the headers come from Termux's `ndk-sysroot` 30, the compiler is clang 21. Code that checks `__NDK_MAJOR__` may disagree. Not tested |
| Versions move | `pkg upgrade` changes clang and libc++, so builds are not reproducible the way a fixed NDK is |

## Recommendation

**Worth offering as an optional add-on, for your own arm64 apps, and only after the phone checks below.** It is cheap (96 MB, 607 MB) and native rebuilds are fast. A normal Gradle project works with one added line.

**Not worth promising as "the NDK".** It will fail on projects that pin an NDK version, use static libc++, use `ndk-build`, or need other ABIs, and those are common in existing projects. Use it for trying things and for your own apps. For a Play release, build with Google's real NDK off the phone, since this output has only been run on API 36.

Route 2 would address some of this (static libc++, stubs, `ndk-build`), so it is the thing to look at if those limits matter. It would mean adopting someone's unofficial binaries, and the only checksum to pin is one we record ourselves, unless that project publishes signed hashes. Google publishes no Linux arm64 NDK to check against.

## Must check before shipping

1. Run the release APK on an old Android (API 24 to 29). Nothing here proves it.
2. On the Xiaomi: build time, heat, and whether anything dies with Claude running. Is `/system/lib64` readable for linking under HyperOS? `logcat | grep -i phantom` shows the process limit.
3. A real project with many source files, for RAM and process counts.

## What shipping it would take (not built)

- An "Include C/C++ (NDK)" option on the Android tile, with its 96 MB and 607 MB shown.
- `setup/pocketrocket-setup.sh`: `--only android-ndk` and `android-ndk-remove`. Install `clang cmake ninja ndk-sysroot`, build the shim, and record packages the way `android-build.packages` does, so uninstall removes only those. Decide what happens if the user already had clang for other work.
- Move `ndk-shim/make-ndk-shim.sh` and `android.toolchain.cmake` into the script, with `$PREFIX` instead of the fixed shebang.
- Point Gradle at it: `ndk.dir` is per project in `local.properties`, so use an init script in `~/.gradle/init.d` setting `android.ndkPath`. Not tested.
- Decide how to handle `ndkVersion` pins and AGP defaults: one shim per revision, or a clear message.
- Consider a symlink-only header tree from `ndk-sysroot` and `libc++`, to stop other packages' headers leaking in.
- Packages come from the Termux repos, checked by apt's signatures, so no new download check is needed for Route 1.
- `docs/android-build.md` limits, and tile text naming the limits in plain words. Unit tests with the existing stub.

## Spike notes

- `app.pocket` on `emulator-5600` was reinstalled at 09:55:28 local, seconds after this spike's first install command, and its first-run setup was driven again. Not by this spike. It wiped the home folder, so the first install was lost and about 18 minutes went. Something else had that emulator open.
- `hello-ndk` is what was built, apart from one word changed during the run to prove incremental builds. `local.properties` is not committed; it holds `ndk.dir=$PREFIX/opt/android-ndk-shim` and `cmake.dir=$PREFIX`.
- Debug and release were built from the same sources; the release is signed with the debug key so that it installs.
