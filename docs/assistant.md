# Assistant: plan

**Hidden for now (decision 111).** Rocket is off for everyone and has no way in: no tab, no Settings card, not offered as the phone's assistant. The code stays. To bring it back, set `ASSISTANT_SHOWN` true in `AssistantPrefs.kt` and take `android:enabled="false"` off `AssistantActivity` in the manifest.

Decisions 51 and 52. A voice assistant, called Rocket, that supervises Claude sessions: ask what's happening, hand real work to sessions, start agents, change settings. A waiting session's own permission prompt is answered in that session, not here (decision 59). It is a remote control for the app: you say or type what you want and it uses the app's own actions. All six steps are written on `feature/rocket` (voice in the app, the voice picker, the popup, Android's assistant setting, the earbud long-press, Hey Rocket, allowing by voice, the fingerprint popup, the lock screen fix, the brain, its tools and memory, and settings by voice). Nothing here has been tested on the phone, so the Android and Xiaomi points below are what we expect, to be checked in the final pass (`docs/rocket-final-pass.md`).

Designs: `docs/ux-mockup/rocket/` (`In-*` boards, and the clickable `prototype.html`). The designs' Sessions, Workspaces and Settings boards are not a redesign: those screens stay as they are in the app, and only gain what this plan adds.

## Screens

- **Rocket tab (decisions 59 and 60):** first in the bottom bar, and where the app opens. A plain chat built from the session chat's own parts: his questions in the same bubbles, Rocket's replies as full-width text like Claude's, and the same chat box with no `+`. What used to go on a card shows inline (`ReplyText`): commands, output and diffs as code blocks, session status as a list, Claude's long answer in full. Every message can be copied: long-press his bubble for Copy, long-press a reply to select text. An empty chat shows a few suggestions. The round button is the talk button while the box is empty: tap to speak, tap again to stop listening or to stop it speaking. No orb, no full-screen listening, no Allow, Deny or Review: a waiting request is answered in that session's chat or notification.
- **Quick controls:** the sliders button at the top of the Rocket tab opens a sheet styled like the session controls sheet. Tiles: Hey Rocket and Speaker. Then Claude login, the conversation (context bar, Compact, Clear conversation), Memory notes, All assistant settings. Clear conversation asks first, then empties the chat and starts the brain on a new Claude conversation (`AssistantBrain.startFresh`, the same path a changed login takes). Memory notes stay.
- **Settings › Assistant:** on/off switch, login, model, voice, wake options, when it listens, locked-phone rules, Projects, Activity. Off removes the Rocket tab, opens the app on Sessions and runs nothing.
- **Popup:** a small chat card at the bottom of the screen, over other apps and the lock screen, for the power button, earbud button and wake word. It listens straight away, shows what was heard and the reply in the chat's own text, and says the reply. Same conversation as the tab, showing only what's said in this popup. It closes itself a few seconds after it has nothing to do, and when you leave it. Approval shows the command large.
- **No mic for Rocket on Sessions or inside a chat.** Inside a chat, the composer mic stays dictation only. A chat records "Allowed by voice" and "Sent through Rocket".

## Shape

```
 Triggers                 assistant/
 wake word ─────┐
 power button ──┼─▶ VoiceLoop ─▶ Router ─┬─▶ app-only commands ─────────┐
 earbud button ─┤   (listen, think,      │   status, stop           │
 notification ──┘    speak, confirm)     │                              ▼
                                         └─▶ AssistantBrain ─▶ tools ─▶ SessionManager
                                             (Claude session)            (already exists)
                                                   │ its own sensitive actions
                                                   ▼
                                              ConfirmGate (app code, asks you itself)
```

One module, `assistant/`, one concern per file:

