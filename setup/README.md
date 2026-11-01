# setup

The shell side of PocketRocket's first run (BRD R2, R10; decisions 3 and 5). Our own module, kept out of the upstream Termux code.

## Pieces

- `pocketrocket-setup.sh`: idempotent first-run script, run inside the Termux shell.
  1. Checks the device (Termux, aarch64), takes a lock, picks the default mirror if none is chosen.
  2. Installs `glibc-repo`, then `glibc-runner patchelf-glibc git gh openssh ripgrep jq curl zellij nodejs-lts`. It uses `apt-get` (what `pkg` wraps) because its CLI is stable and it runs unattended; packages already installed are skipped. On the first run it also brings the bootstrap's own packages up to date (`full-upgrade`). Every install goes through `apt_install_progress` (see the progress contract below), which cleans apt's cache afterwards.
  3. Installs extra packages listed in `~/.pocketrocket/tools.txt` (one per line, `#` comments). Creates the file empty if it is missing.
  4. Resolves the version from `downloads.claude.ai/claude-code-releases/latest` (or `stable`, or a pinned `X.Y.Z`), the same lookup Anthropic's `install.sh` uses. Downloads `manifest.json` and the `linux-arm64` binary, then checks the size and SHA-256 against the manifest. Interrupted downloads resume.
  5. Points the ELF interpreter at `$PREFIX/glibc/lib/ld-linux-aarch64.so.1` with `patchelf`, runs `claude --version` and a full-start probe (`--init-only`), and only then moves the version to `~/.pocketrocket/claude/<version>/` and flips `current`. If a new version fails and an older one works, the older one stays current. The current version and one older one are kept.
  6. Installs the launchers `$PREFIX/bin/claude` and `$PREFIX/bin/pocketrocket-bash`. Merges `autoUpdates: false` and `env.USE_BUILTIN_RIPGREP = "0"` into `~/.claude/settings.json` (or `$CLAUDE_CONFIG_DIR`), and removes any `env.LD_PRELOAD`. Writes `$PREFIX/etc/profile.d/pocketrocket.sh` with `USE_BUILTIN_RIPGREP=0`. Writes the PocketRocket motd. Turns the DNS preload on or off.
  7. Runs `claude --version` through the launcher.
  8. Installs Dockito's CLI (decision 40) as `--only dockito` does, from the release the app read from Dockito's Homebrew formula (`POCKETROCKET_DOCKITO_*`). Without that release (offline) an installed Dockito is kept; otherwise the step fails and Retry tries again.
