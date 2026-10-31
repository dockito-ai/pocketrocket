# Configuring Claude Code from PocketRocket: plan

Decision 43. Goal: manage MCP servers, connectors, plugins and the rest of Claude Code's setup from the app, as far as Claude Code allows, per Claude account. Steps 1 to 5 are built (see "What is built") and were tried on the emulator on 31 Oct 2026 (see "Verified"). It replaces the "Claude panel (skills, MCP, memory, plugins)" line in Phase B of `PROGRESS.md`, which was never built.

## What Claude Code exposes

Checked on the phone on 30 Oct 2026 with Claude Code 2.1.314.

**Control requests, no model call.** Full fields, replies and what was measured or only read from the binary: "Control requests for configuration" in `chat-sessions.md`.

| Control request | Result |
|---|---|
| `initialize` | Reply has `account` (email, organization, subscription, provider), `claude_code_version`, `commands`, `agents`, `models`, `output_style` |
| `mcp_status` | Each server's name, status (`pending`, `connected`, `needs-auth`, `failed`, `disabled`), scope (`user`, `local`, `project`, `dynamic`, `claudeai`), source, transport, error and tools. Servers start `pending`; connectors appear later (by 25 s in the probe). `config.headers` and `config.args` hold values in clear, so never log or show them |
| `get_settings`, `get_status` | `applied`, `effective`, `sources`; the `/status` rows as text |
| `mcp_authenticate`, `mcp_oauth_callback_url`, `mcp_clear_auth` | Connector: returns a claude.ai URL, nothing to send back. Local OAuth server: full sign-in works over the protocol. `mcp_clear_auth` doesn't apply to connectors |
| `mcp_reconnect`, `mcp_toggle` | Work. A toggle persists per folder |
| `mcp_set_servers` | Session-only servers |
| `reload_plugins`, `reload_skills`, `get_skills_dialog` | Work, and pick up CLI changes without a restart |
| `apply_flag_settings`, `update_settings` | Session-only settings; persistent writes for `outputStyle` and `effortLevel` only |

**`plugin_install` is not a control request.** It is a stream event during headless install, and sending it answers `Unsupported control request subtype`. There is also no request to add, edit or remove a configured MCP server.

**CLI commands, from `--help`, usable per account with `CLAUDE_CONFIG_DIR` (`ClaudeCli.run` already does this).** Checked with a temporary config dir holding no login; `plugin` commands against a local throwaway marketplace.

`claude mcp` has no JSON output on any subcommand:

| Command | Options | Notes |
|---|---|---|
| `add <name> <commandOrUrl> [args...]` | `-s local\|user\|project` (default `local`), `-t stdio\|sse\|http`, `-e KEY=value`, `-H "K: v"`, `--client-id`, `--client-secret`, `--callback-port` | `-e` and `-H` take several values and swallow what follows: put name and URL first, or end with `--`. An existing name fails with `already exists` |
| `add-json <name> <json>` | `-s`, `--client-secret` | |
| `add-from-claude-desktop` | `-s` | Mac and WSL only |
| `get <name>` | | Text. **Prints env and header values in clear** |
| `list` | | Text. Runs a health check on every server. Includes claude.ai connectors |
| `remove <name>` | `-s` | Without `-s`, removes from whichever scope has it |
| `login <name>` | `--no-browser` | Needs a terminal for OAuth servers: with stdin not a TTY it fails with `stdin isn't a terminal`. For a connector it only prints the URL (read from binary) |
| `logout <name>` | | Local OAuth servers. A connector answers that its credentials live on claude.ai |
| `reset-project-choices` | | |
| `serve` | `-d`, `--verbose` | Not relevant here |

Measured with a temporary config dir holding no login (2.1.314): `add-json` takes its JSON only as an argument. `-`, `/dev/stdin` and `@file` all answer `Invalid configuration: : Invalid input`, so **a value given through the CLI is on that process's command line for as long as it runs**. Its output never repeated a value (`mcp add` prints headers as `[REDACTED]`). It drops fields it doesn't know and keeps `oauth`; a `{"command":"node"}` with no `type` is accepted. Each write leaves a copy in `<config dir>/backups/.claude.json.backup.*`, Claude Code's own, so a removed value stays there until those rotate.