| Piece | Job |
|---|---|
| `RocketScreen` (`ui/rocket/`) | The tab: a chat made of the session chat's bubbles, replies and `Composer`, plus the quick controls sheet. `ReplyText` turns a reply and its card into Markdown |
| `AssistantPrefs` | The switches, one store for the sheet and Settings |
| `AssistantActivity` | The popup with the live transcript. Shows over the lock screen. Handles the Android assist request |
| `Conversation` | What was said and shown, shared by the tab and the popup, kept across restarts, emptied by Clear conversation. What a card quotes (commands, output) stays in memory and is never written to disk |
| `ActivityLog` | Everything Rocket did, for Settings › Assistant › Activity |
| `WakeWord` | Decides when the service runs (decision 54), and what the Hey Rocket switch shows |
| `WakeWordService` | Foreground service with the mic open, running the wake word model on the phone |
| `WakeDetector` | The small interface over the detector, and sherpa-onnx behind it |
| `WakeModel` | Fetches and checks the detector's library and model, only when asked |
| `VoiceLoop` | The states: idle, listening, thinking, speaking, waiting for yes or no. Reuses `dictation/` where it fits; Android's text-to-speech for speaking |
| `Router` | Sends simple phrases to app-only commands and everything else to the brain. Matches spoken names against real session names |
| `AssistantBrain` | Starts and stops the Claude session, and turns one question into one answer |
| `BrainFiles`, `BrainReply` | Its flags and `CLAUDE.md`; what its words may become on screen and aloud |
| `AssistantTools` | The tools that session can call, and their rules. `ToolHost` is the app as they see it (`AppToolHost` in `RocketServices`) |
| `McpServer` | MCP's HTTP transport on 127.0.0.1, and the hook's endpoint |
| `AssistantMemory` | Notes, nicknames and projects; the secret check |
| `AutoModeNet` | What the `PreToolUse` hook answers for an agent in Auto |
| `SessionChanges` | "Show what changed", from git, with no Claude |
| `ConfirmGate` | The only code that can say yes by voice: what's shown and said, the yes/no parsing, `GateVoice` |
| `SensitiveActions` | What each request or action needs: spoken yes, screen, or fingerprint. Plain rules nothing can change |
| `Approvals` | One approval at a time; `confirm(action)` is what step 5's `ask_to_*` tools call. Ignored for 60 seconds is a no |
| `ApprovalActivity` | The A-Approve screen, and the fingerprint prompt (`Fingerprint`). Never over the lock screen |

## Uses the Claude subscription

The brain is an ordinary Claude Code session: the same binary, the same sign-in and the same `SessionManager.startClaude` path as every other session. There is no API key and no separate billing.

- **Account:** Default (follows the default Claude account), or a login picked in quick controls or Settings. Changing it starts a fresh Rocket conversation.
- **Model:** Sonnet 5.5 (`sonnet`), changeable in Settings (decision 61). 1.0.3's stored Haiku isn't carried over: the setting moved to a new key so the new default applies.
- **Folder:** `~/.pocketrocket/assistant`, with its own `CLAUDE.md` saying how to behave: quick lookups itself, real work handed to a session, answers only when asked. Temporary files go in its `scratch/`. Hidden from the session list.
- **Mode:** Auto, so it doesn't ask at each step. Its hook (Safety rule) still turns anything risky into a request.
- **Lifetime:** a live session takes 200 to 400 MB, so it starts on the first request that needs Claude, stops after a few idle minutes and resumes with `--resume`. The first such request after a pause will take a few seconds, so it says "one moment".
- **Memory:** one continuing conversation, not separate requests. `/compact` works in it (measured); that Claude Code compacts it on its own as it fills is expected and not yet seen. Session status is always read live from the app, so it can't go stale.
- **His setup, plus Rocket's server (decision 61):** `--mcp-config <rocket-mcp.json> --settings <rocket-hook.json> --allowedTools mcp__rocket__* --disallowedTools AskUserQuestion`. No `--tools`, `--strict-mcp-config` or `--setting-sources`, so his user settings, hooks, plugins, skills, MCP servers and claude.ai's connectors load as in any session, and Bash has every CLI on the app's PATH (`git`, `gh`, `dockito` and the rest). `--mcp-config` adds Rocket's server to his; `--settings` adds Rocket's hook to his settings and writes nothing to them. `AskUserQuestion` is left out so a question comes back as text that can be read aloud. `--disable-slash-commands` isn't used, as it also removes `/compact`. `MCP_TOOL_TIMEOUT` is raised, because a tool call is otherwise cut off at 60 seconds, which an approval can outlast. Checked on Claude Code 2.1.309 (`RealClaudeCheck`, on the computer with its own login): the `init` line lists Bash and the other built-ins, his MCP servers and plugins, and Rocket's tools, without AskUserQuestion; `echo` ran without a prompt and `git push --force` came back as a permission request.
- **Its permission requests** go to `Approvals`, which shows the real command and takes a fingerprint or a yes as `SensitiveActions` says. Anything but an allow is answered no, so the turn goes on and Claude can say it didn't happen.
- **Its words:** on a locked phone nothing Claude wrote is said or shown: "I have an answer. Unlock to see it." Unlocked, what looks like code is shown inline in the chat, not aloud and not to disk. `looksLikeCode` is a guess; the locked rule doesn't depend on it.
- **Limit reached:** status and stop don't use Claude, so they keep working. It says when the limit resets.

