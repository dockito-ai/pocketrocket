# Workspaces (R6, decisions 18 to 21 and 23)

A workspace is any folder Shankar marks as one, usually under `~/projects` in his own structure
(`work/pocketrocket`, `personal/notes`). It can hold several repos side by side. Sessions, clones and
terminals in it use the Claude and GitHub accounts it is bound to.

## Files

| Path | What |
|---|---|
| `~/.pocketrocket/workspaces.json` | `{"workspaces":[{"path","name"}]}`, the list. Nothing else. |
| `~/.pocketrocket/accounts/bindings.json` | The workspace folder's accounts, through `AccountWiring.bind` (see accounts.md). |

Removing a workspace takes it off the list and unbinds it. Its files stay unless the delete box is
ticked and its name typed.

## Code

- `workspaces/` (no Compose)
  - `Workspaces`: the list (add, rename, move, remove, `containing(folder)`, display helpers). `move`
    renames the folder on disk and carries bindings and nested workspaces along.
  - `WorkspaceRepos`: repos in a workspace (the folder itself if it is one, else repos up to two levels
    down) and a `RepoSummary` per repo from `GitRepo` (branch, changed files, ahead/behind, last commit).
    `RepoStatus` caches them per workspace. `GitHead` reads a folder's branch from `.git/HEAD` without git.
  - `RepoClone`: `git clone --progress` as a Flow of progress (splits git's `\r` lines). The environment
    is `AccountWiring.envFor(target)` plus `GIT_SSH_COMMAND` with the workspace's GitHub key, because
    `includeIf gitdir:` only applies once the repo exists. The clone screen can switch to another GitHub
    account in its top bar; that clone then uses its key and gh login, and the new repo is bound to it.
  - `RepoScan`: repo folders found by walking the disk. `notWorkspaces` lists repos under `~/projects`
    that aren't workspaces or in one (decision 71), for the Workspaces list's "Not a workspace · Make
    workspace" rows; it skips hidden folders, dependencies, build output and symlinks, never goes into a
    repo or a workspace, and stops six levels down.
  - `WorkspaceCli`, `WorkspaceCliRunner`, `WorkspaceRequests`: `pocketrocket-workspace` (decision 72), see
    below.
  - `GhRepos`: every repo an account can reach (`/user/repos`: own, collaborator, organization) with that
    account's own `GH_CONFIG_DIR`.
- `sessions/history/EarlierConversations`: past Claude Code conversations under a folder, from every
  Claude account's `projects/<encoded cwd>/*.jsonl`: first typed prompt, last write time, message
  count, account. `SessionManager.resumeClaude` brings one back as a paused session with its history;
  opening it runs `--resume`.
- `termux/Termux.openTerminalIn(context, folder, name)`: Termux's own execute intent with a working
  directory and no executable, so a login shell opens there. `~/.bashrc` sources the accounts'
  `env.sh`, which sets `CLAUDE_CONFIG_DIR` and `GH_CONFIG_DIR` for the folder. No Termux file changed.
- `ui/workspaces/`: `WorkspacesScreen` (tab), `NewWorkspaceScreen`, `CloneScreen`, `WorkspaceScreen`,
  `WorkspaceSettingsScreen`, dialogs. `ui/sessions/NewSessionScreen` replaces the old bottom sheet.
  `ui/home/SessionsScreen` groups sessions by workspace with project tabs and collapsible groups.
- `ui/components/MockTheme.kt`, `MockParts.kt`, `MockIcons.kt`: the mockup's colours (Surface0/1/2,
  Accent and the rest, hex for hex), text sizes, building blocks and line icons. TODO: point them at the
  shared `ui/theme` tokens once those merge.

## `pocketrocket-workspace` (decision 72)

```
pocketrocket-workspace add <path> [--name N] [--github ID] [--claude ID] [--dockito ID]
pocketrocket-workspace clone <url> [folder] [--branch B] [--name N] [--github ID] [--claude ID] [--dockito ID]
pocketrocket-workspace list [--json]
```