There is no edit command: edit is `remove` then `add`, or a change to the config file. User-scope servers live in `<config dir>/.claude.json` under `mcpServers`, local ones under `projects[<cwd>].mcpServers`, project ones in `<cwd>/.mcp.json`. Without `CLAUDE_CONFIG_DIR` the file is `~/.claude.json`.

`claude plugin`, also `plugins`:

| Command | Options | Output (`--json`) |
|---|---|---|
| `list` | `--json`, `--available`, `--data-size [plugin]` | Array of `id`, `version`, `scope`, `enabled`, `installPath`, `readFromFolder`, `folderVersion`, `installedAt`, `lastUpdated`, `mcpServers`, `projectEnabled`, `hasUserConfig`. **`mcpServers` holds the plugin's MCP config with header values and URL queries in clear** (measured), so only its names are kept. `--available` gives `{installed, available: [pluginId, name, description, marketplaceName, version, source]}`: about 3,600 rows from `anthropic-plugin-directory`, even with no login |
| `install <plugin>` | `-s user\|project\|local`, `--config k=v`, `--marketplace <source>`, `--registry`, `--accept-command <sha256>`, `-y`, `--json` | `{command, outcome, plugin, pluginId, scope, message, configApplied}`. Failure: `outcome:"failed"`, `failureCode` (e.g. `not_found`), exit 1. A marketplace-declared command refuses with `failureCode:"command_source_refused"` and `shownCommand: {kind, pluginId, command, mode, catalogRevision, sha256}`, human text before the JSON (measured); the `--accept-command` run after it was not |
| `uninstall <plugin>` | `-s`, `--keep-data`, `--prune`, `-y`, `--json` | Adds `keptData` |
| `update <plugin>` | `-s`, `--accept-command`, `-y`, `--json` | Adds `updateOutcome` (e.g. `up_to_date`), `oldVersion`, `newVersion` |
| `enable <plugin>`, `disable [plugin]` | `-s`, `--json`, `disable -a` | `{command, outcome, plugin, pluginId, scope, message}` |
| `configure <plugin>` | `--json`, `--values-stdin` | Needs the full `name@marketplace` id. `{pluginId, displayName, schema, inputs, choices, configured, unconfigured}`. Values on stdin as a JSON object; left out or blank keeps what is saved, so a value can't be cleared this way. Saving answers `{saved, unconfigured}`, or `{refused: {option, message}}` with exit 1 (`--json` still prints the text on stderr too). Saved values: plain in `settings.json` under `pluginConfigs`, `sensitive` ones in `.credentials.json` under `pluginSecrets`, and `inputs` shows those blank. Prints "Restart Claude Code to apply it" |
| `details <name>` | | Text only: component inventory and projected token cost |
| `validate <path>` | `--json`, `--strict`, `--profile` | Report with `success`, `manifest`, `directory.findings` |
| `marketplace add <source>` | `--scope`, `--sparse`, `--claudeai`, `--json` | `{command, outcome, marketplace, message}`. A path, URL or GitHub repo |
| `marketplace list` | `--json` | Array of `name`, `source`, `path`, `installLocation` |
| `marketplace update [name]` | `--json` | Same shape as add |
| `marketplace remove <name>` | `--scope`, `--json` | Same shape. Also uninstalls that marketplace's plugins and deletes their options and secrets |
| `prune`, `init`, `eval`, `tag`, `test` | | Authoring and cleanup, not checked further |

**Where the sources disagree.** `claude mcp list` does show claude.ai connectors. On the personal account, in two comparisons, it called Google Drive and Google Calendar `Connected` while `mcp_status` in a new session called them `needs-auth`, with no tools. The cause wasn't established, so `mcp_status` stays the source of truth because it reports what a session actually gets.

### What the two accounts show

