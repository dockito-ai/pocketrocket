# Accounts (R4, R5, decisions 20, 21 and 38)

Many Claude subscriptions and many GitHub accounts, each with a default, wired per folder. Code: `pocketrocket/src/main/java/com/pocketrocket/profiles/` (lists, wiring), `auth/` (Claude sign-in, status, usage), `github/` (gh, connect, git identity), `ui/accounts/` and `ui/settings/AccountsSetting.kt`.

## Files

Everything is under `~/.pocketrocket/accounts/`, so the app, Claude and a terminal see the same thing:

| Path | What |
|---|---|
| `claude.json`, `github.json`, `dockito.json` | Account lists and the default. Names and ids only, no secrets. `dockito.json` is a copy of `dockito accounts --json`, refreshed after every change. |
| `claude/<id>/` | A Claude account's `CLAUDE_CONFIG_DIR`. The first account (`main`, named "personal") is Claude Code's own `~/.claude`, so the login from setup carries over. |
| `github/<id>/gh/` | A GitHub account's `GH_CONFIG_DIR` (gh keeps its token in `hosts.yml` there). `<id>` is the lowercased login. |
| `github/<id>/id_ed25519` | That account's own SSH key, added to it with `gh ssh-key add`. |
| `github/<id>/gitconfig` | `user.name`, `user.email` (the noreply address `<id>+<login>@users.noreply.github.com`), `core.sshCommand = ssh -i <key> -o IdentitiesOnly=yes -o UserKnownHostsFile=github/known_hosts`. |
| `dockito/` | The one `DOCKITO_CONFIG_DIR` every Dockito login shares; the CLI keeps all its logins there and picks one by email. See `docs/dockito.md`. |
| `github/known_hosts` | GitHub's published ed25519 host key (checked against `ssh-keyscan`). |
| `bindings.json` | Folder → Claude, GitHub and Dockito account ids (null means the default, and follows it). A Dockito id is the account's email. |
| `env.sh` | Generated: the same bindings for interactive shells. |
| `usage/<id>.json` | Last plan usage seen for a Claude account. |

Renaming a Claude account changes only its name; the folder keeps its id so running sessions and transcripts stay put.

## Wiring (`AccountWiring`)

- `bind(folder, claude, github, dockito)` / `unbind(folder)` (`dockito` keeps the folder's own choice when left out); a folder uses its own binding or the nearest bound folder above it, else the defaults.
- `envFor(folder, claude = claudeFor(folder))` returns `CLAUDE_CONFIG_DIR` (absent for `~/.claude`), `GH_CONFIG_DIR`, Dockito's shared `DOCKITO_CONFIG_DIR`, and `DOCKITO_ACCOUNT=<email>` when the folder picked its own Dockito account (left unset otherwise, so the CLI's login in use, the default, applies). Sessions get the session's Claude account plus `toolEnvFor(folder)` (gh and Dockito); a new session preselects the folder's Claude account and the New session sheet can override it.
- `~/.gitconfig` gets a managed block (between `# >>> PocketRocket accounts` markers; the rest of the file is left alone): `[include]` of the default GitHub account's gitconfig, then one `[includeIf "gitdir:<folder>/"]` per folder bound to a specific account, deeper folders last. Any git command in that folder (app, Claude, terminal) uses that identity and key.
- `~/.bashrc` gets one line sourcing `env.sh`, which exports `DOCKITO_CONFIG_DIR` once and sets `CLAUDE_CONFIG_DIR`, `GH_CONFIG_DIR` and `DOCKITO_ACCOUNT` from `$PWD` before every prompt.
- All of it is regenerated on every change and at app start. The global `gh auth switch` is never used.
- Workspaces (Phase B) call `bind` when created or changed. Their pickers (New workspace, workspace settings, and the Claude account in New session) are one shared dropdown per kind (`AccountDropdown`): the current account, and a menu with "Default" (follows the default) and every account, the default marked.

## Claude accounts

