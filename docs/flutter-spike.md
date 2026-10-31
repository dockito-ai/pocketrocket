# Flutter, Dart and Rust spike

Decision 74. 31 Oct 2026, on Shankar's phone (Xiaomi, arm64, Android 15), from a Claude session and its background shell. Can a Claude session check a Flutter or Rust project for compile errors on the phone? **Yes.** Building APKs was dropped as a goal partway (Shankar: compile checks are what matter).

## Result

| Check | Project | Result | Time |
|---|---|---|---|
| `flutter --version` | Flutter 3.44.1, Dart 3.12.1 | Works | 57 s the first time (builds the tool), then instant |
| `flutter pub get` | A large Flutter app (two sibling path dependencies) | Works | 167 s cold |
| `dart run build_runner build` | Same app, 7,410 inputs | Works, 2,918 outputs | 539 s cold |
| `flutter analyze` | Same app | Works, 0 errors, 748 warnings, 1,487 infos | 207 s cold, 42 s warm |
| `flutter test` | Same app | Works, 20 pass, 7 fail, all 7 in the app itself (stale expected strings, a test file without `main`) | 88 s |
| `cargo check --all-targets` | A plugin's Rust crate (tokio, flutter_rust_bridge) | Works, 0 errors | 211 s cold |
| `cargo clippy --all-targets` | same | Works, real lints | 12 s warm |
| `flutter create`, engine downloads | hello app | Works | |
| `flutter build apk --debug` | hello app | Fails: Flutter execs `gradlew` (`#!/usr/bin/env bash`) directly. Not pursued | |

The first `analyze` showed 13 errors, all from a path dependency checked out on the wrong branch. Worth telling the user when a project has sibling path dependencies: they must be on the branch the project's CI uses.

## Flutter: what it took

- **Official only.** `git clone --depth 1 -b <version> https://github.com/flutter/flutter.git` into `~/fvm/versions/<version>` (FVM's layout). Flutter's own script downloads the Dart SDK (`dart-sdk-linux-arm64.zip`, 220 MB) from Google's storage. FVM 4.3.2 publishes `linux-arm64` builds (glibc and musl), not tried yet.
- **glibc loader.** Dart and the engine's host tools are glibc binaries with `/lib/ld-linux-aarch64.so.1` as interpreter. `patchelf --set-interpreter $PREFIX/glibc/lib/ld-linux-aarch64.so.1`, as for Claude Code. Needed on `dart-sdk/bin/{dart,dartvm,dartaotruntime,utils/gen_snapshot,utils/wasm-opt}` and, after the engine downloads, `artifacts/engine/linux-arm64/{flutter_tester,impellerc,font-subset,gen_snapshot}`. Missing ones fail as `ProcessException: No such file or directory`. The launcher must patch any unpatched glibc ELF under `bin/cache` after Flutter downloads something.
- **Two exec helpers that don't mix.** Flutter's scripts start with `#!/usr/bin/env bash`, so they need Termux's bionic `libtermux-exec-ld-preload.so`. Dart can't start with that preloaded: glibc's loader loads it, its `libc.so` dependency resolves to glibc's linker script, and it dies (`invalid ELF header`). Termux's glibc build of termux-exec doesn't help: bionic children then die with `CANNOT LINK EXECUTABLE`, and shebangs still fail. Fix: rename each Dart binary to `<name>.glibc` and put a 3-line bash wrapper (absolute shebang) in its place that unsets `LD_PRELOAD` and execs it. Dart's own children then start without the helper, which is fine for git, gradle's java and the engine tools, and is why `gradlew` (a shebang script) can't be started by Flutter.
- **Launcher.** `$PREFIX/bin/flutter` (and `dart` as a link): absolute shebang, finds the version in the nearest `.fvmrc` up the tree, else a default, sets the bionic preload and runs `bash <root>/bin/flutter`. Works from a Claude session, where `LD_PRELOAD` is empty: tried for `--version`, `dart --version` and the whole `flutter test` run.
- **Small gaps.** `flutter doctor` crashes on a missing `which` (`pkg install which`). The channel shows `[user-branch]` for a shallow tag clone; FVM's own clone may differ.

## Rust: what it took

- **No official compiler for Android.** `static.rust-lang.org` publishes only `rust-std` for `aarch64-linux-android` (for cross-compiling to Android), no `rustc`, `cargo` or `clippy` that run on it. Official `rustup-init` for `aarch64-linux-android` exists and its sha256 matched, but it panics at once (`rustls-platform-verifier` expects to run inside an Android app with a JVM), and the published binary has neither the curl nor the native-tls backend (`RUSTUP_USE_CURL=1`, `RUSTUP_USE_RUSTLS=0` both refuse). So rustup is out.
- **Termux's `rust` package** (1.99.1, the current stable, built from Rust's source tarball): `pkg install rust` adds `rust` and `rust-std-aarch64-linux-android`, 128 MB download, 590 MB installed. `cargo`, `clippy` and `rustfmt` come with it. Build scripts link with the clang already installed (21.1.8). One version only: `rust-toolchain.toml` pins can't be honoured; the tool should say so and check with stable.

## Sizes

| What | Size |
|---|---|
| Flutter 3.44.1 with Dart SDK and engine artifacts | 1.1 GB |
| The app's pub cache (`~/.pub-cache`) | 1.1 GB |
| Rust (`rust`, `rust-std-aarch64-linux-android`) | 590 MB |
| `~/.cargo` after one crate's dependencies | 150 MB |
| The crate's `target/` after check and clippy | 405 MB |

## For the tile

- One tile, "Flutter & Rust", or two tiles sharing a page style. Flutter versions are FVM's; Rust is one Termux package, updated with the others.
- Install, per Flutter version: clone the tag, run its Dart download, patch and wrap. The launcher repeats patch and wrap whenever it finds an unpatched glibc binary in `bin/cache`, so `flutter upgrade`, FVM installs and engine downloads keep working.
- Uninstall removes `~/fvm`, the launchers, `~/.pub-cache`, `~/.dart-tool`, `~/.flutter`, `~/.config/flutter`, and for Rust the two packages and `~/.cargo`. Clang stays when something else needs it.
- The page says plainly: for checking code (pub get, build_runner, analyze, test, cargo check and clippy). No APKs on the phone.
- Not tried: FVM's own CLI, `flutter test` with golden files, `cargo test`, a project pinning a Rust version, two Flutter versions side by side, the Android build tool absent.
