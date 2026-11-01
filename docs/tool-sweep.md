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

## Tested for tiles (1 Nov 2026)

Real run on the phone (Termux packages 1 Nov 2026, Claude Code 2.1.316 shell, Node 24.22.1, npm 12.2.1), for the tiles Shankar chose: Go, deno, Ruby, PHP, Java (Kotlin as an option); vercel, firebase, netlify, awscli, flyctl. Probes ran with `LD_PRELOAD=$PREFIX/lib/libtermux-exec.so bash -c '...'` because this shell had no `pocketrocket-bash` wrapper and no `LD_PRELOAD`. Each tool was removed afterwards: `dpkg -l` and `npm ls -g --depth=0` match the start (apart from `apt-get update`). Download is the `.deb` sizes (npm: the cache after an install into an empty cache); installed is dpkg's Installed-Size (npm: `node_modules/<pkg>`). Install time is on Wi-Fi.

| Tool | Result | Source and package list | Download | Installed | Install time | Caveats |
|---|---|---|---|---|---|---|
| Go 1.27.3 | works | `golang` | 36 MB | 218 MB | 14 s | `go mod tidy` + dependency (google/uuid) + build + run OK, 20 s cold. Leaves `~/go` (1 MB) and `~/.cache/go-build` (59 MB). `clang` not needed for pure Go (it was already installed; cgo would need it). |
| deno 2.9.8 | works | `deno` | 26 MB | 93 MB | 8 s | TS file runs, `npm:lodash@4` import runs (1.6 s). npm cache in `~/.cache/deno` (8 MB for lodash). |
| Ruby 4.0.9 | works with fix | `ruby` (pulls `libyaml`) | 9 MB | 45 MB | 6 s | Script and `gem install` of a pure gem (rainbow, 3 s) and a native gem (bcrypt, 7 s, compiled with clang and make, which were already installed; record them as needed) both work. **But gems are not loaded by default**: `Gem` is undefined, so `require "rainbow"` fails with LoadError. Fix: `export RUBYOPT=-rrubygems` (or `require "rubygems"` first). `gem uninstall` the gems before removing the package, otherwise `$PREFIX/lib/ruby/gems` stays. Gems install into `$PREFIX/lib/ruby/gems/4.0.0` (no `GEM_HOME` set). |
| PHP 8.5.12 | works (composer fails) | `php` (pulls `capstone`, `libzip`, `tidy`) | 13 MB | 101 MB | 6 s | Script and `php -S 127.0.0.1:8765` + curl OK. **`composer` package is broken**: `PharException: phar ... SHA512 signature could not be verified`, because the install-time prefix rewrite changes the phar's shebang and breaks its signature. Untested alternative: `composer.phar` from getcomposer.org (my attempt to download it was blocked by the sandbox classifier). Do not offer composer until fixed. |
| Java (openjdk-21) | works, **but switches the default `java`** | `openjdk-21` (+ `openjdk-21-x`) | 113 MB | 257 MB | 32 s | javac + java run OK on 21.0.13. Installing 21 next to the existing 17 made `update-alternatives` repoint `$PREFIX/bin/java` and `javac` to 21 while `JAVA_HOME` stayed on `java-17-openjdk`. Removing 21 restored 17 (verified: `java -version` 17.0.21). Anything using 17 (Gradle/Android builds) is disturbed while 21 is installed. Decision for the tile: either install 21 and run `update-alternatives --set java ...17...` / `javac` afterwards, or document it. I did this before the coordinator's warning about the other session running Gradle: 17 was the default again after about a minute and I did not remove it. |
| Kotlin 2.4.21 | works | `kotlin` (pulls `openjdk-21`) | 83 MB (+113 MB if 21 is not there) | 93 MB (+257 MB) | 19 s | `kotlinc a.kt -include-runtime -d a.jar` then `java -jar` OK, 12 s cold. Prints a harmless `UnsatisfiedLinkError ... libjansi.so ... libc.so.6 not found` on stderr (jansi native lib, tried to unpack into `$PREFIX/tmp`); output and exit are fine. Kotlin depends on openjdk-21, so it has the same default-`java` caveat. Worked with JDK 21; not tried with 17. |
| vercel 65.9.0 | works | `npm i -g vercel` | 197 MB | 215 MB | 126 s | npm 12 skips install scripts (esbuild, koffi) with an `allow-scripts` warning; the CLI works anyway. |
| firebase-tools 15.34.2 | works | `npm i -g firebase-tools` | 140 MB | 260 MB | 112 s | Skips scripts for protobufjs and re2; works. |
| netlify-cli 27.16.1 | works | `npm i -g netlify-cli` | 263 MB | 254 MB | 168 s | Skips scripts for netlify-cli, esbuild, unix-dgram; works. |
| awscli 2.39.3 | works | `awscli` (pulls `mandoc`) | 36 MB | 204 MB | 24 s | Anonymous `aws s3 ls --no-sign-request` works (6 s). Creates `~/.aws/cli/cache/session.db`. Docs say 208 MB; measured 204 MB. |
| flyctl 0.5.1 | works with fix | `flyctl` | 16 MB | 77 MB | 22 s | `flyctl config validate --local-only` fails with "no access token" (the recipe in `tools/flyctl.sh` is wrong, not the platform; use `flyctl version`). Ignores `FLY_CONFIG_DIR`, uses `~/.fly`. |