| | `personal` | `acme claude` |
|---|---|---|
| Connected | `chrome-devtools`, Claude Docs, Dockito, Gmail | `chrome-devtools`, Claude Docs, Slack, Figma |
| Needs sign-in | Display, Google Drive, Google Calendar | About 20 connectors, including Gmail, Sentry, Notion, ClickUp |
| Plugins | None listed | `design`, `cowork-plugin-management` (both scope `synced`) |

The `plugin:design:*` entries for Slack, Figma and Notion can show as `needs-auth` early on. Slack and Figma dropped off the list once the connectors of the same name had loaded. Others stayed, and `plugin:design:gmail` and `plugin:design:google calendar` show `failed`. The UI must not present these early entries as a problem while servers are still loading.

## What can and can't be done

| Area | In the app | Limit |
|---|---|---|
| Status (`/status`) | Native screen from `get_status` (ready-made rows), `initialize`, `mcp_status`, `get_settings` | `/status` isn't offered in `-p` mode, so it can't be sent as a command. `get_status` is `@internal`, so feature-detect it |
| Connector status | Yes, per session and per account | `mcp_status` needs a process, and connectors take up to about 25 s to list. `claude mcp list` is no substitute: it disagreed on two connectors |
| Connector sign-in | Partly proven. `mcp_authenticate` returns a `claude.ai` URL at once; the app opens it in a browser | Completing it is not tested. It relies on the browser being signed in to claude.ai as the same account, and nothing is sent back to the app. Refresh with a new process. `mcp_reconnect` in the same process is untested. Fallback is the terminal's `/mcp` on the right account |
| Connector sign-out | Link to claude.ai | Credentials live there; neither `mcp_clear_auth` nor `claude mcp logout` applies |
| Reconnect | Yes, `mcp_reconnect` | Measured on throwaway servers, not on a connector |
| On/off | Yes, `mcp_toggle` | Measured on throwaway servers; connectors unmeasured. **It persists, but per folder, not per account**, in `projects[<cwd>].disabledMcpServers`. Label it that way, or apply it per session and accept that it sticks to the folder. A true per-account switch doesn't exist in the protocol |
| Sign-in for local OAuth servers | Yes, over the protocol | Measured against a mock only. Localhost redirect connects by itself and needs the process alive through the browser step. With a custom scheme or `mcp_oauth_callback_url`, send `mcp_reconnect` after it. `claude mcp login` can't be used: it needs a terminal |
| Adding a new connector | Link to claude.ai | Connectors come from the login, not from the phone |
| Local MCP servers | Add and remove with `claude mcp add-json`/`remove`, per account, with `-s user` passed explicitly (the default is `local`, one folder). Edit is remove then add | No control request for this and no JSON from the CLI: the list comes from the account's `.claude.json`, and its state from `mcp_status`. Secrets typed on the phone go only into Claude Code's config, never logged or shown again, so never show raw `mcp get` output or `mcp_status` `config`. New sessions pick up changes; whether a running session does, for example on `mcp_reconnect`, wasn't tested (`mcp_set_servers` only covers session-only servers) |
| Stdio commands on the phone | Offer `node script.js` | `#!/usr/bin/env` scripts fail with a misleading `ENOENT`. `npx` fails with `Connection closed` unless the server's `env` sets `LD_PRELOAD` to `<PREFIX>/lib/libtermux-exec.so`. Presets should do that, and the UI should explain these two errors. `npx -y` from the registry untested |
| Project `.mcp.json` | Show, and warn | **There is no approval step in `-p` mode: its servers start with the session**, unlike `mcp list`, which says pending. This is a code-execution risk for cloned repos, to be decided by Shankar |
| Plugins: list, enable, disable, configure, update, uninstall, marketplaces | Yes, through the CLI with `--json`; then `reload_plugins` in live sessions | `details` is text only. `configure` applies after a restart. Synced org plugins (scope `synced`) were not tested, so the app treats them as read-only |
| Plugins: install | Yes, through the CLI, `plugin install --json` | Tested only with a local marketplace, no git or registry source. Not possible over the control protocol. Marketplace commands go through the confirmation flow (`shownCommand`, `--accept-command`) |
| Skills | List with state and token cost (`get_skills_dialog`), reload in a session (`reload_skills`) | **No on/off.** The only route is the hidden `claude edit-skill-overrides --json`, meant for VS Code and per folder, so the app doesn't depend on it. Built-in skills aren't in the list. Editing a skill's text stays in the terminal or chat |
| Memory (`CLAUDE.md`) | View and edit the account's and a folder's, as plain files | `.claude/CLAUDE.md`, rules and auto memory aren't covered (`get_memory_dialog` lists them; unused) |
| Settings | Read-only: an allowlist of keys, each with its source (`get_settings`) | Persistent writes through the protocol are only `outputStyle` (folder) and `effortLevel` (user, per model), and neither can be removed that way. The app writes no settings JSON itself. Org-managed values are read-only. Hooks and raw JSON stay out |
| Output style, effort | Pick per session with `apply_flag_settings`; save with `update_settings` | Both measured on throwaway config dirs: see step 5 |