- `claude`: the launcher. Runs `~/.pocketrocket/claude/current/claude` with `LD_PRELOAD` unset (Termux's bionic preload crashes a glibc binary), `DISABLE_AUTOUPDATER=1`, `USE_BUILTIN_RIPGREP=0`, and the DNS preload through `BUN_OPTIONS` when it is on. Sets `CLAUDE_CODE_SHELL` to `pocketrocket-bash` unless the user set one. It never updates anything.
- `pocketrocket-bash`: the shell Claude Code's Bash tool runs (`docs/tool-sweep.md`, Phase 0). Sets `LD_PRELOAD` to Termux's `libtermux-exec.so` and runs Termux's bash, so `#!/usr/bin/env` scripts (npx, `node_modules/.bin`, most scripts) work in Claude sessions as in a terminal, while Claude Code itself runs without the helper. Claude Code takes a `CLAUDE_CODE_SHELL` only when its path contains `bash` or `zsh`, hence the name. The app also copies it and `claude` from its assets at every start (`setup/Launchers.kt`), where `claude` is installed, so an app update brings them to a phone set up by an older APK.
- `pocketrocket-workspace`: add, clone and list workspaces from a shell (decision 73). Not installed by this script: the app copies it to `$PREFIX/bin` from its assets at every start, and answers it through files in `~/.pocketrocket/requests/` (`docs/workspaces.md`).
- `flutter`: the Flutter, Dart and FVM launcher (decision 75, `docs/flutter-tool.md`), installed as `$PREFIX/bin/flutter` with `dart` and `fvm` links to it. Picks the version in the nearest `.fvmrc` (else FVM's default, `~/fvm/default`), fetches a missing or old Dart SDK the way Flutter would, prepares any glibc program in that version's `bin/cache` that isn't yet (Termux's glibc loader, and a wrapper that drops the bionic preload), sets the bionic preload for Flutter's own scripts and runs `bin/flutter` or `bin/dart`, in a build slot for `flutter build`, `run`, `test`, `drive` and `analyze` and `dart compile`, `test` and `analyze` (decision 97). It also points the `#!/usr/bin/env` lines of the version's scripts in `bin` and `bin/internal` at Termux's bash (git told to ignore the change), so FVM, which runs them without the preload, can start them. Before `flutter upgrade`, `channel <name>` and `downgrade` it puts them back so git can move the checkout, and finishes the new version's setup after; in a release folder (`~/fvm/versions/3.47.9`) those commands stop with a message, since FVM expects that folder to stay that release. As `fvm` it runs FVM and makes ready the versions it installs, uses or runs, finishing `fvm install`'s setup. Works from Claude sessions, where `LD_PRELOAD` is empty.
- `pocketrocket-build-slot`: build slots (decision 97), installed as `$PREFIX/bin/pocketrocket-build-slot` by the app at every start (`builds/BuildSlots.kt`), like `pocketrocket-workspace`. Builds take one of N slots, `~/.pocketrocket/builds/slot-<i>.lock` held with `flock`, so several sessions building at once take turns instead of getting the app killed for memory. N is 1 under 6 GB of RAM, 2 under 14 GB, else 3; `~/.pocketrocket/settings/build_slots` (or `POCKETROCKET_BUILD_SLOTS`) overrides it, 0 turns it off. A build with no free slot waits in order of arrival and prints `PocketRocket: waiting for another build to finish (N running)…` and a hint to run long builds in the background, on stderr. Each holder or waiter writes `~/.pocketrocket/builds/<pid>.build` (`pid`, `helper`, `state` running or waiting, `slot`, `queued`, `started`, `folder`, `session`, `command`), which the app reads for the Background sheet. A command under a build that already has a slot (found through `POCKETROCKET_BUILD_SLOT` or a parent process) shares it. Modes: `run <label> -- <command…>` (the flutter launcher), `wrap <name> <args…>` (the app's wrappers for `cargo`, `make` and `ninja` in `$PREFIX/libexec/pocketrocket/build-wrappers`, first on PATH for sessions and, through `$PREFIX/etc/profile.d/pocketrocket-build-slots.sh`, terminals: compiling subcommands take a slot, `cargo fmt` or `make --version` don't), `hold <pid> <label>` (the Gradle hook), `status` (the queue, for people and agents) and `config` (slots and Gradle's heap for this script).
- `pocketrocket-build-slots.gradle`: the Gradle hook for build slots, written to `~/.gradle/init.d/` by the Android add-on and refreshed by the app at every start while the SDK is there; removed with the add-on. Each root build starts `pocketrocket-build-slot hold <daemon pid>`, which prints `PR_SLOT held` once it has a slot (`nested` or `off` otherwise) and keeps it until its stdin closes; a build service closes it when the build ends, and a daemon that dies closes it too. The lock lives in a helper process because the daemon outlives the build and Java's file locks (fcntl) don't see `flock`'s. With a reused configuration cache the script doesn't run, and the cached task-completion listener takes the slot from the first finished task.
- `android-ndk.sh`, `android-ndk.toolchain.cmake`, `pocketrocket-android-ndk.gradle`: the Android NDK tool's shim builder, its CMake toolchain file and the Gradle hook template (decision 96, `docs/android-build.md`). The setup script sources `android-ndk.sh`; it also runs alone to build a shim in a scratch folder.
- `NOTICE`: the MIT license of the code we ported.

## Updating Claude Code alone (R9)

The app's Settings › Claude Code screen runs the script with an option instead of the full setup. Both modes skip packages and defaults, log to `~/.pocketrocket/update.log`, and print the same progress contract.

| Command | Steps | What it does |
|---|---|---|
| `POCKETROCKET_CLAUDE_VERSION=X.Y.Z bash pocketrocket-setup.sh --only claude` | 4 | Checks the device, downloads and verifies the version (step 4 above), patches and smoke-tests it and switches `current` (step 5), writes the `claude` and `pocketrocket-bash` launchers again, runs `claude --version` (step 7). A version that fails its smoke test is not made current: the script warns `keeping Claude Code <old>`, still ends with `[done]`, and the caller compares `current` with what it asked for. |
| `bash pocketrocket-setup.sh --switch X.Y.Z` | 3 | Rollback to an installed version: points `current` at it, runs `--version` and the full-start probe, and points `current` back with an `[error]` if it does not start. Writes the `claude` and `pocketrocket-bash` launchers again. Then prunes, so the version you left becomes the kept one. |
| `POCKETROCKET_DOCKITO_VERSION=V POCKETROCKET_DOCKITO_URL=U POCKETROCKET_DOCKITO_SHA256=S bash pocketrocket-setup.sh --only dockito` | 4 | Updates Dockito's CLI (the app's updates; first-run setup does the first install, decision 40; `docs/dockito.md`): checks that U is the tap's linux-arm64 asset for V, downloads it, refuses it unless its SHA-256 is S (from Dockito's Homebrew formula), unpacks, patches the interpreter, runs `dockito --version`, switches `~/.pocketrocket/dockito/current`, installs the `dockito` and `dockito-accounts` launchers. |
| `bash pocketrocket-setup.sh --only chromium` | 4 | Optional headless Chromium (Settings › Tools only, decision 40; `docs/browser.md`): enables the x11 repo, installs `chromium` (187 MB download, 838 MB installed) and `chrome-devtools-mcp@1.12.0`. The app then registers that server with every Claude account. |
| `bash pocketrocket-setup.sh --only chromium-remove` | 3 | Removes `chromium` (and packages only it needed) and uninstalls `chrome-devtools-mcp`. The app then removes the server from every Claude account. |
| `bash pocketrocket-setup.sh --only android-build` | 4 | Optional Android build tool (Settings › Tools only, decision 45; `docs/android-build.md`): Java 17, Gradle and arm64 build tools from Termux, the SDK from Google. Needs `POCKETROCKET_SDK_LICENSE_HASH`, set by the app once the user accepts the license. Its block in `~/.gradle/gradle.properties` also caps Gradle's memory from the phone's RAM (`org.gradle.jvmargs` heap from `pocketrocket-build-slot config`, `org.gradle.workers.max=2`, Kotlin in-process; decision 97), and it writes the build-slot Gradle hook. |
| `bash pocketrocket-setup.sh --only android-build-remove` | 3 | Removes the SDK, the wiring, the Gradle hooks (NDK and build slots), Gradle's downloads and the packages the install brought in. Takes the Android NDK tool with it if installed (decision 50). |
| `bash pocketrocket-setup.sh --only c-cpp` / `c-cpp-remove` | 2 / 2-3 | Optional C and C++ tool (Settings › Tools only, decision 96; `docs/android-build.md`): Termux's clang, lld, llvm, cmake, ninja, make, pkg-config and lldb, as a package tool (`pkg_tool_*`), recording what it added in `~/.pocketrocket/c-cpp.packages`. Removing takes the Android NDK first when it's there, then those packages and what the install added, except what other packages need (Rust needs clang). |
| `bash pocketrocket-setup.sh --only android-ndk` | 4-5 | Optional Android NDK tool (Settings › Tools only, decisions 50 and 96; `docs/android-build.md`): C and C++ first if it isn't there, `ndk-build` from AOSP (pinned, git tree checked), Termux's `ndk-multilib` through `apt-get download`, then the shim built by `android-ndk.sh` (beside this script) in the SDK, the version folders and the Gradle hook. Needs the Android tool installed. |
| `bash pocketrocket-setup.sh --only android-ndk-remove` | 2 | Removes the NDK folders. The Gradle hook stays with the Android tool, which owns it; C and C++ and the Android tool stay. |
| `bash pocketrocket-setup.sh --only android-build-remove` | 3 | Removes the SDK, the wiring, Gradle's downloads and the packages the install brought in. |
| `bash pocketrocket-setup.sh --only wrangler` | 4 | Optional Wrangler tool (Settings › Tools only, decisions 49 and 82): pinned npm package with install scripts off; workerd built without tcmalloc downloaded from the `workerd-v<version>` release (`ports/workerd`), SHA-256 checked, and workerd's loader pointed at a wrapper that runs it through the glibc loader; then `wrangler --version`, a `deploy --dry-run` and one request to `wrangler dev` as the check. Needs Node and glibc, which the base set has. |
| `bash pocketrocket-setup.sh --only wrangler-remove` | 2 | Signs out, removes the package and Wrangler's folder (`~/.config/.wrangler`). Installs no packages, so removes none. |
| `bash pocketrocket-setup.sh --only flutter-rust` | 6 | Optional Flutter & Rust tool (Settings › Tools only, decision 75; `docs/flutter-tool.md`): `which` and `unzip`, FVM 4.3.2 (official linux-arm64 release, checked against its published SHA-256), the newest stable Flutter cloned at its release tag into `~/fvm/versions/<version>` and checked against the commit in Flutter's release list, its Dart SDK, the tool built and the engine tools fetched and prepared, the default set, then Rust (Termux's `rust`). Records the packages each part added. |
| `bash pocketrocket-setup.sh --only flutter [version]` | 5 | The Flutter part alone: adds that version (or the newest stable). `POCKETROCKET_FLUTTER_VERSION` works too; `POCKETROCKET_FLUTTER_DEFAULT=1` makes it the default, otherwise it becomes the default only when there is none. With `POCKETROCKET_FLUTTER_REPLACE=<old>` (Settings › Updates), the old version is removed once the new one is the default, if it was the default. |
| `bash pocketrocket-setup.sh --only flutter-default <version>` | 2 | Points FVM's `~/fvm/default` at an installed version. |
| `bash pocketrocket-setup.sh --only flutter-remove-version <version>` | 2 | Removes one version; if it was the default, the newest other one becomes the default. |
| `bash pocketrocket-setup.sh --only flutter-remove` | 2 | Removes `~/fvm`, FVM, the launchers, `~/.pub-cache`, Dart's and Flutter's own folders and config, and the packages the install added. Packages the Android tool also uses (`unzip`) move to its list while it is installed instead of going; removing the Android tool does the same the other way. |
| `bash pocketrocket-setup.sh --only rust` / `rust-remove` | 2 | Rust alone: installs `rust` (which brings `rust-std-aarch64-linux-android`) and records what it added; removing takes only that, and `~/.cargo`. |
| `bash pocketrocket-setup.sh --only flutter-rust-remove` | 3 | Both removals. |
| `bash pocketrocket-setup.sh --only rust-check` / `rust-update` | 0 / 2 | As `git-check` and `git-update`, for Rust (log `rust-check.log`); the update moves `rust-std-aarch64-linux-android` with it. The app runs the check only while Rust is installed. |
| `bash pocketrocket-setup.sh --only git-check` | 0 | Settings › Updates: `apt-get update`, then prints `apt-cache policy git` (the app reads `Installed:` and `Candidate:`). No steps, no lock, no `[done]`; logs to `~/.pocketrocket/git-check.log`, so it never wipes `git-update`'s `git.log`. |
| `bash pocketrocket-setup.sh --only git-update` | 2 | Settings › Updates (decision 41): upgrades git alone (`apt-get install --only-upgrade git`, plus only what the new git needs) with the same three-phase progress as `apt_install_progress`, then checks the installed version is the candidate and runs `git --version`. |

