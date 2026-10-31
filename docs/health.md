# Health and keep-alive (R15, T8, decisions 8 and 22)

Settings › Health checks everything sessions depend on. Each row has a state (green ok, amber needs attention, grey can't check), a short note and, when not ok, a Fix or Show me button. Layout and wording follow `docs/ux-mockup/Health.dc.html`.

## Code

- `health/` holds one concern per file. `HealthModel` has the item, group, report and fix types. `SystemScreens` maps a target to intents, with Xiaomi's screens first and Android's after. `KeepAliveChecks`, `PermissionChecks`, `SetupChecks` and `AccountChecks` hold the checks. `LastStop` reads exit reasons for the banner. `Health` keeps the last report and runs checks off the main thread.
- `ui/settings/HealthScreen.kt` is the screen. `SettingsScreen`'s `HealthRow` turns amber with "N things need attention". The app provides `LocalOpenHealth` in `PocketRocketApp`, so Home's signature is unchanged.
- Opening Health checks again if the last run is more than 20 s old. Settings does the same when the last run is more than 5 min old. Check again always re-runs. Coming back from a system screen re-reads the local checks (keep-alive and permissions) without starting any processes.

## Checks

| Row | How it's read | Fix |
|---|---|---|
| Battery saver | `ActivityManager.isBackgroundRestricted` | Xiaomi power keeper's per-app page, then Android 14+'s app battery page, then App info |
| Autostart | Xiaomi only: hidden app op 10008 through `AppOpsManager.checkOpNoThrow`, reached by reflection. If that read fails, the row says "can't check". Other phones show "Not needed" | `com.miui.securitycenter` AutoStartManagementActivity |
| Ignore battery optimisation | `PowerManager.isIgnoringBatteryOptimizations` | The request dialog |
| Background service | Whether TermuxService is running and how many sessions are busy | Nothing to fix, it is informational |
| Wake lock | The flag TermuxService reports | Nothing to fix, it is informational |
| Android process limit | Global `settings_enable_monitor_phantom_procs` or its `persist.sys.fflag.override.` property set to false. No limit below Android 12 | A guide: the Developer options toggle, or the three wireless-debugging adb commands |
| Lock in recent apps | Can't be read | A guide (Xiaomi wording on Xiaomi) |
| Notifications | `areNotificationsEnabled`, plus whether the Needs you or Finished channel is off | App notification settings, or the channel's own page |
| Microphone | RECORD_AUDIO | Asks for it once, then opens the app's permissions page |
| Files | Storage permission, and whether `~/storage/shared` resolves | App info, or a guide to run `termux-setup-storage` |
| Claude Code | `claude --version` | Settings › Claude Code |
| Tools | Runs every base tool: `git gh ssh node npm rg jq curl zellij --version` (ssh uses `-V`). Packages listed in tools.txt must also be installed. A binary that won't run (for example an SELinux denial) fails here | Settings › Tools |
| Storage | `StatFs` on home. Amber below 2 GB | Android's storage settings |
| Network | HEAD request to https://downloads.claude.ai and https://github.com. Any HTTP answer counts as reachable | Nothing |
| Claude | `ClaudeStatuses.check`, the same as the accounts screens | Claude accounts |
| GitHub | `ssh -T git@github.com` with only that account's key (`IdentitiesOnly`, `BatchMode`). "successfully authenticated" means OK | GitHub accounts |

## Last-stop banner

`LastStop.onProcessStart`, called from `PocketRocket.init`, saves two things: when this process started and when the previous one started. It also saves how many sessions had a live process at the last change (`recordRunning`, from SessionKeepAlive). The banner shows the newest `getHistoricalProcessExitReasons` entry between those two start times. That entry is whatever ended the previous process, so an old kill doesn't stay on screen.

The banner is hidden below Android 11. Times use the phone's local clock: "3:12 am", or 24-hour format if the phone uses it.

Each reason is put into plain words. A swipe from recents ("remove task") and a force stop are labelled as Shankar's own ("PocketRocket was stopped at … (force stop)"), and the "Fix the items below" line is left off. `am kill` and cleaner apps (kill background) count as Android stops. A description containing battery, power or restrict reads as "battery saver".

## Keep-alive (T8)

- The foreground service (TermuxService) and the `pocketrocket:sessions` partial wake lock are both held only while a session is Starting, Working or WaitingForPermission. Both are let go once every session is ready, Done, Error or Paused. Idle Claude processes keep running, but nothing protects them. If Android stops them, opening the session resumes it.
- The service is declared `foregroundServiceType="specialUse"` with a subtype property and `FOREGROUND_SERVICE_SPECIAL_USE`, and starts with that type on Android 14+. specialUse has no 6-hour cap, unlike dataSync.
- The notification reads "N sessions working · M need you", or "1 session needs you", followed by any terminals. Tapping it opens Home. Exit pauses every session.
- Nothing starts on boot (decision 8). Termux's BOOT_COMPLETED receiver only resets its shell counters.
