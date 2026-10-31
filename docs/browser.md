# Headless Chromium

Decision 37. An optional tool, not part of Dockito and not a Dockito-style account. Dockito is a built-in tool that first-run setup installs (decision 40).

Claude uses Chromium to open the dev servers it starts, screenshot them at phone, tablet and desktop widths, read the console, and click through a flow. Chrome DevTools MCP drives it. This is not Claude in Chrome: it does not open Shankar's logged-in browser.

## Install and remove

- Settings → Tools installs it and shows the size: about 310 MB to download on a fresh install (312 MB with dependencies), 840 MB on the phone. First-run setup no longer offers it (decision 40).
- Settings → Tools installs it again, or removes it. Removing uninstalls the `chromium` package (and packages only it needed) and `chrome-devtools-mcp`, and takes the server out of every Claude account.

Both go through `setup/pocketrocket-setup.sh --only chromium` and `--only chromium-remove`. The install enables Termux's x11 repo, installs `chromium`, then `chrome-devtools-mcp@1.12.0` (the version the spike drove). Remove also drops the `x11-repo` package when no other installed package comes from it. Autoremove runs only after the base packages are marked manual, so it cannot take them.

## How a session gets a browser

When the tool is installed, and when a Claude account is added later, the app runs, in that account's config dir:

```
claude mcp add --scope user chrome-devtools -- \
  $PREFIX/bin/node $PREFIX/lib/node_modules/chrome-devtools-mcp/build/src/bin/chrome-devtools-mcp.js --headless=true --isolated=true \
  --executablePath=$PREFIX/bin/chromium-browser \
  --chrome-arg=--no-sandbox --chrome-arg=--disable-gpu
```

It is started as `node <script>` because Claude Code is a glibc binary with no `/usr/bin/env`, so the package's own `#!/usr/bin/env node` launcher fails with ENOENT.

`--isolated` gives that session a fresh profile, so two terminals do not share a page. `--no-sandbox` and `--disable-gpu` are what the emulator needed. Widths are the MCP `emulate` tool (`390x844x3,mobile,touch`, `820x1180x2,touch`, `1440x900x1`), not a shared window.

One running browser was about 360 MB. Install is a 187 MB download and 838 MB on disk (apt figures, re-measured on the emulator).
