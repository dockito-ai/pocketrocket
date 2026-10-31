# Dockito CLI (decision 38)

Built in. First-run setup installs Dockito's CLI (decision 40); nothing of Dockito's is in the repo or the APK. It shows under BUILT IN in Settings › Tools, and its tool page holds its version (Update goes through the Updates flow, decision 41) and its accounts, which moved there from Settings › Accounts (decision 64).

Code: `pocketrocket/src/main/java/com/pocketrocket/dockito/` (formula, CLI, sign-in, state), `profiles/DockitoAccounts.kt`, `ui/tools/BuiltInToolPages.kt` (the tool page), `ui/accounts/DockitoAccountsSection.kt` (its Accounts section), and the `--only dockito` part of `setup/pocketrocket-setup.sh` with the `setup/dockito` and `setup/dockito-accounts` launchers.

## Install and update

1. The screen reads Dockito's Homebrew formula (`raw.githubusercontent.com/dockito-ai/homebrew-tap/HEAD/Formula/dockito.rb`) for `version` and the `on_linux` / `on_arm` `url` and `sha256`. The URL must be exactly `https://github.com/dockito-ai/homebrew-tap/releases/download/v<version>/dockito-linux-arm64.tar.gz`, or the release is ignored.
2. Install or Update runs `pocketrocket-setup.sh --only dockito` with `POCKETROCKET_DOCKITO_VERSION`, `_URL` and `_SHA256`. The script checks the URL again, downloads with progress, and refuses the tarball unless its SHA-256 is the formula's (`verify_sha256`).
3. It unpacks `dockito` (a ~97 MB Bun-compiled glibc binary), points its ELF interpreter at Termux's glibc loader with patchelf, exactly as for Claude Code, and runs `dockito --version` before switching.
4. Files: `~/.pocketrocket/dockito/<version>/dockito`, `current` → the version in use (older versions are removed). `$PREFIX/bin/dockito` is the launcher (sets the shared `DOCKITO_CONFIG_DIR` when unset, unsets `LD_PRELOAD`, runs `current`). `$PREFIX/bin/dockito-accounts` says which account applies here and lists them. Log: `~/.pocketrocket/update.log`.

Nothing checks or installs on its own; the screen checks the formula when it opens.

## Accounts

Dockito's CLI has its own accounts since 0.0.1-beta.27, the oldest version PocketRocket installs (the app ignores an older formula, the script refuses one with `version_at_least`). PocketRocket uses them through the CLI's public commands only:

- **One login store**: every process uses `DOCKITO_CONFIG_DIR=~/.pocketrocket/accounts/dockito/` (`AccountPaths.dockitoConfig`; the launcher sets it too when it is unset, `env.sh` exports it). The CLI keeps each login's token there (no keychain on Android). The app never reads a token.
- **List**: `dockito accounts --json`, an array of `{"email", "name", "username", "active"}` (`[]` when signed out; shape seen from the real 0.0.1-beta.27 CLI). The app copies it into `dockito.json` after every change and when the screen opens (`DockitoAccounts.sync`), so pickers and bindings work offline. An account is named by its email, which is also its id. Accounts gone from the CLI's list (signed out in a terminal, say) drop out of workspace bindings.
- **Add / sign in again**: `dockito login --add --no-browser` (plain `login --no-browser` while there is none). The CLI prints the link (opened with ACTION_VIEW) and a code, shown on the screen to check against Dockito's page; Cancel kills the process. When it exits 0 the list is read again.
- **Default**: the CLI's login in use. Make default runs `dockito switch <email>`.
- **Sign out**: `dockito logout --account <email>`; the list is read again and workspaces bound to it fall back to the default.
- **Workspaces**: `bindings.json` holds a Dockito email per folder. Sessions (`AccountWiring.toolEnvFor`) and terminals (`env.sh`) get `DOCKITO_ACCOUNT=<email>` there; elsewhere it is unset, so the login in use applies (and `dockito switch` in a terminal takes effect). `DockitoAccounts.env` is the one place that says this.
- **Status**: `dockito whoami --json` per account (with `DOCKITO_ACCOUNT`), for the name, expiry and workspaces on the screen.
- **In a terminal**: `dockito accounts` lists them (✓ on the one in use), `dockito --account <email> …` acts as another for one command. `dockito-accounts` says which one this folder uses, then runs `dockito accounts`.

## Claude skill

After an install or update, a sign-in, and when a Claude account is added, the app runs `dockito init --agent claude-code --no-browser` with each Claude account's `CLAUDE_CONFIG_DIR` (once a Dockito login exists), so every account's `skills/dockito/` has the skill (init also writes `~/.agents/skills`). The app then appends one note to each copy's `SKILL.md`: `dockito accounts` lists the accounts, `--account <email>` picks another for a command, and without it dockito runs as the folder's account (`DOCKITO_ACCOUNT`) or the default. Opening the screen fills in accounts that miss it.
