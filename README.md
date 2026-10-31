# PocketRocket

[![Latest release](https://img.shields.io/github/v/release/dockito-ai/pocketrocket?include_prereleases)](../../releases)
[![Downloads](https://img.shields.io/github/downloads/dockito-ai/pocketrocket/total)](../../releases)
[![Stars](https://img.shields.io/github/stars/dockito-ai/pocketrocket?style=flat)](../../stargazers)
[![Licence: GPL-3.0](https://img.shields.io/badge/licence-GPL--3.0-blue)](LICENSE.md)
![Views](https://komarev.com/ghpvc/?username=ShankarKakumani-pocketrocket&label=Views&color=0e75b6&style=flat)
<!-- The Views badge counts README loads, not unique visitors. -->

Claude Code on an Android phone, in one app. The phone is the machine: repos, sessions and tools all live on it, and Claude runs there, not in the cloud.

PocketRocket is a fork of [Termux](https://github.com/termux/termux-app) and is licensed under the GNU General Public License v3 (see `LICENSE.md`). It is not affiliated with the Termux project; Termux's original README is kept at `docs/upstream-termux-README.md`, and bundled third-party software is listed in `THIRD_PARTY.md`.

> **A personal tool, still under development.** I built PocketRocket for myself, for my own phone and my own way of working. It is not a product, there is no support, and things change or break without notice. You're welcome to look around and try it, but expect rough edges.

## Screenshots

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/1-sessions.png" width="220" alt="Sessions list"><br><sub>Every session's state at a glance</sub></td>
    <td align="center"><img src="docs/screenshots/2-chat.png" width="220" alt="Session chat"><br><sub>Chat with tool calls and permission cards</sub></td>
    <td align="center"><img src="docs/screenshots/3-new-session.png" width="220" alt="New session"><br><sub>Start a session: folder, account, model, mode</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/4-settings.png" width="220" alt="Settings"><br><sub>Settings: accounts, health, tools, updates</sub></td>
    <td align="center"><img src="docs/screenshots/5-tools.png" width="220" alt="Tools"><br><sub>Tools: built in and optional</sub></td>
    <td align="center"><img src="docs/screenshots/6-tool-page.png" width="220" alt="Tool page"><br><sub>A tool page: what it does, size, install</sub></td>
  </tr>
</table>

## Why I made it

I do most of my work with Claude Code on a laptop. When I'm away from it, at a stop, travelling, waiting somewhere, I wanted to keep going: hand Claude a task, let it run for an hour, check back, answer a question, review the diff, push.

The Claude mobile app can run Claude Code, but in Anthropic's cloud, not on my own folders and accounts. Plain Termux stopped running Claude Code once it became a glibc-only binary, and even with the community workaround a raw terminal on a phone is painful to use.

My phone has 24 GB of RAM and 512 GB of storage. It is a perfectly good machine. So PocketRocket turns it into one: a native app around the real Claude Code CLI, with the parts I use on the laptop (several sessions, workspaces with repos, git, multiple accounts) made for a phone screen.

## What it does

- **First run sets everything up.** It installs Claude Code (downloaded from Anthropic and checked against their manifest, never bundled), git, gh, node and npm. Claude sign-in is one tap.
- **Chat with Claude Code, not a terminal.** A native chat drives the real CLI. It has permission cards with Allow, Deny and Always allow, streaming replies, subagent cards, a `/` command picker, attachments and mic dictation.
- **Many sessions at once.** Home shows every session's state: working, needs you, done, or error. "Needs you" and "Finished" notifications have Allow and Deny right on them. Sessions resume after Android kills the app.
- **Session controls.** A status line shows `folder │ branch │ model │ context`. You can switch between Manual, Accept edits, Plan and Auto modes, switch models, Compact, Stop, and Rewind to a message.
- **Workspaces.** Your own folder structure under `~/projects`, with several repos per workspace. You can clone from a URL or from your GitHub repos, and open a terminal in the workspace.
- **Git without leaving the app.** A git panel shows changes, staging, commits, push and pull, diffs, and GitHub-style file browsing.
- **Several Claude and GitHub accounts.** Each workspace picks its own Claude account and its own GitHub account. The GitHub account sets the commit identity, SSH key and `gh` login, so personal and work never mix.
- **Health screen.** It checks every battery, autostart, permission and setup item that can stop sessions in your pocket, with a Fix button for each. It also shows when and why Android last stopped the app.
- **Updates from Settings.** You can update Claude Code from Settings, and switch back to the previous version.
- **Raw terminal** whenever you want one. It is still Termux underneath.

Android only. iOS doesn't allow an app to run programs like this.

## Status

Under active development, and used daily on one phone (Poco F7, Android 16). Expect breaking changes. Releases are debug builds for arm64.

## Install

Download the APK from [Releases](../../releases) and install it. The first run takes a few minutes while it sets up.

The package id is `app.pocket`, so it installs next to the real Termux app. Termux's prebuilt packages have the `com.termux` path compiled in, so their prefix is rewritten when they are installed.

## Build

You need the Android SDK with NDK, and JDK 17 or newer.

```
./gradlew assembleDebug
```

- Docs for contributors and agents: `docs/BRD.md` (what and why), `docs/PROGRESS.md` (where it stands), `docs/decisions.md` (every decision), `AGENTS.md` (rules for coding agents).
- Termux's original README is kept at `docs/upstream-termux-README.md`; third-party notices are in `THIRD_PARTY.md`.
- Security reports: see `SECURITY.md`.

## Credits and licence

- **Termux.** A fork of [Termux](https://github.com/termux/termux-app), licensed GPLv3. The same licence applies here; see `LICENSE.md`.
- **The glibc launcher.** The way Claude Code's glibc binary is made to run on Termux comes from [claude-code-android](https://github.com/ferrumclaudepilgrim/claude-code-android) (MIT); see `setup/NOTICE`.
- **Claude Code** is Anthropic's software, downloaded from Anthropic at first run under their terms.

PocketRocket is not affiliated with Anthropic or the Termux project.