- The script (`setup/pocketrocket-workspace`, copied to `$PREFIX/bin` by the app at every start and after
  first-run setup) only asks. It writes `~/.pocketrocket/requests/<id>.req`: NUL-separated fields, a
  version, the shell's folder, `POCKETROCKET_SESSION_FOLDER`, whether `CLAUDECODE` is set, then the
  arguments. Only the app's uid can read that folder.
- `WorkspaceRequests` watches the folder (a `FileObserver`), takes the request by renaming it, creates
  `<id>.out` at once, appends what to print and writes the exit code to `<id>.exit` last. The script
  gives up after about 10 s without `<id>.out` ("PocketRocket didn't answer", exit 3), then prints the
  answer as it grows (lines starting `error: ` go to stderr) and exits with the code.
- `WorkspaceCliRunner` does the work with the app's own code: `Workspaces.add` for add, `CloneManager`
  for clone (one clone at a time, as in the app; it waits until git is done and the repo is bound).
  `WorkspaceCli` holds the pure parts: request and argument parsing, path checks, account defaulting.
- Exit codes: 0 done (also "already a workspace" and "already in workspace X"), 1 refused or failed, 2
  bad arguments, 3 the app didn't answer.
- Paths are resolved from the shell's folder and must be under `~/projects`, also after symlinks. `add`
  needs an existing folder and refuses a folder that holds workspaces. `clone` without a folder clones
  into the shell's folder; a folder that exists and isn't empty is refused, with a hint to use `add` if
  it is a repo.
- Accounts not given: the target's workspace, else the session's, else the shell folder's, else the
  defaults. Flags take an account's id or its name (`list` shows both).
- Each session runs with `POCKETROCKET_SESSION_FOLDER` and `--append-system-prompt` with a short note
  (`WorkspaceCli.CLAUDE_NOTE`): prefer `pocketrocket-workspace clone`, otherwise register clones under
  `~/projects` with `add`, without being asked. Claude Code in a plain terminal doesn't get the note, and
  neither does Rocket's own hidden session (it gets the variable only): its rules live in its CLAUDE.md.

## Navigation

- `ui/PocketRocketApp` keeps a small back stack of route strings (`Routes` builds the workspace ones,
  `ws|<path>` and so on). `ui/RouteStack` draws the top two entries: a pushed screen slides in from the
  right over the one below; back (button, a screen's back arrow or the predictive back gesture) slides
  it out to the right and reveals the screen underneath, which stayed composed. Each entry has its
  own saved state; a popped entry's is dropped.
- The Home tab is held outside the stack. Tabs switch with a 120 ms cross-fade of the content only, the
  bottom bar stays put, each tab keeps its saved state (scroll, groups), and tapping the current tab
  again resets it to its root.
- MainActivity, SessionActivity and GitActivity set `android:enableOnBackInvokedCallback="true"`, so
  they get Android's predictive back and its default activity transitions. TermuxActivity is untouched.

## Clone and DNS

"Could not resolve host" under `adb shell run-as com.termux` is a run-as limitation (the `runas_app`
context gets no DNS), not the app's: the same Termux git cloned
`https://github.com/octocat/Hello-World.git` from inside the app on the emulator (exit 0, about 2 s).
The optional DNS preload (`POCKETROCKET_DNS_PRELOAD`) only affects Claude Code's own Bun resolver and is
not needed for git.

## Verified on the emulator (29 Oct 2026)

- Workspaces tab, New workspace (name, Change location to `~/projects/work/hello-ws`, defaults
  preselected), workspace page, clone by URL with progress, the cloned repo's card (branch, clean, up
  to date, last commit), Settings rebuilt to the mockup.

## Not done yet

- Model, mode and always-allowed defaults per workspace (shown read-only). New session has Model and Mode
  pickers (decision 32); they start as Default and Manual, not yet from the workspace.
- Picking from `gh repo list` was not run on the emulator (no GitHub login there).
- Inter and JetBrains Mono: the system sans and monospace stand in.
