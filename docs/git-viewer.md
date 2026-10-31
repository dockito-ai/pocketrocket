# Git viewer

The Git panel, diffs and read-only browsing for one repo folder (BRD R6, decision 19). It reads
everything through the git CLI in `$PREFIX/bin/git`; there is no native git library.

## Entry point

```kotlin
GitScreens.open(context, repoPath)          // starts GitActivity on the panel
GitScreens.intent(context, repoPath)        // the same, as an Intent
```

`repoPath` can be any folder inside a repo; the viewer resolves the top level with
`git rev-parse --show-toplevel` and shows a message if the folder is not a repo or git is missing.
For now a session's overflow menu (⋮ in the chat top bar) has **Git**, which opens the session's
folder. Phase B moves this onto the workspace page, one entry per repo.

## Layout

- `git/` (data, no Compose)
  - `GitCli` runs git in the repo with `GIT_TERMINAL_PROMPT=0` and `GIT_OPTIONAL_LOCKS=0` (the viewer
    never takes `index.lock` for reads, so it cannot block a Claude session's own git calls), keeps
    stdout and stderr apart, caps output and logs each call's time under the `PocketRocketGit` tag.
  - `GitParsers` parses `status --porcelain=v2 -b -z`, `diff --numstat -z`, `log` with a fixed
    `%x1f`/`%x1e` format, `show --raw --numstat -z`, `ls-tree -z` and unified diffs. Pure Kotlin,
    covered by `src/test/.../GitParsersTest.kt`.
  - `GitRepo` is the API: `status()`, `log()`, `commitFiles()`, `commitMessage()`, `diff(DiffSource)`,
    `tree(dir)`, `file(path)`, and the only writes: `stage`, `unstage`, `stageAll`, `commit`, `push`
    (sets the upstream on first push), `pull --ff-only`.
- `ui/git/`
  - `GitActivity` with a small back stack of `GitRoute`s; `GitPanelModel` (ViewModel) holds status,
    commits and the write actions; the panel refreshes when the activity returns to the front.
  - `GitPanelScreen`: branch card (ahead/behind, Pull, Push, the branch's PR), tabs Changes / Graph / PRs / Files,
    changes grouped Conflicts / Staged / Changes / Untracked with per-file stage and unstage and
    Stage all, Commit dialog; a commit in the Graph opens its files (see Branches, graph and pull requests).
  - `DiffScreen`: unified diff in a LazyColumn, one Text per line with a number and sign gutter and a
    hanging indent, so long lines wrap and stay aligned under the code. Diffs over 50,000 rows or
    8 MB are cut with a note.
  - `BrowseScreen` / `FileScreen`: folders at HEAD (folders first, changed files flagged), the last
    commit, the folder's README rendered with the chat's `MarkdownText` (badges, link targets and
    HTML stripped first), and a file viewer with line numbers and simple per-line syntax colours
    (`SyntaxColors`). Markdown files open rendered with a Source toggle. Binary files and files over
    1 MB are not shown in full.

## Speed (decision 19)

termux/termux-app (1,523 commits, 346 files) on the arm64 API 36 emulator.

| Command | Shell on device, best of 5 | In the app, as logged |
| --- | --- | --- |
| `status --porcelain=v2 -b` | 5 ms | 12 ms |
| `diff --numstat` (+ `--cached`) | 4 ms each | 11 to 16 ms (run in parallel with status) |
| `log -n 50` | 4 ms | 9 ms |
| `log` all 1,523 commits | 12 ms | not used |
| `diff` of one file | 4 ms | 6 ms (16 ms for a 7,000-line, 345 KB diff) |
| `show --raw --numstat` of a commit | 5 ms | 6 ms |
| `ls-tree HEAD` | 5 ms | 6 ms |
| `show HEAD:README.md` | 5 ms | 5 ms |
| `diff HEAD~200` (19,387 lines) | 28 ms | not used |
| `commit` / `push` to a local remote | | 22 ms / 71 ms |

The panel opens in about 25 ms of git time. The CLI is fast enough; no Rust needed for now.

Scrolling the 7,000-line diff by fast flings on the emulator (debug build): median frame 17 ms,
90th percentile 29 ms, 4% janky frames. The commits list measures 16 ms median on the same
emulator, so the diff is close to the floor here. The first version (number, sign and code as three
Texts per row) measured 26 ms median, which is why each row is now one Text.

## Branches, graph and pull requests (R17, decision 27)

Mockup: `docs/ux-mockup/BranchesPRs.html`. Entry points:

```kotlin
GitScreens.openBranches(context, repoPath)   // the panel with the branch sheet up (status-line branch tap)
GitScreens.startSession(context, folder, title, prompt)   // a Claude session in a worktree, optional first message
```

### Branches

- The branch card's name (`main ▾`) opens `BranchSheet`: Find a branch, **On this phone** (local branches newest
  first: current ticked, ↑/↓ against the upstream, age, `#42 open` when the branch has an open PR, and in blue
  `used by "API tests" (worktree)` when it is checked out in another folder) and **On GitHub only** (remote branches
  no local branch has or tracks). Data: `git for-each-ref --format=<GitParsers.BRANCH_FORMAT> refs/heads refs/remotes`
  (`%(upstream:track,nobracket)` for ahead/behind, `%(worktreepath)` for worktrees).
- Tap a local branch: `git switch <b>`; a remote one: `git switch --track origin/<b>`. A branch checked out in another
  folder is not switched: a dialog names the folder and opens its session (or starts one there).
- Uncommitted changes, or a session in this folder that is Working or waiting on a permission (from
  `SessionManager.sessions`; sessions in `.worktrees/` don't count), bring up `SwitchDialog` with the mockup's
  warning. **Keep them on this branch**: `git stash push -u -m pocketrocket:<branch>`, then switch; if the switch
  fails the stash is popped back. After every switch, a stash marked for the new branch
  (`git stash list --format=%gd%x1f%gs`) is popped, so the changes come back when you return. **Bring them**: plain
  switch; git's own refusal is shown when they clash.
- New branch: `git switch -c <name>` from HEAD. Long-press: Copy name, Delete (only when merged into the default
  branch: `git branch --merged <default>`, then `git branch -d`; the default branch is `origin/HEAD`, else main or
  master), New session on this branch.
- New session on a branch: `git worktree add <dir> <branch>` (existing), `--track -b <b> <dir> origin/<b>` (remote)
  or `-b <name> <dir>` (new, from HEAD). `<dir>` is `<workspace>/.worktrees/<repo>-<branch>` (beside the repo when it
  is in no workspace), so the session keeps the workspace's Claude and GitHub accounts and `WorkspaceRepos` skips it
  (dot folder). When the workspace is the repo itself, `/.worktrees/` goes into `.git/info/exclude`. The session is
  `SessionManager.startClaude` in that folder, renamed to the branch.

### Worktrees (decision 73)

- `git worktree list --porcelain -z` (`GitParsers.worktrees`): path, HEAD, branch (none when detached), `locked [reason]`
  (Claude Code's agents lock theirs: `claude agent agent-…`), `prunable [reason]`. The first entry is the main checkout.
- Per worktree (`WorktreeRows.load`, in parallel, on each panel refresh): `git -C <path> status --porcelain -z`
  (renames counted once), `git -C <path> log -1 --format=%ct`, merged into the default branch (the panel's
  `mergedInto` set), and sessions whose folder is in it (`SessionManager.sessions`; working or waiting counts as working).
- "Worktrees · N" card under the branch card when there is any besides the main checkout; `GitRoute.Worktrees` opens
  `WorktreesScreen`. Remove: `git worktree remove --force <path>` (`--force --force` when locked), then `git branch -d`
  when asked and merged. Clean up: every `tidy` row (merged, clean, no session, not locked, on a branch), then
  `git worktree prune`. Prune missing: `git worktree prune`. The main checkout and the folder the panel is open on
  can't be removed.

### Graph

- The Commits tab is now **Graph**, with This branch / All branches (default All, as in the mockup). Data:
  `git log --topo-order --decorate=full --format=<GitParsers.GRAPH_FORMAT> --skip=<n> -n 200` over `HEAD`, or
  `--branches --remotes --tags HEAD` (never the stash). Scrolling near the end loads the next 200.
- `git/GraphLayout` assigns lanes in pure Kotlin (unit-tested): one lane per open line, the first parent continues
  the lane, other parents take a waiting lane or a new one, lanes heading for the same commit join it; freed lanes
  are reused, never shifted. Each row is drawn by a Canvas (top half in, bottom half out, curves between lanes, a
  5 dp dot), lanes coloured accent, green, blue, purple, amber, teal...
- Labels: the default branch orange, local branches green, `origin/…` blue, tags plain, PR numbers purple.
- Tap: the commit's files (as before). Long-press: See files and diffs, Check out `<branch>` on a branch tip,
  New branch here (`git switch -c <name> <sha>`), Open PR #n, Copy SHA.

### Pull requests

- `git/PullRequests` runs `gh` in the repo folder with the workspace account's `GH_CONFIG_DIR`
  (`AccountWiring.githubFor`, `GhCli.environment`), through `GitCli.exec` (stdout and stderr apart, timed). Parsing
  is in `git/PrParsers` (unit-tested). It never creates PRs.
- Before asking GitHub it checks offline that there is an account, gh and a `github.com` remote; otherwise, and on
  any gh error, the PRs tab shows a short note and the rest of the PR UI stays hidden.
- PRs tab (`PRs · n`): `gh pr list --json number,title,author,headRefName,baseRefName,isDraft,reviewDecision,
  statusCheckRollup,updatedAt,reviewRequests,state,latestReviews -L 50` with `--state open` (Open), `--author @me`
  (Mine), `--search review-requested:@me` (To review, its count on the chip) or `--state closed` (Closed). Cards:
  checks (`✓ 4/4 checks`, `● 2 running`, `✗ 1 failing`), approvals, review requested (amber outline), Draft.
- Branch card: `gh pr view --json number,title,state,isDraft,headRefName,statusCheckRollup` for the current branch;
  an open one shows as `⇄ #42 Title ✓ checks ›`.
- PR screen (`GitRoute.Pr`): Overview (state, author, age, +/−, the body through the chat's `MarkdownText`, checks
  with durations, review requests and latest reviews, comment count → GitHub) from `gh pr view <n> --json …`;
  Files from `gh pr diff <n> --color=never`, split per file (`PrParsers.files`) into the existing diff viewer
  (`GitRoute.PrDiff`); Commits. Bottom: **Check out** (`gh pr checkout <n>`, same questions as a switch),
  **Review with Claude** (`git worktree add --detach <ws>/.worktrees/<repo>-pr-<n> HEAD`, then `gh pr checkout <n>`
  in it, or with `--branch pr-<n>` when the branch is taken elsewhere; a new session there whose first message asks
  Claude to review PR #n with `gh pr view` / `gh pr diff` and not to push or comment), **Merge ▾** (methods from
  `gh repo view --json squashMergeAllowed,mergeCommitAllowed,rebaseMergeAllowed`, Delete branch after →
  `gh pr merge <n> --squash|--merge|--rebase [--delete-branch]`). ⋮: Open on GitHub, Approve / Request changes
  (`gh pr review <n> --approve|--request-changes [--body]`, not on your own PRs), Close (`gh pr close`), Mark ready (`gh pr ready`, drafts).
- Workspace repo cards (`workspaces/RepoPrs`): `⇄ N open PRs` in purple, `N waiting for your review` in amber and the
  checked-out branch's PR (`#42 yours, checks passing`), from two `gh pr list` calls per repo, loaded after the card
  shows and cached for two minutes; no line at all on any gh error or offline.

## Not done yet

- Discard file, "Comment to Claude" and "Ask Claude to commit" from the mockup (need sessions and
  workspaces wired together, Phase B).
- Next/previous file in the diff. Rename and Compare with current on a branch (mockup §3 note); line comments,
  approvals and PR creation (decision 27: not in this round).
- Branches, graph and PRs were written on the phone without a build: not yet compiled or tried on the emulator.
- The workspace card's review count uses `--search review-requested:@me`, which includes team requests; the
  PR screen's "You were asked to review" only matches the account's own login.
- Push and pull use whatever credentials git finds; decision 21 (per-workspace keys and gh config)
  lands with the accounts work. Push in the app was verified against a local bare remote only.