## Build order

Each step ends in something visible on the phone. All of it comes after the session review fixes.

0. **Probe.** Call each unprobed control request on a test account or disposable server and record fields and behaviour in `chat-sessions.md`. Decides what steps 2 to 4 can promise.
1. **Status (read-only).** A Status sheet in the session (account, version, model, config dir, MCP servers and their state) and a Connections section on the Claude account screen. Connects new sessions to `mcp_status`, refreshing until nothing is `pending`.
2. **Connector sign-in and control.** Sign in, reconnect and on/off for each server. Terminal fallback if `mcp_authenticate` doesn't work from `-p`.
3. **Local MCP servers.** Add, edit and remove per account, with presets for common transports. Decide what happens to `chrome-devtools` in this list: shown, but managed by Tools.
4. **Plugins.** List, details with token cost, enable, disable, configure, marketplaces, then install.
5. **The rest.** Skills, memory, settings, output style.

## What is built

Written on the phone, which has no JDK or SDK; compiled and tried on the emulator afterwards (see "Verified").

- **Status sheet** (controls sheet → Status and connections…): email, organization, plan, version, model, account name, config dir, folder, then the servers. `/status` isn't offered in `-p`, and this doesn't use `get_status`: `initialize` has what the sheet needs and its rows weren't checked for secrets.
- **Connections** on the Claude account screen: the last check, cached per account in `~/.pocketrocket/accounts/connections/<id>.json` (parsed fields only), and a button. The button is the only thing that starts a probe; leaving the page ends it.
- **Actions.** Sign in (`mcp_authenticate`, opened in the browser sheet), then Re-check; Reconnect; on/off; a terminal fallback that says what to type, since a terminal's account follows its folder.
- **Grouping.** Connected, Needs sign-in, Failed, Off, Connecting. While loading, a plugin server that isn't connected is only counted. A plugin server repeating a connector's name stays hidden unless connected.
- **Refresh.** Asks at 1, 1, 2, 3, then every 5 s, until nothing is pending and either a connector has appeared or 30 s have passed since the process started (connectors are absent at first, so "nothing pending" alone stops too early). Stops at 60 s regardless.

Choices made from what the probe showed:

| Choice | Why |
|---|---|
| On/off only for servers configured locally. A connector or plugin server that is already off can be turned on, not off | `mcp_toggle` was measured on throwaway servers only. Needs Shankar's call once tried on a connector |
| Reconnect and on/off only in a session, not on the account screen | A probe connects everything as it starts and ends straight after, and a toggle needs a real folder |
| The probe runs in an empty folder, `connections/probe` | A folder's `.mcp.json` servers start with the process, and a folder's local servers aren't the account's. The cost: local-scope servers don't show on the account screen |
| Re-check in a session tries `mcp_reconnect` and `mcp_status`, then a new process in the same folder | Tells "signed in, but this session can't see it" (offers a new session) from "still not signed in" |
| A probe waits for a connector to be listed before `mcp_authenticate` | A connector not listed yet answers `Server not found`, so sign-in from the account screen can take about 25 s |
| A probe stays up for at most 5 minutes through a local OAuth redirect | That flow needs the asking process alive. A connector's doesn't, so its probe ends at once |