The app checks `downloads.claude.ai/claude-code-releases/latest` and `stable` itself, at most once a day when it opens, and never installs without a tap. Claude Code's own updater stays off (`autoUpdates: false`, `DISABLE_AUTOUPDATER=1`).

## How the app calls it

The app copies `setup/` somewhere in `$HOME` (for example `~/.pocketrocket/setup/`; the launcher must sit next to the script) and runs `bash pocketrocket-setup.sh` in the bootstrap shell, reading stdout line by line.

### Progress contract

The setup screen parses exactly these stdout lines; every other line (`[ok]`, `[info]`, `[warn]`, the log tail after an error) is log text to show or ignore.

| Line | When |
|---|---|
| `[step N/M] <short title>` | a step starts (M is 8 for the full setup) |
| `[progress <0-100>] <detail>` | during long steps. Package installs (steps 2 and 3, Chromium) show three phases on one bar: download 0-40 ("Downloading git · 4.3 of 129 MB", bytes in apt's cache), prepare 40-70 ("Preparing git for PocketRocket (12 of 110)", from the relocation hook's progress file, `POCKETROCKET_RELOCATE_PROGRESS`) and install 70-100 ("Installing git (30 of 110)", then "Setting up git (30 of 110)", from `APT::Status-Fd`). After 20 s without a change the detail reads "Still working on git…". Each package also gets a plain log line (`downloaded`, `prepared`, `unpacked`, `installed`). The Claude Code and Dockito downloads report bytes on disk over the expected size (falling back to Content-Length). |
| `[done]` | last line on success, exit 0 |
| `[error] <one-line message>` | right before a non-zero exit; the message is for the user |

