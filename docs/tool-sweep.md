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

## Phase 2: decide per tool

Using the results, each tool becomes one of:

- **Included**: in first-run setup. Only small, widely needed ones; Python is the main candidate.
- **Add-on**: a tile in Settings › Tools, as today.
- **Option on a tile**: e.g. the wasm target on Rust.
- **Unsupported**: listed with the reason, so sessions stop retrying.

Each choice is Shankar's call and goes into `decisions.md`.

## Idea for later: bundles

Not planned. Shankar floated it on 31 Oct 2026: bundles of tools that go together (mobile, Flutter, a web stack) installed with one tap, each just a list of existing tools. Revisit once the sweep shows what works.
