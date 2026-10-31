# PocketRocket: business requirements

29 Oct 2026. A Dockito Labs project, early and experimental (decision 91). Direction and product calls: Shankar.

## 1. Why

A workstation in your pocket: keep working without always depending on the laptop, for comfort and convenience. Stop somewhere, give Claude Code a task on the phone, let it run for hours, check back at intervals and continue. Claude Code in the Claude mobile app runs in Anthropic's cloud and cannot do enough. A modern phone (the reference one: Android 16, 16 GB RAM, 512 GB) can be the machine itself.

Claude Code stopped working in plain Termux at v2.1.113, when it became a glibc-only native binary. The community workaround (ferrumclaudepilgrim/claude-code-android, MIT) patches the official binary to use Termux's glibc loader and works on current phones. PocketRocket packages that, plus everything around it, into one APK that behaves like a laptop with Claude on it.

## 2. Who

Developers who use Claude Code and want to work from their phone, keyboard in hand, wherever they are. Shankar is the first user and the reference: everything is built and tested on his phone (Poco F7, Android 16). Nothing is read or typed while driving.

## 3. Must have

| # | Requirement | Done when |
|---|---|---|
| R1 | One sideloaded APK, installs on Android 16, opens a shell | App icon on the phone, shell prompt |
| R2 | First run installs the current official Claude Code and makes it run | `claude --version` prints the latest version |
| R3 | Sign in to Claude with one tap, no URL copying | Tap, browser, back in the app signed in |
| R4 | Several Claude subscriptions in Settings, one marked default. Each workspace picks one (default preselected when creating it) and can change it in workspace settings; a session can override it | Two accounts, two workspaces, each session runs on its workspace's account |
| R5 | Several GitHub accounts in Settings, one default. Each gets its own SSH key, gh login (own GH_CONFIG_DIR) and commit identity. Each workspace picks one; its repos use that account's key, gh and identity | Clone and push from two workspaces with two GitHub accounts, commits show the right author |
| R6 | Workspaces, like cmux or a code editor's workspace. A workspace is a folder in the user's own structure under `~/projects` that can hold several repos side by side (e.g. app, backend and shared libs in one folder). Create, rename and move workspaces; clone from GitHub (repo picker) or by URL into one, `git init` a new repo, or add an existing folder. Inside a workspace: as many Claude sessions as wanted as tabs, each started in the workspace root or in any repo or subfolder of it. A Git panel per repo: branch, ahead/behind, changed files with diffs, recent commits. Read-only browsing like the GitHub mobile app: file tree, syntax-highlighted file view, diffs. No editor | One workspace with three repos; two sessions in it; see a session's changes as diffs in the Git panel; push |
| R7 | Tabs: a new session picks repo, branch or worktree, profile; resumes after app restart | Three tabs, kill the app, reopen, sessions resume |
| R8 | Sessions keep running for hours with the screen off, best effort: we do what Android lets us; if the app is killed anyway, the user reopens it and resumes | A 2 hour task finishes with the phone in a pocket |
| R9 | Claude Code updates itself safely: daily check, smoke test, rollback on crash | A broken release is skipped, the previous one keeps working |
| R10 | Git, gh, ripgrep, node and npm, zellij, plus the user's own tool list, installed once at first run and available in every session | Present in PATH |
| R12 | A Tools screen to search Termux packages and install one with a tap; anything installed there is added to the tool list so a fresh setup installs it too. The list is also a plain file the user or Claude can edit | Search, tap install, tool works in a new tab and appears in the list |
| R13 | A mic button beside the chat box: tap, speak, the words land in the box for editing before sending. Android's own speech recognition (on-device where available), not Claude Code's | Dictate a prompt at a stop, fix a word, send |
| R14 | Claude controls in the chat: pick the model when starting and switch mid-session; permission mode (ask, accept edits, plan); Stop; a context meter (tokens used of the window) in the session header; plan usage (5-hour and weekly limits) per Claude account, in Settings → Claude → that account (not on Home, since there are several accounts); slash commands Claude reports as available, from a picker; subagents shown as live cards (task, its own tool calls nested and collapsed, status, result), with their permission asks surfaced like any other A status line under the chat box like Claude Code's own in the terminal: folder · branch · model · context used/window · mode (tap to cycle, like shift+tab). | Switch model mid-session and see the next reply use it; context meter moves; usage shows on Home |
| R15 | Settings → Health: one screen that checks everything the app needs and links straight to the fix: battery saver and optimisation exemption, Xiaomi autostart, the background service and wake lock, Android's process limit (wireless debugging), lock in recents (manual), notifications, microphone, files, Claude Code starting, tools, storage, network, Claude and GitHub logins. Shows when Android last stopped the app and why | Every row green on the phone; turning one off shows it amber with a working Fix button |
| R16 | Attachments in the session chat: the composer's left button becomes "+" (typing "/" still opens commands) and opens a sheet with Photos, Files, Camera, Commands and recent screenshots; attached items show as a removable tray above the field and inside the sent message; Share to PocketRocket from any app picks a session; images paste from the clipboard. Images and PDFs go to Claude inside the message (images downscaled), other files by path; copies kept under `~/.pocketrocket/attachments/`. Mockup: `docs/ux-mockup/Attachments.html` (Option A) | Share a screenshot from Gallery to a session and Claude describes it; attach a log via Files and Claude reads it |
| R17 | Branches, commit graph and pull requests per repo, in the Git screen (decision 27): branch list with switch, new branch and new session on a branch (worktree); Graph tab with lanes and labels; PRs tab with checks, reviews, files, Check out, Review with Claude, Merge. No PR creation. Mockup: `docs/ux-mockup/BranchesPRs.html` | Switch branch from the phone, see the graph with all branches, open a PR, see its checks and diff, merge it |
| R11 | Many sessions from the start. Home is a grid of live session cards (repo, profile, last line) with a clear state: working (animated), waiting for you, done, error. Switch between sessions in one gesture (swipe or a quick switcher) without going back to Home. Android notification when a session needs input or finishes; tapping it opens that session | Four sessions in different states read correctly in the grid; switching takes one gesture; a permission prompt in a background session notifies within seconds |

