#!/data/data/app.pocket/files/usr/bin/bash
# Fake setup script with the real one's stdout contract, for building and testing the setup screen.
# Packaged instead of setup/pocketrocket-setup.sh when the build gets -Ppocketrocket.setupStub.
# To force one failure: touch ~/.pocketrocket/stub-fail-once (it fails at step 4, then the marker is gone).
set -u

TOTAL=8
step() { printf '[step %s/%s] %s\n' "$1" "$TOTAL" "$2"; }

# Settings > Tools: --only chromium / chromium-remove, with fake binaries so the app sees it installed.
# touch ~/.pocketrocket/stub-chromium-fail-once to fail the download once; ~/.pocketrocket/stub-pace (seconds) sets each tick.
if [ "${1:-}" = --only ] && [ "${2:-}" = chromium ]; then
  TOTAL=4; pace="$(cat "$HOME/.pocketrocket/stub-pace" 2>/dev/null || echo 0.4)"
  step 1 "Checking the device"; echo "[ok] Termux on aarch64"; sleep "$pace"
  step 2 "Enabling the x11 repository"; echo "\$ pkg install -y x11-repo"; sleep "$pace"
  step 3 "Installing Chromium"
  echo "\$ pkg install -y chromium"
  echo "Get:1 https://packages.termux.dev/apt/termux-x11 x11 InRelease"
  echo "Get:2 chromium 131.0.6778.85 [190 MB]"
  for mb in 0 19 38 57 81; do printf '[progress %s] Downloading chromium · %s of 190 MB\n' $((mb * 40 / 190)) "$mb"; sleep "$pace"; done
  if [ -f "$HOME/.pocketrocket/stub-chromium-fail-once" ]; then
    rm -f "$HOME/.pocketrocket/stub-chromium-fail-once"
    echo "Fetched 81.2 MB in 1min 12s"
    echo "E: Failed to fetch chromium_131.0.6778.85_aarch64.deb  Connection timed out"
    echo "E: Unable to fetch some archives"
    echo "[error] The download stopped at 81 of 190 MB. Check the connection and try again."
    exit 1
  fi
  for mb in 100 133 166 190; do printf '[progress %s] Downloading chromium · %s of 190 MB\n' $((mb * 40 / 190)) "$mb"; sleep "$pace"; done
  printf '#!/bin/sh\necho "Chromium 131.0.6778.85 (stub)"\n' > "$PREFIX/bin/chromium-browser"; chmod 755 "$PREFIX/bin/chromium-browser"
  step 4 "Installing Chrome DevTools MCP"; sleep "$pace"
  printf '#!/bin/sh\n' > "$PREFIX/bin/chrome-devtools-mcp"; chmod 755 "$PREFIX/bin/chrome-devtools-mcp"
  echo "[ok] chrome-devtools-mcp 1.12.0"; echo "[done]"; exit 0
fi
# Settings > Tools: --only android-build / android-build-remove, with a fake SDK so the app sees it installed.
# touch ~/.pocketrocket/stub-android-build-fail-once to fail the download once.
if [ "${1:-}" = --only ] && [ "${2:-}" = android-build ]; then
  TOTAL=4; pace="$(cat "$HOME/.pocketrocket/stub-pace" 2>/dev/null || echo 0.4)"
  step 1 "Checking the device"; echo "[ok] Termux on aarch64"; sleep "$pace"
  step 2 "Installing Java, Gradle and build tools"
  for mb in 0 60 120 180 240 290; do printf '[progress %s] Downloading gradle · %s of 290 MB\n' $((mb * 40 / 290)) "$mb"; sleep "$pace"; done
  printf '[progress 85] Installing gradle (40 of 41)\n'; sleep "$pace"
  step 3 "Downloading the Android SDK"
  printf '[progress 40] 50 MB of 125 MB\n'; sleep "$pace"
  if [ -f "$HOME/.pocketrocket/stub-android-build-fail-once" ]; then
    rm -f "$HOME/.pocketrocket/stub-android-build-fail-once"
    echo "[error] The download of platform-37.0_r02.zip stopped. Check the connection and try again."
    exit 1
  fi
  printf '[progress 100] downloaded\n'; sleep "$pace"
  step 4 "Setting up builds"
  mkdir -p "$PREFIX/opt/android-sdk"; touch "$PREFIX/opt/android-sdk/.pocketrocket-installed"
  sleep "$pace"; echo "[done]"; exit 0
fi
if [ "${1:-}" = --only ] && [ "${2:-}" = android-build-remove ]; then
  TOTAL=3
  step 1 "Checking the device"; sleep 0.4
  step 2 "Removing the Android SDK"; rm -rf "$PREFIX/opt/android-sdk"; sleep 0.6
  step 3 "Removing Java, Gradle and build tools"; sleep 0.4
  echo "[done]"; exit 0
fi
# Settings > Tools: --only android-ndk / android-ndk-remove, with fake NDK folders.
if [ "${1:-}" = --only ] && [ "${2:-}" = android-ndk ]; then
  TOTAL=3
  step 1 "Checking the device"; echo "[ok] Termux on aarch64"; sleep 0.4
  step 2 "Installing clang, CMake and Ninja"; printf '[progress 60] Downloading clang · 58 of 96 MB\n'; sleep 0.6
  step 3 "Setting up the NDK folders"
  mkdir -p "$PREFIX/opt/android-sdk/ndk/28.2.13676358" "$PREFIX/opt/android-sdk/ndk-shim"; touch "$PREFIX/opt/android-sdk/ndk-shim/.pocketrocket-installed"
  sleep 0.4; echo "[done]"; exit 0
