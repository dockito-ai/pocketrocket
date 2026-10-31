# Device testing (design, not built)

Decision 45. A second tile in Settings → Tools, separate from Android build. Claude installs an app it built, runs it on a hidden virtual display on the real phone, looks at it and taps it. The spike passed on the emulator (`docs/android-tools-spike.md`), not yet on the Xiaomi. Check that list before building this.

## What this gives Claude

Wireless debugging makes the shell user (uid 2000) reachable from inside the app. That user can install and remove apps, grant permissions, read logs from every app, take screenshots and inject input on the visible screen. Holding the adb key inside PocketRocket means anything running there could use that power, including a Bash command Claude runs. So the design has to be honest about what it does and does not stop.

**What the MCP boundary does:** it limits what Claude does by normal tool calls, and makes mistakes unlikely. **What it does not do:** stop a Bash command from running `adb` itself if a connection is open, because sessions run as the same Linux user. The defences against that are the connection being off unless the user turned it on, the permission prompts Claude Code already shows for Bash, and the disconnect button. The wording before pairing must say this.

## Off until paired

- Install downloads nothing beyond `android-tools` and adds no MCP server to any account.
- The server is added to Claude accounts only after pairing succeeds, and removed on disconnect and uninstall.
- Requires Developer options and wireless debugging; the tile says so. Decision 35 still holds for everything else.

## Pairing

The tool page walks through it: turn on Developer options, wireless debugging, open "Pair device with pairing code", enter the port and six digits on the PocketRocket page.

Before the first field, in plain words:

> This lets PocketRocket control your phone the way a computer plugged in with USB debugging can. It can install and remove apps, read logs from all apps, and see and tap the screen. Claude will use it through fixed tools, only for the app you are testing, on a hidden screen. But while connected, a command Claude runs could reach the same access, so read what you approve. Disconnect any time here. Wireless debugging also shows a notification while it is on.

The code goes from the field to `adb pair` on stdin or argv and nowhere else. It is never logged, stored, put in the tool log or passed to Claude. The port is not secret. `~/.android/adbkey` is the lasting credential: Disconnect removes it, so connecting again needs a new pairing, and the entry should also be forgotten in Android's Paired devices list. The page explains that last step because the app cannot do it.

The mDNS service is unavailable in Termux's adb (`mdns is not supported`), so the connect port comes from the user, or from `getprop service.adb.tls.port` once a connection exists.

## MCP tools

A small server of our own, started per session. No raw shell tool. Every call builds its command from validated arguments, without passing strings through a shell.

| Tool | Does | Limits |
|---|---|---|
| `install_apk(path)` | `adb install -r` | Path must be inside the session's working folder. Its package becomes the app under test; a different package needs the user's OK in the app |
| `launch(activity?)` | Creates the display if needed, `am start --display N` | App under test only |
| `screenshot()` | The helper's latest frame | Virtual display only |
| `ui_tree()` | `uiautomator dump --windows`, cut to that display and package | Other windows are stripped |
| `tap(x, y)`, `swipe(...)`, `type(text)`, `key(name)` | `input -d N ...` | Virtual display only. `key` from a short list, never Power or Home |
| `logs(lines?)` | `logcat -d --uid=<uid>` | App under test only; capped |
| `stop()` | `am force-stop`, removes the display | App under test only |
| `status()` | Connected or not, display, app under test | |

**App under test.** One per session, set by `install_apk`. A package that is already installed and that the session did not install is refused, unless the user approved it on a prompt: this stops Claude replacing or driving a banking app. `uninstall` is not offered, except for the app under test when the session ends, if the user wants it.

**Text to Claude is data.** Screenshots, UI trees and logs come from an app and may carry instructions. The tool descriptions say so.

## Disconnect

- The tool page has one large **Disconnect**: stops the helper, removes the display, `adb disconnect`, `adb kill-server`, deletes `adbkey`, takes the MCP server out of every account.
- While connected, the service notification adds "Device testing connected" with a Disconnect action.
- Turning off wireless debugging in Settings cuts it too; the next tool call reports that, in words.
- It disconnects by itself when PocketRocket's last session ends, and after 30 minutes without a tool call. Shankar can change that.

## Pieces

- `com.pocketrocket.devicetest`: state, pairing, disconnect. One concern, same pattern as `AndroidBuild`.
- The display helper (`docs/android-tools-spike/vdisplay`) built into the APK as our own code, pushed to `/data/local/tmp` and removed on disconnect.
- The MCP server, as a small Node script (Node is a base package), wired through `claude mcp add`, as Chromium is.
- Mockup in `docs/ux-mockup/Tools.dc.html` first.

## Open questions for Shankar

1. Should Disconnect also delete `adbkey`? It is safer, but means pairing every time. The design says yes.
2. Is the 30-minute automatic disconnect right?
3. Should the display size follow the phone, or stay fixed at 1080×2400?