Add (name, then the normal sign-in for that config dir), sign in again, sign out (`claude auth logout` for that dir), rename, set default, remove (added accounts only: signs out and deletes its dir). Sign-in status comes from `claude auth status --json` per dir.

Usage, only as Claude Code reports it:
- Every session's `rate_limit_event` line carries `rate_limit_info.unifiedWindows.five_hour` and `.seven_day` with `utilization` (0 to 1), `resetsAt` and `status` (2.1.312). The transport passes these to `ClaudeUsage`, keyed by the session's config dir.
- Opening an account page runs `claude -p /cost` for that account, which prints `Current session: 55% used · resets Oct 29, 3:19pm (UTC)` and `Current week (all models): 69% used · resets ...`; "Current session" is shown as the 5-hour limit. It does not count as a model request. `/usage` in `-p` mode prints only the "what's contributing" breakdown, no percentages.
- No per-model weekly line showed up on Shankar's Max plan, so "Weekly · Opus" from the mockup appears only if Claude Code reports it.

## GitHub accounts

Connect (`GitHubConnect`):
1. `gh auth login --web --hostname github.com --git-protocol ssh --skip-ssh-key --scopes admin:public_key,read:user,user:email` into a fresh `GH_CONFIG_DIR`. Without a terminal gh prints `First copy your one-time code: XXXX-XXXX` and the device URL, then polls; the app shows the code with Copy and "Copy and open GitHub" (Custom Tab on `github.com/login/device`).
2. `gh api user` for login, id and name; the gh dir moves to `github/<login>/gh`.
3. `ssh-keygen -t ed25519` for this account, `gh ssh-key add` with title `PocketRocket <phone model> (<login>)`.
4. The gitconfig file; the account is added (the first one becomes the default).

Reconnecting the same login replaces its gh login and keeps its key. Settings → GitHub: make default, edit identity (name and email), remove (deletes the gh login, key and gitconfig on the phone; the key stays on GitHub until deleted there).

`gh` and `openssh` were already in the setup's base packages.

## Dockito accounts

On Dockito's tool page (Settings › Tools › Dockito, decision 64), once the Dockito CLI is 0.0.1-beta.27 or newer. Settings › Accounts shows only Claude and GitHub. The list is the CLI's own (`dockito accounts`): add (`dockito login --add`), sign in again, make default (`dockito switch`), sign out (`dockito logout --account`). A workspace can pick its own, by email. Details in `docs/dockito.md`.

## Verified on the emulator (29 Oct 2026)

- Settings shows Claude "personal · Max · default" and GitHub; the account page shows real usage from `/cost` (5-hour 55%, weekly 69%).
- Added "work": its sign-in reached claude.ai's consent page in the sheet, with `CLAUDE_CONFIG_DIR=.../accounts/claude/work` in the login process; cancelled there.
- GitHub connect reached GitHub's device page with the code shown; cancelled there.
- Wiring, with two GitHub accounts set up as connect leaves them (keys, gitconfigs, `github.json`; no OAuth) and `~/projects/beta-ws` bound to work + beta: in `beta-ws`, `git config user.email` is beta's, `$(git config core.sshCommand) -G github.com` shows beta's `identityfile` and `identitiesonly yes`, and `ssh -T git@github.com` passes host key checking and is refused only for the unknown key; in another repo everything is the default (alpha). Making beta the default in the UI switched the `[include]`; removing both in the UI emptied the block (and removed `~/.gitconfig`, which was empty before). Interactive bash in `beta-ws` had both variables set from `env.sh`. A session started there preselected "work" and its `claude` process had `CLAUDE_CONFIG_DIR=.../claude/work` and `GH_CONFIG_DIR=.../github/beta/gh`.

## Not done yet

- A real second Claude and GitHub OAuth (needs Shankar on the phone).
- "Shared skills and settings" from the mockup's account page.
- Workspace pickers (Phase B, NewWorkspace / WorkspaceSettings) and session cards showing a renamed account's new name (cards keep the name they started with).
