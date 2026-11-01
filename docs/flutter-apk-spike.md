# Spike: Flutter APKs on the phone

Branch `spike/flutter-apk`, 1 Nov 2026, on the Poco F7 (arm64, 11.5 GB RAM). Scripts in `spikes/flutter-apk/`. Logs stayed on the phone in `~/.cache/flutter-apk-spike/`.

Question: can the phone build Flutter APKs?

## Short answer

- **Debug APK, small app: yes.** A new `flutter create` app built to `app-debug.apk` (75 MB, arm64 only) in about 4.5 minutes of Gradle. Its `libflutter.so` loads with `dlopen`. It needs three workarounds, below.
- **Debug APK, large app (Nimbus): not finished.** Shankar stopped all builds part way. It got as far as Gradle configuration. Nothing says it can't work, but it is not shown.
- **Release APK: no, not with official parts that run natively.** Google publishes no Android `gen_snapshot` for an arm64 Linux host. Release builds are emergency-only, so this stops here.

## What the hello-world debug build needed

Flutter 3.44.1 (engine `c416acfe`), JDK 17, the Android tool's SDK at `$PREFIX/opt/android-sdk` with its NDK shims and `aapt2` override, and the Flutter launcher from decision 75.

1. **Run the normal `flutter` launcher once.** The first `flutter build apk` downloads the Android engine artifacts (`android-*-profile`, `android-*-release`, each `linux-x64`), then stops: the launcher prepares the new glibc tools and asks to run again.
2. **Let the Flutter tool start `gradlew`.** The first real attempt failed with `ProcessException: No such file or directory` on `android/gradlew`. The reason: `gradlew` starts with `#!/usr/bin/env bash`. Termux's `termux-exec-glibc` fixes such paths, but only in `execve()`. Dart starts processes with glibc's `execvp()`, which calls glibc's internal execve and never reaches the preload. Fix, three files:
   - `glibc-execvp.c`: a tiny glibc preload that defines `execvp`, `execvpe` and `execv` on top of the exported `execve`, so `termux-exec-glibc` sees them. Built with `clang --target=aarch64-linux-gnu -nostdlib`, no glibc headers needed.
   - `make-preload.sh`: one `LD_PRELOAD` value that works in both worlds. glibc's loader expands `$PLATFORM` (to `aarch64`), bionic's linker does not. So `<dir>/$PLATFORM/1.so:<dir>/$PLATFORM/2.so` loads `termux-exec-glibc` plus the new library in the Flutter tool (glibc), and Termux's normal `termux-exec` plus an empty library in its children (bash, java, which are bionic).
   - `flutter-dual.sh`: runs the Flutter tool snapshot directly with that `LD_PRELOAD`, the way `bin/flutter`'s last line does. The normal launcher drops `LD_PRELOAD` for glibc programs, so it can't do this today.
   Packages: `termux-exec-glibc` (from the glibc repo) and `clang`.
3. **Build arm64 only.** The phone's NDK shims serve only `arm64-v8a`. Flutter's Gradle plugin asks for `armeabi-v7a`, `arm64-v8a` and `x86_64`, and AGP stops at configuration with `CXX1201 ABIs [armeabi-v7a, x86_64] are not supported`. `--target-platform android-arm64` alone does not help, because the plugin sets the ABI filter itself. Flutter has an opt-out, `-Pdisable-abi-filtering=true`; then AGP takes the ABIs the NDK offers, which is only arm64. The stopped run's logs don't record exactly which fix it used for hello, but the hello APK holds only `lib/arm64-v8a`.
4. **Stay inside the memory budget.** `build-apk.sh` does all of the above in one call: the shared build lock, `GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx1536m -Dorg.gradle.workers.max=2 -Dorg.gradle.daemon=false"`, and `--android-project-arg` for `disable-abi-filtering=true` and `kotlin.compiler.execution.strategy=in-process`. The `-D` in `GRADLE_OPTS` wins over a project's `gradle.properties`. This was checked: the project asked for `-Xmx4G`, and the Gradle daemon ran with `-Xmx1536m`.