## 4. Should have

| # | Requirement |
|---|---|
| S2 | Splits inside a tab through zellij |
| S3 | App update from Settings: shows the latest GitHub release, one tap downloads, verifies and installs it (decision 26) |
| S4 | Shared settings and skills across profiles (symlinked, toggle per profile) |
| S5 | Configure Claude Code from the app, per account and as far as it allows: status, connectors, MCP servers, plugins, skills, memory, settings (decision 43, `docs/claude-config.md`) |
| S6 | Voice assistant: ask what's happening, hear announcements, allow or deny requests, unblock a session, start agents, on the Claude subscription (decision 51, `docs/assistant.md`) |
| S7 | Backup and restore: save workspaces and the Claude, Git and Dockito config and logins to one encrypted file, and restore it from Settings after first-run setup (decision 53, `docs/backup-restore.md`) |

## 5. Not now

- Play Store (its policy bans downloading and running native code, which is the core of the app), formal support, Android 8 to 14.
- Android Auto (a separate thin app, later). Dictation into the chat box is in (R13) and the voice assistant is S6.
- Writing a terminal, bundling a Linux rootfs, or an OS of our own.
- Modifying Claude Code beyond the ELF interpreter path.
- A browser for agents (WebView over CDP, or Chromium through proot). Nice to have, dropped for now.
- Starting on boot or running 24/7. If the app is killed or swiped away, the user reopens it and resumes.

## 6. How it is built

