# Backup and restore: plan

Decision 53. Shankar reinstalls often while testing and doesn't want to clone his repos and redo his config each time. It is built, in `pocketrocket/.../backup/`, and tested on the emulator with fake logins (below).

## Why it's needed

Everything the app keeps is in its private folder, `/data/data/app.pocket`. Uninstalling or clearing data deletes all of it, and Android backup is off (`allowBackup="false"`). The one flag `setup_done` decides between Home and Welcome (`MainActivity.kt`). Installing over the same signing key keeps everything, so a normal new build doesn't need a backup. A backup is for an uninstall, a key change or a new phone.

## The flow

**Back up: Settings › Backup.** Shows what goes in, with sizes, and the date of the existing backup. It keeps one backup only, always at `Downloads/PocketRocket/pocketrocket.backup`. It only backs up: what happens next is up to Shankar, and the app offers no uninstall.

**The old backup survives a failure.** The new one is written to a temporary file beside it, read back and checked with the passphrase, and only then renamed over the old one. If anything fails or it's cancelled, the temporary file is deleted and the old backup is untouched. Free space for both is checked first, with a clear message if it's short.

**Progress, both ways.** The screen shows the current step, a real percentage from the total size worked out first, the item in hand and the sizes ("Backing up dockito-api · 42%, 310 of 740 MB"). A foreground service keeps it running with the app in the background, as `CloneService` does for clones, with the same progress and Cancel in its notification and a finished or failed notification at the end.

**Reinstall.** First-run setup runs as usual, then he signs in, then restores by hand from Settings › Backup › Restore. Nothing restores itself, and Welcome and setup don't change.

**Restore: Settings › Backup › Restore.** Done after setup, whenever he wants. It opens Android's file picker, so it needs no storage permission. It shows what's in the file, warns about anything it would replace and refuses while a session is running. It unpacks and checks everything in a staging folder inside the app first, so a wrong passphrase, a damaged file or a cancel changes nothing on the phone. Then it moves the files into place and restarts the app so the accounts and workspaces load.

The same file can be restored again and again across builds.

## What goes in

| Kept | Where it lives |
|---|---|
| Workspace list | `~/.pocketrocket/workspaces.json` |
| Repos and folders in them, with unpushed work | `~/projects` and any other workspace path |
| Account lists | `~/.pocketrocket/accounts/claude.json`, `github.json`, `dockito.json` |
| Which folder uses which accounts | `~/.pocketrocket/accounts/bindings.json` |
| Claude config, per account | `settings.json`, `CLAUDE.md`, skills, plugins, `agents/`, `commands/`, MCP servers in `.claude.json`, per-project memory (`projects/*/memory`) |
| Git identity | `~/.pocketrocket/accounts/github/<id>/gitconfig`, `github/known_hosts` |
| Dockito config and logins | `~/.pocketrocket/accounts/dockito/` |
| Logins | Claude's `.credentials.json`, each GitHub account's `gh/hosts.yml` and `id_ed25519` |

| Left out | Why |
|---|---|
| Chats | Shankar doesn't need them: `sessions.json`, transcripts (`projects/*/*.jsonl`), attachments. Old chats can't be resumed after a restore |
| Tools | Shankar installs them again by hand: `$PREFIX`, `tools.txt`, the Claude Code and Dockito binaries, Headless Chrome |
| `node_modules` and build folders | They can be rebuilt, and they're most of a repo's size |
| Generated files | `env.sh` and the managed block in `~/.gitconfig` are regenerated from the bindings after restore |

## After a restore

- **Restored after setup, over a working install.** Claude's `settings.json` comes back with setup's defaults reapplied (`autoUpdates` false, `USE_BUILTIN_RIPGREP`, no `LD_PRELOAD`), so a backup can't undo them. Workspaces and bindings already on the phone and not in the backup are kept.
- **Accounts merge by who they are, not by id.** Restore usually runs over a phone that is already signed in, often with other ids. Claude matches on email (and organization when both sides have one, from `oauthAccount` in `.claude.json`), GitHub on login, Dockito on email.
  - **Same person:** the backup's config comes back and the phone's login stays, because it is newer: its `.credentials.json` and login fields in `.claude.json`, its `gh/` and SSH key, its Dockito token. The commit identity is config, so it comes back.
  - **Only in the backup:** added with its login, under a free id if its own is taken. A signed-out phone account of the same id is filled instead, which is the fresh-install case.
  - **Only on the phone:** kept. So is the phone's default while it has a real login. With none, the backup's default is used.
  - **No identity:** never guessed. An account without an email in `.claude.json` is kept as a separate account, and Restore says so.
  - **Bindings** are rewritten to the surviving ids. A folder that followed the default is pinned to its account when the merged default is someone else. `env.sh` and `~/.gitconfig`'s managed block are regenerated from them at the restart.
  - **The replace warning** lists each merge and the repos it replaces. Repos and the skills, plugins, agents, commands, memory, `gh` and Dockito folders are replaced whole; other folders merge, so what else is on the phone stays.