Not verified: completing a connector sign-in, what a browser on another account does, whether a new process sees the sign-in at once (`mcp-needs-auth-cache.json` may delay it), and the real account's `initialize` field names beyond the schema read from the binary.

### Steps 3 and 4: local MCP servers and plugins

Two more cards on the Claude account screen, under Connections. See "Verified" for what was tried. The restore path after a refused edit is covered by unit tests only.

| Choice | Why |
|---|---|
| The list is read from `<config dir>/.claude.json` (`~/.claude.json` for the first account), user scope only, parsed to names, types, the command, host, a count of arguments and the names of variables and headers | `mcp list` is text and runs health checks, `mcp get` prints values. `.credentials.json` is never read |
| Only `add-json`, never `add` | One path for every transport, `-e` can't swallow arguments, `oauth` survives an edit |
| **Secrets are on the command line while `add-json` runs** (not timed) | `add-json` has no stdin (measured above). Android hides other UIDs' `/proc/<pid>/cmdline` (from its documented behaviour, not measured here), so other apps shouldn't see it; a Claude session or MCP server running in this app could, if it looked at that moment. The alternative is the app writing `.claude.json` itself, which would race Claude Code's own writes. Plugin options don't have this: they go on stdin |
| Nothing goes through `ClaudeCli.run` here; `AccountCli` uses `ClaudeCli.start` | `run` mixes stdout and stderr, has no stdin or timeout, and its StreamGobbler logs every line when Termux's log level is verbose. Nothing in `AccountCli` logs |
| On an edit, arguments, the URL and values show as "saved, hidden" and stay unless replaced, and the form never holds them | Arguments and URLs often hold tokens. To keep a value the app re-reads the entry at save time and merges in memory, so it is never redisplayed. The form's state is `remember`, never `rememberSaveable`, so nothing reaches the saved instance state |
| Edit reads the old entry, builds the new config, then removes and adds, all in one blocking block | A form problem stops before the remove; a refused add puts the old entry back with `add-json`; if that fails too the screen says the server is gone. Leaving the screen can't stop it halfway. The app being killed in between could: Claude Code's `backups` still has it |
| `LD_PRELOAD=<PREFIX>/lib/libtermux-exec.so` is added for `npx`, `sh` and similar launchers (`npm`, `pnpm`, `yarn`, `bunx`, `bash`, `dash`, `zsh`, `uvx`, `pipx`) and taken away when the command changes; a value of the user's own stays | Measured for `npx` and `sh` only. The confirmation names it. The form warns about a `#!/usr/bin/env` first line or a bare `.js`, and a failed row explains `ENOENT` and `Connection closed` under the real error |
| A row says Connected only from `mcp_status`, and only from a check newer than the last change here. A change starts a check by itself | Not "added = works". Local-scope servers, one folder's, aren't in this list |
| `chrome-devtools` is listed with no Edit or Remove, and refused by name in the ops as well | Decision 37: Tools owns it |
| Plugins: only scope `user` can be changed. `synced`, `project`, `local` and unknown scopes get Details only | `synced` was never measured. `project` and `local` belong to a folder and this runs in `~` |
| Plugins load on a button; the catalogue (about 3,600 rows) only when Add a plugin opens | Each is a Claude Code process |
| Never `-y`. A declared command is shown as it is, then accepted with `--accept-command <sha256>` | That accepts exactly that command for that catalogue |
| An option that is `sensitive`, or named like a token or key, is a password field that starts blank; blank keeps it. Others show their current value | `inputs` blanks only what the manifest marks; the name check covers a plugin that forgot |
| One change at a time per account (`AccountLocks`), and every call carries that account's config dir | `.claude.json` is rewritten whole. The two accounts never wait for each other |
| No `reload_plugins` from here | The screen has no session. Changes are stated as applying to new sessions, which Shankar accepts |

