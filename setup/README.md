# setup

The shell side of PocketRocket's first run (BRD R2, R10; decisions 3 and 5). Our own module, kept out of the upstream Termux code.

## Pieces

- `pocketrocket-setup.sh`: idempotent first-run script, run inside the Termux shell.
  1. Checks the device (Termux, aarch64), takes a lock, picks the default mirror if none is chosen.
  2. Installs `glibc-repo`, then `glibc-runner patchelf-glibc git gh openssh ripgrep jq curl zellij nodejs-lts`. It uses `apt-get` (what `pkg` wraps) because its CLI is stable and it runs unattended; packages already installed are skipped. On the first run it also brings the bootstrap's own packages up to date (`full-upgrade`). Every install goes through `apt_install_progress` (see the progress contract below), which cleans apt's cache afterwards.
  3. Installs extra packages listed in `~/.pocketrocket/tools.txt` (one per line, `#` comments). Creates the file empty if it is missing.
  4. Resolves the version from `downloads.claude.ai/claude-code-releases/latest` (or `stable`, or a pinned `X.Y.Z`), the same lookup Anthropic's `install.sh` uses. Downloads `manifest.json` and the `linux-arm64` binary, then checks the size and SHA-256 against the manifest. Interrupted downloads resume.
  5. Points the ELF interpreter at `$PREFIX/glibc/lib/ld-linux-aarch64.so.1` with `patchelf`, runs `claude --version` and a full-start probe (`--init-only`), and only then moves the version to `~/.pocketrocket/claude/<version>/` and flips `current`. If a new version fails and an older one works, the older one stays current. The current version and one older one are kept.
  6. Installs the launcher as `$PREFIX/bin/claude`. Merges `autoUpdates: false` and `env.USE_BUILTIN_RIPGREP = "0"` into `~/.claude/settings.json` (or `$CLAUDE_CONFIG_DIR`), and removes any `env.LD_PRELOAD`. Writes `$PREFIX/etc/profile.d/pocketrocket.sh` with `USE_BUILTIN_RIPGREP=0`. Writes the PocketRocket motd. Turns the DNS preload on or off.
  7. Runs `claude --version` through the launcher.
  8. Installs Dockito's CLI (decision 40) as `--only dockito` does, from the release the app read from Dockito's Homebrew formula (`POCKETROCKET_DOCKITO_*`). Without that release (offline) an installed Dockito is kept; otherwise the step fails and Retry tries again.