- **MCP servers can point at tools that are gone.** Headless Chrome's entry and any server using something installed by hand show as failed until the tool is back. They stay in the config so they work again then.
- **Backup-only accounts come back signed in.** A login in an older backup can have expired or been replaced, more likely when one file is reused across many builds. That account then shows as signed out and needs signing in again. Not yet tried with a real login.

## Safety

- **The whole file is encrypted with a passphrase.** It sits in shared storage, where other apps with storage access can read it, and it holds logins, MCP secrets and any `.env` files or tokens in repos. Shankar sets the passphrase when backing up and enters it on restore. The key comes from the passphrase, because Android's Keystore is wiped on uninstall. A forgotten passphrase means the backup can't be opened.
- **Logins are the one exception to the rule that they stay in their normal config files** (decision 53). They leave those files only inside this encrypted backup, and return to the same places on restore. Never in logs.
- **What goes in is a list of named paths**, not home minus some exclusions, so anything new stays out until it's added.
- **A restore file is untrusted input.** It writes only to the known paths, refuses `..`, absolute paths and links that lead outside them, and checks a format version.
- **One compressed tar, not copied folders or a plain zip.** Shared storage doesn't keep symlinks or executable bits, and a plain zip doesn't reliably either, which would damage repos. It is written and read in the app (commons-compress 1.21), not by the prefix's tar, so every entry is checked before anything lands on disk.

## What the build decided

- **Build folders.** In a repo, `build`, `dist`, `target`, `out`, `.build` and `DerivedData` stay out only if git ignores them entirely. `node_modules` and tool caches stay out unless git tracks files in them. Outside a repo only the tool caches go, because git can't tell. Nothing inside `.git` is touched by name (a branch called `build/x` is a folder there), except `*.lock` files.
- **Links.** A link is kept if it resolves inside the known paths. One leading outside is left out at backup and listed on the screen, because a restore refuses a file holding one. `..` is allowed only at the start of a target.
- **Workspaces outside ~/projects.** A folder in home goes in, unless it is hidden, `~/storage` or home itself. Shared-storage folders survive an uninstall, so only the list entry goes in. Anything else is listed as left out.
- **Claude's `agents/` and `commands/`** are in, replaced whole on restore like skills and plugins.
- **The picker.** Android's picker lists Downloads from the media database, so the file is scanned after every backup. Without that the folder looks empty.

## Build order

1. **Backup module:** the path list, the encrypted archive and its format version, with unit tests including a wrong passphrase and chats staying out. Includes the replace-only-after-checking write, a failed backup keeping the old file, and staged restore. Foreground service with progress and Cancel.
2. **Settings › Backup:** mockup first (decision 23), then the screen, sizes and progress.
3. **Restore:** mockup, the Restore row, file picker, checks, replace warning, app restart.
4. **Verify on the emulator:** real repos and config, back up, uninstall, restore, then a session, a commit and each account's state. Then Shankar on the phone.

Module `backup/`, one concern per file. No change to upstream Termux files.

## Verified and not

Verified on the arm64 API 36 emulator with fake logins: first-run setup, backup with progress, a second backup replacing the first (also after an uninstall and reinstall, when the old file belongs to the old install's uid), restore through the picker from a file the new install doesn't own, the app reopening itself after restore, a full uninstall, reinstall, setup and restore cycle (workspaces, unpushed commit, symlink, executable bit, MCP config, git identity, regenerated gitconfig block and `env.sh`, every account's state, logins), the account merge under a different id (no duplicates, the phone's login wins, the backup's config applied, bindings and `env.sh` on the surviving id), cancelling a backup, a backup failing on an unreadable file or folder, a restore failing on a read-only folder (rolled back), truncated and tampered files refused with nothing changed, a restore refused while a Claude session runs, and the progress and finished notifications.

Not verified: a real Claude, GitHub or Dockito login (fake ones only), cancelling a restore from the screen (it finishes in seconds; covered by unit tests), a restore with only a plain terminal open (terminals don't count as sessions; the restart ends them), Xiaomi's storage and uninstaller, and a line-by-line comparison against `docs/ux-mockup/Backup.dc.html`.

## Open

The first build restores everything in the file and leaves out the last three points below, which stay proposals until Shankar asks for them.

- **Replacing the file in shared storage.** Saving to this fixed path needs the storage access Health › Files already asks for, so Backup asks if it's missing. Renaming over the old file there must be tried on Xiaomi as well as the emulator.
- **Choosing what to restore.** Proposed: the file lists each part with a checkbox, so a first-run test can restore only repos.
- **Keep data on uninstall.** `hasFragileUserData="true"` makes Android offer it. One line, but it needs checking on Xiaomi's uninstaller.
- **Re-run first-run.** A debug-only button that clears `setup_done` and keeps data, for testing the first-run screens without an uninstall.