All are cause-free on this phone (no A to E applies) except the env-shebang for npm bins (cause A, fixed by the wrapper) and the Ruby and PHP items above. None used `/tmp` (all honoured `$TMPDIR`).

### Removal list per tile

Go: `golang` plus `~/go` and `~/.cache/go-build`. deno: `deno` plus `~/.cache/deno`. Ruby: `gem uninstall` what was added, `ruby`, `libyaml` (autoremove), `~/.local/share/gem`, `$PREFIX/lib/ruby`. PHP: `php` (and `capstone`, `libzip`, `tidy` via autoremove). Java: `openjdk-21 openjdk-21-x`, never `openjdk-17`, then check `java -version`. Kotlin: `kotlin` (then 21 if unused). vercel/firebase-tools/netlify-cli: `npm uninstall -g <pkg>` (netlify removed 935 packages) plus the config below. awscli: `awscli mandoc` plus `~/.aws`. flyctl: `flyctl` plus `~/.fly`.

### Login, logout, status (for Log in / Log out on tiles)

None of these was completed; each was started only far enough to read the first output, then killed. Each login command needs stdin from `/dev/null` or a pty-less background run; output is block-buffered when piped, so write to a file and read it after a few seconds.

| CLI | Login command (prints) | Localhost callback? | Logout | Logged-in check | Credentials |
|---|---|---|---|---|---|
| vercel | `vercel login` prints `  Visit https://vercel.com/oauth/device?user_code=XXXX-XXXX` (after a `> ` line; regex `https://vercel\.com/oauth/device\?user_code=\S+`). Device flow, the command keeps polling and exits when approved. | No | `vercel logout` | `vercel whoami` exits 1 and prints `> Logged out.` Offline: `auth.json` exists in the data dir. | `$XDG_DATA_HOME` (default `~/.local/share`)`/com.vercel.cli/auth.json` (token) and `config.json` there. Only telemetry files and `config.json` appear before login. |
| firebase | `firebase login --no-localhost` prints the session id and `   https://auth.firebase.tools/login?code_challenge=...&session=...&attest=...` (regex `https://auth\.firebase\.tools/login\?\S+`), then step 3 "run `firebase login <authorizationCode>`". With no tty it printed and **exited immediately** (it does not wait), so the code is submitted by a second command, `firebase login <authorizationCode>`; I did not run that, so whether the second command works days later or needs the same session is unverified. | No (copy-paste code) | `firebase logout` | `firebase login:list --non-interactive` exits 0 either way; logged out it prints `No authorized accounts, run "firebase login"`. Offline: `firebase-tools.json` contains a `tokens` or `user` key after login (not present before). | `$XDG_CONFIG_HOME` (default `~/.config`)`/configstore/firebase-tools.json` |
| netlify | `netlify login` prints `Opening https://app.netlify.com/authorize?response_type=ticket&ticket=<hex>&utm_source=cli...` then `Waiting for authorization...` (regex `https://app\.netlify\.com/authorize\?\S+`). Ticket polling, keeps running until approved. There is no `--no-open` flag; it tries to open a browser itself and just printed the URL here. `netlify login --request "<msg>" --json` makes a ticket for another person to approve (did not return in 20 s; unverified). | No | `netlify logout` | `netlify status` exits **0** even when logged out ("Not logged in. Please log in to see project status."), so parse the output or look for a `userId` in the config (before login it holds only `cliId` and `telemetryDisabled`). | `$XDG_CONFIG_HOME` (default `~/.config`)`/netlify/config.json` |
| aws | Console login: `aws login --remote [--profile P --region R]` prints `Please visit the following URL:` and a blank line then `https://<region>.signin.aws.amazon.com/v1/authorize?...` (regex `https://\S+\.signin\.aws\.amazon\.com/v1/authorize\?\S+`), then **prompts on stdin** `Enter the authorization code displayed in your browser:` so the tile must keep the process alive and feed the pasted code to its stdin. SSO: `aws sso login --profile P --use-device-code --no-browser` needs an `sso-session` in `~/.aws/config` first, and its output was not seen (my fake start URL was rejected with `invalid_request`); the docs describe a URL plus a user code. | No with `--remote` (copy-paste code). Without it, a localhost server on a random port (`--redirect-port`): bad on a phone. | `aws logout` (`--all`) clears the `aws login` credentials; for SSO `aws sso logout` | `aws sts get-caller-identity` exits 253 with `NoCredentials` when logged out (needs network when logged in). Offline: `aws configure list` shows `<not set>`, or look for files under `~/.aws/login/cache` (console login) / `~/.aws/sso/cache` (SSO). | `~/.aws/credentials`, `~/.aws/config`; console-login tokens in `~/.aws/login/cache` (override `AWS_LOGIN_CACHE_DIRECTORY`); SSO tokens in `~/.aws/sso/cache`. |
| flyctl | `flyctl auth login` (non-interactive) prints `Open this URL in a browser and approve the login:` then `    https://fly.io/app/auth/cli/<hex>` (regex `https://fly\.io/app/auth/cli/\w+`). It polls and finishes by itself once approved (15 minutes); keep it running in the background. If the process was stopped, finish with `flyctl auth login --code <code shown after approving>`. | No | `flyctl auth logout` | `flyctl auth whoami` exits 1 with `Error: no access token available`; `flyctl auth token` prints the same error. Offline: `~/.fly/config.yml` has `access_token`. | `~/.fly/config.yml` (not `FLY_CONFIG_DIR`). While a login waits it writes `~/.fly/pending-login.json`, which must be removed if the process is killed. |