```
[step 4/7] Downloading Claude Code
[info] downloading linux-arm64 binary (279 MB)
[progress 42] 117 MB of 279 MB
[progress 100] downloaded
[ok] checksum matches the manifest
...
[done]
```

After `[error]`, the last lines of `~/.pocketrocket/setup.log` follow on stderr. Running the script again is the retry; a partial download resumes.

Step 6 also replaces `$PREFIX/etc/motd` with a short PocketRocket welcome.

Environment:

| Variable | Meaning |
|---|---|
| `POCKETROCKET_CLAUDE_VERSION` | `latest` (default), `stable` or `X.Y.Z` |
| `POCKETROCKET_DNS_PRELOAD` | `1` on, `0` off, unset leaves it alone. Off by default (decision 5). The flag lives in `~/.pocketrocket/settings/dns_preload`, so the Settings toggle can also just create or delete that file. |
| `POCKETROCKET_SMOKE_TIMEOUT` | seconds for the full-start probe, default 45 |

For profiles, run it with `CLAUDE_CONFIG_DIR` set to write the defaults into that profile's config dir.

## Testing helpers off the phone

`POCKETROCKET_SETUP_SOURCE_ONLY=1 . setup/pocketrocket-setup.sh` loads the functions without running anything, so `resolve_version`, `verify_binary`, `read_tools_file` and `write_claude_defaults` can be tested on a Mac.

## Credits

The glibc-runner and patchelf approach, the `LD_PRELOAD` handling, the `--init-only` crash probe and the DNS preload come from [claude-code-android](https://github.com/ferrumclaudepilgrim/claude-code-android) by ferrumclaudepilgrim (MIT, see `NOTICE`). The version and manifest lookup follows Anthropic's official installer at `https://claude.ai/install.sh`. Claude Code itself is Anthropic's, downloaded at first run and never bundled.
