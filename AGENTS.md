# PocketRocket: notes for coding agents

Read `README.md`, then `docs/BRD.md` (what we are building and why), `docs/PROGRESS.md` (where it stands) and `docs/decisions.md` (decisions win over everything else). Log every new decision Shankar confirms in decisions.md, numbered and dated.

PocketRocket is a Dockito Labs project, open source under Dockito's GitHub org (decisions 68 and 91). Public-facing text (README, release notes, store-style copy) speaks as "we" and names Dockito Labs, which has no website, so never link it. Direction and product calls are Shankar's. It lives in Dockito's org but stays isolated from Dockito and from his other work: never pull their code, accounts or content into this repo (decision 71). The one exception is Dockito's public CLI, which is installed by first-run setup and updated from Settings (decisions 38 and 40); it is downloaded from Dockito's Homebrew tap at that moment, never bundled.

## Hard rules

- GitHub: `dockito-ai/pocketrocket` (public), reached with the personal account, an admin of the org. `gh` is usually logged into another account, so use `GH_TOKEN=$(gh auth token -u ShankarKakumani) gh …`. SSH `git@github.com` already uses the personal key.
- Git identity in this repo: `Shankar Kakumani <ShankarKakumani@users.noreply.github.com>`. No Co-Authored-By or AI attribution lines in commits or PRs.
- Package id is `app.pocket` (decision 36, replaces decision 2). Termux class names and intent actions keep the `com.termux` namespace; installed packages get their prefix rewritten at install time.
- Never bundle Anthropic's Claude Code binary in the repo or the APK. It is downloaded from `downloads.claude.ai` at first run and checked against Anthropic's manifest.
- Nothing installed system-wide with sudo. Build with the SDK at `~/Library/Android/sdk`; if a newer JDK is needed use the one inside Android Studio.
- Secrets never in chat or commits. Claude and GitHub logins happen on the phone through the official OAuth flows; the app never stores them anywhere but their normal config files, except inside the passphrase-encrypted backup (decision 53).

## How we work

- One agent per subtask, in the order in `docs/BRD.md`. Independent pieces may run in parallel (separate worktree or no overlapping files). Make worktrees under `.claude/worktrees/` in the repo, never as folders beside it in `~/projects` (decision 84). Each ends in something visible on the emulator or the phone, a clean commit and a line in `docs/PROGRESS.md`.
- Opus for building and design; Sonnet for tests, merges and release tasks.
- Agents verify on an arm64 Android emulator (API 36). Shankar tests on his phone; ship him a debug APK path and tell him what to try.
- Clean, modular code: one concern per module (setup, auth, profiles, github, sessions, keepalive, browser, updater), small APIs, no copy-paste. Keep changes to upstream Termux files minimal so merging upstream stays possible.
- Keep the docs in `docs/` current after each step. The BRD also lives on Dockito in project `pocketrocket`: `dockito publish docs/BRD.md --to pocketrocket` after changing it. Same for `docs/architecture.html` (modules, flows, layers): update it when those change, then `dockito publish docs/architecture.html --to pocketrocket/architecture`.
- Ask open questions once, in plain text, without pop-up question dialogs.
