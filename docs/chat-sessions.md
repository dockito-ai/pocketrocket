# Chat sessions (proof of concept)

Sessions are a native chat driving the real Claude Code CLI in its headless streaming mode. The terminal stays as a fallback ("Open a terminal instead" in the New session sheet). Code: `pocketrocket/src/main/java/com/pocketrocket/sessions/` (model, protocol, transports) and `ui/sessions/`, `ui/chat/` (grid, pager, chat).

## How Claude Code is run

One long-lived process per session, in the session's folder, through the PocketRocket launcher in Termux's environment:

```
claude -p --input-format stream-json --output-format stream-json --verbose \
  --permission-prompt-tool stdio --include-partial-messages \
  --session-id <uuid>      # or --resume <uuid>
```

The session's Claude account adds `CLAUDE_CONFIG_DIR=<dir>` to the process env (none for the default `~/.claude`), and its folder's GitHub account adds `GH_CONFIG_DIR`. See `docs/accounts.md`.

`--permission-prompt-tool stdio` is required: without it `-p` silently denies anything that needs approval (checked on 2.1.312; the help text's `--permission-prompts host` default alone is not enough). The process stays alive between turns and reads the next user message from stdin.

`--include-partial-messages` adds `stream_event` lines (the raw API stream) so replies grow token by token.

## Messages we use (one JSON object per line)

Host to CLI (stdin):

| Line | Purpose |
|---|---|
| `{"type":"control_request","request_id":"pr-init","request":{"subtype":"initialize"}}` | SDK handshake, sent first. The reply gives `models` (value, displayName, resolvedModel), `commands` (name, description, argumentHint), `terminal_slash_commands` (hidden) and `current_permission_mode`. |
| `{"type":"user","uuid":"<uuid>","message":{"role":"user","content":"..."}}` | A user turn. Queued by the CLI if a turn is running. The `uuid` we pick becomes the message's id in the transcript (what a rewind targets). Slash commands (`/compact`, skills) are sent the same way. |
| `{"type":"control_response","response":{"subtype":"success","request_id":"<id>","response":{"behavior":"allow","updatedInput":{...}}}}` | Allow a tool use (input passed back unchanged). |
| same with `"updatedPermissions":[...]` added to the allow | Always allow: the entries are the request's `permission_suggestions`, sent back verbatim. The CLI applies them (an `addRules` with `destination:"localSettings"` lands in `<cwd>/.claude/settings.local.json`) and stops asking. |
| same with `{"behavior":"deny","message":"..."}` | Deny. |
| `{"type":"control_request","request_id":"<id>","request":{"subtype":"interrupt"}}` | Stop the running turn (the Stop button). |
| `request":{"subtype":"set_model","model":"haiku"}` | Switch model; reply `{"model":"claude-haiku-5-5"}`. Works before the first turn and between turns. |
| `request":{"subtype":"set_permission_mode","mode":"plan"}` | `default`, `acceptEdits`, `plan`, `auto` (also `bypassPermissions`, `dontAsk`); reply echoes `mode`. |
| `request":{"subtype":"get_context_usage","detail":"summary"}` | `totalTokens` and `maxTokens` (the model's real window), no API calls. Sent after init, each result, a model switch and a rewind. |
| `request":{"subtype":"rewind_conversation","target_message_uuid":"<uuid>"}` | Drops that user message and everything after it; reply `{"rewound":true,"targetMessageUuid","prefillText"}`. Files are not touched. |
| deny with `"interrupt":true` | Keep planning on a plan card: the denial also ends the turn so Claude waits for feedback. |

CLI to host (stdout):

| `type` | What we take |
|---|---|
| `system` / `subtype:init` | `session_id` (kept for `--resume`), `model`, `cwd`, `permissionMode`, `slash_commands` (names, merged into the picker). Arrives when each turn starts. |
| `system` / `status` | `status:"compacting"` while /compact runs; `permissionMode` when the CLI changes mode itself (an approved plan drops back to `default`, or to `acceptEdits` if the approval asked for it). |
| `system` / `compact_boundary` | `compact_metadata.pre_tokens` / `post_tokens`: "Compacted · 28k → 5.8k tokens". |
| `system` / `task_started`, `task_progress`, `task_notification` | Subagent card: `tool_use_id` of its Task/Agent call, `description` (what it is doing now), at the end `status` and `summary`. A shell's lines (`task_type` `local_bash`, every Bash call on 2.1.316) go to its Bash card instead: `task_id`, `is_backgrounded`; at the end `status` (`completed`, `failed`, `stopped`), `output_file` and a summary ending in "(exit code N)". `task_notification` carries no `task_type`, so it updates whichever kind of card has that `tool_use_id`. |
| `system` / `task_updated` | `patch.is_backgrounded: true`: a foreground shell was moved to the background. Its `patch.status` (`killed` on Stop) is ignored; the notification that follows says how it ended. |
| `control_response` | Replies to our requests, matched by request id prefix (`pr-init`, `pr-model-`, `pr-mode-`, `pr-context-`, `pr-rewind-`). |
| `assistant` | `message.content[]`: `text` blocks and `tool_use` blocks (`id`, `name`, `input`). `thinking` is ignored. An `error` field (e.g. `authentication_failed`) marks a CLI-made error message. `message.usage` (input + cache creation + cache read tokens) is the context in use. Lines with `parent_tool_use_id` set are subagent output: only their `tool_use` blocks are kept, as steps nested in the Task/Agent card. With partial messages on, the CLI prints one `assistant` line per content block (same `message.id`), right after that block's deltas and before its `content_block_stop`. |
| `stream_event` | Only `event.type:"content_block_delta"` with `delta.type:"text_delta"`: `api_message_id`, `event.index`, `delta.text`. Tool-input (`input_json_delta`) and thinking deltas, `message_start/stop` and block start/stop are ignored; the full `assistant` line carries those. Subagent lines (`parent_tool_use_id` set) skipped. |
| `user` | `tool_result` blocks (`tool_use_id`, `content`, `is_error`); with `parent_tool_use_id` they finish a subagent step. String content `<local-command-stdout>…` is a local command's output, shown as a notice. |
| `control_request` / `subtype:can_use_tool` | `request_id`, `tool_name`, `input`, `tool_use_id`, `description`, `permission_suggestions` (`blocked_path` unused). The session goes to Needs you until answered. |
| `control_cancel_request` | Drop the pending permission. |
| `result` | End of turn: `is_error`, `result` text, `session_id`, `modelUsage.<model>.contextWindow`. `terminal_reason` `aborted_*` (with `is_error:true`, `error_during_execution`) is a stopped turn: Done with "Interrupted", not Error. |

`rate_limit_event` is read by the transport for the account's plan usage (`docs/accounts.md`), not by the chat. Ignored: `system/status`, hook events, `command_lifecycle`, `control_response` to our own requests, cost fields.

### Streaming text

A delta grows the last chat item if it is a still-streaming text item for the same message id and block index, else it starts one. The full `assistant` text block then replaces the text of the first still-streaming item with that message id (blocks finish in order) and marks it final; the item keeps its id, so the list does not redraw or duplicate it. A blank final block removes its item. `result` or the process exiting settles anything still streaming (an interrupted turn keeps its partial text). Without partial messages (or in the fake transport) text arrives whole as before.

### Always allow

`permission_suggestions` seen on 2.1.312 for `touch pr-probe.txt` in `~`:

```json
[{"type":"addRules","rules":[{"toolName":"Bash","ruleContent":"touch pr-probe.txt"}],"behavior":"allow","destination":"localSettings"},
 {"type":"addDirectories","directories":["/data/data/com.termux/files/home"],"destination":"session"},
 {"type":"setMode","mode":"acceptEdits","destination":"session"}]
```

The card's third button sends only what its label names (`sessions/AlwaysAllow.kt`): the `addRules` allow entries if any ("Always allow `touch pr-probe.txt`"; a `cmd:*` rule reads "`cmd` commands"), else `setMode acceptEdits` ("Always allow edits for this session"), else `addDirectories` ("Always allow access to ~/dir"). The rule alone was enough: the next identical command ran without asking.

## Claude controls (R14, decision 17)

Checked against the CLI itself (strings of the 2.1.308 binary, then probes over stdin/stdout on the Mac and the emulator's 2.1.312). Code: `sessions/Controls.kt` (modes, models, commands, context, subagent runs), `Session` (actions), `SessionReducer` (replies), `ui/chat/StatusLine.kt`, `ControlsSheet.kt`, `SlashPicker.kt`, `PlanCard.kt`, `AgentCard.kt`.

- **Status line** under the chat box: `folder │ branch │ model │ 27k/1M`, then the mode (`▸▸ accept edits`, `‖ plan mode`...). Branch from `.git/HEAD` (worktrees too), re-read after each turn. Model name from the CLI's own `models` list. Context from `get_context_usage` (exact used and window, shown before the first message) and from each reply's usage while a turn runs; amber past 70%, red past 90%. Tap the mode, the first line, or long-press for the controls sheet, where the mode is changed (decision 69). Counts of running agents and background shells sit beside the mode (`▸▸ auto mode │ 1 agent │ 2 shells`); tapping them opens the Background sheet.
- **Controls sheet** (mockup Controls): Mode (Manual `default`, Accept edits `acceptEdits`, Plan `plan`, Auto `auto`, the CLI's classifier mode that asks only for risky actions), Model (the CLI's list minus "Default"), Context bar with Compact, Rewind… and Stop, and the command list.
- **Plan approval**: `can_use_tool` for `ExitPlanMode` shows the plan (`input.plan`, markdown) with Keep planning (deny + interrupt), Approve (allow; the CLI goes back to Manual) and Approve and accept edits (allow with `updatedPermissions: [{type:setMode, mode:acceptEdits, destination:session}]`).
- **Compact**: sends `/compact`; "Compacting" while `status:compacting`, then the boundary notice. `get_context_usage` summary still reports the pre-compaction count until the next reply, so the boundary's `post_tokens` is kept until then.
- **Slash commands**: the `/` button or typing `/` opens a picker over the chat box (initialize `commands` with descriptions and argument hints, plus names from later `init` lines); picking puts `/name ` in the box.
- **Subagents**: a Task/Agent call is a card with its description, live status (`task_progress` description), its tool calls nested (last two shown, all when expanded) and the final report (`task_notification` summary, else the tool result without its hand-back frame). Collapsed by default. Their permission asks come as normal `can_use_tool` requests.
- **Agent numbers** (decision 28): each card shows `Working · 0:42 · 8.1k tokens · 5 tools` (time ticks each second), frozen at the end (`Done · 1:12 · …`, also Failed, Stopped). Tokens and tools from `usage.total_tokens` / `usage.tool_uses` on `task_*` lines (top-level `total_tokens`, `subagent_tokens`, `tool_uses` as fallbacks), time from `duration_ms` on `task_notification`, else our own clock; tool count falls back to nested steps. Replayed agents use the Agent tool result's `toolUseResult` (`totalTokens`, `totalToolUseCount`, `totalDurationMs`). Background agents count as running until their `task_notification`; agents still running when the process ends or the session pauses become Stopped. The status line adds `│ N agents` beside the mode while any run; tapping it opens the Background sheet, where an agent's row scrolls to its card. **Field names on the stream are not yet checked against a live line.**
- **Edit, Retry** (decision 29): long-press your message → Edit · Retry (Copy dropped, decision 34; Rewind… in the sheet is Edit). Edit drops the message and everything after it on screen at once, puts its text and attachments back in the chat box with the keyboard open, and shows "Rewound · Undo" for 4 s; `rewind_conversation` is only sent when that runs out (or on the next send, Edit or Retry), so Undo is real. Retry rewinds and sends the same text and attachments again. Rewinds go through the same ordered outbox as messages and the outbox waits for the CLI's reply (up to 15 s) before sending what follows. While Claude works, both interrupt first and run when the turn has stopped. Files are not touched.
- **Queued messages** (decision 34): a message sent while Claude works doesn't go into the chat at once (it would land in the middle of the streaming reply). It goes to the CLI straight away and waits in a "Queued" box above the chat box. Claude Code reads it at its next step, after the running tool returns, and answers it in the same turn. With `--replay-user-messages` it echoes the message (our uuid, `isReplay: true`) at that moment, and that is when it joins the chat. If the turn ends first, Stop included, the CLI starts the next turn with it, so it joins the chat on the `result`. If the process dies, it goes back into the chat box. Checked against Claude Code 2.1.312 on the phone.
- **Name**: an optional Name field in New session (blank keeps the folder name). **Rename**: long-press a session card on Home (Rename / End session) or a chip in the switcher. `Session.rename` sets the title (trimmed, 40 chars max, blank ignored); it is saved like any other change.
- A new process (resume after the process died) gets the chosen model and current mode sent again; after an app restart both start from the account's defaults.

What the CLI does not give us here:
- Rewind does not restore files. `rewind_files` exists but needs file checkpointing turned on at launch; not wired.
- Model and mode set over the control protocol are not saved in the session; `--resume` starts on the defaults.
- **Background shells** (decision 69, checked on 2.1.316): a Bash card with a shell task (`ShellRun`, `sessions/ShellTasks.kt`) shows its state instead of ✓: `background · 1:02` with a spinner and its latest output line, then `✓ 0:08`, `✗ exit 1 · 0:08` or `stopped`. Its output file path comes from the tool result ("Output is being written to: …") or the notification; the app reads it directly (`ShellOutput`: last 200 lines, ANSI and `\r` redraws stripped). Tapping the card opens `ShellOutputScreen` (live, follows the bottom, Copy all, Stop). A foreground shell past 10 s shows Move to background: `control_request` `background_tasks` with `tool_use_id`. Stop is `stop_task` with `task_id`. Shells still running when the process ends become stopped. When a background shell ends while the session is idle, Claude Code starts a turn by itself to report it. Running background shells count as busy for the keep-alive service and are named in its notification; a session idle except for them shows as working (`SessionState.shownStatus`, "1 shell running" on its card). Replayed history has no task lines, so old background calls show a plain ✓.
- Subagent text and thinking are not sent unless `--forward-subagent-text` is passed; cards show tool calls and the final report only.
- `bypassPermissions` needs a launch flag; Auto uses the CLI's `auto` mode instead.
- Picking the model when starting a session is not in the New session sheet yet (switching before the first message works).

## Control requests for configuration

Probed on the phone on 30 Oct 2026 with Claude Code 2.1.314 (`docs/claude-config.md`, step 0). No user message was sent, so no model call and no usage.

- **Measured** means sent to a live `claude -p` stream-json process. Unless a row says otherwise, that process ran with a temporary `HOME` and `CLAUDE_CONFIG_DIR` holding no login, against throwaway servers and a local mock OAuth server. Nothing in it touched a real account.
- **Measured, personal** means the personal account's default config, in a temp folder. Only `initialize`, `mcp_status`, `get_settings`, `get_status`, `get_skills_dialog`, one `mcp_authenticate` and the CLI's `claude mcp list` ran there.
- **Read from binary** means taken from the schemas and handlers inside the binary and never called.

A process with no user message waits on stdin, answers control requests, and exits 0 when stdin closes (measured, temporary config, with `--no-session-persistence`, which is what `ClaudeConfigProbe` runs). A second `initialize` also succeeds, but a session keeps the first reply's `account` and `claude_code_version` in `SessionState.identity`. `account` is `{email, organization, subscriptionType, tokenSource, apiKeySource, apiProvider}` in the binary's schema; with no login, only `tokenSource` and `apiProvider` came back. The app reads the first three.

In a session these requests use the id prefix `pr-config-` (`Session.configRequest`): the reply goes to whoever asked and doesn't touch `SessionState` or `lastActiveAt`. Code: `claudeconfig/`, plan in `docs/claude-config.md`.

Replies have the shape `{"type":"control_response","response":{"subtype":"success|error","request_id":"<id>","response":{...},"error":"..."}}`. A success with nothing to say has no `response` body. An unknown subtype answers `Unsupported control request subtype: <name>`, which is the feature-detection signal.

None of these are stable contracts. `get_status`, `get_memory_dialog` and `get_skills_dialog` are marked `@internal` in the binary.

### MCP servers

| Request | Fields | Reply | Behaviour |
|---|---|---|---|
| `mcp_status` | `liveReply` (bridge only, ignore) | `mcpServers: [entry]`, see below | Measured, personal. About 0.1 s. Connectors are absent at first, listed by 25 s. |
| `mcp_reconnect` | `serverName` | Empty success once connected, else error with the cause | Measured. `Server not found: x`, `Server status: needs-auth`, or the connection error (`ECONNREFUSED: ...`, `ENOENT: ...`). A disabled server: `MCP server x is disabled — enable it (mcp_toggle) before reconnecting`. |
| `mcp_toggle` | `serverName`, `enabled` | Empty success | Measured on throwaway servers. Status becomes `disabled` and tools go at once; `enabled:true` connects it again. **It persists, per folder:** written to `projects[<cwd>].disabledMcpServers` in the account's `.claude.json`. A second process in that folder still shows `disabled`; a process in another folder shows `connected`. Not tried on a connector; read from binary, it takes the same path. |
| `mcp_set_servers` | `servers: {name: config}` | `{added, removed, errors}` | Measured. Replaces only the set added through this request: `{}` removed those and left `--mcp-config` servers running. Added servers show scope `dynamic`. Not written to `.claude.json` or `settings.json`, so session-only. |
| `mcp_read_resource` | `serverName`, `uri` (`ui://` only) | The resource | Read from binary. Untrusted HTML, render sandboxed. |
| `mcp_call` | `tool` (`mcp__server__name`), `arguments`, optional `input_files`, `output_files`, `expires_at`, `timeout_ms`, `result_not_for_model` | Tool result | Read from binary. Calls a tool with no model turn. |
| `channel_enable` | `serverName` | | Read from binary. |
| `set_mcp_permission_mode_override` | `serverName`, `mode` (`default`, `auto`, null), `askForTools` | | Read from binary, `@internal`. |
| `mcp_message` | `server_name`, `message` | | Read from binary. For SDK in-process servers, not for us. |

`mcp_status` entry, from the schema: `name`, `status` (`connected`, `failed`, `needs-auth`, `pending`, `disabled`), `serverInfo` (`name`, `version`), `error`, `error_code`, `config`, `transport`, `sessionProxied`, `urlHost`, `scope`, `source`, `tools` (`name`, `description`, `annotations`, `_meta`), `capabilities`. `error_code` values: `CLAUDEAI_BEARER_REJECTED`, `FIRST_PARTY_AUTH_REJECTED`, `APPROVAL_REQUIRED`, `CLIENT_REGISTRATION_UNAVAILABLE`, others may be added.

Measured per kind:

| Kind | `scope` and `source` | `transport` | `config` keys | Other keys |
|---|---|---|---|---|
| `claude mcp add -s user` | `user` | `stdio` or `http` | `type`, `command`, `args` (stdio); `type`, `url`, `headers` (http) | |
| `claude mcp add` (local) | `local` | as above | as above | |
| `.mcp.json` | `project` | as above | as above | |
| `--mcp-config`, `mcp_set_servers` | `dynamic` | as above | as above | |
| claude.ai connector (personal) | `claudeai` | `claudeai-proxy` | `type`, `url`, `id` | `sessionProxied: true` |

**`config.headers` carries the header values in clear, and so do `config.args` and a query string in `config.url` (measured with fake values). `config.env` is left out.** Never log a raw `mcp_status` reply or put `config` in the UI. `McpParser` copies an allowlist of fields and drops the rest, so `config` never leaves the parser. Claude Code redacts its own `error` text (`?token=REDACTED`); the parser scrubs it again rather than rely on that.

Plugin servers are named `plugin:<plugin>:<server>`, seen once through `claude mcp list`. A plugin server with the same command as a manually configured server is dropped silently (`Suppressing plugin MCP server`, read from binary). That happened to our test plugin, so a plugin server's `mcp_status` row was not measured here.

### MCP sign-in

| Request | Fields | Reply | Behaviour |
|---|---|---|---|
| `mcp_authenticate` | `serverName`, optional `redirectUri` | See below | Measured, on the mock and on one connector. Errors: `Server not found: x`, `Server type "stdio" does not support OAuth authentication`, `SDK auth failed: ECONNREFUSED...`, `MCP server x is disabled — enable it (mcp_toggle) before authenticating`. |
| `mcp_oauth_callback_url` | `serverName`, `callbackUrl` | Empty success after the token exchange | Measured on the mock. Errors: `No active OAuth flow for server: x`, `Invalid callback URL: missing authorization code...`, `Callback URL not accepted: ... (OAuth state mismatch)...`. A rejected URL leaves the sign-in waiting. |
| `mcp_clear_auth` | `serverName` | Empty success | Measured on the mock: a connected server drops to `needs-auth`. Read from binary: any type other than `sse`/`http` answers `Cannot clear auth for server type "..."`, which includes connectors. Never called on a real account. |

**claude.ai connector** (measured, personal, `claude.ai Google Drive`, not completed):

```json
{"requiresUserAction":true,"callbackExpected":false,"authUrl":"https://claude.ai/api/organizations/<org>/mcp/start-auth/<id>?product_surface=<entrypoint>"}
```

Answered in 0.1 s. Status stayed `needs-auth`. There is nothing to send back: `callbackExpected:false`, and the sign-in happens on claude.ai in a browser. The URL is built from the org id and connector id with no token, so it relies on the browser's claude.ai session. Not measured: opening the URL, what happens if the browser is signed in to another account, and whether `mcp_reconnect` in the same process picks up the result. Claude Code's own CLI text says "Once authorized on claude.ai, the connector will be available the next time you start Claude Code", so a new process is the safe assumption.

**OAuth server configured locally** (measured against a mock that accepts any redirect URI, no real provider and no real browser):

```json
{"authUrl":"http://.../authorize?client_id&code_challenge&code_challenge_method&redirect_uri&resource&response_type&state",
 "requiresUserAction":true,"callbackExpected":true,"redirectScheme":"localhost","state":"...","callbackPort":52072}
```

| Route | Steps | Result |
|---|---|---|
| Localhost | Without `redirectUri`, Claude Code registers `http://localhost:<callbackPort>/callback` and listens there. An HTTP GET on it (done with a script, standing in for the browser redirect) | Connected by itself within 2 s |
| Callback URL | Same flow, send the redirect through `mcp_oauth_callback_url` | Tokens stored but status stays `needs-auth`. **`mcp_reconnect` must follow**, then `connected` |
| Custom scheme | `redirectUri: "app.pocket://mcp-oauth"`: reply has `redirectScheme:"custom"`, no `callbackPort`. Then `mcp_oauth_callback_url` and `mcp_reconnect` | Connected |

The process that called `mcp_authenticate` must stay alive until the redirect. From the binary: if the provider rejects the custom URI, Claude Code falls back to localhost, and `redirectScheme` says which one applies. A server with a pre-set `oauth.clientId` ignores `redirectUri`. `claude_authenticate`, `claude_oauth_callback` and `claude_oauth_wait_for_completion` do the same for the Claude login itself (read from binary, not used).

Claude Code also keeps a cache at `<config dir>/mcp-needs-auth-cache.json`, seen holding one connector's entry. How it affects `mcp_status` wasn't established.

### Plugins and skills

| Request | Fields | Reply | Behaviour |
|---|---|---|---|
| `plugin_install` | | `Unsupported control request subtype: plugin_install` | Measured. **Not a control request.** It is a `system` stream line (`status`: `started`, `installed`, `failed`, `completed`) sent during headless install with `CLAUDE_CODE_SYNC_PLUGIN_INSTALL`. There is no install request: install through the CLI. |
| `reload_plugins` | optional `hold_on_cache_impact`, `add_plugins` | `commands`, `agents`, `plugins` (`name`, `path`, `source`, `version`), `mcpServers`, `error_count`; `held`, `cache_impact` when asked | Measured. A plugin installed or disabled by the CLI while the session runs appears or goes without a restart. `plugins` also lists built-ins (`...@builtin`). |
| `reload_skills` | | `skills: [{name, description, argumentHint}]` | Measured. Includes the built-in skills, unlike `get_skills_dialog`. |
| `get_skills_dialog` | | `skills: [{name, display_name, description, source, tokens, state, locked_by, advertised, handles}]` | Measured, also personal. `state`: `on`, `name-only`, `user-invocable-only`, `off`. `locked_by`: `author`, `plugin`, `policy`, `flag`, `reserved-name`. Read-only. Lists no built-in skills, and a skill added on disk shows only after `reload_skills` (measured, temporary config). |
| `reload_output_styles` | | `available_output_styles`: names | Measured: `default`, `Proactive`, `Concise`, `Explanatory`, `Learning`. `initialize` has the same list and `output_style`. |

To change a skill's state, pipe `{"name":"foo","state":"off"}` (optional `handles`) to the hidden `claude edit-skill-overrides --json`. Measured: it answers `{"ok":true,"written":{...},"changed":true}` and writes `skillOverrides` to the `.claude/settings.local.json` of the folder it runs in, so it is per folder. The next `get_skills_dialog` shows `off` and `advertised:false`. Its help says it is for the VS Code extension.

### Settings and status

| Request | Fields | Reply | Behaviour |
|---|---|---|---|
| `get_settings` | | `effective`, `sources: [{source, settings}]`, `applied` (`model`, `effort`, `advisor`, `ultracode`, `ultracodeRequested`, `ultracodeAvailable`); `errors` when settings are invalid | Measured, also personal. `source`: `userSettings`, `projectSettings`, `localSettings`, `flagSettings`, `policySettings`, low to high priority. Only sources with settings are listed. |
| `apply_flag_settings` | `settings` | Empty success | Measured with `model`, `outputStyle`, `effortLevel`. Shows up under `flagSettings`, no file written, so session-only. `applied.effort` changes at once. A null value clears the key. **It answers success for anything:** an unknown effort level is dropped, and an unknown style name is kept as it is, so read the result back. |
| `update_settings` | `source`, `settings` | Empty success | Measured. Only two writes are allowed: `localSettings` with `outputStyle` (into `<cwd>/.claude/settings.local.json`) and `userSettings` with `effortLevel` (into `settings.json`, under `modelSettings.<current model>`). Others: `update_settings keys not allowed: model`, and `outputStyle` under `userSettings` is refused too. Values must be strings: `update_settings values must be strings (deletion is not supported)`. A new process starts with both saved values; **the running process keeps its `applied.effort`**. |
| `get_status` | | `sections: [{title, rows: [{label, value}]}]` | Measured, also personal. The terminal's `/status` rows, as text. |
| `get_memory_dialog` | | `files`, `folders`, `memories`, `auto_memory`, `auto_dream` | Measured. `files` rows: `kind`, `path`, `label`, `description`, `exists`. |
| `get_hooks_listing` | | `events`, `hooks`, `eventCatalog`, `policy` | Measured. |
| `list_permission_rules` | | `state: {rules, workspaceDirectories, originalCwd, managedOnly}` | Measured. |

### Starting stdio servers on the phone

The launcher unsets `LD_PRELOAD` (the bionic `libtermux-exec` would crash the glibc binary) and `/usr/bin/env` doesn't exist on Android, so anything that relies on Termux rewriting shebang paths fails. Measured:

| Server command | Result in `mcp_status` |
|---|---|
| Script with `#!/usr/bin/env node` | `failed`, `ENOENT: no such file or directory, posix_spawn '<script>'`. With `--mcp-config` the message says `'stdio'` instead of the path. Same text as a file that doesn't exist, so it is misleading. |
| Same, with `LD_PRELOAD` in the server's `env` | `failed`, same error |
| `node script.js` | `connected` |
| Script with `#!<PREFIX>/bin/env node` | `connected` |
| `sh -c "exec script"` | `failed`, `Connection closed` |
| `sh -c "exec script"` with `env: {"LD_PRELOAD":"<PREFIX>/lib/libtermux-exec.so"}` | `connected` |
| `npx --no-install thing` (local package, `#!/usr/bin/env node` bin) | `failed`, `Connection closed` |
| Same, with that `LD_PRELOAD` in `env` | `connected` |

Not measured: `npx -y` downloading a package from the registry, including whether the download beats the connect timeout.

### `.mcp.json` in `-p` mode

Measured: a hand-written `.mcp.json` in a new folder **connects as soon as `claude -p` starts**, with no approval asked and `hasTrustDialogAccepted` false, while `claude mcp list` in the same folder calls it `Pending approval`. Opening a chat session in a cloned repo therefore runs that repo's MCP server commands.

## Dictation (R13)

A mic inside the chat box (`dictation/Dictation.kt`, `ui/chat/MicButton.kt`). Tap: Android's SpeechRecognizer, on-device (`createOnDeviceSpeechRecognizer`, API 31+) when the phone has it, with `EXTRA_PREFER_OFFLINE`; if that fails for a missing language pack or a service error, it retries once with the default recognizer online. Partial results stream into the box after whatever was typed; tap again or stop talking to finish. Never sends. Sending a message cancels listening. First tap shows a plain reason, then the system prompt; after a second denial a dialog offers the app's settings. `RECORD_AUDIO` and a `<queries>` entry for `android.speech.RecognitionService` (Android 11+ visibility) are in the module manifest.

## Attachments (R16, decision 24)

"+" left of the chat box opens Photos (Android photo picker, no permission), Files (`OpenMultipleDocuments`) and Commands. `sessions/attachments/Attachments` copies each pick to `~/.pocketrocket/attachments/<session>/<time>-<name>`; photos over 1568 px or 3.5 MB, and types the API cannot take inline (HEIC), become a 1568 px JPEG. `AttachmentMessage` builds the user turn as content blocks: images (`image`) and PDFs up to 20 MB (`document`) as base64, then the typed text, then a text block starting `[Attached on this phone]` with every path, so Claude can open other files with Read and the transcript replay can show the attachments again. The encode runs off the main thread.

Also in the sheet: Camera (writes to `<time>-photo.jpg` in the session folder through PocketRocket's FileProvider, `${applicationId}.pocketrocket.files`, which only exposes `~/.pocketrocket/attachments/`), Paste (shown when the clipboard description has a non-text type, so the clip is read only on tap), and the latest 8 screenshots from MediaStore (`RELATIVE_PATH LIKE %Screenshots%`), with photo access asked on tap. A tap on a sent attachment opens it with ACTION_VIEW. Share to PocketRocket is `ui/share/ShareActivity` (label "Send to session", translucent): it copies shared files into the picked session and hands them to its composer through `PendingAttachments`; shared text goes into the box.

## Status

Starting, Ready (no message yet), Working, Needs you (permission), Done (turn finished), Error (failed turn or the process exited), Paused (restored after an app restart, no process yet).

## Persist and resume

- Real sessions (not demos) are saved to `files/pocketrocket/sessions.json` on every change (debounced 300 ms, temp file + rename): id, Claude session id, title, folder, profile, created and last-active times, last status, last line. Code: `sessions/store/SessionStore.kt`.
- At app start (`PocketRocket.init` from `TermuxApplication`) the list comes back into the grid as Paused, with no processes. Paused cards sit on a quieter surface with a lilac "Paused" chip; the summary line counts them.
- History comes from Claude Code's own transcript, `<config dir>/projects/<encoded cwd>/<session id>.jsonl`, where the cwd has every non-alphanumeric character turned into `-` (`~` is `-data-data-com-termux-files-home`). Checked on the emulator, 2.1.312. Its `assistant` lines and tool-result `user` lines have the stream-json shape, so they replay through `StreamJsonParser` and `SessionReducer`; typed prompts are `user` lines with string content; `isSidechain`, `isMeta`, attachments, API-request records and queue operations are skipped. "Allowed Bash" notices are not in the transcript, so they don't come back. Code: `sessions/history/Transcript.kt`.
- Opening a paused session (it comes in front in the pager) starts `claude ... --resume <id>`; sending a message to a session whose process died does the same. With no transcript yet (never sent a message) it starts with `--session-id` again. The CLI keeps the same session id on resume.
- Long-press a card → "End session" confirm: kills the process, removes it from the grid and the saved list. The transcript stays.

## Running in the background

- No new service: `TermuxService` (Termux's foreground service, type `specialUse` on Android 14+) asks `keepalive/SessionKeepAlive` whether any session is busy (Starting, Working or WaitingForPermission) and does not stop while one is. Its notification reads "2 sessions working · 1 needs you" (plus "· N terminals" when terminals are open) and opens PocketRocket's Home.
- It holds its own partial wake lock (`pocketrocket:sessions`) under the same rule, separate from Termux's user toggle; both go once every session is idle or done. Exit on that notification pauses every session. See `docs/health.md`.
- Hooks in upstream files: a few lines in `TermuxService` (host, stop condition, notification text, wake lock) and one call in `TermuxApplication`.

## Notifications (R11)

- Channels "Needs you" (high) and "Finished" (default). Needs you: a permission ask (title "<session> needs you", text "Allow Bash?  touch x", profile as subtext) with Deny and Allow actions that answer without opening the app (ignored if the question is stale). Finished: a turn ending Done or Error ("<session> is done" / "hit an error", last line). One per session, replaced as it moves on, grouped under a summary. Code: `notifications/`.
- Not posted for the session on screen; opening a session clears its notification; answering in the app clears the ask.
- Tapping opens `SessionActivity` on that session (jumps the pager if it is already open).
- Permission: the app targets API 28, so Android has no runtime POST_NOTIFICATIONS prompt for it. At the first session start, if notifications are off, a rationale dialog offers the app's notification settings (asked once).

## Memory per session

Debug builds log each running session's process-tree RSS once a minute: `adb logcat -s PocketRocketMemory`. Measured on the arm64 API 36 emulator (29 Oct 2026), two idle sessions after short turns: **182–197 MB RSS per session** (one `claude` process each, no children), 380 MB for two.

## Real vs fake

- Real: `ClaudeProcessTransport` runs the CLI. Verified on the emulator (signed in, 29 Oct 2026): two sessions in `~` at once, each asked to `touch` a file, both showed Needs you, Allow in each, both files created and both turns ended Done.
- Fake: `FakeClaudeTransport` replays hand-written stream-json (`sessions/demo/DemoConversation.kt`) with the same line shapes, reacting to user messages, Allow/Deny and Stop. "Add demo sessions (no login)" in the New session sheet adds four: one waiting for permission, one working for about two minutes, one done, one failed.

## Running the real tests again

1. Install the debug APK, sign in (Settings → Account).
2. Sessions → New session → folder `~` → Start Claude. Send: `Use Bash to run touch pr-a.txt then say done`.
3. Back, start a second session the same way with `pr-b.txt`.
4. Both cards turn amber (Needs you). Open one, Allow, swipe or tap the chip to the other, Allow. Both end Done; `ls ~` shows both files.
5. Always allow: send `Use Bash to run touch pr-aa.txt`, tap "Always allow `touch pr-aa.txt`", then `Run the same touch command again`. The second run does not ask; `~/.claude/settings.local.json` has `Bash(touch pr-aa.txt)`.
6. Streaming: send `Without tools, write about 200 words on the history of C`. The reply grows in place and ends scrolled to its last line.

## Verified on the emulator (29 Oct 2026)

Two real sessions; app in background; a Bash ask in one → "home 2 needs you" with Deny/Allow appeared, the service notification read "2 sessions · 1 needs you"; tapping it landed in that session and cleared it; Allow ran the command. Allow from the notification action in the other session also worked and a "home is done" notification followed. `am force-stop com.termux`, reopen → both back as Paused with their last lines; opening one showed the full history and ran `--resume`; "What word did I ask you to remember?" answered from before the restart. Ending a running session from the grid killed its process and the service stopped. Re-checked after rebasing on the chat polish (streaming, always allow): background ask → notification → Allow, force-stop, reopen, history replays with no duplicate streamed text, and the resumed conversation (`--resume` plus `--include-partial-messages`) answers from before the restart. Only the session list is saved; chat items and pending permissions are not (history comes from the transcript), so the new ChatItem and PendingPermission fields need nothing in the store. Not verified: hours with the screen off on a real phone (Doze, OEM battery killers).

## Not done yet

- A session that was waiting for a permission when the app died comes back Paused; the question is lost (the CLI re-asks if the turn is retried).
- Dictation was only checked on the emulator up to recognition (permission flow, listening state, fallback to the online recognizer, "didn't catch that" hint); words actually landing in the box needs a phone with a working mic.
- Tool input is not streamed (`input_json_delta`); tool cards appear when the block is complete.
- If you rewind and then leave without sending anything, a restart shows the old branch again (the transcript records no rewind marker we read).