Best fit for the Wrangler-style tile (print a URL, app opens the browser, CLI keeps polling): vercel, netlify and flyctl. Firebase and aws `--remote` need a paste-the-code step in the tile.

### Traces

Everything installed was removed and `dpkg -l` / `npm ls -g --depth=0` match the start. Traces to know: briefly the default `java` pointed at 21 (see Java); the `~/.cache/com.vercel.cli` and `~/.cache/gem` folders and `~/.aws`, `~/.fly`, `~/.config/netlify`, `~/.config/configstore`, `~/.cache/deno`, `~/go`, `~/.cache/go-build` and `~/.local/share/gem` created by the tests were deleted. `~/.npm` and the npm log folder were not touched (the installs used a throwaway cache).

## Phase 2: decide per tool

Using the results, each tool becomes one of:

- **Included**: in first-run setup. Only small, widely needed ones; Python is the main candidate.
- **Add-on**: a tile in Settings › Tools, as today.
- **Option on a tile**: e.g. the wasm target on Rust.
- **Unsupported**: listed with the reason, so sessions stop retrying.

Each choice is Shankar's call and goes into `decisions.md`.

## Idea for later: bundles

Not planned. Shankar floated it on 31 Oct 2026: bundles of tools that go together (mobile, Flutter, a web stack) installed with one tap, each just a list of existing tools. Revisit once the sweep shows what works.