- `claude`: the launcher. Runs `~/.pocketrocket/claude/current/claude` with `LD_PRELOAD` unset (Termux's bionic preload crashes a glibc binary), `DISABLE_AUTOUPDATER=1`, `USE_BUILTIN_RIPGREP=0`, and the DNS preload through `BUN_OPTIONS` when it is on. It never updates anything.
- `pocketrocket-workspace`: add, clone and list workspaces from a shell (decision 73). Not installed by this script: the app copies it to `$PREFIX/bin` from its assets at every start, and answers it through files in `~/.pocketrocket/requests/` (`docs/workspaces.md`).
- `flutter`: the Flutter and Dart launcher (decision 75, `docs/flutter-tool.md`), installed as `$PREFIX/bin/flutter` with `dart` a link to it. Picks the version in the nearest `.fvmrc` (else FVM's default, `~/fvm/default`), fetches a missing or old Dart SDK the way Flutter would, prepares any glibc program in that version's `bin/cache` that isn't yet (Termux's glibc loader, and a wrapper that drops the bionic preload), sets the bionic preload for Flutter's own scripts and runs `bin/flutter` or `bin/dart`. Works from Claude sessions, where `LD_PRELOAD` is empty.
- `NOTICE`: the MIT license of the code we ported.

## Updating Claude Code alone (R9)

The app's Settings › Claude Code screen runs the script with an option instead of the full setup. Both modes skip packages and defaults, log to `~/.pocketrocket/update.log`, and print the same progress contract.

| Command | Steps | What it does |
|---|---|---|
| `POCKETROCKET_CLAUDE_VERSION=X.Y.Z bash pocketrocket-setup.sh --only claude` | 4 | Checks the device, downloads and verifies the version (step 4 above), patches and smoke-tests it and switches `current` (step 5), runs `claude --version` (step 7). A version that fails its smoke test is not made current: the script warns `keeping Claude Code <old>`, still ends with `[done]`, and the caller compares `current` with what it asked for. |
| `bash pocketrocket-setup.sh --switch X.Y.Z` | 3 | Rollback to an installed version: points `current` at it, runs `--version` and the full-start probe, and points `current` back with an `[error]` if it does not start. Then prunes, so the version you left becomes the kept one. |
| `POCKETROCKET_DOCKITO_VERSION=V POCKETROCKET_DOCKITO_URL=U POCKETROCKET_DOCKITO_SHA256=S bash pocketrocket-setup.sh --only dockito` | 4 | Updates Dockito's CLI (the app's updates; first-run setup does the first install, decision 40; `docs/dockito.md`): checks that U is the tap's linux-arm64 asset for V, downloads it, refuses it unless its SHA-256 is S (from Dockito's Homebrew formula), unpacks, patches the interpreter, runs `dockito --version`, switches `~/.pocketrocket/dockito/current`, installs the `dockito` and `dockito-accounts` launchers. |
| `bash pocketrocket-setup.sh --only chromium` | 4 | Optional headless Chromium (Settings › Tools only, decision 40; `docs/browser.md`): enables the x11 repo, installs `chromium` (187 MB download, 838 MB installed) and `chrome-devtools-mcp@1.12.0`. The app then registers that server with every Claude account. |
| `bash pocketrocket-setup.sh --only chromium-remove` | 3 | Removes `chromium` (and packages only it needed) and uninstalls `chrome-devtools-mcp`. The app then removes the server from every Claude account. |
| `bash pocketrocket-setup.sh --only android-build` | 4 | Optional Android build tool (Settings › Tools only, decision 45; `docs/android-build.md`): Java 17, Gradle and arm64 build tools from Termux, the SDK from Google. Needs `POCKETROCKET_SDK_LICENSE_HASH`, set by the app once the user accepts the license. |
| `bash pocketrocket-setup.sh --only android-build-remove` | 3 | Removes the SDK, the wiring, Gradle's downloads and the packages the install brought in. Takes the Android NDK tool with it if installed (decision 50). |
| `bash pocketrocket-setup.sh --only android-ndk` | 3 | Optional Android NDK tool (Settings › Tools only, decision 50; `docs/android-build.md`): Termux's clang, cmake, ninja and ndk-sysroot behind NDK folders in the SDK. Needs the Android tool installed. |
| `bash pocketrocket-setup.sh --only android-ndk-remove` | 2 | Removes the NDK folders, the Gradle init script and the packages the install brought in. The Android tool stays. |
| `bash pocketrocket-setup.sh --only android-build-remove` | 3 | Removes the SDK, the wiring, Gradle's downloads and the packages the install brought in. |
| `bash pocketrocket-setup.sh --only wrangler` | 3 | Optional Wrangler tool (Settings › Tools only, decision 49): pinned npm package with install scripts off, workerd's loader pointed at a stub because it has no Android build, then `wrangler --version` and a `deploy --dry-run` as the check. Needs Node, which the base set has. |
| `bash pocketrocket-setup.sh --only wrangler-remove` | 2 | Signs out, removes the package and Wrangler's folder (`~/.config/.wrangler`). Installs no packages, so removes none. |
| `bash pocketrocket-setup.sh --only flutter-rust` | 6 | Optional Flutter & Rust tool (Settings › Tools only, decision 75; `docs/flutter-tool.md`): `which` and `unzip`, FVM 4.3.2 (official linux-arm64 release, checked against its published SHA-256), the newest stable Flutter cloned at its release tag into `~/fvm/versions/<version>` and checked against the commit in Flutter's release list, its Dart SDK, the tool built and the engine tools fetched and prepared, the default set, then Rust (Termux's `rust`). Records the packages each part added. |
| `bash pocketrocket-setup.sh --only flutter [version]` | 5 | The Flutter part alone: adds that version (or the newest stable). `POCKETROCKET_FLUTTER_VERSION` works too; `POCKETROCKET_FLUTTER_DEFAULT=1` makes it the default, otherwise it becomes the default only when there is none. |
| `bash pocketrocket-setup.sh --only flutter-default <version>` | 2 | Points FVM's `~/fvm/default` at an installed version. |
| `bash pocketrocket-setup.sh --only flutter-remove-version <version>` | 2 | Removes one version; if it was the default, the newest other one becomes the default. |
| `bash pocketrocket-setup.sh --only flutter-remove` | 2 | Removes `~/fvm`, FVM, the launchers, `~/.pub-cache`, Dart's and Flutter's own folders and config, and the packages the install added. |
| `bash pocketrocket-setup.sh --only rust` / `rust-remove` | 2 | Rust alone: installs `rust` (which brings `rust-std-aarch64-linux-android`) and records what it added; removing takes only that, and `~/.cargo`. |
| `bash pocketrocket-setup.sh --only flutter-rust-remove` | 3 | Both removals. |
| `bash pocketrocket-setup.sh --only rust-check` / `rust-update` | 0 / 2 | As `git-check` and `git-update`, for Rust; the update moves `rust-std-aarch64-linux-android` with it. |
| `bash pocketrocket-setup.sh --only git-check` | 0 | Settings › Updates: `apt-get update`, then prints `apt-cache policy git` (the app reads `Installed:` and `Candidate:`). No steps, no lock, no `[done]`; logs to `~/.pocketrocket/git.log`. |
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