Helpers also in `spikes/flutter-apk/`: `dlopen-check.c` (loads a `.so` the way Android's loader would; `libflutter.so` from the APK loads) and `wait-idle.sh` (waits until no Java build is busy; the shared `flock` lock replaced it).

## How far Nimbus got

Nimbus is a large app with flavors, Firebase and many plugins. It was tried in its own worktree. No tracked files were changed and nothing was committed there.

- `flutter pub get` needed the sibling packages its `pubspec.yaml` reaches by relative path. From a worktree that path points inside `.claude/worktrees/`, so two links were added there to the real sibling checkouts.
- Some generated Dart files are gitignored. The project's rules say an agent must not run `build_runner`, so the seven ignored generated files were copied from the main checkout, which is on the same commit.
- First try (`build-apk.sh --debug --flavor dev -t lib/main_dev.dart`, without the ABI opt-out): 11 min 41 s in total, including the wait for the lock. Gradle downloaded Gradle 8.12 and its plugins, then failed after 6 min at configuration with the same `CXX1201` ABI error. That led to step 3 above.
- Second try, with `disable-abi-filtering=true`: stopped after about 9 minutes, still in Gradle configuration, with no error printed. **Not finished.** It is unknown whether it would have passed configuration, the plugins' native code or dexing in 1.5 GB.
- Flutter warned that the project's Gradle (8.12), AGP (8.9.1) and Kotlin (2.1.0) will soon be too old. That isn't a phone problem.

Left on the phone for whoever cleans up: the two links in `project_nimbus/.claude/worktrees/` (`project_arwen`, `nats_flutter`) and the copied ignored files in that worktree.

## Release builds

Release and profile builds compile Dart ahead of time with `gen_snapshot`, a host tool. On an arm64 Linux host Flutter looks for it in `.../android-arm64-release/linux-arm64/`.

Google's bucket for engine `c416acfe` (Flutter 3.44.1) has, under `android-arm64-release/`: `linux-x64.zip`, `darwin-x64.zip`, `windows-x64.zip`, `artifacts.zip`, `symbols.zip`, `analyze-snapshot-linux-x64.zip`. No `linux-arm64.zip` (404). Same for engine `89c39edf` (Flutter 3.47.9). The `linux-arm64/` folder has only desktop artifacts, which target Linux, not Android.

Options, for Shankar to pick only if a release build on the phone ever matters:

1. **Google's x86_64 `gen_snapshot` under `qemu-user`.** Official binary, Termux's `qemu-user-x86-64` and x86_64 glibc. `wrap-gen-snapshot.sh` and `fetch-termux-deb.sh` set it up. `gen_snapshot --version` runs this way. A full release build was started by the stopped run and never finished, so it is unproven, and it will be slow.
2. **Build `gen_snapshot` from the engine source** for a linux-arm64 host. Large checkout and build; not done.
3. **A community build.** Not done.

Recommendation: release APKs stay on a Mac or CI.

## What a Flutter add-on would need for `flutter build apk --debug`

On top of today's Flutter & Dart tool (decision 75):

- The Android tool installed: SDK, build-tools, platforms, `aapt2` override, NDK shims, JDK 17.
- `termux-exec-glibc` installed.
- The exec preload: `glibc-execvp.c` built to a small `.so` (at install with `clang`, or prebuilt in the APK), plus the `$PLATFORM` folder layout from `make-preload.sh`.
- The `flutter` launcher runs the Flutter tool with that dual `LD_PRELOAD` instead of dropping it (what `flutter-dual.sh` does), at least for `build` and `run`.
- Android engine artifacts fetched and prepared up front (`flutter precache --android` through the launcher), so the first build doesn't stop and ask to run again.
- arm64-only builds by default: `-Pdisable-abi-filtering=true` (or an init script that sets `abiFilters` to `arm64-v8a`), so users don't need to know about `CXX1201`.
- Memory caps by default: Gradle heap about 1.5 GB, 2 workers, no daemon, Kotlin in-process, overriding the project's `gradle.properties` through `GRADLE_OPTS`.
- Still not covered: plugins that use `ndk-build` (the NDK tool refuses them), any release or profile build, and APK size (a debug APK is about 75 MB for an empty app).

## Recommended next step

Decide whether debug APKs on the phone are worth an add-on. Earlier he said APK builds on the phone don't matter (the tool is for compile checks). If they do matter now, finish one large-app debug build first (Nimbus `assembleDevDebug` with `build-apk.sh`), at a quiet time with no other builds, before building the add-on. Leave release to CI.