fi
if [ "${1:-}" = --only ] && [ "${2:-}" = android-ndk-remove ]; then
  TOTAL=2
  step 1 "Checking the device"; sleep 0.4
  step 2 "Removing clang, CMake and Ninja"; rm -rf "$PREFIX/opt/android-sdk/ndk" "$PREFIX/opt/android-sdk/ndk-shim"; sleep 0.4
  echo "[done]"; exit 0
fi
# Settings > Updates: --only git-check prints apt-cache policy; --only git-update fakes the apt phases.
# echo 2.51.1 > ~/.pocketrocket/stub-git-candidate offers an update (the stub's git is 2.51.0 until updated).
if [ "${1:-}" = --only ] && [ "${2:-}" = git-check ]; then
  have="$(cat "$HOME/.pocketrocket/stub-git-installed" 2>/dev/null || echo 2.51.0)"
  want="$(cat "$HOME/.pocketrocket/stub-git-candidate" 2>/dev/null || echo "$have")"
  printf 'git:\n  Installed: %s\n  Candidate: %s\n  Version table:\n' "$have" "$want"; exit 0
fi
if [ "${1:-}" = --only ] && [ "${2:-}" = git-update ]; then
  TOTAL=2; pace="$(cat "$HOME/.pocketrocket/stub-pace" 2>/dev/null || echo 0.4)"
  want="$(cat "$HOME/.pocketrocket/stub-git-candidate" 2>/dev/null || echo 2.51.0)"
  step 1 "Checking the device"; echo "[ok] Termux on aarch64"; sleep "$pace"
  step 2 "Updating Git"
  for mb in 0 2 4 6 8; do printf '[progress %s] Downloading git · %s of 8.0 MB\n' $((mb * 40 / 8)) "$mb"; sleep "$pace"; done
  printf '[progress 55] Preparing git for PocketRocket (1 of 1)\n'; sleep "$pace"
  printf '[progress 85] Setting up git (1 of 1)\n'; sleep "$pace"
  echo "$want" > "$HOME/.pocketrocket/stub-git-installed"
  echo "[ok] git version $want"; echo "[done]"; exit 0
fi
if [ "${1:-}" = --only ] && [ "${2:-}" = chromium-remove ]; then
  TOTAL=3
  step 1 "Checking the device"; sleep 0.4
  step 2 "Removing Chromium"; rm -f "$PREFIX/bin/chromium-browser"; sleep 0.6
  step 3 "Removing Chrome DevTools MCP"; rm -f "$PREFIX/bin/chrome-devtools-mcp"; sleep 0.4
  echo "[done]"; exit 0
fi

step 1 "Checking the device"
echo "[info] arch: $(uname -m)"
sleep 1

step 2 "Installing base packages"
# The real script's three phases: download (0-40), prepare (40-70), install (70-100).
pkgs="glibc-repo glibc-runner patchelf-glibc git gh openssh ripgrep jq curl zellij nodejs-lts"
n=0; count=$(echo $pkgs | wc -w); total=129
for p in $pkgs; do
  n=$((n + 1)); have=$((n * total / count))
  printf '[progress %s] Downloading %s · %s of %s MB\n' $((have * 40 / total)) "$p" "$have" "$total"
  echo "downloaded $p ($n of $count)"; sleep 0.3
done
n=0
for p in $pkgs; do
  n=$((n + 1))
  printf '[progress %s] Preparing %s for PocketRocket (%s of %s)\n' $((40 + n * 30 / count)) "$p" "$n" "$count"
  echo "prepared $p ($n of $count)"; sleep 0.3
done
n=0
for p in $pkgs; do
  n=$((n + 1))
  printf '[progress %s] Installing %s (%s of %s)\n' $((70 + n * 30 / count)) "$p" "$n" "$count"
  echo "installed $p ($n of $count)"; sleep 0.3
done
step 3 "Installing your extra tools"
echo "[info] ~/.pocketrocket/tools.txt is empty"
sleep 1

step 4 "Downloading Claude Code"
if [ -f "$HOME/.pocketrocket/stub-fail-once" ]; then
  rm -f "$HOME/.pocketrocket/stub-fail-once"
  printf '[progress 37] 84 MB of 228 MB\n'
  sleep 1
  echo "curl: (56) Recv failure: Connection reset by peer"
  echo "[error] Download failed. Check the network and try again."
  exit 1
fi
for mb in 0 23 46 69 92 115 138 161 184 207 228; do
  printf '[progress %s] %s MB of 228 MB\n' $((mb * 100 / 228)) "$mb"
  sleep 0.5
done

step 5 "Preparing Claude Code for Android"
sleep 1.5

step 6 "Writing launcher and defaults"
sleep 1

step 7 "Checking claude --version"
echo "2.1.0 (Claude Code, stub)"
sleep 1

step 8 "Installing Dockito"
for mb in 0 30 60 96; do printf '[progress %s] %s MB of 96 MB\n' $((mb * 100 / 96)) "$mb"; sleep 0.5; done
sleep 1
echo "[done]"