- The app is a proper native app, not a terminal with menus. Setup, the session overview, profiles, GitHub, Tools and Settings are Material 3 screens in Jetpack Compose, in our own `pocketrocket` module; long jobs show real progress (steps, bars, what is downloading). The terminal is one screen inside it, used for Claude sessions. The shell's welcome text says PocketRocket.
- Fork of Termux (GPLv3). Keep its terminal, sessions, extra keys, foreground service, wakelock. Rename the visible app only; package id stays `com.termux` because every Termux package hardcodes that install path.
- Setup script in the bootstrap shell: Termux packages (glibc-repo, glibc-runner, patchelf-glibc, git, gh, openssh, ripgrep, jq, curl, zellij, nodejs-lts, then the user's tool list from `~/.pocketrocket/tools.txt`, one package per line, starting empty since the base already covers git, node and npm), download Claude Code `linux-arm64` + `manifest.json` from `downloads.claude.ai`, verify SHA-256, patch the interpreter, smoke test, write defaults (`autoUpdates: false`, `USE_BUILTIN_RIPGREP=0`). Launcher logic ported from claude-code-android with credit. Binary never bundled.
- Claude login: run `claude` in a hidden pty, catch the OAuth URL, open a Chrome Custom Tab; the localhost callback lands in the app's process. Fallback: show URL and a paste field.
- Profiles: one config dir per profile under `~/.claude-profiles/<name>`, sessions start with `CLAUDE_CONFIG_DIR` set to it.
- GitHub: ed25519 key, `gh auth login --web --git-protocol ssh` with the one-time code pre-copied, `gh ssh-key add`, `gh auth setup-git`, identity from `gh api user`. Second account: same button; `gh auth switch` as a dropdown.
- Tabs over Termux sessions; the app records tab to Claude session id for `claude --resume`.
- Session status: each tab starts with `POCKETROCKET_TAB` set. Claude Code hooks in every profile (`UserPromptSubmit` = working, `Notification` = needs input, `Stop` = done, `SessionEnd` = ended) write a small status file under `~/.pocketrocket/status/<tab>`. The app watches that folder, colours the tab dot, keeps the overview list, and posts one grouped notification per state change. Opening a tab marks it seen. No polling, no network.
- Keep-alive: foreground service + wakelock on while a session lives; battery exemption prompt; Settings toggle that pairs the bundled adb with the phone's own wireless debugging and runs the phantom-process commands (`max_phantom_processes`, `set_sync_disabled_for_tests persistent`, `settings_enable_monitor_phantom_procs false`).
- DNS preload to 8.8.8.8 exists in the community script to dodge a hang; off by default here, a Settings toggle.

## 7. Risks

- A Claude release crashes on Bionic: smoke test + rollback; worst case pin a known-good build.
- Android tightens the targetSdk 28 exec exemption: same exposure as Termux; proot remains.
- Upstream Termux drifts: keep our changes small and in our own modules.

## 8. Build order

One agent per task, in order, visible result after each. Opus builds, Sonnet for T9 and T10.

| Task | Delivers |
|---|---|
| T1 | Fork renamed to PocketRocket, debug APK builds, shell opens on the emulator |
| T2 | Setup screen + script; `claude --version` works in a tab |
| T3 | One-tap Claude sign-in |
| T4 | Profiles and picker; two logins in two tabs |
| T5 | Connect GitHub + repo picker + clone |
| T6 | Tabs: new session, resume after restart |
| T7 | Session status: tab dots, overview, notifications (R11) |
| T8 | Keep-alive settings with in-app adb pairing |
| T9 | Launcher and updater port, app update check, Tools screen (R12) |
| T10 | Polish, README, release APK on GitHub |
| T11 | Attachments (R16): "+" sheet with Photos and Files, tray and sent thumbnails, recent screenshots, Share to PocketRocket, Camera and paste |
| T12 | Branches, Graph and PRs (R17); subagent numbers and agent count (decision 28); Edit and Retry on messages (decision 29) |
| T13 | Claude Code configuration (S5), in the order in `docs/claude-config.md` |
| T14 | Voice assistant (S6), in the order in `docs/assistant.md` |
| T15 | Backup and restore (S7), in the order in `docs/backup-restore.md` |
| T16 | Background shells in the chat: live Bash cards, Background sheet, output screen, Stop and Move to background (decision 69) |

## 9. Sizes

APK about 40 to 60 MB; first run downloads about 230 MB for Claude plus about 150 MB of packages. Each live Claude session about 200 to 400 MB RSS.