Not verified: `npx -y` from the registry (the form warns the first start may not finish in time); removing and re-adding a server with stored OAuth tokens (the confirmation warns it may ask for sign-in again); `--accept-command` end to end; any real `synced` plugin; marketplace sources other than a local folder, including the shape of `marketplace list` for them, which falls back to a name and a kind.

### Step 5: skills, memory, settings, output style

The control requests were first sent by hand to a live `claude -p` with a temporary config dir and no login (2.1.314). See "Verified" for what the app did with them; no org policy has been met yet.

Where it is:

- **Claude account screen:** Memory opens the account's `CLAUDE.md`; Skills and settings load on a button, through one probe in the empty folder.
- **Workspace settings:** Memory opens the folder's `CLAUDE.md`.
- **A session's controls sheet** → Style, effort, skills and settings…: everything over that session's process.

| Choice | Why |
|---|---|
| **Skill on/off is skipped** | `claude edit-skill-overrides` is hidden, says it is for the VS Code extension, and writes per folder. Depending on it would break quietly on an update. Say if you want it anyway |
| `reload_skills` only in a session | A probe starts fresh each time, so it has nothing to reload |
| Settings show only keys in `SettingsParser`'s list, as a plain boolean, number or short plain string. Others are a count, "N others are not shown" | `env`, `hooks`, `apiKeyHelper` and headers are never looked at beyond the count. A listed key holding something odd, like a URL with a query or a long token, reads "Set, hidden". Error text from `errors` is dropped as well, since it can quote a value |
| The list of keys comes from what settings are for, not from a real reply | A key not on the list stays hidden rather than leaks, so being too short is the safe way to be wrong. Add keys as they prove useful |
| Style and effort picks use `apply_flag_settings`, session only. Saving is a separate button | Output style saves to `<cwd>/.claude/settings.local.json`, effort to the account's `settings.json` under the current model. Both measured, and a new process starts with them. Neither can be removed through the protocol, and the screen says so for the style |
| A pick is read back with `get_settings` | Claude Code answers success for any name, even a style or level that doesn't exist |
| Only listed styles and levels are offered | `reload_output_styles`, and `supportedEffortLevels` per model from `initialize`. A model listing none gets no effort picker |
| No style or effort on the account screen | A folder setting has no folder there, and "current model" would be the probe's default |
| Managed values can't be picked or saved | A `policySettings` key outranks anything set from here |
| Memory: compared by a hash of the contents, not the time | A touch isn't a change; an edit within the same second is |
| Memory: temp file, fsync, rename, never writing in place. A link is followed | The old file stays whole if anything fails, and a linked `CLAUDE.md` stays linked. If the rename fails, the save fails |
| Memory refuses over 256 KB, what isn't UTF-8 or holds NUL, and what isn't a plain file | A large file would make the text field crawl. Saving a non-UTF-8 file would change it. A pipe would block for ever. Each says why and points to the terminal |
| Conflict: nothing is written. Keep editing, load theirs, or replace theirs | Replace only goes over the version the dialog saw; if the file moved again, it asks again |
| The text is in `remember`, not `rememberSaveable` | 256 KB would overflow saved instance state. The activity handles rotation itself, so rotation keeps it |

Not verified: typing speed near 256 KB, and back over the app's own back handler (written as `SignInScreen` does it). Known gaps: the app being killed loses unsaved text, and a tap on the sessions notification goes Home without asking. Between the conflict check and the rename there is a tiny window another writer can use, since Claude Code respects no file lock.

Remaining, not built: skill on/off (above), `get_hooks_listing`, `list_permission_rules`, `.claude/CLAUDE.md` and rules, a way to clear a saved style or effort, and a memory link from the session's sheet (memory belongs to the account or folder).

### Verified

31 Oct 2026, arm64 API 36 emulator, Claude Code 2.1.312, the emulator's existing login on a personal Max account. No message was sent. Throwaway servers, marketplace and files, removed afterwards.

