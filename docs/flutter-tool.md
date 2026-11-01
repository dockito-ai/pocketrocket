# Flutter & Dart and Rust

Since decision 87 these are two tools, Flutter & Dart and Rust, each installed and removed on its own; what follows describes both.

Decision 75. An optional tool for checking code on the phone, from a terminal or a Claude session: `flutter pub get`, `dart run build_runner build`, `flutter analyze`, `flutter test`, `cargo check` and `cargo clippy`. Since decision 100 it also builds debug APKs (`flutter build apk --debug`) when Android and C and C++ are installed; release builds need a Mac or CI. How it was worked out: `docs/flutter-spike.md` and `docs/flutter-apk-spike.md`.

## Install and remove

Settings → Tools → Flutter & Rust. Nothing is bundled and first-run setup never installs it. Install takes the newest stable Flutter (it becomes the default) and Rust: about 580 MB to download, about 1.7 GB on the phone (1.1 GB per Flutter version, 590 MB for Rust). A project's packages come on top: a large app's `~/.pub-cache` is 1.1 GB, a Rust crate's `target/` a few hundred MB.

It does not need the Android tool. Compile checks need no Android SDK, so the tile installs on its own; `flutter doctor` reports the missing Android toolchain, which is expected.

The page then shows:

- **Flutter**: each installed version with its size and the default marked. Tapping a version offers Set default and Remove. Add version opens a list of the 12 newest stable releases from Flutter's own release list. Removing the default makes the newest other version the default.
- **Rust**: the installed version, Install Rust or Remove Rust. Termux's build, one version; a project's `rust-toolchain.toml` is not followed, and the page says so. Installed, version and size come from dpkg's status (`rust` and every `rust-std-*`), as for the decision 92 add-ons, so a `pkg install rust` or `pkg uninstall rust` from a terminal or a Claude session shows on the tile. Remove takes `rust`, `rust-std-aarch64-linux-android` and every other `rust-std-*` target installed, however they got there, plus what the tile's own install brought in (listed in `~/.pocketrocket/rust.packages`, a record for Uninstall, not an "installed" flag), except what other packages still need (checked with `apt-get -s`, the same `pkg_tool_purge` as Python and Bun).
- **Installed** is read from the phone each time Tools opens, whoever set it up; no flag is saved at install (`FlutterTool.presence`). The tile shows Installed while any of these is there: a version under `~/fvm/versions` with `bin/flutter` (from the tile, `fvm install` or a terminal), FVM (`$PREFIX/opt/fvm`), the `flutter` launcher, or the list a run left when it stopped in its apt step. It works when FVM, the launcher and at least one version with its Dart SDK (`bin/cache/dart-sdk/bin/dart`) are there. Otherwise the tile reads, for example, "Installed · Flutter SDK missing" (after `rm -rf ~/fvm`) and the page offers Repair, which runs the install again, beside Uninstall. `~/.pocketrocket/flutter.packages` is only the record of what the install added, for Uninstall. Installs from before this wrote `~/fvm/.pocketrocket-installed`; it is no longer read or written.
- **Uninstall** takes everything: every Flutter version, FVM, the launchers, `~/.pub-cache`, Dart's and Flutter's own folders and settings (`~/.dart-tool`, `~/.dartServer`, `~/.dart`, `~/.flutter`, `~/.flutter-devtools`, `~/.config/flutter`, `~/.config/fvm`), the packages the install added, and Rust with `~/.cargo`. Projects stay.

Every action goes through `setup/pocketrocket-setup.sh` (`setup/README.md` lists the options): `--only flutter-rust` and `flutter-rust-remove` for the whole tool, `flutter [version]`, `flutter-default`, `flutter-remove-version`, `flutter-remove`, `rust`, `rust-remove`. Retry runs the same option again; a Flutter clone that finished is kept, so Retry after a failed Dart download doesn't clone again.

Settings › Updates shows Flutter and Rust only while they are installed. Flutter offers a newer stable for the **default** version only: updating installs it, makes it the default and then removes the old default (`POCKETROCKET_FLUTTER_REPLACE`), only once the new one is set up. Other versions stay, and no project's `.fvmrc` is ever changed; a project that pins the old version gets it back with `fvm install <version>` (or Add version). Shankar confirmed this on 31 Oct 2026 (decision 75). Rust updates through apt like Git (`--only rust-check`, `rust-update`), with `rust-std-aarch64-linux-android` moved with it.

## How Flutter is installed

Official only, in FVM's layout so `fvm` and PocketRocket agree:

1. `which`, `unzip` and `termux-exec-glibc` from Termux (`flutter doctor` needs `which`; Flutter's Dart download needs `unzip`; debug APKs need `termux-exec-glibc`, which the base set already brings). When clang is on the phone (the C and C++ add-on), the exec helper for debug APKs is built from `setup/flutter-exec.c` (see Debug APKs below). What they add is recorded in `~/.pocketrocket/flutter.packages` and only that is removed later.
2. FVM 4.3.2, its official `linux-arm64` release from GitHub, checked against the SHA-256 GitHub publishes for the asset (pinned in the script), into `$PREFIX/opt/fvm`, with `$PREFIX/bin/fvm`. Its bundled Dart is prepared like Flutter's. FVM's git cache (a mirror of Flutter's whole history) and its update check are turned off.
3. `git clone --depth 1 -b <version>` of `github.com/flutter/flutter` into `~/fvm/versions/<version>`, then `HEAD` is checked against the commit Flutter's `releases_linux.json` names for that version. A git commit names its whole content, so this is the checksum.
4. Flutter's own `update_dart_sdk.sh` downloads `dart-sdk-linux-arm64.zip` (225 MB) from Google's storage.
5. The launcher prepares the glibc programs, `flutter --version` builds the tool, `flutter precache --universal` fetches the engine's tools for this phone (`flutter_tester` among them) and they are prepared too, so the first `flutter test` works. The default is set when there is none.

Why a clone and not Flutter's archive: the Linux archives in `releases_linux.json` (with a SHA-256 each) are x64 only. They hold the x64 Dart SDK, which would be thrown away, and the 3.47.9 one is 1.5 GB to download against about 35 MB for the shallow clone. The commit check gives the same guarantee as the archive's checksum.

## The launcher

`$PREFIX/bin/flutter` (`setup/flutter`), with `$PREFIX/bin/dart` a link to it:

- Version: the nearest `.fvmrc` up from the current folder (`"flutter": "3.44.1"`; FVM 2's `.fvm/fvm_config.json` too), else FVM's default link `~/fvm/default`. A pinned version that isn't installed stops with "Flutter X isn't installed (… pins it): Settings → Tools → Flutter & Dart, or `fvm install X`."
- Dart SDK: when `bin/internal/engine.version` and `bin/cache/engine-dart-sdk.stamp` differ (after `fvm install` or `flutter upgrade`), it runs Flutter's own Dart download first, so the new SDK is prepared before its first use.
- Prepare: every glibc program in that version's `bin/cache` gets Termux's glibc loader (`patchelf`, `LD_PRELOAD` cleared) and becomes `<name>.glibc` behind a three-line bash wrapper with an absolute shebang that unsets `LD_PRELOAD`. A program already done is a script, so preparing again changes nothing. The check is cheap: the names and contents of the stamps Flutter writes in `bin/cache` (`*.stamp`) are saved in `bin/cache/.pocketrocket-prepared`; a new or changed stamp, or an unwrapped `dart`, means Flutter downloaded something.
- Scripts: the first line of each script in that version's `bin` and `bin/internal` (`#!/usr/bin/env bash`) is pointed at Termux's bash, and git is told to ignore the change (`update-index --assume-unchanged`), so the checkout still looks clean. Programs that start Flutter's scripts without Termux's exec helper, FVM among them, can then run them. Pointing again changes nothing.
- Git: with that flag git refuses to move the checkout when the new revision changes one of those scripts. So before `flutter upgrade`, `flutter channel <name>` and `flutter downgrade` the launcher puts the scripts back as git has them, and points them again afterwards. When the checkout moved and Flutter's own second half (it starts `bin/flutter` from Dart, which can't run the original first line) failed, the launcher sets up the new Dart SDK and builds the tool itself. In a release folder (`~/fvm/versions/3.47.9`) those commands stop with a message instead: FVM checks that the folder still holds that release and otherwise stops at a question, which a Claude session can't answer. Only channel folders (`fvm install stable`) move.
- Then it sets Termux's bionic exec helper and runs `bash <version>/bin/flutter` or `bin/dart`. It waits rather than `exec`, so that tools Flutter downloads during a run are prepared right after; if that run failed because of them, it says to run the command again.

## Debug APKs

Decision 100, from `docs/flutter-apk-spike.md`. `flutter build apk --debug` (and `appbundle --debug`) works when the Android add-on (SDK, Java 17, Gradle) and the C and C++ add-on (clang) are installed. Three things make it work:

- **The exec helper.** Flutter starts the project's `android/gradlew` (`#!/usr/bin/env bash`) from the Flutter tool, a glibc program, with glibc's `execvp`. Termux's `termux-exec-glibc` fixes such paths only in `execve`, which glibc's `execvp` bypasses. `setup/flutter-exec.c` defines `execvp`, `execvpe` and `execv` on top of the exported `execve`. The install builds it with clang into `$PREFIX/lib/pocketrocket-flutter-exec/` (no binary ships), next to links that make one `LD_PRELOAD` value work in both C libraries: glibc's loader reads `$PLATFORM` in the path as `aarch64`, bionic's takes it literally. So `<dir>/$PLATFORM/1.so:<dir>/$PLATFORM/2.so` gives the Flutter tool `termux-exec-glibc` plus the helper, and gives its bionic children (bash, Java) Termux's usual exec helper plus an empty library.
- **Only the Flutter tool gets it.** For an APK build the launcher exports `POCKETROCKET_GLIBC_PRELOAD` with that value. Every wrapper the launcher writes turns it into `LD_PRELOAD` and drops it, so only the next glibc program (the Flutter tool's Dart) has it. One exception: in newer Dart SDKs (Flutter 3.44 and 3.47 here) `dart` is a front end that starts its sibling `dartvm` (through that one's wrapper) to run the tool, so the `dart` wrapper keeps the variable and the `dartvm` wrapper drops it. Before that fix the `dart` wrapper used it up, `dartvm`'s wrapper cleared `LD_PRELOAD`, and every APK build stopped at `ProcessException: No such file or directory` on `gradlew`. Glibc tools started further down (Gradle running `flutter assemble`, `gen_snapshot`) run as before. Wrappers written by an older launcher are written again on the next run.
- **arm64 only.** The phone's NDK serves only arm64, and Flutter's Gradle plugin asks for armeabi-v7a and x86_64 too, which stops AGP at configuration (`CXX1201`). The launcher adds `--target-platform android-arm64` unless one is given, and turns Flutter's ABI filter off through the Gradle project property `disable-abi-filtering` in the environment.

Release and profile builds (`flutter build apk` without `--debug`) stop at once with a message. They need `gen_snapshot`, and Google publishes the Android one only for x86_64 hosts. Running it under qemu, building it from source or using a community build were left out (Shankar: release builds on the phone are emergency-only).

Memory: an APK build is a full Gradle build. Gradle's memory caps and the one-build-at-a-time slot are the build-control work's, not this tool's.

The tile: when clang is on the phone but the helper isn't (Flutter installed before C and C++, or before decision 100), Flutter & Dart reads "Installed · APK builds missing" and Repair builds it. Without clang nothing is missing; the launcher says what APK builds need when one is tried.

Not yet tried on the phone from the tile: the spike built a hello-world debug APK with the same pieces run by hand.

## FVM

`$PREFIX/bin/fvm` is a link to the same launcher. It runs FVM 4.3.2 (its Dart prepared by the setup script) and makes the versions it touches ready around it: before `fvm install`, `use`, `global`, `flutter`, `dart`, `spawn` and `exec` every installed version gets its scripts pointed and anything new prepared, and a version with no Dart SDK yet (one FVM cloned) gets it downloaded, prepared and its tool built, which is FVM's own setup step. FVM's setup of a version it just cloned fails first (it runs `bin/flutter` before the launcher has seen the version), so after `install`, `use` and `global` the launcher finishes that version and, if FVM's command had failed, runs it once more. `fvm flutter upgrade` (and `channel`, `downgrade`) goes through the launcher's own path above. Tried on the phone with `LD_PRELOAD` empty: `fvm install 3.47.8`, `fvm flutter --version`, `fvm dart --version`, `fvm use 3.47.8` in a project, then `fvm flutter analyze` and `flutter analyze` there.

`fvm install <version>` clones Flutter's whole history (FVM's git cache is off): about 500 MB and several minutes on the phone, against a 35 MB shallow clone for Add version on the tool page, plus the 225 MB Dart SDK either way.

Sessions need nothing extra: `flutter`, `dart`, `fvm`, `cargo` and `rustc` are all in `$PREFIX/bin`, and the launcher works with `LD_PRELOAD` empty, as in a Claude session. `PUB_CACHE` stays at its default.

## Limits

- Debug APKs only, arm64 only, and only with Android and C and C++ installed. No release or profile builds. `flutter run` gets no special handling.
- Plugins with `ndk-build` native code are refused by the NDK tool's Gradle hook.
- `flutter upgrade` and `flutter downgrade` only work in a channel folder; a release (the tile's versions, `fvm install 3.47.9`) stays as it is. Updates go through Settings › Updates, or `fvm install` and `fvm use`.
- `flutter doctor` shows the channel as `[user-branch]` for a tag clone, and no Android toolchain.
- Rust is one version (Termux's), whatever `rust-toolchain.toml` says.
- Unzip comes with the Android tool too. Whichever tool added it keeps it on its list; removing that tool while the other is installed moves unzip to the other's list (`packages_hand_over`) instead of removing it.

## Code

`fluttertool/` (`FlutterTool`: facts, release list, version order, sizes, options; `FlutterRust`: state and jobs), `ui/tools/FlutterToolPage.kt`, `updates/` (`FlutterSource`; `AptUpdater` now serves Git and Rust), `setup/flutter`, `setup/flutter-exec.c`, and the Flutter & Rust part of `setup/pocketrocket-setup.sh`. The setup stub (`-Ppocketrocket.setupStub`) fakes every option with empty version folders. Tests: `FlutterToolTest` (release list, version order in the app and the script, sizes, removal set, the script's release lookup, removal list and package lists, the launcher's `.fvmrc` search, its prepare check, and its script pointing and putting back around git; the APK build detection, release refusal and arm64 default), `FlutterDetectionTest` ("APK builds missing" and the helper's folder agreed by the app, the script and the launcher).