## Tools for the brain

`list_workspaces`, `list_sessions`, `read_session`, `session_context` (tokens), `compact_session`, `start_session` (folder, login, model and mode, as the New session screen takes them), `set_model`, `send_message`, `stop_session`, `ask_to_set_mode`. Git: `clone_repo`, which clones into a workspace with that workspace's GitHub account and the app's progress notification. Memory: `list_memory`, `remember_note`, `ask_to_save_habit`, `forget_note`, `remember_nickname`, `forget_nickname`. Settings: `list_settings`, `list_voices`, `set_theme`, `set_voice`, `set_quiet`, `set_rocket_model`, `ask_to_set_default_account`, `ask_to_install_tool`, `check_updates`, `ask_to_update`. A debug build adds `debug_confirm`, which shows any approval and does nothing. Each respects the per-project switch in Settings › Assistant › Projects: with it off, Rocket can only report that project's status.

These are the app's own actions, which a shell can't do. Everything else it does with Claude Code's own tools and his CLIs: the git tools (`list_repos`, `switch_branch`, `create_branch`, `list_prs`, `ask_to_push`, `ask_to_merge`, `ask_to_open_pr`, `ask_to_delete_branch`) were removed as duplicates of `git` and `gh` under the hook, and `ask_to_answer_permission` went with decision 59: a session's prompts stay in that session.

**Delegating:** quick lookups (a status, a file, `gh pr list`, `dockito ...`) it does itself. Code changes, builds, tests and anything long go to a session through `start_session` or `send_message`, with a brief, so the work shows in Sessions; it says what it started and stops. Asked later, it checks with `list_sessions` and `read_session`. It never volunteers updates (decision 59).

`start_session` checks the folder is still there (a workspace stays listed after its folder is deleted), and waits through the session's first two seconds rather than until it leaves Starting, since a launch that fails reads as ready for a moment first.

**Modes when starting a session:** Manual unless you say otherwise. Rocket never picks a less restrictive mode on its own. Manual and Plan start at once and it says what it started. Accept edits and Auto are read back and need a yes.

Plain phrases don't reach Claude: status, how full, what it wants, `stop X` and "show what changed" stay in `Router`, which reads nicknames itself. "Allow" and "deny" are no longer answered there (decision 59); they go to the brain like anything else, which has no way to answer a request.

What a tool returns from a session, a project or GitHub is wrapped in `<data>`, and `CLAUDE.md` says that's for reporting. That's guidance to Claude; the protection is that no tool can approve anything.

**Git:** clone, create a branch and listing just happen, naming the repo and account used. Only github.com repos are cloned, since git runs some other URLs as commands. Switching branch just happens unless there are uncommitted changes or a session is working there; then it stops and asks. With no account named it uses the workspace's default, and asks if a repo could belong to more than one.

**Settings (step 6):** the same server, in `SettingsTools`. Each change goes through the calls the Settings screens make (`SettingsControl`, `ClaudeAccounts.setDefault`, `GitHubAccounts.setDefault`, `Chromium.install`, `Updates.update`), so the screen follows, and each is written in Activity.

