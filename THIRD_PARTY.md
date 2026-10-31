# Third-party notices

PocketRocket is licensed under the GNU General Public License v3 (`LICENSE.md`). It includes or uses the following. Their licences are compatible with GPLv3 and their notices are kept.

## Code in this repository

| Component | Licence | Where |
|---|---|---|
| [Termux](https://github.com/termux/termux-app) (app, `termux-shared`) | GPLv3 only (`termux-shared` is MIT with some GPLv3-only and Apache 2.0 exceptions, see `termux-shared/LICENSE.md`) | The whole project is a fork of it. Upstream copyright headers are kept. Termux's original README: `docs/upstream-termux-README.md`. |
| `terminal-emulator`, `terminal-view` (from Termux, derived from [Terminal Emulator for Android](https://github.com/jackpal/Android-Terminal-Emulator)) | Apache 2.0 | `terminal-emulator/`, `terminal-view/`. Full text: `LICENSES/Apache-2.0.txt`. |
| [claude-code-android](https://github.com/ferrumclaudepilgrim/claude-code-android), parts of the setup and `claude` launcher scripts | MIT, Copyright (c) 2026 ferrumclaudepilgrim | `setup/pocketrocket-setup.sh`, `setup/claude`. Notice: `setup/NOTICE`. |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) Kotlin API (Xiaomi Corporation), trimmed to keyword spotting | Apache 2.0 | `pocketrocket/src/main/java/com/k2fsa/sherpa/onnx/`. Full text: `LICENSES/Apache-2.0.txt`. |
| [Inter](https://rsms.me/inter/) font | SIL Open Font License 1.1 | `pocketrocket/src/main/res/font/`, licence text `pocketrocket/src/main/res/raw/license_inter.txt` |
| [JetBrains Mono](https://www.jetbrains.com/lp/mono/) font | SIL Open Font License 1.1 | `pocketrocket/src/main/res/font/`, licence text `pocketrocket/src/main/res/raw/license_jetbrains_mono.txt` |

## Downloaded at runtime, not in the repository or the APK

| Component | Licence | Notes |
|---|---|---|
| sherpa-onnx native library and its English keyword model (for the opt-in "Hey Rocket" wake word) | Apache 2.0 | Pinned release from `github.com/k2-fsa/sherpa-onnx`, checked after download. |
| Claude Code | Anthropic's terms, not GPL | Downloaded on the phone from `downloads.claude.ai` at first run and checked against Anthropic's manifest. Never bundled. |
| Termux bootstrap packages, git, gh, node, npm and the other tools set up on first run | Their own licences | Installed on the phone by the package manager. |
| Dockito CLI | Dockito's terms | Optional, installed from Dockito's Homebrew tap on the phone. |

## Libraries linked into the APK (Gradle dependencies)

| Library | Licence |
|---|---|
| AndroidX (annotation, core, appcompat, drawerlayout, preference, viewpager, window, activity-compose, lifecycle, browser) | Apache 2.0 |
| Jetpack Compose (BOM 2026.06.00: ui, foundation, material3, material-icons-core, tooling-preview) | Apache 2.0 |
| Material Components for Android (`com.google.android.material`) | Apache 2.0 |
| Guava and `listenablefuture` | Apache 2.0 |
| [Markwon](https://github.com/noties/Markwon) (core, ext-strikethrough, linkify, recycler) | Apache 2.0 |
| kotlinx-coroutines-android | Apache 2.0 |
| Apache Commons Compress, Apache Commons IO | Apache 2.0 |
| [hiddenapibypass](https://github.com/LSPosed/AndroidHiddenApiBypass) (`org.lsposed.hiddenapibypass`) | Apache 2.0 |
| [termux-am-library](https://github.com/termux/termux-am-library) (`com.termux:termux-am-library`) | MIT |

The test keystore `app/testkey_untrusted.jks` is a public upstream Termux test key, not PocketRocket's signing key.
