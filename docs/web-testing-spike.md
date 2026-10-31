# Web testing spike

Decision 37. Measured on the arm64 API 36 emulator (`PocketRocket`, package `app.pocket`) on 30 Oct 2026. The page under test is `docs/web-testing-spike/index.html`: one button, one deliberate console error, and a layout that is one column below 800 px and three columns above it. `docs/web-testing-spike/cdp-check.mjs` drives the DevTools protocol directly. `docs/web-testing-spike/mcp-check.mjs` drives `chrome-devtools-mcp` the way Claude Code would.

A real `claude` turn was not run. The Claude login on the emulator was at its 5-hour limit.

## A. Headless Chromium from Termux

Package `chromium` 156.0.8078.47 from the x11 repo (not in the main repo). It runs with `--headless=new` and no X server. The launcher script already uses `/data/data/app.pocket` after the prefix rewrite, and nothing in the run failed because of that rewrite.

| | |
|---|---|
| Download | 187 MB (apt) |
| Installed | 839 MB (apt). The prefix grew by 863 MiB. |
| Install time | 56 s, after an 8 s repo update. The first attempt failed on DNS and was retried. |
| Cold start | 526 ms to `/json/version` the first time, 165 ms the next |
| Navigate + load | 54 ms |
| Screenshot | 25 ms at 390 px, 24 ms at 1280 px |
| Click | 6 ms |
| RAM | 11 processes, 363 MB PSS (356 MB on a second run) |
| chrome-devtools-mcp | 1.12.0, 17 MB, 4 s to install |

Navigate, both widths, the console error and the click all worked over CDP. Screenshots were real 390×844 and 1280×800 images. At 390 the title wraps onto two lines and the cards stack. At 1280 the cards sit in three columns.

`chrome-devtools-mcp` worked both ways: pointed at the already running browser (`--browserUrl`) and launching Chromium itself (`--executablePath`). `resize_page`, screenshots, the console error, the snapshot, the click and `evaluate_script` all succeeded.

It logged two errors that did not stop the run: D-Bus socket missing, and `/proc/sys/fs/inotify/max_user_watches` unreadable. The user agent string says `X11; Linux x86_64` even though the binary is arm64.

## B. The phone's WebView

System WebView, Chrome/133.0.6943.137. `WebView.setWebContentsDebuggingEnabled(true)` listens on the abstract socket `webview_devtools_remote_<pid>`, which only the app itself (or root or shell) can open. `DevToolsBridge` runs in the app and forwards that socket to `127.0.0.1:9223`. The shell starts it with `am broadcast` to `BrowserReceiver`.

Where the WebView is attached decides whether it paints:

| Mode | What happened |
|---|---|
| Detached (created and laid out, never in a window) | DevTools answered in 49 ms. `Page.captureScreenshot` timed out. It does not paint. |
| Virtual display (`Presentation` on a private `VirtualDisplay`) | Paints. Start to a page target was 276–291 ms. |
| Same virtual display, launcher in front | Still paints. The app does not have to be on screen. |

On the virtual display, over CDP: navigate 43 ms (31 ms with the launcher in front), screenshots 73–86 ms, click 6–8 ms, and the console error was read. Screenshots were 390×844 and 1280×800. At 390 the cards stack and the title stays on one line (Android's `system-ui`; Chromium's fonts wrap it). At 1280 the background goes blue and the cards sit in three columns, so the layout query is real.

The first load of `http://127.0.0.1` failed with `ERR_CLEARTEXT_NOT_PERMITTED`. The app now allows plain http for `localhost` and `127.0.0.1` only.

`chrome-devtools-mcp` navigated, listed the console error, snapshotted, clicked and evaluated. `resize_page` failed: it calls `Browser.getWindowForTarget`, which this WebView does not implement. The `emulate` tool (`Emulation.setDeviceMetricsOverride`) did both widths, in 83 ms and 28 ms, and the screenshots came back at 65 ms and 55 ms.

After one good CDP run, `Emulation.clearDeviceMetricsOverride` did not return. The checks above it had already passed. The harness now gives that call 3 seconds and continues.

RAM from `dumpsys meminfo` while the virtual WebView was up: `app.pocket` 319,660 KB PSS, `webview_service` 110,080 KB, `webview_zygote` 90,380 KB. After `STOP`: `app.pocket` 167,615 KB, `webview_service` 7,240 KB. A `/proc` reading in the same script said 149 MB then 164 MB and does not match dumpsys, so the dumpsys figures are the ones here.

## What was chosen

Shankar chose headless Chromium (decision 37, 30 Oct 2026). The size is acceptable: Claude itself is already a few hundred MB, the phone has the RAM and the disk, and the browser is an optional tool people can remove.

The WebView is not the engine. Every WebView in one app shares one renderer and one DevTools socket, so two sessions would fight over the same page. Chromium gives each session its own process.

`resize_page` works on this Chromium. `emulate` is what changes the page between phone, tablet and desktop widths.

## After the spike

The install, the Settings → Tools screen and the per-account MCP wiring are in `docs/browser.md`. The WebView bridge from this spike is not in the app. The open DevTools port it needed does not apply to Chromium, which each session starts itself.