- **Just happen, and it says what changed:** theme, voice (only the phone's on-device voices), Quiet, and the model Rocket itself runs on. On a locked phone these take a fingerprint, like starting an agent: anyone nearby can talk to it.
- **Need a yes, through `Approvals.confirm`:** default Claude or GitHub account (the popup says which it replaces), installing Headless Chrome, and updates (the popup shows the versions from the Updates page's own rows). A locked phone unlocks first, as a push does. Installs and updates start and it says they aren't done yet; the page they belong to shows how they go.
- **PocketRocket's own update:** the popup says the app restarts and how many working sessions that pauses, and Android's own install screen still follows. If Android hasn't allowed the app to install packages, it points to Settings › Updates, which does the asking.
- **Never tools:** signing in or out, adding or removing accounts, tokens and passwords, the sensitive list, approval rules, the locked-phone rule, per-project switches, Rocket's own login, the assistant's off switch (turning it off takes away the fingerprint in the chat and the Auto hook), Hey Rocket and when it listens. A request gets a plain refusal naming Settings. It doesn't open the screen. `SettingsToolsTest` fails if a tool for one appears.
- **Left out because nobody asked:** Speaker and Dockito's default account. They're in Settings; it says so.
- **What Settings doesn't have:** there are no quiet hours on a schedule, only Quiet for one hour, on or off. There's no default model for new sessions: each picks its own on the New session screen, or `start_session`'s `model`. The setting that exists is the model Rocket runs on, and that's what `set_rocket_model` changes. It keeps the conversation, and its next start is on the new model.
- **No Claude:** `Router` handles what's said in so many words through `SettingPhrases`: dark, light or system theme, go quiet, Quiet off, and refuses logins, tokens and the safety rules outright. A bare "quiet" doesn't count, since it shuts the mic for an hour. With the mic shut, ending Quiet takes typing, or the tile.

## Memory

Rocket learns as you use it, in the conversation, with no setup.

- **Learning:** when a name is unknown or could match two projects it asks, then saves the answer ("nimbus" is `project_nimbus`, also "the Acme v2 client-facing app"). "Remember…" and "forget that" work directly. A repeated habit gets a question ("use Opus there from now on?") and is saved only on a yes. It always says what it saved.
- **Where:** a short notes file in `~/.pocketrocket/assistant`, loaded when the Claude session starts, and capped at about 20 notes because each adds to every request. Nicknames also go in a list the app reads itself, so `Router` resolves them with no Claude.
- **Settings › Assistant › Memory:** every note, to edit or delete, and Refresh projects, which rescans workspaces for names and short descriptions. New workspaces are picked up on their own.
- **Limits:** a note can't remove an approval, since `ConfirmGate` and the sensitive list don't read memory. Tokens and passwords are refused. Text from project files is stored as a description, never followed as instructions. Before acting it names the project it resolved ("starting in project_nimbus"), so a wrong note shows at once.

They are served by the app on `127.0.0.1` over MCP's HTTP transport with a random token, and wired into this session only. Other processes on the phone run as the same user and could read that token, so no tool grants anything on its own: see the next section.

## Safety rule

Claude never answers a permission request: other sessions' prompts stay in their own chat and notification (decision 59), and Rocket's own go to `Approvals`. `ConfirmGate` reads the real command from the request, says it aloud and listens for yes or no itself, outside Claude. Text in a session's output can't approve anything, and neither can a misheard word. Long or risky commands are not approved by voice: it says "check your screen".

As written in step 4: it reads the request's whole input, because the one-line summary stops at a command's first line. Only a whole final answer counts: "yes", "allow", "go ahead" and the like, or "no", "deny", "stop". Anything else, "yes but wait" included, is unclear: nothing is allowed, it says so, and the request stays open with the buttons. An explicit no denies at once. A chat records "Allowed by voice · 09:14". These lines live in the running chat like its other notices, so they're gone after a restart; Activity keeps the fact, without the command.

The same gate covers switching a session to Auto mode, since that removes later prompts. A message to a session already in Auto is read back first, typed too, because a misheard one would run with no later check; one that can't be read aloud is answered on screen. `ask_to_push` confirms through the gate and then pushes from the app, using that workspace's GitHub account, as the git panel's Push does.

| | Unlocked | Locked |
|---|---|---|
| Status, tokens, stop, deny | Just does it | Just does it, no code shown |
| Start an agent, message a session, compact, change theme, voice, Quiet or Rocket's model | Just does it | Fingerprint, or his own earbuds or car |
| See code, diffs, screenshots | Just shows it | Fingerprint |
| Allow, push, merge, open a PR, Auto and Accept edits, default accounts, installing tools and updates | Spoken yes | Fingerprint (unlock first, then the approval screen). The spoken yes over his own earbuds or car isn't built: see Open |
| Sensitive actions | Fingerprint | Fingerprint |

Voice recognition doesn't know whose voice it is, and neither does the wake word. Anyone nearby can trigger it, so the same limits apply however it was started.

### Sensitive actions

Destructive actions and messages to other people need a fingerprint every time, with the phone's PIN as the fallback: delete a branch, force-push, discard changes, send a message (Slack and the like). Anything Rocket doesn't recognise counts as sensitive.

A popup written by the app, not Claude, shows the exact action: the branch and repo, or the channel and message. Dismissing it or letting it time out means no. Away from the screen, Rocket says it needs a fingerprint and sends a notification that opens the popup.

The app decides what is sensitive. Claude and memory can't change that. Settings › Assistant › Sensitive actions shows the list, read-only for now; adding to it and removing a built-in with a fingerprint aren't built. On a locked phone, starting an agent, messaging a session and compacting take a fingerprint too; their tools ask for it through `RocketAction.WhileLocked`.

How far this reaches:

- **Rocket:** a hook, like agents in Auto, but stricter (decision 61). Its own session starts with `--settings ~/.pocketrocket/assistant/rocket-hook.json`, an HTTP `PreToolUse` hook to `/hook/rocket` that `AutoModeNet.decideForRocket` answers whatever the mode. "Ask" for: a command on the sensitive list (fingerprint), with `rm` and `rmdir` of paths that are all inside its `scratch/` or the temp folder let through; a push, `gh pr merge` or `gh pr create` (a yes); an MCP tool of his that isn't Rocket's and isn't named like a lookup (`get_`, `list_`, `search`…), since it could send a message; anything touching the app's own settings or Rocket's wiring (`shared_prefs`, the hook and MCP files); and on a locked phone, anything that acts, so only a fingerprint lets it run. Reading tools and Rocket's own tools pass, each of the latter checking for itself. The request then goes to `Approvals`. It reads what's written, so as for agents a script can get past it; `CLAUDE.md` tells it not to look for a way round a no, which is guidance, not protection.
- **Agents in Manual:** solid for what the list recognises. They stop and ask, and the fingerprint sits on that request, from Rocket and in the chat, where Allow would otherwise be the way round it. In the chat, a tool merely not recognised (an MCP tool, say) stays a tap, or every browser step would need a fingerprint; through Rocket it takes one. With Rocket off, the chat is as it was.
- **Agents in Auto:** a safety net only. While Rocket is on, every real session starts with `--settings ~/.pocketrocket/assistant/agent-hook.json`: an HTTP `PreToolUse` hook to the app, which answers "ask" for a command on the list. Claude Code then sends an ordinary permission request, so the fingerprint sits on it as in Manual, and until he answers nothing runs. Measured on Claude Code 2.1.308 against the real server: it fires in Auto and follows a switch mid-session, "ask" reaches the request in Auto, a refusal stops the command, and other commands run. `--settings` adds to his own settings and writes nothing to them, and another settings source's hook still fired alongside. It fails open: if the app's server isn't there, or errors, the tool runs. An agent with a shell can reach the same result another way, such as a script, which a pattern won't recognise. Settings says so. Agents started before Rocket was turned off and on again have a dead hook until they restart. Protected branches on GitHub are the real protection for important work.

## Wake word

Android's built-in hotword system is for preinstalled assistants, so a sideloaded app runs its own detector. The mic audio goes to a small model on the phone. Nothing leaves the phone until the word is heard.

- **Engine:** sherpa-onnx 2.0.1 keyword spotting with its English GigaSpeech model: custom words without training. openWakeWord remains the fallback if real voices don't work well: it needs a model trained for the word.
- **Download, not bundled:** the first time it's turned on, the app shows the size, asks for the mic, and fetches 53 MB from sherpa-onnx's GitHub releases: the arm64 library in its static build (35 MB) and the model (18 MB). Both archives and every file kept are checked against SHA-256s pinned in `WakeFiles`, and the files again before each load. It takes 29 MB on the phone. Its default-on setting reads Off until this is done. Arm64 only.
- **Licences:** sherpa-onnx is Apache-2.0, and the static library includes ONNX Runtime (MIT). The model's README says Apache 2.0. Its classes are kept in `com/k2fsa/sherpa/onnx/KeywordSpotter.kt` with their notice, since the library looks them up by name. Nothing else of theirs is in the APK.
- **Word:** two or more distinct sounds, so "Hey Rocket", not "Rocket". Short everyday words trigger by accident.
- **After the word:** the mic closes and the popup opens, then Android's speech recognition takes over, on-device when the language pack is there.
- **Opening the popup:** Android 10 and later stop a background app opening a screen unless it has "Display over other apps" (Settings › Assistant has a row for it). Without that it posts a full-screen notification: over the lock screen or with the screen off the popup opens, and with the screen on it's a banner to tap.
- **When it runs:** only while he has switched it on (decision 62). Off by default, and 1.0.3's stored "on" isn't carried over (new key). Nothing starts it on its own: no sessions-or-charging rule. It stops with Quiet, with Hey Rocket off and with the assistant off, and lets go of the mic while another app or a call has it. After the app has been killed it comes back when the app next opens, if it was on.
- **Why it never fired on 1.0.3 (found 31 Oct 2026, on a computer with sherpa-onnx 2.0.1 and this model, synthetic voices from macOS `say`):** the keyword tokens are right (`▁HE Y ▁RO CK ET`, exactly what the model's `bpe.model` gives "HEY ROCKET"), but (1) the model has no level normalisation: the same clips heard 18 of 22 at about -15 dBFS fell to 12 at -35 and 2 at -45, and VOICE_RECOGNITION has no gain control, so speech at arm's length sits down there; (2) one stream has blind spots about every 1.6 s of listening: the word starting 1.4–1.5, 3.0, 4.6 or 6.2 s in was heard 1 in 11 against 7 in 11 elsewhere, and the stream on the phone runs for hours; (3) a heard word could still show nothing: Xiaomi's "Display pop-up windows while running in the background" blocks a background start silently even with "Display over other apps", and no notification followed. Fixes: `WakeGain` lifts quiet speech (gain from a slowly falling peak, up to 30x); `SherpaWakeDetector` runs two streams 0.8 s apart; `ScreenLaunch` posts the notification when the popup hasn't come up 1.5 s after asking; and if VOICE_RECOGNITION gives three seconds of exact zeros, it moves to MIC. With gain and two streams, the same set at -35 dBFS went from 12 to 17 of 22, with false triggers on near words ("hey racket", "hey Robert") 3–5 of 66.
- **Readout:** under the switch in Settings › Assistant while it's on: the mic level now and the loudest of the last 3 s, before the gain, the gain, the source, and how many times and when it was last heard. No level means no audio reaches it; heard with no popup means Android blocked the screen. On a Xiaomi phone it names the pop-up permission.
- **Checked on the emulator:** a debug build's `WakeFileCheck` receiver runs the real downloaded library, gain and detector on WAV files (`am broadcast -n app.pocket/com.pocketrocket.assistant.WakeFileCheck -a app.pocket.wake.CHECK --es wav <file or folder>`). On the arm64 emulator (API 36), 88 clips from macOS `say` (11 voices; 22 "Hey Rocket", 66 near words and others) made quiet (about -41 dBFS) after 1–6 s of faint noise: heard 15 of 22, with 4 false triggers, all "hey racket". The same files through sherpa-onnx 2.0.1 on the computer give exactly the same 15 and 4 with the new pipeline, and 10 and 2 with 1.0.3's, so the phone's library and JNI behave as measured. Synthetic voices, not his: his phone and voice are the real test. Switched on with the mic granted, the service records VOICE_RECOGNITION at 16 kHz mono and the notification reads "Listening"; switched off (the default), nothing starts.
- **Crashes:** native code runs in the app's process, so a crash there would take sessions with it. If the app dies in the first 20 seconds of a new detector's life, Hey Rocket turns itself off and says so, rather than crash on every start.

## Other ways in

- **Android's assistant:** the popup answers `ACTION_ASSIST`, so it can be picked as the phone's digital assistant app; only the person can pick it, from the row in Settings › Assistant. Where the phone sends the power button's long-press to that app, this opens the popup. It reads nothing from the assist request.
- **Not a `VoiceInteractionService`:** that also makes the service's recogniser the phone's default speech recognition, which would break `Dictation`, and a sideloaded app gets no hotword access from it.
- **Earbud button:** the popup also answers `VOICE_COMMAND` and `VOICE_SEARCH_HANDS_FREE`, which Android sends for a long-press on a headset button. Android may ask which app to use. No media session is held and no media button handled, so play, pause and skip stay with the music app. Earbuds that keep the long-press for their own features never reach it.

What it costs:

- **Battery:** the phone's processor has to stay awake, with no low-power chip helping as Google's assistant gets. Not measured yet.
- **Mic indicator:** Android's privacy dot stays on while listening.
- **In a pocket:** the phone mic hears poorly there. Listening through earbuds holds their call mode open, which drains them and lowers music quality. So with earbuds in, the earbud button is the better trigger.
- **Calls and recording:** the detector pauses when another app takes the mic.
- **Xiaomi:** may stop the service with the screen off (see Open).

## Request and response (no announcements)

Decision 59: Rocket answers only when asked. It doesn't speak when a session needs you, fails or finishes, posts nothing into its own chat about sessions, and doesn't listen again after it has spoken. Ask and it checks then. A session's permission prompt stays in that session's chat and its notification. The `Announcer`, its switches in Settings and the Announcements tile are gone; the `assistant_announce_*` keys are removed from the prefs on the next save.

## Voice

Android's built-in text to speech first. Rocket speaks only when he spoke to it, with the mic or the wake word; a typed question gets text only. With no voice picked it uses "Best on this phone" (`Voices.best`): the phone's language, its own country's accent first, then the highest quality the engine has, never the engine's default voice, which is often a low-quality one. Settings › Assistant lists the phone's on-device voices, each with a Play button, to pick another. Kokoro through sherpa-onnx isn't in the app; it could only be an optional download, to be judged by ear against these first. No cloud voices: Rocket reads commands aloud and they can contain secrets. Wording is written for speech (`git push origin feat/auth` becomes "push feat auth to origin"), except when confirming, which reads the command closely. Spoken names are matched against real session names.

## Build order

Each step ends in something he can try.

1. **Rocket tab:** the tab, Settings › Assistant, quick controls and `AssistantPrefs`. Typed questions answered by `Router` with no Claude: what's happening, how full is X. Off removes the tab.
2. **Voice in the app:** the mic on the tab, `VoiceLoop`, text to speech, voice comparison, the conversation with cards.
3. **Triggers outside the app:** the popup, Android's assistant setting, the earbud long-press and Hey Rocket. Written; the phone test, the battery measurement and every Xiaomi question moved to the final pass (`docs/rocket-final-pass.md`).
4. **Approvals for Rocket's own actions:** `ConfirmGate`, `ActivityLog`, the chat records, the lock screen fix and the fingerprint popup. Written; the test moved to the final pass (`docs/rocket-final-pass.md`). A notification's Allow asks Android for the unlock (`setAuthenticationRequired`, Android 12 on; AOSP's SystemUI has no target SDK check on it, read at its `main` branch), and `NotificationActionReceiver` checks `isDeviceLocked` itself whatever Android did. Private on the lock screen. Where the command isn't wholly shown, or it takes a fingerprint, the buttons are Deny and Review.
5. **Claude:** brain, tools, starting agents with any model and mode, clone and branches, unblocking, push, Auto mode, compact, Projects, memory, the hook for agents in Auto, the memory bar, and Show what changed. Written; flags and hook measured on Claude Code on a Mac with `RealClaudeCheck` (`ROCKET_REAL_CLAUDE=<scratch folder> ./gradlew :pocketrocket:testDebugUnitTest --tests '*RealClaudeCheck'`), the Android test moved to the final pass (`docs/rocket-final-pass.md`).
6. **Settings:** the settings tools. Written; the test moved to the final pass (`docs/rocket-final-pass.md`).

## Open

- Whether the earbuds and car exception can tell his own devices reliably. If not, fingerprint only. Step 4 is fingerprint only for now: Bluetooth can't be told apart yet, and a yes to a command it may not read aloud would be a blind one. The setting is stored and has no effect yet.
- Whether a real voice wakes it reliably, and how often other speech does. Tested only on synthetic voices on a computer.
- What Hey Rocket costs in battery.
- Whether the power button can reach a sideloaded assistant on Shankar's Xiaomi. HyperOS has its own power-button setting; we don't know what it offers.
- Whether the earbud long-press reaches the popup, on his earbuds and on Xiaomi.
- Whether Xiaomi stops the service with the screen off, and whether its "Display pop-up windows while running in the background" and "Show on Lock screen" permissions block the popup.
- Whether the flags and the hook behave on the phone's Claude Code as they did on the Mac's.
