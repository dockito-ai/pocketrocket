# Flutter & Rust

Decision 74. An optional tool for checking code on the phone, from a terminal or a Claude session: `flutter pub get`, `dart run build_runner build`, `flutter analyze`, `flutter test`, `cargo check` and `cargo clippy`. No APKs on the phone; release builds need a Mac or CI. How it was worked out: `docs/flutter-spike.md`.

## Install and remove

Settings → Tools → Flutter & Rust. Nothing is bundled and first-run setup never installs it. Install takes the newest stable Flutter (it becomes the default) and Rust: about 580 MB to download, about 1.7 GB on the phone (1.1 GB per Flutter version, 590 MB for Rust). A project's packages come on top: Nimbus's `~/.pub-cache` is 1.1 GB, a Rust crate's `target/` a few hundred MB.

It does not need the Android tool. Compile checks need no Android SDK, so the tile installs on its own; `flutter doctor` reports the missing Android toolchain, which is expected.

The page then shows:

- **Flutter**: each installed version with its size and the default marked. Tapping a version offers Set default and Remove. Add version opens a list of the 12 newest stable releases from Flutter's own release list. Removing the default makes the newest other version the default.
- **Rust**: the installed version (from apt), Install Rust or Remove Rust. Termux's build, one version; a project's `rust-toolchain.toml` is not followed, and the page says so.
- **Uninstall** takes everything: every Flutter version, FVM, the launchers, `~/.pub-cache`, Dart's and Flutter's own folders and settings (`~/.dart-tool`, `~/.dartServer`, `~/.dart`, `~/.flutter`, `~/.flutter-devtools`, `~/.config/flutter`, `~/.config/fvm`), the packages the install added, and Rust with `~/.cargo`. Projects stay.

Every action goes through `setup/pocketrocket-setup.sh` (`setup/README.md` lists the options): `--only flutter-rust` and `flutter-rust-remove` for the whole tool, `flutter [version]`, `flutter-default`, `flutter-remove-version`, `flutter-remove`, `rust`, `rust-remove`. Retry runs the same option again; a Flutter clone that finished is kept, so Retry after a failed Dart download doesn't clone again.

Settings › Updates shows Flutter and Rust only while they are installed. Flutter offers a newer stable for the **default** version only: updating adds it as a new version and makes it the default. The old one stays for projects that pin it (Remove it on the tool page), and no project's `.fvmrc` is ever changed. Rust updates through apt like Git (`--only rust-check`, `rust-update`), with `rust-std-aarch64-linux-android` moved with it.

## How Flutter is installed

Official only, in FVM's layout so `fvm` and PocketRocket agree:

1. `which` and `unzip` from Termux (`flutter doctor` needs `which`; Flutter's Dart download needs `unzip`). What they add is recorded in `~/.pocketrocket/flutter.packages` and only that is removed later.
2. FVM 4.3.2, its official `linux-arm64` release from GitHub, checked against the SHA-256 GitHub publishes for the asset (pinned in the script), into `$PREFIX/opt/fvm`, with `$PREFIX/bin/fvm`. Its bundled Dart is prepared like Flutter's. FVM's git cache (a mirror of Flutter's whole history) and its update check are turned off.
3. `git clone --depth 1 -b <version>` of `github.com/flutter/flutter` into `~/fvm/versions/<version>`, then `HEAD` is checked against the commit Flutter's `releases_linux.json` names for that version. A git commit names its whole content, so this is the checksum.
4. Flutter's own `update_dart_sdk.sh` downloads `dart-sdk-linux-arm64.zip` (225 MB) from Google's storage.
5. The launcher prepares the glibc programs, `flutter --version` builds the tool, `flutter precache --universal` fetches the engine's tools for this phone (`flutter_tester` among them) and they are prepared too, so the first `flutter test` works. The default is set when there is none.

Why a clone and not Flutter's archive: the Linux archives in `releases_linux.json` (with a SHA-256 each) are x64 only. They hold the x64 Dart SDK, which would be thrown away, and the 3.47.9 one is 1.5 GB to download against about 35 MB for the shallow clone. The commit check gives the same guarantee as the archive's checksum.

## The launcher

`$PREFIX/bin/flutter` (`setup/flutter`), with `$PREFIX/bin/dart` a link to it:

- Version: the nearest `.fvmrc` up from the current folder (`"flutter": "3.44.1"`; FVM 2's `.fvm/fvm_config.json` too), else FVM's default link `~/fvm/default`. A pinned version that isn't installed stops with "Flutter X isn't installed (… pins it): Settings → Tools → Flutter & Rust, or `fvm install X`."
- Dart SDK: when `bin/internal/engine.version` and `bin/cache/engine-dart-sdk.stamp` differ (after `fvm install` or `flutter upgrade`), it runs Flutter's own Dart download first, so the new SDK is prepared before its first use.
- Prepare: every glibc program in that version's `bin/cache` gets Termux's glibc loader (`patchelf`, `LD_PRELOAD` cleared) and becomes `<name>.glibc` behind a three-line bash wrapper with an absolute shebang that unsets `LD_PRELOAD`. A program already done is a script, so preparing again changes nothing. The check is cheap: the names and contents of the stamps Flutter writes in `bin/cache` (`*.stamp`) are saved in `bin/cache/.pocketrocket-prepared`; a new or changed stamp, or an unwrapped `dart`, means Flutter downloaded something.
- Then it sets Termux's bionic exec helper (Flutter's scripts start with `#!/usr/bin/env bash`) and runs `bash <version>/bin/flutter` or `bin/dart`. It waits rather than `exec`, so that tools Flutter downloads during a run are prepared right after; if that run failed because of them, it says to run the command again.

Sessions need nothing extra: `flutter`, `dart`, `fvm`, `cargo` and `rustc` are all in `$PREFIX/bin`, and the launcher works with `LD_PRELOAD` empty, as in a Claude session. `PUB_CACHE` stays at its default.

## Limits

- No APKs, no `flutter run`: Flutter starts `gradlew` (`#!/usr/bin/env bash`) from Dart, which runs without the exec helper.
- `fvm flutter …` and FVM's own setup step run Flutter's scripts from Dart in the same way and can fail; use `flutter` (it reads `.fvmrc`). `fvm install X` clones the version and the launcher finishes it on the first `flutter` run.
- `flutter doctor` shows the channel as `[user-branch]` for a tag clone, and no Android toolchain.
- Rust is one version (Termux's), whatever `rust-toolchain.toml` says.
- Unzip comes with the Android tool too. If it was there first, the Flutter tool didn't add it, and removing the Android tool takes it; reinstall it with `pkg install unzip` before Flutter's next Dart download.

## Code

`fluttertool/` (`FlutterTool`: facts, release list, version order, sizes, options; `FlutterRust`: state and jobs), `ui/tools/FlutterToolPage.kt`, `updates/` (`FlutterSource`; `AptUpdater` now serves Git and Rust), `setup/flutter`, and the Flutter & Rust part of `setup/pocketrocket-setup.sh`. The setup stub (`-Ppocketrocket.setupStub`) fakes every option with empty version folders. Tests: `FlutterToolTest` (release list, version order, sizes, removal set, the script's release lookup and removal list, the launcher's `.fvmrc` search and its prepare check).
