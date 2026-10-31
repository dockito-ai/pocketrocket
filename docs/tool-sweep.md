# Tool sweep: plan

Status: plan, 31 Oct 2026, on `spike/tool-sweep`. Nothing built yet.

## Why

Tools have been added one at a time, each after a session ran into a missing one (Rust's wasm target, Python, bun). Most failures on the phone come from a few shared causes, not from the tools themselves. One sweep over the tools developers commonly use, sorted by cause, lets us fix each cause once and pick the next tools from evidence rather than from whichever session complained last.

## Causes already known

| Cause | What breaks | Fix |
|---|---|---|
| A. `#!/usr/bin/env` fails in Claude sessions | `npx`, everything in `node_modules/.bin`, most scripts | Phase 0 below |
| B. Hardcoded `/tmp` | Tools that ignore `$TMPDIR` | None in the app (`/tmp` belongs to Android's `shell` user); note it per tool |
| C. glibc-only binaries | bun, deno, many prebuilt linux-arm64 CLIs | The glibc loader, as for Claude Code and Dart |
| D. Installers that reject "android" | npm packages with platform binaries (bun, esbuild-style) | Install the linux-arm64 build directly, then C |
| E. No Android build at all | Docker, MongoDB server, some databases | A port (decision 86) if worth it, else unsupported |

## Phase 0: `#!/usr/bin/env` in Claude sessions

Measured on the phone: from Claude's Bash tool a `#!/usr/bin/env sh` script fails with `bad interpreter`. With `LD_PRELOAD=$PREFIX/lib/libtermux-exec.so` set when bash starts, it works. `setup/claude` unsets `LD_PRELOAD` because the bionic helper crashes the glibc Claude Code binary, so every shell Claude starts inherits no helper. Setting it inside the shell is too late, because bash has to be started with it.

Plan: the launcher sets `CLAUDE_CODE_SHELL` to a small PocketRocket bash wrapper. The wrapper exports `LD_PRELOAD` with the bionic helper and execs `$PREFIX/bin/bash`. Claude Code itself stays without the helper. Check:

- The Bash tool runs an env-shebang script, `npx --version` and `node_modules/.bin/tsc`.
- The Bash tool still works in every case it did before: background shells, ripgrep, git hooks, Flutter's launcher (decision 50 keeps the helper away from Dart).
- MCP servers are spawned by Claude Code directly, not through the shell, so the `LD_PRELOAD` advice in `McpConfig.kt` stays.
- If `CLAUDE_CODE_SHELL` won't accept a wrapper, fall back to `CLAUDE_CODE_SHELL_PREFIX`.

Phase 0 ships on its own, before the sweep, because it changes the result for about half of the list.

### Phase 0 result (31 Oct 2026, branch `fix/env-shebang`)

Done as planned, with `CLAUDE_CODE_SHELL`; no fallback was needed. Claude Code (2.1.316) takes the variable only when the path is executable and contains `bash` or `zsh`, otherwise it logs "is not a valid bash/zsh path" and falls back to detection, so the wrapper is `setup/pocketrocket-bash`, installed as `$PREFIX/bin/pocketrocket-bash`. It sets `LD_PRELOAD=$PREFIX/lib/libtermux-exec.so` (unless one is set) and execs `$PREFIX/bin/bash "$@"`. `setup/claude` points `CLAUDE_CODE_SHELL` at it when it exists and the user hasn't set one; Claude Code itself still runs with `LD_PRELOAD` unset.

Measured on the phone with a nested `claude -p` through copies of both launchers in a temp folder, the Bash tool printed:

```
shell=<tmp>/bin/pocketrocket-bash
LD_PRELOAD=/data/data/app.pocket/files/usr/lib/libtermux-exec.so
env-shebang-ok                         # a #!/usr/bin/env sh script
node_modules/.bin ok, node v24.22.1    # a #!/usr/bin/env node bin in a temp npm project
npx 12.2.1
git version 2.56.0
ripgrep 15.2.0
Flutter 3.47.9 • channel [user-branch] • https://github.com/flutter/flutter.git
Dart SDK version: 3.13.7 (stable) (Thu Oct 22 14:28:57 2026 -0700) on "linux_arm64"
```

A second run: a background shell (`run_in_background`) ran the env-shebang script, and a git `pre-commit` hook with `#!/usr/bin/env sh` ran on commit. Without the wrapper the same script fails with `/usr/bin/env: bad interpreter`. Flutter is unaffected: its launcher keeps or sets the helper for Flutter's scripts and its wrappers drop it before Dart and the engine tools. glibc programs a Claude shell starts now get the helper, as they do in a terminal; the ones PocketRocket ships drop it themselves (`claude`, `dockito`, Flutter's wrappers). MCP servers are spawned by Claude Code directly, so `McpConfig.kt`'s `LD_PRELOAD` advice stays.

Reaching phones already set up: the setup script writes both launchers in full setup, `--only claude` and `--switch`; the app also copies them from its assets at every start when they differ (`Launchers.refreshClaude`, only where `$PREFIX/bin/claude` exists), the way it already installs `pocketrocket-workspace`. So installing the new APK and opening it is enough; sessions started after that get the wrapper. `ClaudeLauncherTest` covers the launcher (wrapper set, a user's own shell kept, none without the wrapper) and the wrapper.

## Phase 1: the sweep

`spikes/tool-sweep/` (removed after the spike, as other spikes):

- `tools/<name>.sh`, one recipe per tool with `install`, `probe` and `remove`. `probe` does one small real task, not `--version`: run a script, build a hello world, open a database and query it.
- `sweep.sh` runs each recipe from a Claude session (so it sees what Claude sees), times it, records download and installed size, and removes the tool afterwards so the phone doesn't fill up.
- Output: `docs/tool-sweep.md` results table, one row per tool: works / works with fix / doesn't, cause letter, source, sizes, notes.

Source order per tool: Termux package first (updated with the other packages); then the official linux-arm64 build through the glibc loader; then npm. Nothing unofficial, nothing bundled in the APK.

### Tools to sweep

| Group | Tools |
|---|---|
| Languages | Python (pip, venv, uv), bun, deno, Go, Java / Kotlin (partly there via Android), Ruby, PHP, Zig, Elixir, .NET |
| JS tooling | pnpm, yarn, tsc, vite, next, esbuild, prisma, eslint, prettier, playwright |
| Databases | sqlite3, PostgreSQL, Redis, MariaDB, MongoDB |
| Cloud / deploy | vercel, netlify, supabase, firebase, aws, gcloud, flyctl, terraform |
| Utilities | make, fd, bat, fzf, yq, httpie, tmux, ffmpeg, imagemagick |
| Known gaps | Rust `wasm32-unknown-unknown` target (21 MB down, 90 MB on the phone) |

Already in the base set, so not swept: git, gh, ssh, node, npm, rg, jq, curl, zellij.

## Evaluation (desk, 31 Oct 2026)

Shankar wanted a technical evaluation, not a full install run, so the sweep harness was run on 9 tools only and the rest were judged from Termux package metadata (`apt-cache`), npm metadata (`npm view`) and upstream docs. Details and sources: `spikes/tool-sweep/results/eval-languages-db-utils.md` and `eval-js-cloud.md`. Package lists were not refreshed (apt was locked), so versions may lag by a little.

**Works from a Termux package** (no glibc loader, no fix needed beyond Phase 0): Python + pip (33 MB installed), uv, bun (measured), deno, Go (224 MB + clang), Java 17/21/25, Kotlin, Ruby, PHP, Zig (407 MB), Elixir + Erlang, sqlite3 (measured), Redis, PostgreSQL and MariaDB (each needs a one-time init and a start command, no service manager; Android may kill them in the background), make, fd, bat, fzf, yq, httpie, tmux, ffmpeg, ImageMagick, pnpm, yarn, esbuild, awscli (208 MB), flyctl.

**Works from npm** once Phase 0 is in: eslint (measured), prettier, vite (rolldown, rollup, lightningcss, tailwind's oxide and @parcel/watcher all publish `android-arm64` packages), vercel, netlify, firebase, TypeScript 5.

**Works with a known fix**
- Next 16: no Android SWC; falls back to `@next/swc-wasm-nodejs`, webpack only, slower.
- sharp: `@img/sharp-wasm32`, slower.
- Playwright: Termux `playwright` with Termux chromium; never `playwright install` (its browsers are glibc). Chromium only.
- Prisma 7: the client works (wasm); migrate and `db push` need its glibc schema engine through the loader. Prisma 6 and older don't.

**Doesn't, or unproven**
- TypeScript 7 (native, no Android package): use TS 5.
- MongoDB server: no Android build, only the `mongosh` client.
- .NET: packaged, but old reports of GC crashes on Snapdragon; needs a probe.
- terraform, supabase CLI, gcloud: linux-arm64 Go/Python builds hit one shared blocker, Go reading DNS and CA files from `/etc`, which Android lacks. One fix would unlock all three; `supabase start` needs Docker regardless.

**Pattern.** A glibc `.node` addon can't load into Termux's bionic Node, so for npm native addons the route is `android-arm64` packages or wasm, not the glibc loader. The loader is for standalone executables only.

## Phase 2: decide per tool

Using the results, each tool becomes one of:

- **Included**: in first-run setup. Only small, widely needed ones; Python is the main candidate.
- **Add-on**: a tile in Settings › Tools, as today.
- **Option on a tile**: e.g. the wasm target on Rust.
- **Unsupported**: listed with the reason, so sessions stop retrying.

Each choice is Shankar's call and goes into `decisions.md`.

## Idea for later: bundles

Not planned. Shankar floated it on 31 Oct 2026: bundles of tools that go together (mobile, Flutter, a web stack) installed with one tap, each just a list of existing tools. Revisit once the sweep shows what works.