| Area | Result |
|---|---|
| Unit tests | 290 PocketRocket, 145 terminal-emulator, 2 app, all pass |
| Status sheet | Account, plan, version, model, folder, servers. Reconnect and on/off work on a local server; a failed reconnect says why. A reinstall leaves a session Paused, and it reports again once opened |
| Connections | Connectors listed from 7 to 12 s after the process starts, in the account's states. A probe ends when it is done or the page goes (seen in `ps`) |
| Local MCP | Add, edit, remove, failure rows and the `Connection closed` help. A stdio server reaches Connected. `chrome-devtools` shows no Edit or Remove |
| Plugins | Local marketplace add and remove, catalogue, install with source shown, details and token cost, configure, disable, enable, update, uninstall |
| Skills and settings | Account screen and session. Output style and effort picks read back correctly; saves land in the files the plan names. Reload works |
| Memory | Account and folder. Save, conflict dialog with Replace theirs (its other two choices not tried), discard prompt, refusal over 256 KB and of a file with NUL |
| Secrets | Fake values in env, arguments, an HTTP header, a URL query and a sensitive plugin option. Never in logcat, in the connections cache or anywhere under `.pocketrocket`; never on screen after the confirmation, which shows only what was just typed. A URL query reads `[redacted]` in errors, and a sensitive option reopens blank. Bound to the plan's limits: a stdio server's arguments are on its command line while it runs, and `add-json`'s while it runs |

Seen and not explained, in Claude Code rather than the app: a process sometimes lists no connectors at all. It happened once on the first check after a reinstall, and every time while a test plugin with a failing MCP server was enabled; the list came back after it was uninstalled. Google Drive and Google Calendar also went from `connected` to `needs-auth` between processes with no change on the app's side. `mcp_status` is what the screens show, so these appear as they are. That is why the empty list says "Claude Code listed no connectors" and not that the account has none.

Not tried: connector sign-in and Re-check (would mean a sign-in), local OAuth, the terminal fallback dialog, `npx` and other launchers with the helper, `--accept-command`, a `synced` plugin, a second account, a refused edit in the app, rotation, the phone. CI: `pr_check.yml` is valid and its Gradle command passes locally, but GitHub Actions is off for this repo, so it has never run.

### For the next steps

- `ControlChannel.request(subtype, fields, timeoutMs)` gives a `ControlReply` (`Success`, `Refused` with `.unsupported`, `NoAnswer`). `SessionControlChannel(session)` and `ClaudeConfigProbe.run(context, folder, env) { probe -> }` both implement it; the probe always ends. Add typed helpers beside `mcpStatus` in `ControlProtocol.kt`.
- `McpPanel` over a `McpHost` holds the list and its actions; `McpServerList` draws it. `McpPanelState.can*` decide what shows.
- Parse replies from an allowlist, as `McpParser` does, and run error text through `McpParser.scrub`. Never keep a reply body.
- `ConfigCli` (`AccountCli` on the phone) for CLI commands, then `reload_plugins` through a channel for live sessions if that is ever wanted. `CliJson` finds a result among other lines; `CliText` makes output fit to show.

## How it is built

- New module `claudeconfig/`, one concern, UI in `ui/claudeconfig/`. Reuses `ClaudeCli.environment` for a probe's account, `ClaudeProcessTransport` for its process, and the session's control channel for live requests.
- The account screen needs status with no session open: a short-lived `claude -p` that sends only control requests (no model call, no usage), then exits, cached per account. Each probe costs a Claude Code process for up to about 30 s, so run it on demand rather than in the background.
- Feature-detect every control request. If Claude Code answers with an error, hide that action or fall back to the terminal, because these requests aren't a stable public contract and the updater can change the version.
- Nothing in this module reads `.credentials.json`.

## Risks

- **Undocumented protocol:** control requests can change between Claude Code versions. Handled by feature detection and the terminal fallback.
- **Code execution:** MCP servers and plugins run code on the phone with the app's access. Install and add screens show the source and ask for confirmation.
- **Memory:** every MCP server runs per session (see `chat-sessions.md`). Showing this next to each server helps, but per-session choice is out of scope.

## Out of scope

- Choosing MCP servers per session: per account covers it (Shankar, 30 Oct 2026).
- Moving a conversation to another account (decision 44).
- Bundling any MCP server or plugin in the APK.
