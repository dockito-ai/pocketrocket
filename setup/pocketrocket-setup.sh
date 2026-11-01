#!/data/data/app.pocket/files/usr/bin/bash
# PocketRocket first-run setup (Termux, aarch64 Android).
#
# Installs the Termux packages PocketRocket needs, downloads Anthropic's
# official Claude Code linux-arm64 binary from downloads.claude.ai, checks it
# against Anthropic's manifest.json, points its ELF interpreter at Termux's
# glibc loader, smoke-tests it and only then makes it the current version.
# Nothing of Anthropic's is bundled with PocketRocket (decision 3).
#
# Safe to run again: finished steps are skipped, and a version that fails its
# checks never replaces the one that works.
#
# Progress contract for the app's setup screen (stdout, one line each):
#   [step N/M] <short title>       when a step starts
#   [progress 0-100] <detail>      during long steps: "Downloading git · 4.3 of 129 MB",
#                                  "Preparing git for PocketRocket (12 of 110)", "Installing git (30 of 110)",
#                                  "Still working on git…" after 20 s without a change (see apt_install_progress)
#   [done]                         last line on success, exit 0
#   [error] <one-line message>     right before a non-zero exit
# Every other line ([ok], [info], [warn], log tails) is plain log text.
# Package manager noise goes to ~/.pocketrocket/setup.log.
#
# Options:
#   (none)            full first-run setup, 8 steps (the last installs Dockito's CLI, decision 40;
#                     the app passes POCKETROCKET_DOCKITO_* from the formula as for --only dockito)
#   --only claude     install POCKETROCKET_CLAUDE_VERSION alone: download, verify, smoke test,
#                     switch `current`, prune; 4 steps (the app's Claude Code screen)
#   --switch X.Y.Z    make the kept version current (rollback), smoke-test it, switch back if it
#                     does not start; 3 steps
#   --only dockito    update Dockito's CLI (the app's updates): download the release
#                     the app read from Dockito's Homebrew formula, check it against the formula's
#                     SHA-256, patch it for glibc like Claude Code, smoke-test, switch `current`,
#                     install the `dockito` launcher; 4 steps. Needs POCKETROCKET_DOCKITO_VERSION
#                     (0.0.1-beta.27 or newer),
#                     POCKETROCKET_DOCKITO_URL and POCKETROCKET_DOCKITO_SHA256.
#   --only android-build / android-build-remove  the optional Android build tool, Settings > Tools (decision 45)
#   --only android-ndk / android-ndk-remove  the optional Android NDK tool, on top of it and C and C++ (decisions 50, 96)
#   --only c-cpp / c-cpp-remove  the optional C and C++ tool: Termux's compilers and build tools (decision 96)
#   --only wrangler / wrangler-remove  the optional Wrangler tool, Settings > Tools (decisions 49, 82)
#   --only flutter-rust / flutter-rust-remove  the optional Flutter & Rust tool, all of it (decision 75)
#   --only flutter [version]  add a Flutter version (newest stable by default; POCKETROCKET_FLUTTER_VERSION
#                     also works, POCKETROCKET_FLUTTER_DEFAULT=1 makes it the default)
#   --only flutter-default <version> / flutter-remove-version <version> / flutter-remove
#   --only flutter-release / flutter-release-remove  Release builds, the opt-in piece of Flutter & Dart (decision 108):
#                     Termux's qemu-user-x86-64 and an x86_64 C library, so release and profile APKs build; 3 steps
#                     (remove: 2). Uninstall of Flutter takes it too
#   --only rust / rust-remove  Rust alone, Termux's package (rust-remove takes the wasm target too)
#   --only python / python-remove, bun / bun-remove, rust-wasm / rust-wasm-remove  the package tools (decision 92):
#                     Termux packages only, 2 steps; Python's uninstall leaves what Headless Chrome needs
#   --only go, deno, ruby, php, java (and <name>-remove)  the language tiles, package tools too (decision 98); java is
#                     openjdk-21 with kotlin and keeps Java 17 the default java while it is installed
#   --only aws, flyctl (and -remove)  the deploy CLIs from Termux's packages, package tools too (decision 98); their
#                     uninstall signs out first and removes ~/.aws or ~/.fly; awscli-check/-update, flyctl-check/-update
#   --only vercel, netlify, firebase (and -remove)  the npm deploy CLIs (decision 98): npm install -g, 2 steps; uninstall
#                     signs out and removes their config
#   --only java-17, java-21, java-25, kotlin (and <name>-remove; java-17-remove keeps Java 17)  one JDK or Kotlin on
#                     its own, from the Java page; java-setup only does the setup (Repair). Every Java job leaves Gradle
#                     a list of the JDKs on the phone (decision 101)
#   --only vercel-check / netlify-check / firebase-check  print `npm view <package> version` for the Updates screen; no
#                     steps, no lock, no [done] (decision 103)
#   --only vercel-update / netlify-update / firebase-update  `npm install -g <package>@latest` with the install's
#                     progress; 2 steps
#   --only nodejs-lts-check / chromium-check, and the same with -update: Node.js and npm, and Headless Chrome's Chromium,
#                     as below (decision 103)
#   --only golang-check / deno-check / ruby-check / php-check / openjdk-21-check, and the same with -update: as below
#   --only git-check / rust-check / python-check / bun-check  refresh the package index and print `apt-cache policy <package>` (its
#                     Installed and Candidate lines) for the Updates screen; no steps, no lock, no [done]
#   --only git-update / rust-update / python-update / bun-update  upgrade the package alone (and only what the new version needs) with
#                     the apt progress phases; 2 steps (the Updates screen, decision 41)
# A version that fails the smoke test during --only claude is not made current: the script warns,
# keeps the old one, and still ends with [done]; the caller compares `current` with what it asked for.
#
# Environment knobs:
#   POCKETROCKET_CLAUDE_VERSION  latest (default) | stable | X.Y.Z
#   POCKETROCKET_DNS_PRELOAD     1 turns the 8.8.8.8 resolver preload on,
#                                0 turns it off, unset leaves it as it is
#                                (off by default, decision 5)
#   POCKETROCKET_SMOKE_TIMEOUT   seconds for the launch probe (default 45)
#
# Credits: the glibc-runner + patchelf approach, the LD_PRELOAD handling, the
# --init-only crash probe and the DNS preload are ported from
# claude-code-android by ferrumclaudepilgrim (MIT License, Copyright (c) 2026
# ferrumclaudepilgrim), https://github.com/ferrumclaudepilgrim/claude-code-android
# Version and manifest lookup follow Anthropic's official installer
# (https://claude.ai/install.sh).

set -euo pipefail

PR_HOME="${POCKETROCKET_HOME:-$HOME/.pocketrocket}"
PR_CLAUDE_DIR="$PR_HOME/claude"
PR_DOCKITO_DIR="$PR_HOME/dockito"
DOCKITO_RELEASES="https://github.com/dockito-ai/homebrew-tap/releases/download"
PR_LOG="$PR_HOME/setup.log"
PR_TOOLS="$PR_HOME/tools.txt"
PR_SETTINGS_DIR="$PR_HOME/settings"
PR_SETDNS="$PR_HOME/setdns.js"
DL_BASE="https://downloads.claude.ai/claude-code-releases"
PLATFORM="linux-arm64"
TOTAL_STEPS=8
CURRENT_STEP=0

BASE_PACKAGES="glibc-runner patchelf-glibc git gh openssh ripgrep jq curl zellij nodejs-lts"

# ---------------------------------------------------------------- output

step() { CURRENT_STEP=$(( CURRENT_STEP + 1 )); printf '[step %s/%s] %s\n' "$CURRENT_STEP" "$TOTAL_STEPS" "$*"; }
ok()   { printf '[ok] %s\n' "$*"; }
info() { printf '[info] %s\n' "$*"; }
warn() { printf '[warn] %s\n' "$*"; }
progress() { printf '[progress %s] %s\n' "$1" "$2"; }
fail() {
  printf '[error] %s\n' "$(printf '%s' "$*" | tr '\n' ' ')"
  if [ -s "$PR_LOG" ]; then
    printf '[info] last lines of %s:\n' "$PR_LOG" >&2
    tail -n 15 "$PR_LOG" | sed 's/^/    /' >&2
  fi
  exit 1
}

log_run() { "$@" >>"$PR_LOG" 2>&1; }

# download_with_progress <url> <out> [expected bytes]
# Resumes a partial file (-C -) and prints [progress N] as the file grows.
# Without an expected size it falls back to the Content-Length header.
download_with_progress() {
  local url="$1" out="$2" total="${3:-0}" pid have pct last=-1 rc=0
  case "$total" in ''|*[!0-9]*) total=0 ;; esac
  if [ "$total" -eq 0 ]; then
    total="$(curl -fsSLI --max-time 20 "$url" 2>/dev/null | tr -d '\r' \
      | awk 'tolower($1)=="content-length:" {n=$2} END {print n+0}')"
  fi
  curl -fsSL --retry 3 --max-time 1800 -C - "$url" -o "$out" >>"${PR_LOG:-/dev/null}" 2>&1 &
  pid=$!
  while kill -0 "$pid" 2>/dev/null; do
    have=""
    [ -f "$out" ] && have="$(wc -c <"$out" | tr -d ' ')"
    if [ "${total:-0}" -gt 0 ] && [ -n "$have" ]; then
      pct=$(( have * 100 / total )); [ "$pct" -gt 99 ] && pct=99
      if [ "$pct" != "$last" ]; then
        progress "$pct" "$(( have / 1048576 )) MB of $(( total / 1048576 )) MB"
        last="$pct"
      fi
    fi
    sleep 1
  done
  wait "$pid" || rc=$?
  if [ "$rc" -eq 0 ]; then progress 100 "downloaded"; fi
  return "$rc"
}

# ---------------------------------------------------------------- helpers
# These are plain functions with no Termux assumptions so they can be tested
# on any machine by sourcing this file with POCKETROCKET_SETUP_SOURCE_ONLY=1.

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

is_version() { printf '%s' "$1" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?$'; }

# resolve_version latest|stable|X.Y.Z -> prints X.Y.Z
resolve_version() {
  local want="$1" got
  if is_version "$want"; then printf '%s\n' "$want"; return 0; fi
  case "$want" in latest|stable) ;; *) return 1 ;; esac
  got="$(curl -fsSL --retry 3 --max-time 20 "$DL_BASE/$want")" || return 1
  got="$(printf '%s' "$got" | tr -d '[:space:]')"
  is_version "$got" || return 1
  printf '%s\n' "$got"
}

# manifest_field <manifest.json> <checksum|size>
manifest_field() {
  jq -er --arg p "$PLATFORM" --arg f "$2" '.platforms[$p][$f]' "$1"
}

# verify_binary <binary> <manifest.json> [expected-version]
# Returns 0 only if the manifest is for the expected version and the file's
# size and SHA-256 match the linux-arm64 entry.
verify_binary() {
  local bin="$1" manifest="$2" want_version="${3:-}" exp_sum exp_size act_sum act_size mver
  [ -s "$bin" ] || { echo "binary missing or empty: $bin" >&2; return 1; }
  if [ -n "$want_version" ]; then
    mver="$(jq -er '.version' "$manifest" 2>/dev/null)" || { echo "manifest has no version" >&2; return 1; }
    [ "$mver" = "$want_version" ] || { echo "manifest is for $mver, expected $want_version" >&2; return 1; }
  fi
  exp_sum="$(manifest_field "$manifest" checksum 2>/dev/null)" || { echo "no $PLATFORM checksum in manifest" >&2; return 1; }
  printf '%s' "$exp_sum" | grep -qE '^[a-f0-9]{64}$' || { echo "malformed checksum in manifest" >&2; return 1; }
  exp_size="$(manifest_field "$manifest" size 2>/dev/null || true)"
  act_size="$(wc -c <"$bin" | tr -d ' ')"
  if [ -n "$exp_size" ] && [ "$exp_size" != "$act_size" ]; then
    echo "size mismatch: expected $exp_size bytes, got $act_size" >&2; return 1
  fi
  act_sum="$(sha256_of "$bin")"
  [ "$act_sum" = "$exp_sum" ] || { echo "checksum mismatch: expected $exp_sum, got $act_sum" >&2; return 1; }
}

# verify_sha256 <file> <expected sha256>: 0 only when the file's SHA-256 is the expected one.
verify_sha256() {
  local file="$1" want="$2" got
  [ -s "$file" ] || { echo "download missing or empty: $file" >&2; return 1; }
  printf '%s' "$want" | grep -qE '^[a-f0-9]{64}$' || { echo "malformed checksum: $want" >&2; return 1; }
  got="$(sha256_of "$file")"
  [ "$got" = "$want" ] || { echo "checksum mismatch: expected $want, got $got" >&2; return 1; }
}

# dockito_url_ok <version> <url>: the URL is the linux-arm64 asset of that version on Dockito's tap.
dockito_url_ok() {
  [ "$2" = "$DOCKITO_RELEASES/v$1/dockito-linux-arm64.tar.gz" ]
}

# The oldest Dockito CLI with its own accounts (accounts, login --add, switch, --account).
DOCKITO_MIN_VERSION="0.0.1-beta.27"

# version_at_least <version> <min>: semver order; a pre-release sorts before its release, and
# pre-release parts compare as numbers when both are numbers (beta.9 < beta.27).
version_at_least() {
  local a="$1" b="$2" i x y ra="" rb=""
  local IFS=.
  local -a ca=(${a%%-*}) cb=(${b%%-*}) pa pb
  for i in 0 1 2; do
    x="${ca[i]:-0}"; y="${cb[i]:-0}"
    ((10#$x > 10#$y)) && return 0
    ((10#$x < 10#$y)) && return 1
  done
  [[ "$a" == *-* ]] && ra="${a#*-}"
  [[ "$b" == *-* ]] && rb="${b#*-}"
  [ -z "$ra" ] && return 0
  [ -z "$rb" ] && return 1
  pa=($ra); pb=($rb)
  for ((i = 0; i < ${#pa[@]} && i < ${#pb[@]}; i++)); do
    x="${pa[i]}"; y="${pb[i]}"
    if [[ "$x" =~ ^[0-9]+$ && "$y" =~ ^[0-9]+$ ]]; then
      ((10#$x > 10#$y)) && return 0
      ((10#$x < 10#$y)) && return 1
    elif [ "$x" != "$y" ]; then
      [[ "$x" =~ ^[0-9]+$ ]] && return 1
      [[ "$y" =~ ^[0-9]+$ ]] && return 0
      [[ "$x" > "$y" ]] && return 0
      return 1
    fi
  done
  ((${#pa[@]} >= ${#pb[@]}))
}

# read_tools_file <file> -> package names, one per line
read_tools_file() {
  sed -e 's/#.*//' -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' "$1" | grep -v '^$' || true
}

valid_package_name() { printf '%s' "$1" | grep -qE '^[a-z0-9][a-z0-9+.-]*$'; }

# ---------------------------------------------------------------- termux pieces

GLIBC_LD=""
PATCHELF=""
APT_OPTS=(-y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold)

pkg_installed() { dpkg -s "$1" >/dev/null 2>&1; }

missing_packages() {
  local p out=""
  for p in "$@"; do pkg_installed "$p" || out="$out $p"; done
  printf '%s' "${out# }"
}

apt_refresh() {
  log_run apt-get update "${APT_OPTS[@]}" || fail "apt-get update failed (check the network)"
}

apt_install() {
  log_run apt-get install "${APT_OPTS[@]}" "$@" || fail "could not install: $*"
}

# ---------------------------------------------------------------- apt with live progress
# apt_install_progress <pkgs…> installs the packages not installed yet, with everything they pull in, in three
# phases that each own part of the step's bar:
#   download  0-APT_W_DL     "Downloading nodejs-lts · 81 of 140 MB"   bytes in apt's archive cache
#   prepare   ..-APT_W_PREP  "Preparing openssl-glibc for PocketRocket (12 of 47)"   the relocation hook's
#                            progress file (decision 36; PrefixRelocation.kt writes the hook)
#   install   ..-100         "Installing git (30 of 47)"   apt's machine-readable status (APT::Status-Fd)
# Each package also gets a plain log line, and when nothing changes for APT_QUIET seconds the detail becomes
# "Still working on <item>…". The prefix rewrite still happens: the install phase is a normal apt-get install
# from the cache, so apt runs the DPkg::Pre-Install-Pkgs hook on every .deb before dpkg sees it.
# Weights from the emulator (base set, 110 debs, 129 MB): download depends on the network, preparing took
# 13 s and installing about as long; on a phone both are slower, so the bar gives them more room.
APT_W_DL=40
APT_W_PREP=70
APT_QUIET=20
RELOCATE_PROGRESS_ENV=POCKETROCKET_RELOCATE_PROGRESS   # PrefixRelocation.PROGRESS_ENV

# mb <bytes> -> "81" or "4.3" (one decimal under 10 MB; a small file that has started reads 0.1, not 0.0)
mb() { awk -v b="$1" 'BEGIN { m = b / 1048576; if (b > 0 && m < 0.1) m = 0.1; if (m < 10) printf "%.1f", m; else printf "%d", m }'; }

# deb_name <file.deb> -> the package name (the part before the first underscore)
deb_name() { local f="${1##*/}"; printf '%s' "${f%%_*}"; }

# apt_error <log offset>: apt's own error lines written to the log since the offset, on one line.
apt_error() {
  tail -c +"$(( $1 + 1 ))" "$PR_LOG" 2>/dev/null \
    | grep -E '^(E: |dpkg: error|PocketRocket: could not relocate)' | tail -n 3 | tr '\n' ' ' | sed 's/ *$//' || true
}

# What the screen shows now. apt_show prints only when the bar or the detail changes; apt_idle says
# "Still working on <item>…" once when nothing has changed for APT_QUIET seconds.
APT_PCT=0; APT_SHOWN=""; APT_CHANGED=0; APT_STILL=0
apt_show() {
  if [ "$1" != "$APT_PCT" ] || [ "$2" != "$APT_SHOWN" ]; then
    APT_PCT="$1"; APT_SHOWN="$2"; APT_CHANGED=$(date +%s); APT_STILL=0
    progress "$1" "$2"
  fi
}
apt_idle() {
  if [ "$APT_STILL" = 0 ] && [ $(( $(date +%s) - APT_CHANGED )) -ge "$APT_QUIET" ]; then
    progress "$APT_PCT" "Still working on $1…"
    APT_STILL=1
  fi
}

# APT_WANT: the packages asked for that are not installed yet. APT_FILES: "file size" per .deb to fetch.
# APT_VERB: install, or full-upgrade (then APT_WANT is empty).
APT_VERB=install; APT_WANT=(); APT_FILES=""; APT_TOTAL=0; APT_COUNT=0; APT_M=0; APT_OFF=0

apt_install_progress() {
  local p
  APT_VERB=install; APT_WANT=()
  for p in "$@"; do pkg_installed "$p" || APT_WANT+=("$p"); done
  [ ${#APT_WANT[@]} -gt 0 ] || return 0
  apt_progress_run
}

# apt_upgrade_progress: apt-get full-upgrade with the same three phases (a fresh bootstrap is often behind).
apt_upgrade_progress() {
  APT_VERB=full-upgrade; APT_WANT=()
  apt_progress_run
}

# apt_package_upgrade_progress <pkgs…>: upgrades installed packages to their candidates (and whatever the new
# versions need), nothing else, with the same three phases. `install --only-upgrade` never adds the package.
apt_package_upgrade_progress() {
  local saved=("${APT_OPTS[@]}")
  APT_VERB=install; APT_WANT=("$@"); APT_OPTS+=(--only-upgrade)
  apt_progress_run
  APT_OPTS=("${saved[@]}")
}

apt_progress_run() {
  local uris
  APT_OFF="$(wc -c <"$PR_LOG" | tr -d ' ')"
  # The real set: what apt will install (its Inst lines) and what it still has to fetch (URIs with sizes;
  # a .deb already in the cache is not listed).
  APT_M="$(apt-get "$APT_VERB" -s "${APT_OPTS[@]}" "${APT_WANT[@]}" 2>>"$PR_LOG" | grep -c '^Inst ' || true)"
  uris="$(apt-get "$APT_VERB" --print-uris -qq "${APT_OPTS[@]}" "${APT_WANT[@]}" 2>>"$PR_LOG")" \
    || fail "could not ${APT_VERB} ${APT_WANT[*]}: $(apt_error "$APT_OFF")"
  APT_FILES="$(printf '%s\n' "$uris" | awk 'NF >= 3 { print $2, $3 }')"
  APT_TOTAL="$(printf '%s\n' "$APT_FILES" | awk 'NF == 2 { s += $2 } END { print s + 0 }')"
  APT_COUNT="$(printf '%s\n' "$APT_FILES" | awk 'NF == 2' | wc -l | tr -d ' ')"
  if [ "$APT_M" -eq 0 ]; then
    [ "$APT_VERB" = install ] || return 0   # nothing to upgrade
    APT_M="${#APT_WANT[@]}"
  fi
  info "$APT_M packages to ${APT_VERB/full-/}, $APT_COUNT to download ($(mb "$APT_TOTAL") MB)${APT_WANT[*]:+: ${APT_WANT[*]}}"
  APT_PCT=-1; APT_SHOWN=""
  apt_download
  apt_unpack
  # The cache now holds the rewritten, uncompressed copies; they are never reused, so free the space.
  apt-get clean >>"$PR_LOG" 2>&1 || true
  if [ "$APT_VERB" = install ]; then progress 100 "Installed $APT_M packages"; else progress 100 "Updated $APT_M packages"; fi
}

# Download phase: apt-get --download-only in the background; the bar follows the bytes in apt's cache
# (finished files in archives/, the one being fetched in archives/partial/).
apt_download() {
  [ "$APT_COUNT" -gt 0 ] || return 0
  local archives pid rc=0 have f size got cur="" n=0 pct
  local -A logged=()
  eval "$(apt-config shell archives Dir::Cache::archives/d)"
  archives="${archives%/}"
  apt-get "$APT_VERB" --download-only "${APT_OPTS[@]}" "${APT_WANT[@]}" >>"$PR_LOG" 2>&1 </dev/null &
  pid=$!
  while :; do
    have=0
    while read -r f size; do
      [ -n "$f" ] || continue
      if [ -f "$archives/$f" ]; then
        have=$(( have + size ))
        if [ -z "${logged[$f]:-}" ]; then
          logged[$f]=1; n=$(( n + 1 ))
          printf 'downloaded %s (%s MB, %s of %s)\n' "$(deb_name "$f")" "$(mb "$size")" "$n" "$APT_COUNT"
        fi
      elif [ -f "$archives/partial/$f" ]; then
        got="$(stat -c %s "$archives/partial/$f" 2>/dev/null || echo 0)"
        have=$(( have + got )); cur="$(deb_name "$f")"
      fi
    done <<<"$APT_FILES"
    [ "$have" -le "$APT_TOTAL" ] || have="$APT_TOTAL"
    pct=0; [ "$APT_TOTAL" -gt 0 ] && pct=$(( have * APT_W_DL / APT_TOTAL ))
    apt_show "$pct" "Downloading${cur:+ $cur} · $(mb "$have") of $(mb "$APT_TOTAL") MB"
    apt_idle "the download${cur:+ of $cur}"
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done
  wait "$pid" || rc=$?
  [ "$rc" -eq 0 ] || fail "the download stopped at $(mb "$have") of $(mb "$APT_TOTAL") MB: $(apt_error "$APT_OFF")"
}

# Prepare and install phases: apt-get install from the cache. apt runs the relocation hook first, which
# appends "start N", "begin <deb>" and "done <deb>" to the progress file; then dpkg, whose steps apt writes to
# the status file as "pmstatus:<pkg>:<percent>:<message>".
apt_unpack() {
  local pf="$PR_HOME/.relocate-progress" sf="$PR_HOME/.apt-status" pid rc=0 line kind name pct msg
  local nprep=0 prepped=0 cur="" phase=prepare unpacked=0 configured=0 buf_p="" buf_s="" fdp fds base
  local w_inst=$(( 100 - APT_W_PREP ))
  : >"$pf"; : >"$sf"
  env "$RELOCATE_PROGRESS_ENV=$pf" apt-get "$APT_VERB" "${APT_OPTS[@]}" -o APT::Status-Fd=3 "${APT_WANT[@]}" \
    3>"$sf" >>"$PR_LOG" 2>&1 </dev/null &
  pid=$!
  exec {fdp}<"$pf" {fds}<"$sf"
  apt_show "$APT_W_DL" "Preparing $APT_M packages for PocketRocket"
  while :; do
    # The hook's lines. A read at the end of the file returns what it has; keep a partial line for later.
    while IFS= read -r -u "$fdp" line || { buf_p="$buf_p$line"; false; }; do
      line="$buf_p$line"; buf_p=""
      case "$line" in
        "start "*) nprep="${line#start }"; prepped=0 ;;
        "begin "*) cur="$(deb_name "${line#begin }")" ;;
        "done "*) prepped=$(( prepped + 1 )); printf 'prepared %s (%s of %s)\n' "$(deb_name "${line#done }")" "$prepped" "$nprep" ;;
      esac
    done
    # apt's status lines, once dpkg runs.
    while IFS= read -r -u "$fds" line || { buf_s="$buf_s$line"; false; }; do
      line="$buf_s$line"; buf_s=""
      [ "${line%%:*}" = pmstatus ] || continue
      line="${line#pmstatus:}"; name="${line%%:*}"; line="${line#*:}"; pct="${line%%:*}"; msg="${line#*:}"
      [ "$name" = dpkg-exec ] && continue
      phase=install; base="${pct%%.*}"; cur="$name"
      case "$msg" in
        "Installing "*) unpacked=$(( unpacked + 1 )); printf 'unpacked %s (%s of %s)\n' "$name" "$unpacked" "$APT_M" ;;
        "Installed "*) configured=$(( configured + 1 )); printf 'installed %s (%s of %s)\n' "$name" "$configured" "$APT_M" ;;
      esac
    done
    if [ "$phase" = prepare ]; then
      if [ "$nprep" -gt 0 ]; then
        apt_show $(( APT_W_DL + prepped * (APT_W_PREP - APT_W_DL) / nprep )) \
          "Preparing${cur:+ $cur} for PocketRocket ($(( prepped < nprep ? prepped + 1 : nprep )) of $nprep)"
      fi
      apt_idle "${cur:-the packages}"
    else
      if [ "$configured" -eq 0 ] && [ "$unpacked" -lt "$APT_M" ]; then
        apt_show $(( APT_W_PREP + ${base:-0} * w_inst / 100 )) "Installing $cur ($(( unpacked < APT_M ? unpacked + 1 : APT_M )) of $APT_M)"
      else
        apt_show $(( APT_W_PREP + ${base:-0} * w_inst / 100 )) "Setting up $cur ($(( configured < APT_M ? configured + 1 : APT_M )) of $APT_M)"
      fi
      apt_idle "$cur"
    fi
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done
  wait "$pid" || rc=$?
  exec {fdp}<&- {fds}<&-
  rm -f "$pf" "$sf"
  [ "$rc" -eq 0 ] || fail "could not ${APT_VERB} ${APT_WANT[*]}: $(apt_error "$APT_OFF")"
}

# smoke_test <binary>: 0 runs, 1 definitely crashes here, 2 inconclusive.
# Ported from claude-code-android: some releases pass --version but die on a
# full start (Android seccomp SIGSYS, or glibc-runner's epoll_pwait2 shim
# SIGSEGV). --init-only boots the full runtime and exits; it runs in a
# throwaway HOME so no hooks or logins are touched.
smoke_test() {
  local bin="$1" dir err home limit started rc elapsed
  dir="$(dirname "$bin")"
  err="$dir/.smoke-stderr"; home="$dir/.smoke-home"
  limit="${POCKETROCKET_SMOKE_TIMEOUT:-45}"
  rm -rf "$home"; mkdir -p "$home/.claude"
  started=$(date +%s)
  rc=0
  HOME="$home" CLAUDE_CONFIG_DIR="$home/.claude" LD_PRELOAD='' DISABLE_AUTOUPDATER=1 \
    timeout -s KILL "$limit" "$bin" --init-only </dev/null >/dev/null 2>"$err" || rc=$?
  elapsed=$(( $(date +%s) - started ))
  rm -rf "$home"
  if grep -qE 'Bad system call|oh no: Bun has crashed|panic\(|bun\.report' "$err" 2>/dev/null; then
    rm -f "$err"; return 1
  fi
  rm -f "$err"
  [ "$elapsed" -ge "$limit" ] && return 2
  if [ "$rc" -gt 128 ] && [ "$rc" -le 159 ]; then return 1; fi
  if [ "$rc" -eq 126 ] || [ "$rc" -eq 127 ]; then return 2; fi
  return 0
}

# current_version [dir]: the version `current` points at (Claude Code's dir by default).
current_version() {
  local link="${1:-$PR_CLAUDE_DIR}/current"
  [ -L "$link" ] || return 1
  basename "$(readlink "$link")"
}

# prune_versions <current> [dir] [keep the newest older one: 1|0] [name]
# Keep the current version and the newest other one (for rollback); drop the rest.
prune_versions() {
  local cur="$1" dir="${2:-$PR_CLAUDE_DIR}" keep="${3:-1}" name="${4:-Claude Code}" keep_prev="" v
  if [ "$keep" = 1 ]; then
    keep_prev="$(find "$dir" -mindepth 1 -maxdepth 1 -type d -name '[0-9]*' -exec basename {} \; \
      | grep -E '^[0-9]+\.[0-9]+\.[0-9]+' | grep -vxF "$cur" | sort -V | tail -n 1 || true)"
  fi
  for v in $(find "$dir" -mindepth 1 -maxdepth 1 -type d -name '[0-9]*' -exec basename {} \;); do
    if [ "$v" = "$cur" ] || [ "$v" = "$keep_prev" ]; then continue; fi
    rm -rf "${dir:?}/$v"
    info "removed old $name $v"
  done
}

# write_claude_defaults <config dir>: merge our defaults into settings.json.
# autoUpdates false: PocketRocket's updater owns updates (R9).
# env.USE_BUILTIN_RIPGREP=0: use Termux's ripgrep.
# env.LD_PRELOAD removed: a bionic preload in settings leaks into the Bash
# tool and breaks the glibc tools (finding from claude-code-android).
write_claude_defaults() {
  local dir="$1" sf tmp
  sf="$dir/settings.json"
  mkdir -p "$dir"
  if [ -e "$sf" ]; then
    tmp="$(mktemp "${TMPDIR:-/tmp}/pr-settings.XXXXXX")"
    if jq '.autoUpdates = false
           | .env = ((.env // {}) | del(.LD_PRELOAD) | .USE_BUILTIN_RIPGREP = "0")' "$sf" >"$tmp" 2>/dev/null; then
      cat "$tmp" >"$sf"   # write through a symlink instead of replacing it
      rm -f "$tmp"
    else
      rm -f "$tmp"
      warn "$sf is not valid JSON; left untouched. Set autoUpdates false by hand."
      return 0
    fi
  else
    printf '%s\n' '{' '  "autoUpdates": false,' '  "env": { "USE_BUILTIN_RIPGREP": "0" }' '}' >"$sf"
  fi
}

# Replace Termux's welcome text (links to Termux docs) with our own.
write_motd() {
  printf '%s\n' "" "PocketRocket: Claude Code on your phone." \
    "Type 'claude' to start. Clone repos into ~/repos." "" >"$PREFIX/etc/motd"
}

acquire_lock() {
  local lock="$PR_HOME/.setup.lock" pid
  if ! mkdir "$lock" 2>/dev/null; then
    pid="$(cat "$lock/pid" 2>/dev/null || true)"
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      fail "setup is already running (pid $pid)"
    fi
    rm -rf "$lock"; mkdir "$lock" || fail "could not take the setup lock"
  fi
  echo $$ >"$lock/pid"
  trap 'rm -rf "$PR_HOME/.setup.lock"' EXIT
}

# ---------------------------------------------------------------- steps

step_preflight() {
  step "Checking the device"
  preflight_checks
  # A brand-new Termux with no mirror chosen can stop on a prompt; pick the default.
  if [ ! -e "$PREFIX/etc/termux/chosen_mirrors" ] && [ -e "$PREFIX/etc/termux/mirrors/default" ]; then
    ln -sf "$PREFIX/etc/termux/mirrors/default" "$PREFIX/etc/termux/chosen_mirrors" 2>/dev/null || true
  fi
  ok "Termux on aarch64"
}

preflight_checks() {
  [ -n "${PREFIX:-}" ] && [ -d "$PREFIX" ] || fail "PREFIX is not set; run this inside the PocketRocket shell"
  [ "$(uname -m)" = "aarch64" ] || fail "needs a 64-bit ARM phone (uname -m says $(uname -m))"
  mkdir -p "$PR_HOME" "$PR_CLAUDE_DIR" "$PR_SETTINGS_DIR"
  : >"$PR_LOG"
  acquire_lock
  export DEBIAN_FRONTEND=noninteractive
}

step_base_packages() {
  step "Installing base packages"
  local missing
  if ! pkg_installed glibc-repo; then
    info "updating the package index (first run takes a while)"
    apt_refresh
    info "updating the packages that came with the app"
    apt_upgrade_progress
    info "adding the glibc package repository"
    apt_install_progress glibc-repo
  fi
  # shellcheck disable=SC2086
  missing="$(missing_packages $BASE_PACKAGES)"
  if [ -n "$missing" ]; then
    info "installing: $missing"
    apt_refresh
    # shellcheck disable=SC2086
    apt_install_progress $missing
  fi
  locate_glibc_tools
  ok "base packages ready"
  node_corepack_enable
}

# node_corepack_enable: pnpm and yarn at the version each project pins (its packageManager field), through the
# corepack that comes with Node: `corepack enable` links them into $PREFIX/bin. Runs after Node's install and every
# Node update, since a new Node brings a new corepack. A pnpm or yarn that isn't corepack's (pkg install pnpm,
# npm i -g yarn) is left alone, and a failure only warns.
node_corepack_enable() {
  local corepack="$PREFIX/bin/corepack" name target names=()
  [ -e "$corepack" ] || { info "this Node has no corepack, so pnpm and yarn are not set up"; return 0; }
  for name in pnpm yarn; do
    target="$(readlink -f "$PREFIX/bin/$name" 2>/dev/null || true)"
    if [ -e "$PREFIX/bin/$name" ] && [[ "$target" != */corepack/* ]]; then info "keeping the $name already installed"; continue; fi
    names+=("$name")
  done
  [ "${#names[@]}" -gt 0 ] || return 0
  if log_run "$PREFIX/bin/node" "$corepack" enable --install-directory "$PREFIX/bin" "${names[@]}"; then
    ok "${names[*]} ready through corepack"
  else
    warn "could not enable corepack for ${names[*]}; run corepack enable in a terminal"
  fi
}

locate_glibc_tools() {
  PATCHELF="$PREFIX/glibc/bin/patchelf"
  GLIBC_LD="$PREFIX/glibc/lib/ld-linux-aarch64.so.1"
  [ -x "$PATCHELF" ] || fail "patchelf not found at $PATCHELF (run the full setup first)"
  [ -f "$GLIBC_LD" ] || fail "glibc loader not found at $GLIBC_LD (run the full setup first)"
}

step_extra_packages() {
  step "Installing your extra tools"
  local p wanted="" missing
  if [ ! -e "$PR_TOOLS" ]; then
    printf '%s\n' "# Extra Termux packages to install at setup, one per line." \
      "# Lines starting with # are ignored. Example:" "# python" >"$PR_TOOLS"
  fi
  for p in $(read_tools_file "$PR_TOOLS"); do
    valid_package_name "$p" || { warn "skipping invalid package name in tools.txt: $p"; continue; }
    wanted="$wanted $p"
  done
  if [ -z "$wanted" ]; then ok "no extra tools listed in $PR_TOOLS"; return 0; fi
  # shellcheck disable=SC2086
  missing="$(missing_packages $wanted)"
  if [ -z "$missing" ]; then ok "extra tools already installed"; return 0; fi
  info "installing: $missing"
  apt_refresh
  # shellcheck disable=SC2086
  apt_install_progress $missing
  ok "extra tools installed"
}

# Sets NEW_VERSION and NEW_DIR (a ready, verified, unpatched staging dir) or
# NEW_VERSION only when that version is already installed.
step_download() {
  step "Downloading Claude Code"
  local want="${POCKETROCKET_CLAUDE_VERSION:-latest}" stage size
  NEW_VERSION="$(resolve_version "$want")" || fail "could not find Claude Code version '$want' on downloads.claude.ai"
  info "Claude Code $NEW_VERSION"
  NEW_DIR=""
  if [ -x "$PR_CLAUDE_DIR/$NEW_VERSION/claude" ] && [ -f "$PR_CLAUDE_DIR/$NEW_VERSION/.verified" ]; then
    ok "already downloaded and verified"
    return 0
  fi
  stage="$PR_CLAUDE_DIR/.staging-$NEW_VERSION"
  mkdir -p "$stage"
  curl -fsSL --retry 3 --max-time 30 "$DL_BASE/$NEW_VERSION/manifest.json" -o "$stage/manifest.json" \
    || fail "could not download the release manifest"
  size="$(manifest_field "$stage/manifest.json" size 2>/dev/null || echo 0)"
  info "downloading $PLATFORM binary ($(( size / 1048576 )) MB)"
  # Resumes a partial download left by an earlier interrupted run.
  if ! download_with_progress "$DL_BASE/$NEW_VERSION/$PLATFORM/claude" "$stage/claude.download" "$size"; then
    # A corrupt partial cannot be resumed; start clean next time.
    rm -f "$stage/claude.download"
    fail "download failed (check the network and run setup again)"
  fi
  info "checking SHA-256 against Anthropic's manifest"
  local why
  if ! why="$(verify_binary "$stage/claude.download" "$stage/manifest.json" "$NEW_VERSION" 2>&1)"; then
    rm -rf "$stage"
    fail "verification failed: $why"
  fi
  mv "$stage/claude.download" "$stage/claude"
  sha256_of "$stage/claude" >"$stage/original.sha256"
  NEW_DIR="$stage"
  ok "checksum matches the manifest"
}

step_patch_and_test() {
  step "Preparing Claude Code for Android"
  local cur dest out rc
  cur="$(current_version || true)"
  dest="$PR_CLAUDE_DIR/$NEW_VERSION"
  if [ -n "$NEW_DIR" ]; then
    chmod +x "$NEW_DIR/claude"
    LD_PRELOAD='' "$PATCHELF" --set-interpreter "$GLIBC_LD" "$NEW_DIR/claude" >>"$PR_LOG" 2>&1 \
      || { rm -rf "$NEW_DIR"; fail "patchelf could not set the ELF interpreter"; }
    ok "interpreter set to $GLIBC_LD"
    info "smoke test: claude --version"
    out="$(LD_PRELOAD='' DISABLE_AUTOUPDATER=1 timeout 60 "$NEW_DIR/claude" --version 2>&1)" || out="FAILED: $out"
    if ! printf '%s' "$out" | grep -qF "$NEW_VERSION"; then
      rm -rf "$NEW_DIR"
      keep_or_fail "$cur" "Claude Code $NEW_VERSION did not start: $out"
      return 0
    fi
    info "smoke test: full start"
    rc=0; smoke_test "$NEW_DIR/claude" || rc=$?
    if [ "$rc" -eq 1 ]; then
      rm -rf "$NEW_DIR"
      keep_or_fail "$cur" "Claude Code $NEW_VERSION crashes on this device (known upstream issue on some releases)"
      return 0
    fi
    [ "$rc" -eq 2 ] && warn "full-start probe was inconclusive (slow device?); --version passed, continuing"
    date +%s >"$NEW_DIR/.verified"
    rm -rf "$dest"
    mv "$NEW_DIR" "$dest"
  else
    # Already installed: re-apply the interpreter in case the glibc path moved.
    if [ "$(LD_PRELOAD='' "$PATCHELF" --print-interpreter "$dest/claude" 2>/dev/null)" != "$GLIBC_LD" ]; then
      LD_PRELOAD='' "$PATCHELF" --set-interpreter "$GLIBC_LD" "$dest/claude" >>"$PR_LOG" 2>&1 \
        || fail "patchelf could not repair $dest/claude"
    fi
  fi
  # Flip current only now that the new version has passed.
  switch_current "$NEW_VERSION"
  ok "current version is $NEW_VERSION"
  prune_versions "$NEW_VERSION"
}

# A broken new release must not replace a working one (R9).
keep_or_fail() {
  local cur="$1" msg="$2"
  if [ -n "$cur" ] && [ -x "$PR_CLAUDE_DIR/$cur/claude" ]; then
    warn "$msg"
    warn "keeping Claude Code $cur"
    NEW_VERSION="$cur"
  else
    fail "$msg"
  fi
}

# install_launcher <name>: copy the launcher shipped next to this script to $PREFIX/bin/<name>.
install_launcher() {
  local here launcher
  here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  launcher="$here/$1"
  [ -f "$launcher" ] || fail "launcher not found next to this script: $launcher"
  install -m 0755 "$launcher" "$PREFIX/bin/$1" || fail "could not install $PREFIX/bin/$1"
  ok "launcher at $PREFIX/bin/$1"
}

# claude_launchers: `claude`, and `pocketrocket-bash`, the shell it gives Claude Code's Bash tool (with Termux's exec
# helper, so `#!/usr/bin/env` scripts run). Updates and rollbacks write them again, so a phone set up by an
# older APK gets the current ones then.
claude_launchers() {
  install_launcher claude
  install_launcher pocketrocket-bash
}

step_defaults() {
  step "Writing launcher and defaults"
  claude_launchers

  write_claude_defaults "${CLAUDE_CONFIG_DIR:-$HOME/.claude}"
  ok "Claude settings: autoUpdates false, USE_BUILTIN_RIPGREP=0"

  # Login shells source $PREFIX/etc/profile.d/*.sh in Termux.
  mkdir -p "$PREFIX/etc/profile.d"
  printf '%s\n' "# Written by PocketRocket setup." "export USE_BUILTIN_RIPGREP=0" \
    >"$PREFIX/etc/profile.d/pocketrocket.sh"
  ok "USE_BUILTIN_RIPGREP=0 set for every session"

  write_motd
  ok "welcome message written to $PREFIX/etc/motd"

  case "${POCKETROCKET_DNS_PRELOAD:-}" in
    1)
      printf '%s\n' 'try { require("dns").setServers(["8.8.8.8", "8.8.4.4"]); } catch (e) {}' >"$PR_SETDNS"
      echo 1 >"$PR_SETTINGS_DIR/dns_preload"
      ok "DNS preload to 8.8.8.8 on"
      ;;
    0)
      rm -f "$PR_SETTINGS_DIR/dns_preload"
      ok "DNS preload off"
      ;;
    *) [ -f "$PR_SETTINGS_DIR/dns_preload" ] && info "DNS preload stays on" || info "DNS preload off (default)" ;;
  esac
}

step_final_check() {
  step "Checking claude --version"
  local out
  hash -r 2>/dev/null || true
  out="$("$PREFIX/bin/claude" --version 2>&1)" || fail "claude --version failed: $out"
  printf '%s' "$out" | grep -qF "$NEW_VERSION" || fail "claude --version printed '$out', expected $NEW_VERSION"
  ok "$out"
}

# switch_current <version> [dir]: point `current` at an installed version, atomically.
switch_current() {
  local dir="${2:-$PR_CLAUDE_DIR}"
  ln -sfn "$1" "$dir/current.new"
  mv -fT "$dir/current.new" "$dir/current"
}

# --switch X.Y.Z (rollback): point `current` at the kept version, smoke-test it, and point it back if
# it does not start.
step_switch() {
  local want="$1" cur out rc=0 why=""
  step "Switching to Claude Code $want"
  is_version "$want" || fail "not a version: $want"
  [ -x "$PR_CLAUDE_DIR/$want/claude" ] || fail "Claude Code $want is not installed"
  cur="$(current_version || true)"
  NEW_VERSION="$want"
  if [ "$cur" = "$want" ]; then ok "Claude Code $want is already current"; return 0; fi
  switch_current "$want"
  info "smoke test: claude --version"
  out="$(LD_PRELOAD='' DISABLE_AUTOUPDATER=1 timeout 60 "$PR_CLAUDE_DIR/current/claude" --version 2>&1)" || out="FAILED: $out"
  if ! printf '%s' "$out" | grep -qF "$want"; then
    why="it did not start: $out"
  else
    info "smoke test: full start"
    smoke_test "$PR_CLAUDE_DIR/current/claude" || rc=$?
    [ "$rc" -eq 1 ] && why="it crashes on this device"
    [ "$rc" -eq 2 ] && warn "full-start probe was inconclusive (slow device?); --version passed, continuing"
  fi
  if [ -n "$why" ]; then
    [ -n "$cur" ] && switch_current "$cur"
    fail "did not switch to Claude Code $want, $why. Staying on ${cur:-nothing}."
  fi
  ok "current version is $want"
  prune_versions "$want"
}

# ---------------------------------------------------------------- Dockito (optional)
# Dockito's CLI is a Bun-compiled glibc binary like Claude Code, so it goes through the same glibc
# loader. The app reads the version, URL and SHA-256 from Dockito's Homebrew formula and passes them in;
# the download is refused unless it matches that SHA-256.

dockito_download() {
  local v="${POCKETROCKET_DOCKITO_VERSION:-}" url="${POCKETROCKET_DOCKITO_URL:-}" sum="${POCKETROCKET_DOCKITO_SHA256:-}" stage why
  is_version "$v" || fail "not a Dockito version: '$v'"
  version_at_least "$v" "$DOCKITO_MIN_VERSION" || fail "Dockito $v is too old: PocketRocket needs $DOCKITO_MIN_VERSION or newer"
  dockito_url_ok "$v" "$url" || fail "not a Dockito release URL: $url"
  NEW_VERSION="$v"; NEW_DIR=""
  info "Dockito $v"
  if [ -x "$PR_DOCKITO_DIR/$v/dockito" ] && [ -f "$PR_DOCKITO_DIR/$v/.verified" ]; then
    ok "already downloaded and verified"
    return 0
  fi
  stage="$PR_DOCKITO_DIR/.staging-$v"
  mkdir -p "$stage"
  if ! download_with_progress "$url" "$stage/dockito.tar.gz"; then
    rm -f "$stage/dockito.tar.gz"
    fail "download failed (check the network and try again)"
  fi
  info "checking SHA-256 against Dockito's Homebrew formula"
  if ! why="$(verify_sha256 "$stage/dockito.tar.gz" "$sum" 2>&1)"; then
    rm -rf "$stage"
    fail "verification failed: $why"
  fi
  ok "checksum matches the formula"
  tar -xzf "$stage/dockito.tar.gz" -C "$stage" dockito >>"$PR_LOG" 2>&1 || { rm -rf "$stage"; fail "could not unpack the download"; }
  rm -f "$stage/dockito.tar.gz"
  NEW_DIR="$stage"
}

dockito_install() {
  local dest="$PR_DOCKITO_DIR/$NEW_VERSION" out
  if [ -n "$NEW_DIR" ]; then
    chmod +x "$NEW_DIR/dockito"
    LD_PRELOAD='' "$PATCHELF" --set-interpreter "$GLIBC_LD" "$NEW_DIR/dockito" >>"$PR_LOG" 2>&1 \
      || { rm -rf "$NEW_DIR"; fail "patchelf could not set the ELF interpreter"; }
    ok "interpreter set to $GLIBC_LD"
    info "smoke test: dockito --version"
    out="$(LD_PRELOAD='' timeout 60 "$NEW_DIR/dockito" --version 2>&1)" || out="FAILED: $out"
    if ! printf '%s' "$out" | grep -qF "$NEW_VERSION"; then
      rm -rf "$NEW_DIR"
      fail "Dockito $NEW_VERSION did not start: $out"
    fi
    date +%s >"$NEW_DIR/.verified"
    rm -rf "$dest"
    mv "$NEW_DIR" "$dest"
  elif [ "$(LD_PRELOAD='' "$PATCHELF" --print-interpreter "$dest/dockito" 2>/dev/null)" != "$GLIBC_LD" ]; then
    LD_PRELOAD='' "$PATCHELF" --set-interpreter "$GLIBC_LD" "$dest/dockito" >>"$PR_LOG" 2>&1 \
      || fail "patchelf could not repair $dest/dockito"
  fi
  switch_current "$NEW_VERSION" "$PR_DOCKITO_DIR"
  ok "current version is $NEW_VERSION"
  prune_versions "$NEW_VERSION" "$PR_DOCKITO_DIR" 0 Dockito
  install_launcher dockito
  install_launcher dockito-accounts
}

# Headless Chromium so Claude can test the pages it builds (decision 37). Termux's x11-repo
# package, plus the Chrome DevTools MCP server measured in the spike. About 187 MB download, 838 MB installed.
CHROMIUM_MCP_VERSION=1.12.0

step_chromium_repo() {
  step "Enabling the x11 repository"
  if pkg_installed x11-repo; then ok "x11 repo already enabled"; return 0; fi
  apt_refresh
  apt_install x11-repo
  ok "x11 repo enabled"
}

step_chromium_install() {
  step "Installing Chromium"
  if pkg_installed chromium && [ -x "$PREFIX/bin/chromium-browser" ]; then
    ok "chromium already installed"
    return 0
  fi
  apt_refresh
  apt_install_progress chromium
  [ -x "$PREFIX/bin/chromium-browser" ] || fail "chromium-browser is not on PATH after install"
  ok "chromium installed"
}

step_chromium_mcp() {
  step "Installing Chrome DevTools MCP"
  log_run npm install -g "chrome-devtools-mcp@${CHROMIUM_MCP_VERSION}" \
    || fail "npm install chrome-devtools-mcp failed"
  [ -x "$PREFIX/bin/chrome-devtools-mcp" ] || fail "chrome-devtools-mcp is not on PATH"
  local out
  out="$(npm list -g --depth=0 chrome-devtools-mcp 2>/dev/null || true)"
  printf '%s' "$out" | grep -qF "chrome-devtools-mcp@${CHROMIUM_MCP_VERSION}" \
    || fail "chrome-devtools-mcp did not install at ${CHROMIUM_MCP_VERSION}: $out"
  ok "chrome-devtools-mcp ${CHROMIUM_MCP_VERSION}"
  printf '[done]\n'
}

step_chromium_remove() {
  step "Removing Chromium"
  if pkg_installed chromium; then
    log_run apt-get remove "${APT_OPTS[@]}" chromium || fail "could not remove chromium"
    # Autoremove may only take what came with Chromium. Pin the base set as manual first so it
    # can never be swept up, whatever the earlier installs marked it as.
    local p
    for p in $BASE_PACKAGES glibc-repo; do
      if pkg_installed "$p"; then apt-mark manual "$p" >/dev/null 2>&1 || true; fi
    done
    log_run apt-get autoremove "${APT_OPTS[@]}" || warn "autoremove did not finish; Chromium itself is removed"
  fi
  if pkg_installed x11-repo; then
    # Packages the x11 repo supplies are listed as "name/x11". Keep the repo while any is installed.
    local others
    others="$(apt list --installed 2>/dev/null | grep '/x11[ ,]' | cut -d/ -f1 | grep -vx x11-repo | tr '\n' ' ' || true)"
    if [ -n "$others" ]; then
      info "keeping x11-repo, still needed by: $others"
    else
      log_run apt-get remove "${APT_OPTS[@]}" x11-repo || warn "could not remove x11-repo"
    fi
  fi
  ok "chromium removed"
}

step_chromium_mcp_remove() {
  step "Removing Chrome DevTools MCP"
  if [ -e "$PREFIX/lib/node_modules/chrome-devtools-mcp" ] || [ -x "$PREFIX/bin/chrome-devtools-mcp" ]; then
    log_run npm uninstall -g chrome-devtools-mcp || fail "npm uninstall chrome-devtools-mcp failed"
  fi
  chromium_remove_leftovers
  ok "chrome-devtools-mcp removed"
  printf '[done]\n'
}

# What Chromium and its MCP server leave in the home folder (profiles, caches, the server's state), removed with them
# (decision 102). The one list; the tests read it.
chromium_leftovers() {
  local config="${XDG_CONFIG_HOME:-$HOME/.config}" cache="${XDG_CACHE_HOME:-$HOME/.cache}"
  printf '%s\n' "$config/chromium" "$cache/chromium" "$cache/chrome-devtools-mcp" "$config/configstore/update-notifier-chrome-devtools-mcp.json"
}

chromium_remove_leftovers() {
  local p
  while IFS= read -r p; do rm -rf "${p:?}"; done < <(chromium_leftovers)
  rmdir "$HOME/.config/configstore" "$HOME/.config" "$HOME/.cache" 2>/dev/null || true
}

# Android build, an optional tool (decision 45, docs/android-build.md): Java, Gradle and Termux's arm64 aapt2 and
# aidl from the Termux repos, the SDK platform and build-tools from Google, wired so terminals and sessions can build.
# Google's zips are pinned to the sha1 in Google's manifest (repository2-3.xml). sdkmanager is not used: since
# cmdline-tools 16111833 it hands off to an x86_64 binary that cannot run here. So the script writes the package.xml
# sdkmanager would have, and the Android Gradle Plugin treats the packages as installed.
ANDROID_BUILD_PACKAGES="openjdk-17 gradle aapt2 aapt aidl android-tools unzip"
ANDROID_SDK_DIR="${POCKETROCKET_ANDROID_SDK_DIR:-${PREFIX:-}/opt/android-sdk}" # the knob is for tests into a scratch folder
ANDROID_JAVA_HOME="${PREFIX:-}/lib/jvm/java-17-openjdk"
ANDROID_PLATFORM=android-37.0
ANDROID_PLATFORM_ZIP=platform-37.0_r02.zip
ANDROID_PLATFORM_SHA1=ed8ebf7f8822a4de5686d427f237d2fa30ff7410
ANDROID_BUILD_TOOLS=36.0.0
ANDROID_BUILD_TOOLS_ZIP=build-tools_r36_linux.zip
ANDROID_BUILD_TOOLS_SHA1=b0b6376977657e8ad9b969bacf4093601da2c6fb
ANDROID_REPO_URL=https://dl.google.com/android/repository
ANDROID_PROFILE="${PREFIX:-}/etc/profile.d/pocketrocket-android-build.sh"
ANDROID_ADDED_FILE="$PR_HOME/android-build.packages"   # what this tool installed, so uninstall removes only that
ANDROID_BASELINE_FILE="$PR_HOME/android-build.baseline" # the packages before a run, until its apt step is recorded
ANDROID_GRADLE_BEGIN="# >>> PocketRocket Android build (removed on uninstall)"
ANDROID_GRADLE_END="# <<< PocketRocket Android build"

# verify_sha1 <file> <expected sha1>: 0 only when the file's SHA-1 is the expected one.
verify_sha1() {
  local have
  have="$(sha1sum "$1" 2>/dev/null | awk '{print $1}')"
  [ -n "$have" ] && [ "$have" = "$2" ]
}

# android_sdk_ready: the Android tool's SDK can build, whoever set it up: Java, a platform and build tools the plugin
# recognises. No flag is written at install; the app reads the same pieces (AndroidBuildTool.presence).
android_sdk_ready() {
  local p b
  [ -x "$ANDROID_JAVA_HOME/bin/java" ] || return 1
  for p in "$ANDROID_SDK_DIR"/platforms/*/package.xml; do
    [ -f "$p" ] || continue
    for b in "$ANDROID_SDK_DIR"/build-tools/*/package.xml; do [ -f "$b" ] && return 0; done
  done
  return 1
}

# android_package_xml <dir> <sdk path> <major> <minor> <micro> <display name> [platform api level] [extension level]
# The file sdkmanager writes after installing a package; without it the plugin does not see the package.
android_package_xml() {
  local details='xsi:type="ns5:genericDetailsType"/>'
  if [ -n "${7:-}" ]; then
    details="xsi:type=\"ns6:platformDetailsType\"><api-level>$7</api-level><codename></codename><extension-level>$8</extension-level><base-extension>true</base-extension><layoutlib api=\"15\"/></type-details>"
  fi
  cat >"$1/package.xml" <<EOF
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<ns2:repository xmlns:ns2="http://schemas.android.com/repository/android/common/02" xmlns:ns5="http://schemas.android.com/repository/android/generic/02" xmlns:ns6="http://schemas.android.com/sdk/android/repo/repository2/04">
<localPackage path="$2" obsolete="false"><type-details xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" $details<revision><major>$3</major><minor>$4</minor><micro>$5</micro></revision><display-name>$6</display-name></localPackage>
</ns2:repository>
EOF
}

# gradle_props_set <gradle.properties> <key> <value>: one key, replaced where it is (later copies go) or added at the
# end; every other line stays as it was. For any tool that writes ~/.gradle/gradle.properties.
gradle_props_set() {
  mkdir -p "$(dirname "$1")"
  [ -f "$1" ] || : >"$1"
  awk -v k="$2" -v v="$3" '
    { t = $0; sub(/^[ \t]+/, "", t) }
    index(t, k) == 1 && substr(t, length(k) + 1) ~ /^[ \t]*[=:]/ { if (!done) print k "=" v; done = 1; next }
    { print }
    END { if (!done) print k "=" v }' "$1" >"$1.prtmp" && mv -f "$1.prtmp" "$1"
}

# gradle_props_unset <gradle.properties> <key>: takes the key out; deletes the file if nothing else is left.
gradle_props_unset() {
  [ -f "$1" ] || return 0
  awk -v k="$2" '{ t = $0; sub(/^[ \t]+/, "", t) } index(t, k) == 1 && substr(t, length(k) + 1) ~ /^[ \t]*[=:]/ { next } { print }' \
    "$1" >"$1.prtmp" && mv -f "$1.prtmp" "$1"
  if ! grep -q '[^[:space:]]' "$1"; then rm -f "$1"; fi
}

# android_gradle_props_remove <gradle.properties>: takes out only our block; deletes the file if nothing else is left.
android_gradle_props_remove() {
  [ -f "$1" ] || return 0
  sed -i.prbak -e "/^$ANDROID_GRADLE_BEGIN\$/,/^$ANDROID_GRADLE_END\$/d" "$1"
  rm -f "$1.prbak"
  if ! grep -q '[^[:space:]]' "$1"; then rm -f "$1"; fi
}

# android_gradle_props_add <gradle.properties> <aapt2 path>: the plugin's own aapt2 is x86_64, so point it at Termux's.
# A daemon idles for 3 hours by default, holding over 1 GB; on a phone it goes after 10 minutes.
# Memory caps sized from the phone's RAM (decision 97): this file wins over a project's gradle.properties, so a project
# asking for -Xmx4g gets a quarter of its build slot's share (1536 MB on a 12 GB phone), two workers, and Kotlin
# compiled inside Gradle's own JVM instead of a second daemon.
android_gradle_props_add() {
  local heap
  heap="$(bash "$(dirname "${BASH_SOURCE[0]}")/pocketrocket-build-slot" config 2>/dev/null | sed -n 's/^heap_mb=//p')"
  case "$heap" in ''|*[!0-9]*) heap=1536 ;; esac
  android_gradle_props_remove "$1"
  mkdir -p "$(dirname "$1")"
  printf '%s\n' "$ANDROID_GRADLE_BEGIN" "android.aapt2FromMavenOverride=$2" \
    "org.gradle.daemon.idletimeout=600000" \
    "org.gradle.jvmargs=-Xmx${heap}m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8" \
    "org.gradle.workers.max=2" \
    "kotlin.compiler.execution.strategy=in-process" "$ANDROID_GRADLE_END" >>"$1"
}

# The build-slot Gradle hook (decision 97), from the template beside this script; the app refreshes the same file at
# every start (BuildSlots.kt) and removes it once the Android tool is gone.
ANDROID_SLOTS_INIT_SCRIPT="$HOME/.gradle/init.d/pocketrocket-build-slots.gradle"
android_slots_write_hook() {
  local tmp="$ANDROID_SLOTS_INIT_SCRIPT.tmp"
  mkdir -p "$(dirname "$ANDROID_SLOTS_INIT_SCRIPT")"
  sed -e "s|@POCKETROCKET_BUILD_SLOT@|$PREFIX/bin/pocketrocket-build-slot|g" \
    "$(dirname "${BASH_SOURCE[0]}")/pocketrocket-build-slots.gradle" >"$tmp" && mv -f "$tmp" "$ANDROID_SLOTS_INIT_SCRIPT"
}

# android_installed_packages: packages really installed. `dpkg-query -W` alone also lists removed ones that kept their
# config files, and a reinstall would then count them as already there and never take them off.
android_installed_packages() {
  dpkg-query -W -f '${db:Status-Status} ${Package}\n' | awk '$1 == "installed" { print $2 }'
}

# android_new_packages <baseline list> <current list>: the packages in current that are not in baseline.
android_new_packages() {
  sort -u "$1" >"$1.sorted"; sort -u "$2" >"$2.sorted"
  comm -13 "$1.sorted" "$2.sorted"
  rm -f "$1.sorted" "$2.sorted"
}

# android_removable <sim output of apt-get -s purge> <allowed pkgs…>: 0 when apt would remove only allowed packages.
android_removable() {
  local sim="$1" p; shift
  for p in $(printf '%s\n' "$sim" | awk '$1 == "Purg" || $1 == "Remv" {print $2}'); do
    case " $* " in *" $p "*) ;; *) return 1 ;; esac
  done
}

android_sdk_zip() {
  local zip="$1" sha1="$2" tmp="$TMPDIR/$1"
  if ! verify_sha1 "$tmp" "$sha1"; then
    download_with_progress "$ANDROID_REPO_URL/$zip" "$tmp" || fail "The download of $zip stopped. Check the connection and try again."
    verify_sha1 "$tmp" "$sha1" || { rm -f "$tmp"; fail "$zip does not match Google's checksum. Try again."; }
  fi
}

step_android_packages() {
  step "Installing Java, Gradle and build tools"
  apt_refresh
  # The baseline goes once recorded, so a later run never counts what other tools (Flutter & Rust) added since.
  packages_baseline "$ANDROID_BASELINE_FILE" "$ANDROID_ADDED_FILE"
  # Java 17 goes first: gradle accepts any JDK, and on its own apt would pick 21.
  apt_install_progress openjdk-17
  apt_install_progress $ANDROID_BUILD_PACKAGES
  packages_record "$ANDROID_BASELINE_FILE" "$ANDROID_ADDED_FILE"
  [ -x "$ANDROID_JAVA_HOME/bin/java" ] || fail "Java 17 is missing after install"
  [ -x "$PREFIX/bin/gradle" ] || fail "gradle is missing after install"
  ok "packages installed"
}

step_android_sdk() {
  step "Downloading the Android SDK"
  [ -n "${POCKETROCKET_SDK_LICENSE_HASH:-}" ] || fail "The Android SDK license was not accepted."
  mkdir -p "$ANDROID_SDK_DIR/licenses" "$ANDROID_SDK_DIR/platforms" "$ANDROID_SDK_DIR/build-tools" "$ANDROID_SDK_DIR/platform-tools"
  # The user just accepted it on the tool page. The same file `sdkmanager --licenses` writes.
  printf '\n%s' "$POCKETROCKET_SDK_LICENSE_HASH" >"$ANDROID_SDK_DIR/licenses/android-sdk-license"

  local platform="$ANDROID_SDK_DIR/platforms/$ANDROID_PLATFORM" tools="$ANDROID_SDK_DIR/build-tools/$ANDROID_BUILD_TOOLS" d
  if [ ! -f "$platform/package.xml" ]; then
    android_sdk_zip "$ANDROID_PLATFORM_ZIP" "$ANDROID_PLATFORM_SHA1"
    rm -rf "$platform"
    log_run unzip -q -o "$TMPDIR/$ANDROID_PLATFORM_ZIP" -d "$ANDROID_SDK_DIR/platforms" || fail "could not unpack $ANDROID_PLATFORM_ZIP"
    android_package_xml "$platform" "platforms;$ANDROID_PLATFORM" 2 0 0 "Android SDK Platform 37.0" 37.0 22
    rm -f "$TMPDIR/$ANDROID_PLATFORM_ZIP"
  fi
  if [ ! -f "$tools/package.xml" ]; then
    android_sdk_zip "$ANDROID_BUILD_TOOLS_ZIP" "$ANDROID_BUILD_TOOLS_SHA1"
    rm -rf "$tools"
    d="$TMPDIR/android-build-tools"; rm -rf "$d"; mkdir -p "$d"
    log_run unzip -q -o "$TMPDIR/$ANDROID_BUILD_TOOLS_ZIP" -d "$d" || fail "could not unpack $ANDROID_BUILD_TOOLS_ZIP"
    mv "$d"/* "$tools"; rm -rf "$d"
    android_package_xml "$tools" "build-tools;$ANDROID_BUILD_TOOLS" 36 0 0 "Android SDK Build-Tools 36"
    rm -f "$TMPDIR/$ANDROID_BUILD_TOOLS_ZIP"
  fi
  ok "SDK downloaded and checked"
}

step_android_wire() {
  step "Setting up builds"
  local tools="$ANDROID_SDK_DIR/build-tools/$ANDROID_BUILD_TOOLS" sdkplat="$ANDROID_SDK_DIR/platform-tools" b
  # Google's binaries are x86_64 and cannot run on the phone. These three have arm64 builds in Termux.
  for b in aapt aapt2 aidl; do ln -sf "$PREFIX/bin/$b" "$tools/$b"; done
  for b in adb fastboot; do ln -sf "$PREFIX/bin/$b" "$sdkplat/$b"; done
  android_package_xml "$sdkplat" platform-tools 37 0 0 "Android SDK Platform-Tools"

  android_gradle_props_add "$HOME/.gradle/gradle.properties" "$PREFIX/bin/aapt2"
  mkdir -p "$(dirname "$ANDROID_PROFILE")"
  printf '%s\n' "# Written by PocketRocket's Android build tool. Removed on uninstall." \
    "export JAVA_HOME=$ANDROID_JAVA_HOME" "export ANDROID_HOME=$ANDROID_SDK_DIR" >"$ANDROID_PROFILE"
  # The Gradle hook ships with this tool too (decision 96): without the NDK, native tasks fail plainly instead of the
  # plugin downloading Google's x86_64 NDK, and Kotlin, Java and unit tests still run.
  android_ndk_write_hook
  # Builds take turns in the phone's build slots (decision 97); the app installs pocketrocket-build-slot itself.
  android_slots_write_hook || fail "could not write $ANDROID_SLOTS_INIT_SCRIPT"
  ok "ANDROID_HOME, JAVA_HOME and aapt2 set for every session, builds take turns"
  printf '[done]\n'
}

step_android_remove_files() {
  step "Removing the Android SDK"
  # A running daemon would keep writing into the folders about to go.
  pkill -f GradleDaemon 2>/dev/null || true
  pkill -f KotlinCompileDaemon 2>/dev/null || true
  # The NDK tool sits on top of this one, so it goes with it (decision 50), and so does the Gradle hook it owns.
  android_ndk_remove
  rm -f "$ANDROID_SLOTS_INIT_SCRIPT"
  android_ndk_remove_hook
  rm -rf "$ANDROID_SDK_DIR"; rmdir "$PREFIX/opt" 2>/dev/null || true
  rm -f "$ANDROID_PROFILE"
  android_gradle_props_remove "$HOME/.gradle/gradle.properties"
  # What builds downloaded (about 800 MB after one Compose build). Your own gradle.properties lines and init scripts stay.
  if [ -d "$HOME/.gradle" ]; then
    find "$HOME/.gradle" -mindepth 1 -maxdepth 1 ! -name gradle.properties ! -name init.d -exec rm -rf {} +
    rmdir "$HOME/.gradle" 2>/dev/null || true
  fi
  rm -rf "$HOME/.kotlin"
  ok "SDK and Gradle downloads removed"
}

# android_purge_added <list file>: purges the packages in the list, leaving any that something else now needs.
android_purge_added() {
  local added="" installed="" p sim kept=""
  [ -f "$1" ] && added="$(tr '\n' ' ' <"$1")"
  for p in $added; do pkg_installed "$p" && installed="$installed $p"; done
  # shellcheck disable=SC2086
  if [ -n "$installed" ]; then
    # The whole set at once: apt's resolver refuses to take some libraries on their own.
    sim="$(apt-get purge -s "${APT_OPTS[@]}" $installed 2>/dev/null)" || sim=""
    if [ -n "$sim" ] && android_removable "$sim" $added; then
      log_run apt-get purge "${APT_OPTS[@]}" $installed || fail "could not remove the packages; see $PR_LOG"
    else
      # Something else now needs part of it (a user's maven needs Java). Take only what goes without touching that.
      for p in $installed; do
        pkg_installed "$p" || continue
        sim="$(apt-get purge -s "${APT_OPTS[@]}" "$p" 2>/dev/null)" || sim=""
        if [ -n "$sim" ] && android_removable "$sim" $added; then
          log_run apt-get purge "${APT_OPTS[@]}" "$p" || kept="$kept $p"
        else
          kept="$kept $p"
        fi
      done
    fi
  fi
  [ -z "$kept" ] || info "kept because other packages need them:$kept"
}

step_android_remove_packages() {
  step "Removing Java, Gradle and build tools"
  # shellcheck disable=SC2086
  if flutter_present; then packages_hand_over "$ANDROID_ADDED_FILE" "$FLUTTER_ADDED_FILE" $FLUTTER_PACKAGES; fi
  android_purge_added "$ANDROID_ADDED_FILE"
  rm -f "$ANDROID_ADDED_FILE" "$ANDROID_BASELINE_FILE"
  ok "packages removed"
  printf '[done]\n'
}

# C and C++, an optional tool for any C or C++ project (decision 96, docs/android-build.md): Termux's compilers and build
# tools. A package tool like Python and Bun (pkg_tool_* below; the app reads dpkg, CppTool.PACKAGES), with one twist:
# the Android NDK tool builds on it, so removing C and C++ removes the NDK first.
CPP_PACKAGES="clang lld llvm cmake ninja make pkg-config lldb"
CPP_ADDED_FILE="$PR_HOME/c-cpp.packages"   # what its install brought in (pkg_tool_purge's list)

# Every package is there, whoever installed it: the NDK install skips its C and C++ step.
cpp_complete() {
  # shellcheck disable=SC2086
  [ -z "$(missing_packages $CPP_PACKAGES)" ]
}

step_cpp_install() { step_pkg_tool_install c-cpp; }

step_cpp_remove() {
  if android_ndk_present; then
    step "Removing the Android NDK"
    android_ndk_remove
    ok "Android NDK removed"
  fi
  step_pkg_tool_remove c-cpp
}

# Android NDK, an optional tool on top of Android build and C and C++ (decisions 50 and 96, docs/android-build.md).
# Google publishes no arm64 NDK, so the shim built by android-ndk.sh (beside this script) stands in: Termux's clang
# behind folders laid out the way the Android Gradle Plugin expects, Google's ndk-build from AOSP, and four ABIs from
# Termux's ndk-multilib. Each version below is a folder that only claims that revision, for builds that look the NDK
# up by version without the Gradle hook; with the hook any ndkVersion goes to the shim. docs/android-ndk-gaps-spike.md.
# shellcheck source=android-ndk.sh
. "$(dirname "${BASH_SOURCE[0]}")/android-ndk.sh"
# The default NDK of each plugin: 8.0-8.3, 8.4-8.6, 8.7-8.13, 9.0-9.5.
ANDROID_NDK_VERSIONS="25.1.8937393 26.1.10909125 27.0.12077973 28.2.13676358"
ANDROID_CMAKE_VERSION=3.22.1 # The plugin's default. Without this folder it would download Google's x86_64 CMake.
ANDROID_NDK_DIR="$ANDROID_SDK_DIR/ndk"
ANDROID_NDK_SHIM_DIR="$ANDROID_SDK_DIR/ndk-shim"
ANDROID_NDK_PLACEHOLDER_DIR="$ANDROID_SDK_DIR/ndk-placeholder" # the Gradle hook's stand-in while no NDK is installed
ANDROID_CMAKE_DIR="$ANDROID_SDK_DIR/cmake"
# Installs before decision 96 brought the compilers in themselves and listed them here; C and C++ takes them over.
ANDROID_NDK_ADDED_FILE="$PR_HOME/android-ndk.packages"
ANDROID_NDK_WORK="${TMPDIR:-/tmp}/pocketrocket-ndk"
# The Gradle hook. The Android tool writes it and owns it (with no NDK it fails native tasks plainly and keeps the
# plugin from downloading Google's NDK); the NDK tool writes the same file, which then finds the shim.
ANDROID_NDK_INIT_SCRIPT="$HOME/.gradle/init.d/pocketrocket-android-ndk.gradle"

android_ndk_present() { [ -d "$ANDROID_NDK_SHIM_DIR" ] || [ -d "$ANDROID_NDK_SHIM_DIR.new" ] || [ -d "$ANDROID_NDK_DIR" ] || [ -f "$ANDROID_NDK_ADDED_FILE" ]; }

android_ndk_write_hook() {
  ndk_write_hook "$ANDROID_NDK_INIT_SCRIPT" "$ANDROID_NDK_SHIM_DIR" "$ANDROID_NDK_PLACEHOLDER_DIR" || fail "could not write $ANDROID_NDK_INIT_SCRIPT"
}

android_ndk_remove_hook() {
  rm -f "$ANDROID_NDK_INIT_SCRIPT"; rmdir "$(dirname "$ANDROID_NDK_INIT_SCRIPT")" 2>/dev/null || true
}

# android_ndk_package_xml <version>: the package.xml sdkmanager writes beside an NDK it installed (decision 104). Tools
# that check for an installed NDK that way (cargokit, for Flutter plugins in Rust) would otherwise run sdkmanager to
# download Google's x86_64 one.
android_ndk_package_xml() {
  local v="$1" major minor micro
  IFS=. read -r major minor micro <<<"$v"
  printf '%s\n' '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' \
    '<ns2:repository xmlns:ns2="http://schemas.android.com/repository/android/common/02" xmlns:ns5="http://schemas.android.com/repository/android/generic/02" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">' \
    "<localPackage path=\"ndk;$v\" obsolete=\"false\"><type-details xsi:type=\"ns5:genericDetailsType\"/><revision><major>$major</major><minor>${minor:-0}</minor><micro>${micro:-0}</micro></revision><display-name>NDK (Side by side) $v, PocketRocket: Termux clang</display-name></localPackage>" \
    '</ns2:repository>'
}

# android_ndk_version_folders: one folder per version claiming that revision, the rest links to the shim; and CMake.
android_ndk_version_folders() {
  local v d f b
  for v in $ANDROID_NDK_VERSIONS; do
    d="$ANDROID_NDK_DIR/$v"; mkdir -p "$d"
    printf 'Pkg.Desc = PocketRocket: Termux clang behind the NDK layout, not Google NDK %s\nPkg.Revision = %s\n' "$v" "$v" >"$d/source.properties"
    for f in meta build toolchains ndk-build; do ln -sfn "$ANDROID_NDK_SHIM_DIR/$f" "$d/$f"; done
    android_ndk_package_xml "$v" >"$d/package.xml"
  done
  d="$ANDROID_CMAKE_DIR/$ANDROID_CMAKE_VERSION"; mkdir -p "$d/bin"
  printf 'Pkg.Desc = PocketRocket: Termux CMake\nPkg.Revision = %s\n' "$ANDROID_CMAKE_VERSION" >"$d/source.properties"
  for b in cmake ctest cpack ninja; do ln -sfn "$PREFIX/bin/$b" "$d/bin/$b"; done
}

android_ndk_remove_folders() {
  rm -rf "${ANDROID_NDK_SHIM_DIR:?}" "${ANDROID_NDK_SHIM_DIR:?}.new" "${ANDROID_NDK_DIR:?}" "${ANDROID_CMAKE_DIR:?}/${ANDROID_CMAKE_VERSION:?}" "$ANDROID_NDK_WORK"
  rmdir "$ANDROID_CMAKE_DIR" 2>/dev/null || true
}

# The compilers an install before decision 96 brought in move to C and C++'s list, so removing the NDK leaves them.
android_ndk_hand_over_packages() {
  [ -f "$ANDROID_NDK_ADDED_FILE" ] || return 0
  # shellcheck disable=SC2046
  packages_hand_over "$ANDROID_NDK_ADDED_FILE" "$CPP_ADDED_FILE" $(cat "$ANDROID_NDK_ADDED_FILE")
  [ -s "$ANDROID_NDK_ADDED_FILE" ] || rm -f "$ANDROID_NDK_ADDED_FILE"
}

step_android_ndk_build() {
  step "Fetching ndk-build"
  android_sdk_ready || fail "Install the Android tool first."
  cpp_complete || fail "Install C and C++ first."
  rm -rf "$ANDROID_NDK_WORK/build"; mkdir -p "$ANDROID_NDK_WORK"
  ndk_fetch_build "$ANDROID_NDK_WORK/build" 2>>"$PR_LOG" || fail "Could not fetch ndk-build from android.googlesource.com. Check the connection and try again."
  ok "ndk-build from AOSP $NDK_BUILD_COMMIT, build/ tree $NDK_BUILD_TREE"
}

step_android_ndk_multilib() {
  step "Downloading the other ABIs"
  local name size pid rc=0
  apt_refresh
  read -r name size < <(ndk_multilib_info) || true
  [ -n "${name:-}" ] || fail "$NDK_MULTILIB_PACKAGE is not in the package index"
  ndk_multilib_download "$ANDROID_NDK_WORK" 2>>"$PR_LOG" &
  pid=$!
  watch_file "$pid" "$ANDROID_NDK_WORK/$name" "$size" "Downloading $NDK_MULTILIB_PACKAGE"
  wait "$pid" || rc=$?
  [ "$rc" -eq 0 ] || fail "The download of $NDK_MULTILIB_PACKAGE stopped. Check the connection and try again."
  progress 100 "Unpacking $NDK_MULTILIB_PACKAGE"
  ANDROID_NDK_MULTILIB="$(ndk_multilib_unpack "$ANDROID_NDK_WORK" 2>>"$PR_LOG")" || fail "could not unpack $NDK_MULTILIB_PACKAGE; see $PR_LOG"
  ok "$NDK_MULTILIB_PACKAGE $(apt_policy_field "$NDK_MULTILIB_PACKAGE" Candidate), checked by apt"
}

step_android_ndk_wire() {
  step "Setting up the NDK folders"
  progress 10 "Making stub libraries for four ABIs"
  ndk_make_shim "$ANDROID_NDK_SHIM_DIR" "$ANDROID_NDK_WORK/build" "$ANDROID_NDK_MULTILIB" 2>>"$PR_LOG" || fail "could not set up the NDK folders; see $PR_LOG"
  printf '%s\n' "$(apt_policy_field "$NDK_MULTILIB_PACKAGE" Candidate)" >"$ANDROID_NDK_SHIM_DIR/.ndk-multilib-version"
  rm -rf "$ANDROID_NDK_WORK"
  android_ndk_version_folders
  android_ndk_write_hook
  ok "NDK ready: ndk-build and CMake, arm64-v8a, armeabi-v7a, x86 and x86_64, any ndkVersion"
}

# Folders and what an install before decision 96 brought in. The hook stays while the Android tool is installed (it
# owns it) and now fails native tasks plainly. Removing Android or C and C++ runs this first.
android_ndk_remove() {
  android_ndk_remove_folders
  [ -d "$ANDROID_SDK_DIR" ] || android_ndk_remove_hook
  if [ -f "$ANDROID_NDK_ADDED_FILE" ]; then
    if pkg_installed clang; then android_ndk_hand_over_packages; fi
    android_purge_added "$ANDROID_NDK_ADDED_FILE"
    rm -f "$ANDROID_NDK_ADDED_FILE" "$PR_HOME/android-ndk.baseline"
  fi
}

# main_android_ndk <android-ndk|android-ndk-remove|c-cpp|c-cpp-remove>
main_android_ndk() {
  PR_LOG="$PR_HOME/$1.log"
  case "$1" in
    android-ndk) TOTAL_STEPS=4; cpp_complete || TOTAL_STEPS=5 ;;
    android-ndk-remove) TOTAL_STEPS=2 ;;
    c-cpp) TOTAL_STEPS=2 ;;
    c-cpp-remove) TOTAL_STEPS=2; if android_ndk_present; then TOTAL_STEPS=3; fi ;;
  esac
  step "Checking the device"; preflight_checks; ok "Termux on aarch64"
  case "$1" in
    android-ndk)
      android_sdk_ready || fail "Install the Android tool first."
      cpp_complete || step_cpp_install
      android_ndk_hand_over_packages
      step_android_ndk_build
      step_android_ndk_multilib
      step_android_ndk_wire ;;
    android-ndk-remove)
      step "Removing the Android NDK"
      android_ndk_remove
      ok "Android NDK removed; C and C++ stay" ;;
    c-cpp) step_cpp_install ;;
    c-cpp-remove) step_cpp_remove ;;
  esac
  printf '[done]\n'
}

# Packages on the Updates screen (decision 41): Git, and Rust once the Flutter & Rust tool has it (decision 75).
# apt-cache policy prints "Installed: X" and "Candidate: Y". awk reads to the end: leaving early would hand apt-cache a
# SIGPIPE, which pipefail turns into a failed assignment and set -e into a silent exit.
apt_policy_field() { apt-cache policy "$1" 2>/dev/null | awk -v k="$2:" '$1 == k && !seen { print $2; seen = 1 }'; }

# apt_check <package> [packages its row follows too]: refreshes the index and prints their policy. Its own log
# (<package>-check.log, so a check never wipes the install or update log); no lock, no steps. Check now refreshes the
# index once for every row (--only apt-refresh, AptIndex in the app) and runs each check with POCKETROCKET_APT_FRESH=1,
# which only reads it.
apt_check() {
  PR_LOG="$PR_HOME/$1-check.log"; mkdir -p "$PR_HOME"; : >"$PR_LOG"
  export DEBIAN_FRONTEND=noninteractive
  [ "${POCKETROCKET_APT_FRESH:-}" = 1 ] || apt_refresh
  apt-cache policy "$@" 2>/dev/null
}

# --only apt-refresh: one apt-get update for a whole Check now. Its own log, no lock, no steps.
apt_index_refresh() {
  PR_LOG="$PR_HOME/apt-refresh.log"; mkdir -p "$PR_HOME"; : >"$PR_LOG"
  export DEBIAN_FRONTEND=noninteractive
  apt_refresh
}

# The packages a row follows beyond the one it is named for (AptUpdater's list in the app): every JDK and Kotlin the
# Java tile covers, never openjdk-17 (PackageTool.updated), and every C and C++ package (CppTool.PACKAGES).
JAVA_UPDATED="openjdk-21 openjdk-25 kotlin"

# main_apt_update <name> <version command> <package> [packages that move with it]: --only <package>-update, with the
# device check, the package's own log and its version at the end.
main_apt_update() {
  local name="$1" check="$2"; shift 2
  TOTAL_STEPS=2; PR_LOG="$PR_HOME/$1.log"
  step "Checking the device"; preflight_checks; ok "Termux on aarch64"
  step_apt_update "$name" "$@"
  if [ "$1" = nodejs-lts ]; then node_corepack_enable; fi
  # shellcheck disable=SC2086
  ok "$($check 2>&1)"
  printf '[done]\n'
}

# step_apt_update <name> <package> [packages that move with it]: upgrades them alone, plus only what the new
# versions need, then checks each one that had a newer candidate is at it. Any of them may be the one that moved (Java
# 25 without 21, CMake without clang); the ones not installed are skipped, and at least one has to be.
step_apt_update() {
  local name="$1" p have want any="" moved=()
  shift
  step "Updating $name"
  apt_refresh
  for p in "$@"; do
    have="$(apt_policy_field "$p" Installed)"; want="$(apt_policy_field "$p" Candidate)"
    [ -n "$have" ] && [ "$have" != "(none)" ] || continue
    any=1
    if [ -n "$want" ] && [ "$want" != "(none)" ] && [ "$have" != "$want" ]; then
      info "$p $have -> $want"; moved+=("$p=$want")
    fi
  done
  [ -n "$any" ] || fail "$1 is not installed (run the setup or install it first)"
  if [ "${#moved[@]}" -eq 0 ]; then
    ok "$name is the newest"
  else
    apt_package_upgrade_progress "$@"
    for p in "${moved[@]}"; do
      have="$(apt_policy_field "${p%%=*}" Installed)"
      [ "$have" = "${p#*=}" ] || fail "${p%%=*} is still $have after the upgrade, expected ${p#*=}"
    done
  fi
  hash -r 2>/dev/null || true
}

# The version lines main_apt_update ends with for Node.js and Chromium (decision 103). Chromium's comes from dpkg:
# starting the browser to ask it needs more than a version check should.
node_npm_versions() { printf 'Node.js %s, npm %s' "$(node --version)" "$(npm --version)"; }
chromium_version() { dpkg-query -W -f='Chromium ${Version}' chromium; }
# dpkg_versions <packages…>: the installed ones with their versions ("openjdk-25 25.0.1, kotlin 2.4.21"), for rows
# that follow several packages.
dpkg_versions() {
  local p out=""
  for p in "$@"; do if pkg_installed "$p"; then out="$out, $p $(dpkg-query -W -f='${Version}' "$p")"; fi; done
  printf '%s' "${out#, }"
}

dockito_check() {
  local out
  hash -r 2>/dev/null || true
  out="$("$PREFIX/bin/dockito" --version 2>&1)" || fail "dockito --version failed: $out"
  printf '%s' "$out" | grep -qF "$NEW_VERSION" || fail "dockito --version printed '$out', expected $NEW_VERSION"
  ok "$out"
}

# The app's Claude Code screen (R9) runs only the Claude Code part, with its own log.
# Settings > Updates runs the Claude Code, Dockito and Git parts. Settings > Tools runs only the Chromium or Android build part.
# Wrangler, an optional tool (decisions 49 and 82): Cloudflare's CLI for deploying and managing Workers, from npm at a
# pinned version, with local dev. workerd, its local runtime, has no Android build: the stock linux-arm64 one uses
# tcmalloc, which needs a 48-bit address space, and Android gives apps 39 bits. So workerd's install script is skipped,
# its loader is pointed at a wrapper, and the wrapper runs a build of the same workerd version with tcmalloc off
# (ports/workerd, released here as workerd-v<version>) through the glibc loader. Node and glibc come with the base set, so the
# tool installs no packages. npm's download cache and the workerd download stay in temp folders and go when done.
WRANGLER_VERSION="4.156.0"
WRANGLER_NODE_MIN=22
WRANGLER_NPM_MB=78   # npm's cache after the install, for the bar (WranglerTool.DOWNLOAD_MB less workerd)
WRANGLER_DIR="${PREFIX:-}/lib/node_modules/wrangler"
WRANGLER_CONFIG="$HOME/.config/.wrangler"   # Wrangler's own folder, with its login, logs and telemetry choice
# The workerd that wrangler@WRANGLER_VERSION pins. Bumping Wrangler means building its workerd first: ports/workerd/README.md.
WORKERD_VERSION="1.20261023.1"
WORKERD_URL="https://github.com/dockito-ai/pocketrocket/releases/download/workerd-v${WORKERD_VERSION}/workerd-linux-arm64-notcmalloc-${WORKERD_VERSION}.tar.gz"
WORKERD_SHA256="e2a7a6d99dc6998506436995880b64db0b561d6652a7f3ca6509bcbf04a59b5a"
WORKERD_BYTES=40968480

# Points workerd's loader at the Android wrapper: $1 is workerd/lib/main.js. The wrapper runs lib/workerd-glibc, which
# step_wrangler_workerd puts there. Safe to run twice.
wrangler_patch_workerd() {
  local main="$1" dir wrapper
  dir="$(cd "$(dirname "$main")" && pwd)"; wrapper="$dir/workerd-android"
  grep -q '"workerd-android"' "$main" || grep -q '^function generateBinPath() {$' "$main" || return 1
  # LD_PRELOAD is the bionic termux-exec, which breaks glibc programs. The certificates aren't where glibc expects.
  cat > "$wrapper" <<EOF
#!${PREFIX:-}/bin/sh
unset LD_PRELOAD
export SSL_CERT_FILE="\${SSL_CERT_FILE:-${PREFIX:-}/etc/tls/cert.pem}"
exec "${PREFIX:-}/glibc/lib/ld-linux-aarch64.so.1" --library-path "${PREFIX:-}/glibc/lib" "$dir/workerd-glibc" "\$@"
EOF
  chmod 700 "$wrapper"
  grep -q '"workerd-android"' "$main" && return 0
  sed -i.orig 's|^function generateBinPath() {$|function generateBinPath() {\n  if (process.platform === "android") return { binPath: require("path").join(__dirname, "workerd-android") };|' "$main"
  rm -f "$main.orig"
  grep -q '"workerd-android"' "$main"
}

step_wrangler_install() {
  step "Installing Wrangler"
  # workerd's own install script is what can't run here (see above); step_wrangler_workerd stands in for it.
  npm_global_install wrangler Wrangler "$WRANGLER_NODE_MIN" "$WRANGLER_NPM_MB" "wrangler@${WRANGLER_VERSION}" --ignore-scripts
  wrangler_patch_workerd "$WRANGLER_DIR/node_modules/workerd/lib/main.js" \
    || fail "wrangler ${WRANGLER_VERSION} has a workerd this setup does not know"
  npm_tool_fix_launcher wrangler
  ok "wrangler ${WRANGLER_VERSION}"
}

# Downloads the Android build of workerd, checks it against the pinned SHA-256 and that it starts, then puts it in place.
step_wrangler_workerd() {
  step "Downloading the local runtime"
  local wd="$WRANGLER_DIR/node_modules/workerd" dl="$TMPDIR/workerd-download" have out
  have="$(jq -r .version "$wd/package.json" 2>/dev/null || true)"
  [ "$have" = "$WORKERD_VERSION" ] || fail "wrangler ${WRANGLER_VERSION} wants workerd ${have:-unknown}, this setup has ${WORKERD_VERSION}"
  [ -f "${PREFIX:-}/glibc/lib/ld-linux-aarch64.so.1" ] || fail "glibc loader missing (run the full setup first)"
  rm -rf "$dl"; mkdir -p "$dl"
  download_with_progress "$WORKERD_URL" "$dl/workerd.tar.gz" "$WORKERD_BYTES" || { rm -rf "$dl"; fail "workerd download failed"; }
  verify_sha256 "$dl/workerd.tar.gz" "$WORKERD_SHA256" >>"$PR_LOG" 2>&1 || { rm -rf "$dl"; fail "workerd download failed its checksum"; }
  tar -xzf "$dl/workerd.tar.gz" -C "$dl" workerd || { rm -rf "$dl"; fail "could not unpack workerd"; }
  rm -f "$dl/workerd.tar.gz"
  chmod 700 "$dl/workerd"
  mv -f "$dl/workerd" "$wd/lib/workerd-glibc" || { rm -rf "$dl"; fail "could not put workerd in place"; }
  rm -rf "$dl"
  out="$(timeout 60 "$wd/lib/workerd-android" --version 2>&1)" || out="FAILED: $out"
  printf '%s\n' "$out" >>"$PR_LOG"
  printf '%s' "$out" | grep -q '^workerd ' || fail "workerd does not start here: $out"
  ok "$out"
}

# wrangler_dev_check <project dir>: starts `wrangler dev` on a spare port, asks it for the check Worker's "ok", stops it.
wrangler_dev_check() {
  local d="$1" port=18787 pid got="" i
  ( cd "$d" && WRANGLER_SEND_METRICS=false WRANGLER_LOG_PATH="$d/wrangler.log" \
      exec timeout 120 wrangler dev --ip 127.0.0.1 --port "$port" ) >>"$PR_LOG" 2>&1 &
  pid=$!
  for i in $(seq 1 60); do
    got="$(curl -fsS --max-time 3 "http://127.0.0.1:$port/" 2>/dev/null || true)"
    [ "$got" = ok ] && break
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done
  kill "$pid" 2>/dev/null; wait "$pid" 2>/dev/null || true
  [ "$got" = ok ]
}

step_wrangler_check() {
  step "Checking Wrangler"
  local out d="$TMPDIR/wrangler-check"
  out="$(wrangler --version 2>&1 || true)"
  printf '%s\n' "$out" >>"$PR_LOG"
  printf '%s' "$out" | grep -qF "$WRANGLER_VERSION" || fail "wrangler --version did not work: $out"
  rm -rf "$d"; mkdir -p "$d"
  printf 'export default { fetch() { return new Response("ok"); } };\n' > "$d/index.js"
  printf 'name = "check"\nmain = "index.js"\ncompatibility_date = "2026-01-01"\n' > "$d/wrangler.toml"
  ( cd "$d" && WRANGLER_SEND_METRICS=false WRANGLER_LOG_PATH="$d/wrangler.log" wrangler deploy --dry-run --outdir out ) >>"$PR_LOG" 2>&1 \
    || { rm -rf "$d"; fail "wrangler deploy --dry-run failed"; }
  [ -s "$d/out/index.js" ] || { rm -rf "$d"; fail "wrangler deploy --dry-run built nothing"; }
  wrangler_dev_check "$d" || { rm -rf "$d"; fail "wrangler dev did not answer a request"; }
  rm -rf "$d"
  ok "wrangler deploy --dry-run and wrangler dev work"
  printf '[done]\n'
}

step_wrangler_remove() {
  step "Removing Wrangler"
  # Sign out first so the login doesn't outlive the tool. Offline it just can't revoke; the local copy still goes.
  if [ -x "$PREFIX/bin/wrangler" ] && [ -d "$WRANGLER_CONFIG/config" ]; then
    WRANGLER_LOG_PATH="$TMPDIR/wrangler-remove.log" timeout 20 wrangler logout >/dev/null 2>&1 || true
    rm -f "$TMPDIR/wrangler-remove.log"
  fi
  if [ -e "$WRANGLER_DIR" ]; then
    log_run npm uninstall -g wrangler || fail "npm uninstall wrangler failed"
  fi
  # A failed install can leave these behind with nothing to point at.
  rm -f "$PREFIX/bin/wrangler" "$PREFIX/bin/wrangler2"
  rm -rf "$WRANGLER_CONFIG"
  rmdir "$HOME/.config" 2>/dev/null || true
  ok "wrangler removed"
  printf '[done]\n'
}

# Flutter and Rust, an optional tool for checking code on the phone (decision 75, docs/flutter-tool.md): pub get,
# build_runner, analyze, test, cargo check and clippy. No APKs. Flutter is official: the release tag cloned from
# github.com/flutter/flutter into FVM's layout (~/fvm/versions/<version>); its own script downloads the Dart SDK, and
# the `flutter` launcher (setup/flutter) points the glibc programs at Termux's glibc loader and wraps them so they run
# without the bionic exec helper. FVM is its official linux-arm64 release, pinned and checked, prepared the same way.
# Rust is Termux's `rust` package: Rust publishes no compiler that runs on Android, and rustup panics there.
# docs/flutter-spike.md has the why.
FLUTTER_REPO="https://github.com/flutter/flutter.git"
FLUTTER_RELEASES="https://storage.googleapis.com/flutter_infra_release/releases/releases_linux.json"
FLUTTER_HOME="$HOME/fvm"                                # FVM's cache folder; FlutterTool.FVM_RELATIVE
FLUTTER_VERSIONS="$FLUTTER_HOME/versions"
FLUTTER_DEFAULT="$FLUTTER_HOME/default"                 # FVM's global version: a link to versions/<version>
FLUTTER_PACKAGES="which unzip termux-exec-glibc"        # flutter doctor needs which; the Dart SDK download needs unzip;
                                                        # debug APK builds need termux-exec-glibc (base has it already)
FLUTTER_ADDED_FILE="$PR_HOME/flutter.packages"
FLUTTER_BASELINE_FILE="$PR_HOME/flutter.baseline"
FVM_VERSION=4.3.2
FVM_SHA256=df05c7682eeac21b08939e7be131687dc3d54fd5575b5e4bca931717ddeb0a01   # GitHub's published digest of the asset
FVM_URL="https://github.com/leoafarias/fvm/releases/download/$FVM_VERSION/fvm-$FVM_VERSION-linux-arm64.tar.gz"
FVM_DIR="${PREFIX:-}/opt/fvm"
# Debug APK builds (decision 100): the exec helper the Flutter tool needs to start gradlew. FlutterTool.EXEC_RELATIVE
# and the launcher's PR_EXEC_DIR name the same folder.
FLUTTER_EXEC_DIR="${PREFIX:-}/lib/pocketrocket-flutter-exec"
# Commands Flutter plugins' build scripts expect and the phone lacks (decision 104): shasum. The launcher puts this folder
# at the end of PATH (the launcher's PR_BIN_DIR names the same folder).
FLUTTER_BIN_DIR="${PREFIX:-}/lib/pocketrocket-flutter-bin"
RUST_PACKAGES="rust"                                    # brings rust-std-aarch64-linux-android with it
# What the install added, for Uninstall (pkg_tool_purge rust reads these same two names). The app reads "installed"
# from dpkg, so these are not markers: a `pkg install rust` from a terminal has none and still shows as installed.
RUST_ADDED_FILE="$PR_HOME/rust.packages"
RUST_BASELINE_FILE="$PR_HOME/rust.baseline"
# PocketRocket's rustup (setup/rustup, decision 104): build tools that drive cargo through rustup (cargokit,
# flutter_rust_bridge) work with Termux's Rust. Recognised by this mark, so a rustup from elsewhere is never touched.
RUST_RUSTUP="${PREFIX:-}/bin/rustup"
RUST_RUSTUP_MARK="rustup (PocketRocket)"

# flutter_release <releases_linux.json> [version|stable]: "<version> <commit>" of the newest stable, or of that version
# (the commit is empty when Flutter's list doesn't have it; the tag is still cloned, without that check).
flutter_release() {
  local json="$1" want="${2:-stable}"
  if [ "$want" = stable ] || [ "$want" = latest ]; then
    jq -er '.current_release.stable as $h | first(.releases[] | select(.hash == $h and .channel == "stable")) | "\(.version) \(.hash)"' "$json"
  else
    is_version "$want" || return 1
    printf '%s %s\n' "$want" "$(jq -r --arg v "$want" 'first(.releases[] | select(.version == $v) | .hash) // ""' "$json")"
  fi
}

# flutter_clone <version> <folder>: a shallow clone of the release tag, with the download on the bar (0-95).
flutter_clone() {
  local version="$1" dest="$2" line pct last=-1 size=""
  rm -rf "$dest"
  git clone --progress --depth 1 --single-branch --no-tags -b "$version" "$FLUTTER_REPO" "$dest" 2>&1 \
    | tr '\r' '\n' | while IFS= read -r line; do
      case "$line" in
        *"Receiving objects:"*%*)
          pct="${line#*Receiving objects:}"; pct="${pct%%%*}"; pct="${pct//[^0-9]/}"
          case "$line" in *"), "*" | "*) size="${line#*), }"; size="${size%% |*}" ;; esac
          [ -n "$pct" ] || continue
          pct=$(( pct * 90 / 100 ))
          if [ "$pct" != "$last" ]; then
            last="$pct"; progress "$pct" "Downloading Flutter $version${size:+ · $size}"
          fi ;;
        *"Resolving deltas:"*100%*|*"Updating files:"*100%*) progress 95 "Unpacking Flutter $version" ;;
        *) [ -z "$line" ] || printf '%s\n' "$line" >>"$PR_LOG" ;;
      esac
    done
}

# watch_file <pid> <file> <total bytes> <label>: the file's size on the bar while the process runs.
watch_file() {
  local pid="$1" file="$2" total="$3" label="$4" have pct last=-1
  case "$total" in ''|*[!0-9]*) total=0 ;; esac
  while kill -0 "$pid" 2>/dev/null; do
    have=0; [ -f "$file" ] && have="$(stat -c %s "$file" 2>/dev/null || echo 0)"
    if [ "$total" -gt 0 ] && [ "$have" -gt 0 ]; then
      pct=$(( have * 100 / total )); [ "$pct" -gt 99 ] && pct=99
      [ "$pct" = "$last" ] || { last="$pct"; progress "$pct" "$label · $(mb "$have") of $(mb "$total") MB"; }
    fi
    sleep 1
  done
}

# packages_baseline <file> [added] / packages_record <baseline> <added>: what an install brings in, so its uninstall
# removes only that. The baseline lives only from a run's apt step to its record (a Retry after apt stopped reuses it).
# The record adds what this run brought to the list, never drops what an earlier run added, and deletes the baseline,
# so a later run never counts what other tools installed in between. Same helpers as the Android tools.
packages_baseline() { [ -z "${2:-}" ] || packages_drop_stale "$1" "$2"; [ -f "$1" ] || android_installed_packages >"$1"; }
# packages_drop_stale <baseline> <added>: a baseline older than the tool's list was left by an install from before the
# record deleted it; a Repair or an uninstall using it would count every package added since as this tool's.
packages_drop_stale() { if [ -f "$1" ] && [ "$2" -nt "$1" ]; then rm -f "$1"; fi; }
packages_record() {
  local now="$TMPDIR/pocketrocket-packages.now" list="$TMPDIR/pocketrocket-packages.list"
  android_installed_packages >"$now"
  { if [ -f "$2" ]; then cat "$2"; fi; android_new_packages "$1" "$now"; } | sed '/^$/d' | sort -u >"$list"
  mv -f "$list" "$2"
  rm -f "$now" "$1"
}

# packages_hand_over <from list> <to list> <packages…>: another installed tool needs some of the same packages (unzip
# is in both the Flutter and the Android tool). Those move from this tool's list to the other's, so removing this tool
# leaves them and removing the other later takes them.
packages_hand_over() {
  local from="$1" to="$2" p moved=""; shift 2
  [ -f "$from" ] || return 0
  for p in "$@"; do grep -qxF "$p" "$from" && moved="$moved $p"; done
  [ -n "$moved" ] || return 0
  # shellcheck disable=SC2086
  grep -vxF -f <(printf '%s\n' $moved) "$from" >"$from.tmp" || true
  mv -f "$from.tmp" "$from"
  # shellcheck disable=SC2086
  { if [ -f "$to" ]; then cat "$to"; fi; printf '%s\n' $moved; } | sort -u >"$to.tmp"
  mv -f "$to.tmp" "$to"
  info "kept for the other tool that uses them:$moved"
}

# Whether each tool is on the phone, finished or not, for packages_hand_over.
android_build_present() { [ -d "$ANDROID_SDK_DIR" ] || [ -f "$ANDROID_ADDED_FILE" ]; }
# Flutter is there, working or not (FlutterTool.presence reads the same pieces): versions, FVM, or a failed install's list.
flutter_present() { [ -d "$FLUTTER_VERSIONS" ] || [ -d "$FVM_DIR" ] || [ -f "$FLUTTER_ADDED_FILE" ] || [ -f "$FLUTTER_BASELINE_FILE" ]; }

# The launchers: flutter from setup/flutter, with dart and fvm links to it. As fvm it runs FVM's own Dart (prepared
# like Flutter's) and makes ready the versions FVM installs or runs.
flutter_launchers() {
  install_launcher flutter
  ln -sf flutter "$PREFIX/bin/dart"
  rm -f "$PREFIX/bin/fvm"
  ln -s flutter "$PREFIX/bin/fvm"
  mkdir -p "$FLUTTER_BIN_DIR"
  install -m 0755 "$(dirname "${BASH_SOURCE[0]}")/shasum" "$FLUTTER_BIN_DIR/shasum" || fail "could not install shasum for Flutter builds"
}

step_flutter_helpers() {
  step "Installing FVM and helpers"
  local out tgz="$TMPDIR/fvm-$FVM_VERSION.tar.gz" d="$TMPDIR/fvm-unpack"
  # shellcheck disable=SC2086
  [ -z "$(missing_packages $FLUTTER_PACKAGES)" ] || apt_refresh
  packages_baseline "$FLUTTER_BASELINE_FILE" "$FLUTTER_ADDED_FILE"
  # shellcheck disable=SC2086
  apt_install_progress $FLUTTER_PACKAGES
  packages_record "$FLUTTER_BASELINE_FILE" "$FLUTTER_ADDED_FILE"
  if [ "$(cat "$FVM_DIR/.version" 2>/dev/null)" != "$FVM_VERSION" ]; then
    if ! verify_sha256 "$tgz" "$FVM_SHA256" >/dev/null 2>&1; then
      download_with_progress "$FVM_URL" "$tgz" || { rm -f "$tgz"; fail "The download of FVM stopped. Check the connection and try again."; }
      verify_sha256 "$tgz" "$FVM_SHA256" >>"$PR_LOG" 2>&1 || { rm -f "$tgz"; fail "FVM $FVM_VERSION does not match its published checksum. Try again."; }
    fi
    rm -rf "$d" "$FVM_DIR"; mkdir -p "$d" "$(dirname "$FVM_DIR")"
    log_run tar -xzf "$tgz" -C "$d" || fail "could not unpack FVM"
    [ -f "$d/fvm/src/dart" ] && [ -f "$d/fvm/src/fvm.snapshot" ] || fail "FVM $FVM_VERSION is not laid out as expected"
    mv "$d/fvm" "$FVM_DIR"; rm -rf "$d" "$tgz"
    printf '%s\n' "$FVM_VERSION" >"$FVM_DIR/.version"
  fi
  flutter_launchers
  flutter_exec_build
  # Repair: Release builds that are half there (the emulator or the C library taken away) are put back.
  if flutter_release_partial; then
    info "putting back what release builds need"
    flutter_release_packages
    flutter_release_sysroot
  fi
  POCKETROCKET_FLUTTER_PREPARE="$FVM_DIR/src" "$PREFIX/bin/flutter" >>"$PR_LOG" 2>&1 || fail "could not prepare FVM for this phone"
  out="$(fvm --version 2>&1)" || fail "fvm --version failed: $out"
  # Versions come from the tool page; FVM's mirror of the whole Flutter history would add gigabytes.
  log_run fvm config --no-use-git-cache --no-update-check || warn "could not turn off FVM's git cache"
  ok "FVM $out"
}

# flutter_exec_build: the exec helper for `flutter build apk --debug` (decision 100, docs/flutter-apk-spike.md), built
# here from setup/flutter-exec.c with the C and C++ add-on's clang; no binary ships. The Flutter tool is a glibc
# program and starts gradlew (#!/usr/bin/env bash) with glibc's execvp, which Termux's termux-exec-glibc never sees.
# One LD_PRELOAD value serves both C libraries: glibc's loader reads $PLATFORM as "aarch64", bionic's takes it as is.
#   aarch64/1.so    termux-exec-glibc            aarch64/2.so    flutter-exec.c (execvp on top of execve)
#   $PLATFORM/1.so  Termux's bionic exec helper  $PLATFORM/2.so  an empty library
# Without clang Flutter still works for compile checks; the launcher says what APK builds need, and the tile offers
# Repair once clang is on the phone. Never fails the install.
flutter_exec_build() {
  local here src d="$FLUTTER_EXEC_DIR" new="$FLUTTER_EXEC_DIR.new" glibc_exec="$PREFIX/glibc/lib/libtermux-exec.so" bionic_exec="" lib
  here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; src="$here/flutter-exec.c"
  if ! command -v clang >/dev/null 2>&1; then
    info "debug APK builds need clang: add C and C++ (and Android) from Tools, then Repair Flutter & Dart"
    return 0
  fi
  for lib in "$PREFIX/lib/libtermux-exec-ld-preload.so" "$PREFIX/lib/libtermux-exec.so"; do
    if [ -f "$lib" ]; then bionic_exec="$lib"; break; fi
  done
  [ -f "$src" ] && [ -f "$glibc_exec" ] && [ -n "$bionic_exec" ] \
    || { warn "debug APK builds are not set up: flutter-exec.c, termux-exec-glibc or Termux's exec helper is missing"; return 0; }
  rm -rf "$new"; mkdir -p "$new/aarch64" "$new/\$PLATFORM"
  if ! log_run clang --target=aarch64-linux-gnu -O2 -fPIC -shared -nostdlib -fno-stack-protector -o "$new/libflutter-exec-glibc.so" "$src" \
    || ! printf 'void pocketrocket_flutter_exec_empty(void) {}\n' >"$new/empty.c" \
    || ! log_run clang -O2 -fPIC -shared -nostdlib -o "$new/libempty-bionic.so" "$new/empty.c"; then
    rm -rf "$new"; warn "could not build the exec helper for debug APK builds; Repair tries again"; return 0
  fi
  rm -f "$new/empty.c"
  ln -s "$glibc_exec" "$new/aarch64/1.so"
  ln -s "$d/libflutter-exec-glibc.so" "$new/aarch64/2.so"
  ln -s "$bionic_exec" "$new/\$PLATFORM/1.so"
  ln -s "$d/libempty-bionic.so" "$new/\$PLATFORM/2.so"
  rm -rf "$d"; mv "$new" "$d"
  ok "debug APK builds set up (exec helper in $d)"
}

# step_flutter_download [version]: sets FLUTTER_VERSION and FLUTTER_ROOT.
step_flutter_download() {
  local want="${1:-${POCKETROCKET_FLUTTER_VERSION:-stable}}" json="$TMPDIR/flutter-releases.json" release commit stage
  curl -fsSL --retry 3 --max-time 60 "$FLUTTER_RELEASES" -o "$json" || fail "could not read Flutter's release list (check the network)"
  release="$(flutter_release "$json" "$want")" || { rm -f "$json"; fail "Flutter '$want' is not a release version"; }
  rm -f "$json"
  FLUTTER_VERSION="${release%% *}"; commit="${release#* }"
  FLUTTER_ROOT="$FLUTTER_VERSIONS/$FLUTTER_VERSION"
  step "Downloading Flutter $FLUTTER_VERSION"
  if [ -f "$FLUTTER_ROOT/bin/flutter" ] && [ -e "$FLUTTER_ROOT/.git" ]; then ok "Flutter $FLUTTER_VERSION is already here"; return 0; fi
  stage="$FLUTTER_VERSIONS/.staging-$FLUTTER_VERSION"
  mkdir -p "$FLUTTER_VERSIONS"
  # A clone that finished before a later step failed is kept for Retry; one that stopped midway starts again.
  if [ ! -f "$stage/.git/pocketrocket-cloned" ]; then
    flutter_clone "$FLUTTER_VERSION" "$stage" || { rm -rf "$stage"; fail "The download of Flutter $FLUTTER_VERSION stopped. Check the connection and try again."; }
    : >"$stage/.git/pocketrocket-cloned"
  fi
  # A git commit names its whole content, so this checks the tree against Flutter's own release list.
  if [ -n "$commit" ] && [ "$(git -C "$stage" rev-parse HEAD 2>/dev/null)" != "$commit" ]; then
    rm -rf "$stage"; fail "Flutter $FLUTTER_VERSION is not the commit Flutter's release list names. Nothing was installed."
  fi
  rm -rf "$FLUTTER_ROOT"; mv "$stage" "$FLUTTER_ROOT"
  if [ -n "$commit" ]; then ok "Flutter $FLUTTER_VERSION, commit $commit, as in Flutter's release list"; else ok "Flutter $FLUTTER_VERSION"; fi
}

step_flutter_dart() {
  step "Downloading the Dart SDK"
  local internal="$FLUTTER_ROOT/bin/internal" cache="$FLUTTER_ROOT/bin/cache" engine="" realm="" url total pid rc=0
  # Flutter's scripts start other scripts by their #!/usr/bin/env lines, which need Termux's exec helper.
  export LD_PRELOAD="${LD_PRELOAD:-$PREFIX/lib/libtermux-exec-ld-preload.so}"
  log_run bash "$internal/update_engine_version.sh" || fail "Flutter could not work out its engine version"
  read -r engine <"$cache/engine.stamp" || true
  if [ -f "$cache/engine.realm" ]; then read -r realm <"$cache/engine.realm" || true; fi
  url="https://storage.googleapis.com${realm:+/$realm}/flutter_infra_release/flutter/$engine/dart-sdk-linux-arm64.zip"
  total="$(curl -fsSLI --max-time 20 "$url" 2>/dev/null | tr -d '\r' | awk 'tolower($1)=="content-length:" {n=$2} END {print n+0}')"
  bash "$internal/update_dart_sdk.sh" >>"$PR_LOG" 2>&1 &
  pid=$!
  watch_file "$pid" "$cache/dart-sdk-linux-arm64.zip" "${total:-0}" "Downloading the Dart SDK"
  wait "$pid" || rc=$?
  [ "$rc" -eq 0 ] && [ -e "$cache/dart-sdk/bin/dart" ] || fail "The Dart SDK download stopped. Check the connection and try again."
  progress 100 "Dart SDK ready"
  ok "Dart SDK for engine $engine"
}

step_flutter_setup() {
  step "Setting up Flutter $FLUTTER_VERSION"
  local out
  POCKETROCKET_FLUTTER_PREPARE="$FLUTTER_ROOT" "$PREFIX/bin/flutter" >>"$PR_LOG" 2>&1 || fail "could not prepare Flutter's Dart SDK for this phone"
  info "building the flutter tool (about a minute the first time)"
  out="$(cd "$HOME" && POCKETROCKET_FLUTTER_ROOT="$FLUTTER_ROOT" "$PREFIX/bin/flutter" --version 2>&1)" \
    || { printf '%s\n' "$out" >>"$PR_LOG"; fail "flutter --version failed: $(printf '%s' "$out" | tail -n 1)"; }
  printf '%s\n' "$out" >>"$PR_LOG"
  printf '%s' "$out" | grep -qF "Flutter $FLUTTER_VERSION" || fail "flutter --version did not say $FLUTTER_VERSION"
  # The engine's tools for this phone (flutter_tester, for flutter test), fetched and prepared now, not on first use.
  info "downloading Flutter's engine tools"
  (cd "$HOME" && POCKETROCKET_FLUTTER_ROOT="$FLUTTER_ROOT" "$PREFIX/bin/flutter" precache --universal) >>"$PR_LOG" 2>&1 \
    || warn "flutter precache failed; Flutter downloads what it needs on first use"
  POCKETROCKET_FLUTTER_PREPARE="$FLUTTER_ROOT" "$PREFIX/bin/flutter" >>"$PR_LOG" 2>&1 || fail "could not prepare Flutter's engine tools for this phone"
  local old replace="${POCKETROCKET_FLUTTER_REPLACE:-}"
  old="$(flutter_default_name)"
  if [ ! -e "$FLUTTER_DEFAULT/bin/flutter" ] || [ "${POCKETROCKET_FLUTTER_DEFAULT:-0}" = 1 ]; then
    flutter_set_default "$FLUTTER_VERSION"
  fi
  # An update from Settings › Updates replaces the default: the old one goes once the new one works, only if it was
  # the default, and other versions stay. A project that pins it gets it back with `fvm install <version>`.
  if [ -n "$replace" ] && [ "$replace" = "$old" ] && [ "$replace" != "$FLUTTER_VERSION" ] && flutter_version_name_ok "$replace" \
    && [ "$(flutter_default_name)" = "$FLUTTER_VERSION" ]; then
    flutter_delete_version "$replace"
    info "Flutter $replace removed; \`fvm install $replace\` brings it back"
  fi
  ok "$(printf '%s\n' "$out" | grep -m 1 '^Flutter ')"
}

# flutter_default_name: the default version's folder name, or nothing.
flutter_default_name() {
  [ -e "$FLUTTER_DEFAULT/bin/flutter" ] || return 0
  basename "$(cd -P "$FLUTTER_DEFAULT" && pwd)"
}

# flutter_delete_version <version>: its folder and any unfinished clone of it, after stopping what runs from it.
flutter_delete_version() {
  pkill -f "$FLUTTER_VERSIONS/$1/" 2>/dev/null || true
  rm -rf "${FLUTTER_VERSIONS:?}/$1" "$FLUTTER_VERSIONS/.staging-$1"
}

# flutter_set_default <version>: FVM's global version, which the launcher uses outside pinned projects.
flutter_set_default() {
  [ -f "$FLUTTER_VERSIONS/$1/bin/flutter" ] || fail "Flutter $1 is not installed"
  rm -f "$FLUTTER_DEFAULT"
  ln -s "versions/$1" "$FLUTTER_DEFAULT"
  ok "Flutter $1 is the default"
}

# flutter_newest_installed [except]: the newest installed version, the default when the default goes.
flutter_newest_installed() {
  local d
  for d in "$FLUTTER_VERSIONS"/*/; do
    d="${d%/}"; d="${d##*/}"
    if [ "$d" != "${1:-}" ] && [ -f "$FLUTTER_VERSIONS/$d/bin/flutter" ]; then printf '%s\n' "$d"; fi
  done | flutter_version_order | head -n 1
}

# flutter_version_order: names on stdin, newest first, in FlutterTool.installedVersions' order: numbers part by part,
# a pre-release ("3.50.0-0.3.pre") before its release, and names that aren't versions last, by name.
flutter_version_order() {
  awk '{
    if (match($0, /^[0-9]+\.[0-9]+\.[0-9]+(-.+)?$/)) {
      split($0, v, "-"); split(v[1], n, "."); pre = substr($0, length(v[1]) + 2)
      printf "0 %d %d %d %d %s %s\n", n[1], n[2], n[3], (pre == "" ? 1 : 0), (pre == "" ? "-" : pre), $0
    } else if ($0 != "" && substr($0, 1, 1) != ".") printf "1 0 0 0 0 - %s\n", $0
  }' | LC_ALL=C sort -k1,1n -k2,2nr -k3,3nr -k4,4nr -k5,5nr -k6,6r -k7,7 | cut -d' ' -f7-
}

# flutter_version_name_ok <version>: a folder name under versions/, never a path.
flutter_version_name_ok() { [ -n "$1" ] && [ "${1#*/}" = "$1" ] && [ "$1" != . ] && [ "$1" != .. ]; }

step_flutter_remove_version() {
  local v="$1" next
  flutter_version_name_ok "$v" || fail "no such Flutter version: $v"
  step "Removing Flutter $v"
  flutter_delete_version "$v"
  if [ ! -e "$FLUTTER_DEFAULT/bin/flutter" ]; then
    rm -f "$FLUTTER_DEFAULT"
    next="$(flutter_newest_installed)"
    [ -z "$next" ] || flutter_set_default "$next"
  fi
  ok "Flutter $v removed"
}

# What uninstalling Flutter removes, besides the packages it added. The one list; the tests read it.
flutter_remove_paths() {
  printf '%s\n' "$FLUTTER_HOME" "$FVM_DIR" "$FLUTTER_EXEC_DIR" "$FLUTTER_BIN_DIR" "$PREFIX/bin/flutter" "$PREFIX/bin/dart" "$PREFIX/bin/fvm" \
    "$HOME/.pub-cache" "$HOME/.dart-tool" "$HOME/.dartServer" "$HOME/.dart" "$HOME/.flutter" "$HOME/.flutter-devtools" \
    "$HOME/.config/flutter" "$HOME/.config/fvm" "$HOME/.fvm"
}

step_flutter_remove() {
  step "Removing Flutter, Dart and FVM"
  local p
  pkill -f "$FLUTTER_VERSIONS/" 2>/dev/null || true   # an analysis server left running would keep writing
  # Release builds go with Flutter: their wrappers, the x86_64 C library and the emulator the piece added.
  flutter_release_remove
  while IFS= read -r p; do rm -rf "${p:?}"; done < <(flutter_remove_paths)
  rmdir "$PREFIX/opt" "$HOME/.config" 2>/dev/null || true
  # A run that stopped inside apt left a baseline it didn't record yet.
  packages_drop_stale "$FLUTTER_BASELINE_FILE" "$FLUTTER_ADDED_FILE"
  if [ -f "$FLUTTER_BASELINE_FILE" ]; then packages_record "$FLUTTER_BASELINE_FILE" "$FLUTTER_ADDED_FILE"; fi
  # shellcheck disable=SC2086
  if android_build_present; then packages_hand_over "$FLUTTER_ADDED_FILE" "$ANDROID_ADDED_FILE" $ANDROID_BUILD_PACKAGES; fi
  android_purge_added "$FLUTTER_ADDED_FILE"
  rm -f "$FLUTTER_ADDED_FILE" "$FLUTTER_BASELINE_FILE"
  ok "Flutter removed"
}

# Release builds (decision 108, docs/flutter-tool.md): an opt-in piece of Flutter & Dart for `flutter build apk` in
# release and profile mode. Google publishes Flutter's Android AOT compiler, gen_snapshot, only for x86_64 hosts. Flutter
# downloads it anyway (android-<arch>-<mode>/linux-x64/gen_snapshot) and on this linux-arm64 host looks for it in
# android-<arch>-<mode>/linux-arm64/, so the launcher writes a wrapper there that runs the x86_64 one under Termux's
# qemu-x86_64, with an x86_64 C library as its root (-L). That library is the x86_64 build of Termux's own `glibc`
# package from the glibc repository (the one glibc-repo adds), fetched the way apt checks it: the InRelease signed by a
# key in apt's trusted.gpg.d, the Packages index's SHA-256 from that signed text, the .deb's SHA-256 from the index. It
# is unpacked, never installed: only the five files gen_snapshot needs are kept, in the package's own layout (its
# loader looks for them there), with lib64/ld-linux-x86-64.so.2 a relative link to the loader (qemu's -L doesn't follow
# an absolute one). The launcher's PR_X64_SYSROOT and PR_AOT_MARK, and FlutterTool.RELEASE_*, name the same things.
FLUTTER_RELEASE_SYSROOT="${PREFIX:-}/opt/flutter-x64-sysroot"
FLUTTER_RELEASE_QEMU="${PREFIX:-}/bin/qemu-x86_64"
FLUTTER_RELEASE_PACKAGES="qemu-user-x86-64"
FLUTTER_RELEASE_ADDED_FILE="$PR_HOME/flutter-release.packages"
FLUTTER_RELEASE_BASELINE_FILE="$PR_HOME/flutter-release.baseline"
FLUTTER_RELEASE_MARK="PocketRocket gen_snapshot wrapper"
FLUTTER_RELEASE_REPO="https://packages-cf.termux.dev/apt/termux-glibc"   # glibc-repo's source: dist glibc, component stable
FLUTTER_RELEASE_LIBS="ld-linux-x86-64.so.2 libc.so.6 libm.so.6 libdl.so.2 libpthread.so.0"

# termux_deb_fetch <repo> <dist> <component> <arch> <package> <empty dir>: downloads the package's .deb for any
# architecture without adding it to dpkg, checked as apt checks it (above). Sets TERMUX_DEB (its path), TERMUX_DEB_SHA256
# and TERMUX_DEB_VERSION; the download's progress goes to stdout, problems to stderr.
termux_deb_fetch() {
  local repo="$1" dist="$2" comp="$3" arch="$4" pkg="$5" out="$6" k keys=() idx want file="" sha="" version=""
  TERMUX_DEB=""; TERMUX_DEB_SHA256=""; TERMUX_DEB_VERSION=""
  mkdir -p "$out/index"
  curl -fsSL --retry 3 --max-time 60 "$repo/dists/$dist/InRelease" -o "$out/index/InRelease" \
    || { echo "could not download $repo/dists/$dist/InRelease" >&2; return 1; }
  for k in "${PREFIX:-}"/etc/apt/trusted.gpg.d/*.gpg "${PREFIX:-}"/etc/apt/trusted.gpg; do
    if [ -f "$k" ]; then keys+=(--keyring "$k"); fi
  done
  [ "${#keys[@]}" -gt 0 ] || { echo "apt has no trusted keys to check $repo with" >&2; return 1; }
  # Only the signed text is read from here on. (Termux's gpgv writes --output <file> as <file>.part, so stdout it is.)
  gpgv "${keys[@]}" --output - "$out/index/InRelease" >"$out/index/Release" 2>"$out/index/gpgv.log" \
    || { cat "$out/index/gpgv.log" >&2; echo "$repo's InRelease is not signed by a key apt trusts" >&2; return 1; }
  idx="$comp/binary-$arch/Packages"
  want="$(awk -v f="$idx" '/^SHA256:/ { s = 1; next } /^[^ ]/ { s = 0 } s && $3 == f { print $1; exit }' "$out/index/Release")"
  [ -n "$want" ] || { echo "the signed Release lists no $idx" >&2; return 1; }
  curl -fsSL --retry 3 --max-time 120 "$repo/dists/$dist/$idx" -o "$out/index/Packages" || { echo "could not download $idx" >&2; return 1; }
  verify_sha256 "$out/index/Packages" "$want" || { echo "$idx does not match the signed Release" >&2; return 1; }
  read -r file sha version < <(awk -v p="$pkg" '
    /^Package: / { cur = $2; f = ""; s = ""; v = "" }
    cur == p && /^Filename: / { f = $2 } cur == p && /^SHA256: / { s = $2 } cur == p && /^Version: / { v = $2 }
    /^$/ && cur == p && f != "" { print f, s, v; found = 1; exit }
    END { if (!found && cur == p && f != "") print f, s, v }' "$out/index/Packages") || true
  [ -n "${file:-}" ] && [ -n "${sha:-}" ] || { echo "$pkg is not in $idx" >&2; return 1; }
  case "$file" in /* | *..* | *[!A-Za-z0-9._+~/-]*) echo "unexpected file name in $idx: $file" >&2; return 1 ;; esac
  download_with_progress "$repo/$file" "$out/${file##*/}" || { echo "the download of $pkg stopped" >&2; return 1; }
  verify_sha256 "$out/${file##*/}" "$sha" >&2 || { echo "$pkg does not match the signed index" >&2; return 1; }
  TERMUX_DEB="$out/${file##*/}"; TERMUX_DEB_SHA256="$sha"; TERMUX_DEB_VERSION="$version"
}

flutter_release_ready() { [ -x "$FLUTTER_RELEASE_QEMU" ] && [ -e "$FLUTTER_RELEASE_SYSROOT/lib64/ld-linux-x86-64.so.2" ]; }

# flutter_release_sysroot: the x86_64 C library at FLUTTER_RELEASE_SYSROOT, from Termux's x86_64 glibc package. Built
# beside it and swapped in once qemu runs its loader, so a failed run leaves the old one.
flutter_release_sysroot() {
  local work="$TMPDIR/pocketrocket-flutter-release" new="$FLUTTER_RELEASE_SYSROOT.new" deb sha version members=() lib libdir rel out
  rm -rf "$work" "$new"; mkdir -p "$work/download" "$work/unpack"
  termux_deb_fetch "$FLUTTER_RELEASE_REPO" glibc stable x86_64 glibc "$work/download" 2>>"$PR_LOG" \
    || { rm -rf "$work"; fail "could not download Termux's x86_64 C library: $(tail -n 1 "$PR_LOG")"; }
  deb="$TERMUX_DEB"; sha="$TERMUX_DEB_SHA256"; version="$TERMUX_DEB_VERSION"
  info "glibc $version for x86_64, SHA-256 $sha, as the signed index of Termux's glibc repository lists it"
  for lib in $FLUTTER_RELEASE_LIBS; do members+=("*/glibc/lib/$lib"); done
  # Only the libraries come out of the archive; nothing in it is run, and its control scripts are never unpacked.
  dpkg-deb --fsys-tarfile "$deb" | tar -x --no-same-owner --no-same-permissions --wildcards -C "$work/unpack" "${members[@]}" 2>>"$PR_LOG" \
    || { rm -rf "$work"; fail "could not unpack the x86_64 C library"; }
  libdir=""
  for lib in "$work"/unpack/*/*/*/*/*/glibc/lib/ld-linux-x86-64.so.2; do if [ -f "$lib" ]; then libdir="${lib%/*}"; break; fi; done
  [ -n "$libdir" ] || { rm -rf "$work"; fail "the x86_64 C library is not laid out as expected"; }
  for lib in $FLUTTER_RELEASE_LIBS; do
    [ -f "$libdir/$lib" ] && [ ! -L "$libdir/$lib" ] || { rm -rf "$work"; fail "the x86_64 C library has no $lib"; }
  done
  rel="${libdir#"$work/unpack/"}"
  mkdir -p "$new/$rel" "$new/lib64"
  for lib in $FLUTTER_RELEASE_LIBS; do install -m 0644 "$libdir/$lib" "$new/$rel/$lib" || fail "could not set up the x86_64 C library"; done
  chmod 0755 "$new/$rel/ld-linux-x86-64.so.2"
  ln -s "../$rel/ld-linux-x86-64.so.2" "$new/lib64/ld-linux-x86-64.so.2"
  printf 'glibc %s x86_64 %s %s\n' "$version" "$sha" "$FLUTTER_RELEASE_REPO" >"$new/.pocketrocket-source"
  rm -rf "$work"
  out="$(env -u LD_PRELOAD -u LD_LIBRARY_PATH "$FLUTTER_RELEASE_QEMU" -L "$new" "$new/lib64/ld-linux-x86-64.so.2" --version 2>&1)" \
    && [ -n "$out" ] || { rm -rf "$new"; fail "qemu-x86_64 could not run the x86_64 C library: $out"; }
  out="${out%%$'\n'*}"
  rm -rf "$FLUTTER_RELEASE_SYSROOT"; mkdir -p "$(dirname "$FLUTTER_RELEASE_SYSROOT")"; mv "$new" "$FLUTTER_RELEASE_SYSROOT"
  ok "x86_64 C library: $out"
}

# flutter_release_wrappers: every installed version gets its gen_snapshot wrappers now (the launcher's prepare writes
# them); a version whose Android tools aren't downloaded yet gets them at its first APK build.
flutter_release_wrappers() {
  local d n=0
  for d in "$FLUTTER_VERSIONS"/*/; do
    d="${d%/}"
    case "${d##*/}" in .*) continue ;; esac
    [ -d "$d/bin/cache" ] || continue
    POCKETROCKET_FLUTTER_PREPARE="$d" "$PREFIX/bin/flutter" >>"$PR_LOG" 2>&1 || warn "could not prepare Flutter ${d##*/}"
    n=$((n + $(flutter_release_wrapper_list "$d" | wc -l)))
  done
  ok "$n gen_snapshot wrappers in place"
}

# flutter_release_wrapper_list [flutter root]: the wrappers the launcher wrote (they carry FLUTTER_RELEASE_MARK), in
# that version or every version. Anything else at those paths is never listed, so never removed.
flutter_release_wrapper_list() {
  local roots=("$FLUTTER_VERSIONS"/*) root w
  [ -z "${1:-}" ] || roots=("$1")
  for root in "${roots[@]}"; do
    for w in "$root"/bin/cache/artifacts/engine/android-*-release/linux-arm64/gen_snapshot \
      "$root"/bin/cache/artifacts/engine/android-*-profile/linux-arm64/gen_snapshot; do
      if [ -f "$w" ] && [ ! -L "$w" ] && LC_ALL=C grep -qF -- "$FLUTTER_RELEASE_MARK" "$w" 2>/dev/null; then printf '%s\n' "$w"; fi
    done
  done
}

step_flutter_release_install() {
  step "Installing the x86_64 emulator"
  [ -x "$PREFIX/bin/flutter" ] && [ -d "$FLUTTER_VERSIONS" ] || fail "Flutter isn't installed; install Flutter & Dart first"
  flutter_release_packages
  step "Setting up release builds"
  flutter_release_setup
}

# flutter_release_packages / flutter_release_setup: the two halves of the install; Repair of Flutter runs both when
# the piece is half there.
flutter_release_packages() {
  # shellcheck disable=SC2086
  [ -z "$(missing_packages $FLUTTER_RELEASE_PACKAGES)" ] || apt_refresh
  packages_baseline "$FLUTTER_RELEASE_BASELINE_FILE" "$FLUTTER_RELEASE_ADDED_FILE"
  # shellcheck disable=SC2086
  apt_install_progress $FLUTTER_RELEASE_PACKAGES
  packages_record "$FLUTTER_RELEASE_BASELINE_FILE" "$FLUTTER_RELEASE_ADDED_FILE"
  hash -r 2>/dev/null || true
  [ -x "$FLUTTER_RELEASE_QEMU" ] || fail "qemu-x86_64 is missing after the install"
  local v; v="$("$FLUTTER_RELEASE_QEMU" --version 2>&1)" || true
  ok "${v%%$'\n'*}"
}

flutter_release_setup() {
  flutter_release_sysroot
  install_launcher flutter
  flutter_release_wrappers
  info "release and profile APK builds work now; the AOT compile runs under emulation and takes a few minutes"
}

# flutter_release_partial: some of the piece is here but not all (the emulator or the C library taken away, or an
# install that stopped after its packages); FlutterTool.releaseProblem reads the same.
flutter_release_partial() {
  ! flutter_release_ready || return 1
  [ -d "$FLUTTER_RELEASE_SYSROOT" ] || [ -f "$FLUTTER_RELEASE_ADDED_FILE" ]
}

# flutter_release_remove: the wrappers in every version, the C library, and the packages the install added, except
# what other packages need. Also what Uninstall of Flutter runs.
flutter_release_remove() {
  local w
  while IFS= read -r w; do
    rm -f "$w"; rmdir "${w%/gen_snapshot}" 2>/dev/null || true
  done < <(flutter_release_wrapper_list)
  rm -rf "$FLUTTER_RELEASE_SYSROOT" "$FLUTTER_RELEASE_SYSROOT.new" "$TMPDIR/pocketrocket-flutter-release"
  rmdir "$PREFIX/opt" 2>/dev/null || true
  packages_drop_stale "$FLUTTER_RELEASE_BASELINE_FILE" "$FLUTTER_RELEASE_ADDED_FILE"
  if [ -f "$FLUTTER_RELEASE_BASELINE_FILE" ]; then packages_record "$FLUTTER_RELEASE_BASELINE_FILE" "$FLUTTER_RELEASE_ADDED_FILE"; fi
  android_purge_added "$FLUTTER_RELEASE_ADDED_FILE"
  rm -f "$FLUTTER_RELEASE_ADDED_FILE" "$FLUTTER_RELEASE_BASELINE_FILE"
}

step_flutter_release_remove() {
  step "Removing release builds"
  flutter_release_remove
  if [ -x "$FLUTTER_RELEASE_QEMU" ]; then ok "release builds removed; qemu-x86_64 stays (it was here before, or something else needs it)"
  else ok "release builds removed"; fi
}

rust_rustup_ours() { [ -f "$RUST_RUSTUP" ] && grep -qF "$RUST_RUSTUP_MARK" "$RUST_RUSTUP"; }

# rust_rustup_install: PocketRocket's rustup, unless another one is there.
rust_rustup_install() {
  if [ -e "$RUST_RUSTUP" ] && ! rust_rustup_ours; then info "kept the rustup already in $RUST_RUSTUP"; return 0; fi
  install -m 0755 "$(dirname "${BASH_SOURCE[0]}")/rustup" "$RUST_RUSTUP" || fail "could not install rustup for Rust build tools"
}

step_rust_install() {
  step "Installing Rust"
  local out
  apt_refresh
  packages_baseline "$RUST_BASELINE_FILE" "$RUST_ADDED_FILE"
  # shellcheck disable=SC2086
  apt_install_progress $RUST_PACKAGES
  hash -r 2>/dev/null || true
  out="$(cargo --version 2>&1)" || fail "cargo --version failed: $out"
  out="$(rustc --version 2>&1)" || fail "rustc --version failed: $out"
  packages_record "$RUST_BASELINE_FILE" "$RUST_ADDED_FILE"
  rust_rustup_install
  ok "$out"
}

# Uninstall works the same whether this tool or a terminal installed rust: rust and its targets go (pkg_tool_packages
# and pkg_tool_companions rust), plus what the tool's own install brought in when it did (clang, lld, libllvm…), except
# what other packages still need. The wasm target goes first with its own record.
step_rust_remove() {
  step "Removing Rust"
  pkg_tool_purge rust-wasm
  pkg_tool_purge rust
  rust_rustup_ours && rm -f "$RUST_RUSTUP"
  rm -rf "$HOME/.cargo"
  if [ -n "$PKG_TOOL_KEPT" ]; then ok "removed the rest; $PKG_TOOL_KEPT stayed"; else ok "Rust removed"; fi
}

# Package tools (decision 92): add-ons that are only Termux packages, Python (with pip and uv), bun, and Rust's wasm
# target on the Rust page. PackageTool.kt holds the same lists. The app reads "installed" from dpkg's status, so a
# `pkg install` or `pkg uninstall` from a terminal shows on the tile too; nothing here writes a marker. Uninstall
# removes the tool's packages and what its install brought in, except what other packages still need: Headless
# Chrome (chromium) needs python and python-pip, so those stay while it is installed.
pkg_tool_packages() {
  case "$1" in
    python) printf 'python python-pip uv' ;;
    bun) printf 'bun' ;;
    rust-wasm) printf 'rust-std-wasm32-unknown-unknown' ;;
    rust) printf 'rust rust-std-aarch64-linux-android' ;;   # removal only: step_rust_install installs it
    go) printf 'golang' ;;
    deno) printf 'deno' ;;
    ruby) printf 'ruby' ;;
    php) printf 'php' ;;          # not composer: its package's phar fails its signature check after the prefix rewrite
    java) printf 'openjdk-21 kotlin' ;;
    aws) printf 'awscli' ;;       # the deploy CLIs from Termux's packages (decision 98)
    flyctl) printf 'flyctl' ;;
    c-cpp) printf '%s' "$CPP_PACKAGES" ;;
    java-17 | java-21 | java-25) printf 'openjdk-%s' "${1#java-}" ;;   # one JDK on the Java page (decision 101)
    kotlin) printf 'kotlin' ;;
    *) return 1 ;;
  esac
}

# pkg_tool_companions <name>: packages that come with the tool's own (python recommends its ensurepip wheels) and go
# with it on uninstall. Rust's are every target installed (rust-std-*, however added): none depends on rust, so apt
# would leave them behind.
pkg_tool_companions() {
  case "$1" in
    python) printf 'python-ensurepip-wheels' ;;
    java) printf 'openjdk-21-x' ;;
    java-21) printf 'openjdk-21-x kotlin' ;;   # kotlin depends on openjdk-21, so it goes with it
    java-25) printf 'openjdk-25-x' ;;
    rust) dpkg-query -W -f='${db:Status-Abbrev} ${Package}\n' 'rust-std-*' 2>/dev/null | awk '$1 == "ii" {print $2}' | tr '\n' ' ' ;;
  esac
}

pkg_tool_title() {
  case "$1" in
    rust) printf 'Rust' ;;
    python) printf 'Python, pip and uv' ;;
    bun) printf 'Bun' ;;
    rust-wasm) printf 'the wasm target' ;;
    go) printf 'Go' ;;
    deno) printf 'Deno' ;;
    ruby) printf 'Ruby' ;;
    php) printf 'PHP' ;;
    java) printf 'Java 21 and Kotlin' ;;
    aws) printf 'the AWS CLI' ;;
    flyctl) printf 'flyctl' ;;
    c-cpp) printf 'C and C++' ;;
    java-17 | java-21 | java-25) printf 'Java %s' "${1#java-}" ;;
    kotlin) printf 'Kotlin' ;;
  esac
}

# pkg_tool_check <name>: one line that proves it runs.
pkg_tool_check() {
  case "$1" in
    python) printf '%s, pip %s, %s' "$(python3 --version 2>&1)" "$(pip --version 2>&1 | awk '{print $2}')" "$(uv --version 2>&1)" \
      && python3 -c 'import ssl, sqlite3' && uv --version >/dev/null ;;
    bun) printf 'bun %s' "$(bun --version 2>&1)" && bun -e 'process.exit(0)' ;;
    rust-wasm) rustc --print target-list 2>/dev/null | grep -qx wasm32-unknown-unknown \
      && [ -d "$PREFIX/lib/rustlib/wasm32-unknown-unknown" ] && printf 'wasm32-unknown-unknown for %s' "$(rustc --version 2>&1)" ;;
    go) go version ;;
    deno) deno eval 'console.log("deno " + Deno.version.deno)' ;;
    ruby) RUBYOPT=-rrubygems ruby -e 'require "json"; print "ruby #{RUBY_VERSION}, gem #{Gem::VERSION}"' ;;
    php) php -r 'echo "PHP " . PHP_VERSION;' ;;
    java) "$JAVA21_HOME/bin/java" -version 2>&1 | sed -n 1p && kotlinc -version 2>&1 | grep -o 'kotlinc-jvm [^ ]*' ;;
    aws) aws --version ;;
    flyctl) flyctl version ;;
    c-cpp) for b in clang clang++ ld.lld cmake ninja make pkg-config lldb; do command -v "$b" >/dev/null 2>&1 || { printf '%s is missing' "$b"; return 1; }; done
      clang --version 2>&1 | head -1 ;;
    java-17 | java-21 | java-25) "${PREFIX:-}/lib/jvm/java-${1#java-}-openjdk/bin/java" -version 2>&1 | sed -n 1p ;;
    kotlin) kotlinc -version 2>&1 | grep -o 'kotlinc-jvm [^ ]*' ;;
  esac
}

# pkg_tool_signout <name>: a deploy CLI's own sign-out before it goes, so the login doesn't outlive the tool. Offline
# it can't revoke; the local copy still goes with its folder (pkg_tool_cleanup).
pkg_tool_signout() {
  case "$1" in
    aws) if [ -x "$PREFIX/bin/aws" ]; then timeout 20 aws logout --all </dev/null >>"$PR_LOG" 2>&1 || true; fi ;;
    flyctl) if [ -x "$PREFIX/bin/flyctl" ]; then timeout 20 flyctl auth logout </dev/null >>"$PR_LOG" 2>&1 || true; fi ;;
  esac
  return 0
}

# Ruby (decision 98): Termux's ruby doesn't load RubyGems by itself, so `require "<gem>"` fails until it does. Terminals
# get RUBYOPT from this profile file; Claude sessions get it from the app whenever ruby is on the phone.
RUBY_PROFILE="${PREFIX:-}/etc/profile.d/pocketrocket-ruby.sh"

# Java 21 and Kotlin (decision 98): kotlin needs openjdk-21, and installing it moves update-alternatives' java group
# (java, javac, profile.d/java.sh) to 21. Android and Flutter builds use 17 (ANDROID_JAVA_HOME), so while 17 is on the
# phone the group is set to it by hand, before apt runs (21's install then leaves it alone) and again after. 21 stays
# at JAVA21_HOME. Without 17 the default is 21, as apt leaves it.
JAVA21_HOME="${PREFIX:-}/lib/jvm/java-21-openjdk"
# Never removed by a package tool, whatever its lists say.
PKG_TOOL_NEVER="openjdk-17 openjdk-17-x"

java_keep_17() {
  [ -x "$ANDROID_JAVA_HOME/bin/java" ] || return 0
  update-alternatives --set java "$ANDROID_JAVA_HOME/bin/java" >>"$PR_LOG" 2>&1 || fail "could not keep Java 17 as the default java"
}

# java_tool <name>: 0 for the Java tile's jobs (the tile, one JDK, Kotlin), which all end with java_settle.
java_tool() { case "$1" in java | java-* | kotlin) return 0 ;; *) return 1 ;; esac; }

# Several JDKs side by side (decision 101). Gradle toolchains (languageVersion = 21) only look in the usual Linux
# folders, and their auto-download fetches glibc JDKs that can't run here. So Gradle gets the list of JDKs on the phone
# in ~/.gradle/gradle.properties, kept current after every Java job, and auto-download off. GradleJdks.kt reads the same.
JAVA_GRADLE_PATHS=org.gradle.java.installations.paths
JAVA_GRADLE_AUTO=org.gradle.java.installations.auto-download

# java_jdk_dirs: the JDK folders with a java in them, oldest first, comma-separated ("" when none).
java_jdk_dirs() {
  local d n
  for d in "${PREFIX:-}"/lib/jvm/java-*-openjdk; do
    [ -x "$d/bin/java" ] || continue
    n="${d##*/java-}"; n="${n%-openjdk}"
    case "$n" in '' | *[!0-9]*) continue ;; esac
    printf '%s %s\n' "$n" "$d"
  done | sort -n | cut -d' ' -f2- | paste -sd, -
}

# The Gradle hook that compiles an Android module asking for a newer Java (Capacitor's 21) with that JDK while Gradle
# stays on 17, from the template beside this script; the app refreshes the same file at every start (GradleJdks.kt).
JAVA_GRADLE_HOOK="$HOME/.gradle/init.d/pocketrocket-java.gradle"

# java_gradle_jdks [gradle.properties]: sets the two keys to the JDKs there now and writes the hook, or takes them out
# when there are none.
java_gradle_jdks() {
  local f="${1:-$HOME/.gradle/gradle.properties}" dirs
  dirs="$(java_jdk_dirs)"
  if [ -n "$dirs" ]; then
    gradle_props_set "$f" "$JAVA_GRADLE_PATHS" "$dirs"
    gradle_props_set "$f" "$JAVA_GRADLE_AUTO" false
    mkdir -p "$(dirname "$JAVA_GRADLE_HOOK")"
    cp -f "$(dirname "${BASH_SOURCE[0]}")/pocketrocket-java.gradle" "$JAVA_GRADLE_HOOK.tmp" && mv -f "$JAVA_GRADLE_HOOK.tmp" "$JAVA_GRADLE_HOOK" || true
  else
    gradle_props_unset "$f" "$JAVA_GRADLE_PATHS"
    gradle_props_unset "$f" "$JAVA_GRADLE_AUTO"
    rm -f "$JAVA_GRADLE_HOOK"
  fi
}

# java_settle: after any Java job, java stays on 17 while it's there and Gradle knows every JDK.
java_settle() {
  java_keep_17
  java_gradle_jdks
  if [ -x "$ANDROID_JAVA_HOME/bin/java" ]; then info "java stays on Java 17 for Android builds"; fi
  info "Gradle sees: $(java_jdk_dirs | sed 's/,/, /g')"
}

# --only java-setup: Repair on the Java page; java_settle alone, no packages.
main_java_setup() {
  TOTAL_STEPS=2; PR_LOG="$PR_HOME/java.log"
  step "Checking the device"; preflight_checks; ok "Termux on aarch64"
  step "Setting up Java"
  java_settle
  ok "java and Gradle's JDK list set"
  printf '[done]\n'
}

# pkg_tool_setup <name>: what the tool needs beyond its packages, once they're in. Repair runs it again.
pkg_tool_setup() {
  case "$1" in
    python) uv_config_add "$UV_CONFIG" ;;
    ruby) mkdir -p "$(dirname "$RUBY_PROFILE")"
      printf '%s\n' "# Written by PocketRocket's Ruby tool. Removed on uninstall." "export RUBYOPT=-rrubygems" >"$RUBY_PROFILE" ;;
    java | java-* | kotlin) java_settle ;;
  esac
}

# pkg_tool_cleanup <name>: after the purge, what the tool left outside its packages; only once its main package is gone.
# Uninstall takes everything the tool put on the phone, its config and credentials included, whoever wrote them
# (decision 102): pkg_tool_leftovers lists it. Your own projects stay.
pkg_tool_cleanup() {
  local main f p
  main="$(pkg_tool_packages "$1" | awk '{print $1}')"
  [ "$1" != python ] || uv_config_remove "$UV_CONFIG"
  # uv goes with the Python tool even while Headless Chrome keeps python itself.
  if [ "$1" = python ] && ! pkg_installed uv; then rm -rf "$(dirname "$UV_CONFIG")" "${XDG_CACHE_HOME:-$HOME/.cache}/uv" "${XDG_DATA_HOME:-$HOME/.local/share}/uv"; fi
  ! pkg_installed "$main" || return 0
  # Go's module cache is read-only.
  [ "$1" != go ] || chmod -R u+w "$HOME/go/pkg" 2>/dev/null || true
  while IFS= read -r p; do [ -z "$p" ] || rm -rf "${p:?}"; done < <(pkg_tool_leftovers "$1")
  case "$1" in
    go) rmdir "$HOME/go" 2>/dev/null || true ;;   # ~/go/src, code of your own from GOPATH days, stays
    ruby)
      # The launchers RubyGems wrote for the gems.
      for f in "${PREFIX:-}"/bin/*; do
        if [ -f "$f" ] && head -c 4096 "$f" 2>/dev/null | grep -q 'generated by RubyGems'; then rm -f "$f"; fi
      done ;;
  esac
  rmdir "$HOME/.config" "$HOME/.local/share" "$HOME/.local" "$HOME/.cache" 2>/dev/null || true
  return 0
}

# pkg_tool_leftovers <name>: what a package tool leaves outside its packages, one path a line: caches, what it
# installed, its config and its credentials. The tests read the same list.
pkg_tool_leftovers() {
  local config="${XDG_CONFIG_HOME:-$HOME/.config}" cache="${XDG_CACHE_HOME:-$HOME/.cache}"
  case "$1" in
    python) printf '%s\n' "$cache/pip" "$config/pip" "$HOME/.pypirc" "$HOME/.python_history" "$HOME/.local/lib/python3"* ;;
    bun) printf '%s\n' "$HOME/.bun" "$HOME/.bunfig.toml" "$cache/bun" ;;
    # Programs `go install` put in ~/go/bin go too, and `go env -w` settings.
    go) printf '%s\n' "$cache/go-build" "$HOME/go/pkg" "$HOME/go/bin" "$config/go" ;;
    deno) printf '%s\n' "$cache/deno" "$HOME/.deno" ;;
    # Their config, credentials and caches, keys set up by hand included (flyctl ignores FLY_CONFIG_DIR).
    aws) printf '%s\n' "$HOME/.aws" ;;
    flyctl) printf '%s\n' "$HOME/.fly" ;;
    # Its profile file, gems installed into the prefix, and RubyGems' and Bundler's config and credentials.
    ruby) printf '%s\n' "$RUBY_PROFILE" "${PREFIX:-}/lib/ruby" "$HOME/.local/share/gem" "$cache/gem" "$HOME/.gem" "$HOME/.gemrc" \
      "$HOME/.bundle" "$HOME/.irbrc" "$HOME/.irb_history" "$HOME/.local/share/irb" ;;
    # php.ini changes and extensions pecl added, which the purge leaves; PEAR's config.
    php) printf '%s\n' "${PREFIX:-}/etc/php" "${PREFIX:-}/lib/php" "$HOME/.pearrc" "$HOME/.php_history" ;;
  esac
}

# uv uses Termux's Python and never downloads its own (those are glibc builds). One block at the top of uv's user
# config, so it is read before any table and taken out again on uninstall.
UV_CONFIG="${XDG_CONFIG_HOME:-$HOME/.config}/uv/uv.toml"
UV_BEGIN="# >>> PocketRocket Python tool (removed on uninstall)"
UV_END="# <<< PocketRocket Python tool"

uv_config_add() {
  grep -qxF "$UV_BEGIN" "$1" 2>/dev/null && return 0
  mkdir -p "$(dirname "$1")"
  { printf '%s\n' "$UV_BEGIN" 'python-downloads = "never"' 'python-preference = "only-system"' "$UV_END"
    if [ -f "$1" ]; then cat "$1"; fi; } >"$1.tmp"
  mv -f "$1.tmp" "$1"
}

uv_config_remove() {
  [ -f "$1" ] || return 0
  awk -v b="$UV_BEGIN" -v e="$UV_END" '$0 == b { skip = 1; next } $0 == e { skip = 0; next } !skip' "$1" >"$1.tmp"
  if grep -q '[^[:space:]]' "$1.tmp"; then mv -f "$1.tmp" "$1"; else rm -f "$1.tmp" "$1"; rmdir "$(dirname "$1")" 2>/dev/null || true; fi
}

# pkg_tool_kept_line <packages>: why a removal left them, said plainly ("kept python python-pip: Headless Chrome needs them").
pkg_tool_kept_line() {
  local kept="$1" sim users="" p
  # shellcheck disable=SC2086
  for p in $kept; do
    sim="$(apt-get purge -s "${APT_OPTS[@]}" "$p" 2>/dev/null)" || continue
    users="$users $(printf '%s\n' "$sim" | awk '$1 == "Purg" || $1 == "Remv" {print $2}' | grep -vxF -e "$p" | tr '\n' ' ')"
  done
  case " $users " in
    *" chromium "*) printf 'kept%s: Headless Chrome needs them' "$kept" ;;
    *) printf 'kept%s: other packages need them' "$kept" ;;
  esac
}

# pkg_tool_purge <name>: purges the tool's packages and what its install brought in, leaving what others need.
pkg_tool_purge() {
  local name="$1" added="$PR_HOME/$1.packages" baseline="$PR_HOME/$1.baseline" list="$PR_HOME/$1.remove" p kept="" main sim
  local companions; companions="$(pkg_tool_companions "$name")"
  packages_drop_stale "$baseline" "$added"
  if [ -f "$baseline" ]; then packages_record "$baseline" "$added"; fi
  # shellcheck disable=SC2046
  { if [ -f "$added" ]; then cat "$added"; fi; printf '%s\n' $(pkg_tool_packages "$name"); } | sed '/^$/d' | sort -u \
    | { grep -vxF -f <(printf '%s\n' $PKG_TOOL_NEVER) || true; } >"$list"
  # Companions go only when the main package can go too: python's venvs need its ensurepip wheels while it stays.
  main="$(pkg_tool_packages "$name" | awk '{print $1}')"
  if [ -n "$companions" ] && pkg_installed "$main"; then
    sim="$(apt-get purge -s "${APT_OPTS[@]}" "$main" 2>/dev/null)" || sim=""
    # shellcheck disable=SC2046,SC2086
    if [ -n "$sim" ] && android_removable "$sim" $(cat "$list") $companions; then printf '%s\n' $companions >>"$list"; fi
  fi
  android_purge_added "$list"
  for p in $(pkg_tool_packages "$name"); do pkg_installed "$p" && kept="$kept $p"; done
  rm -f "$added" "$baseline" "$list"
  PKG_TOOL_KEPT="${kept# }"
  [ -z "$kept" ] || info "$(pkg_tool_kept_line "$kept")"
}

step_pkg_tool_install() {
  local name="$1" out
  step "Installing $(pkg_tool_title "$name")"
  if [ "$name" = rust-wasm ] && ! pkg_installed rust; then fail "Rust isn't installed; install Rust first"; fi
  apt_refresh
  packages_baseline "$PR_HOME/$name.baseline" "$PR_HOME/$name.packages"
  if java_tool "$name"; then java_keep_17; fi
  # shellcheck disable=SC2046
  apt_install_progress $(pkg_tool_packages "$name")
  packages_record "$PR_HOME/$name.baseline" "$PR_HOME/$name.packages"
  hash -r 2>/dev/null || true
  pkg_tool_setup "$name"
  out="$(pkg_tool_check "$name" 2>&1)" || fail "$(pkg_tool_title "$name") does not run: $out"
  ok "$out"
}

step_pkg_tool_remove() {
  local name="$1"
  step "Removing $(pkg_tool_title "$name")"
  pkg_tool_signout "$name"
  pkg_tool_purge "$name"
  pkg_tool_cleanup "$name"
  # The Java tile's Uninstall takes Java 25 too, and what the page's own adds brought in (Java 17 always stays).
  if [ "$name" = java ]; then
    local part kept="$PKG_TOOL_KEPT"
    for part in kotlin java-21 java-25; do pkg_tool_purge "$part"; kept="$kept $PKG_TOOL_KEPT"; done
    # shellcheck disable=SC2086
    PKG_TOOL_KEPT="$(printf '%s\n' $kept | sort -u | paste -sd' ' -)"
  fi
  if java_tool "$name"; then java_settle; fi
  if [ -n "$PKG_TOOL_KEPT" ]; then ok "removed the rest; $PKG_TOOL_KEPT stayed"; else ok "$(pkg_tool_title "$name") removed"; fi
}

# --only <name> / <name>-remove for a package tool: 2 steps, its own log.
main_pkg_tool() {
  local option="$1" name="${1%-remove}"
  TOTAL_STEPS=2; PR_LOG="$PR_HOME/$name.log"
  step "Checking the device"; preflight_checks; ok "Termux on aarch64"
  if [ "$option" = "$name" ]; then step_pkg_tool_install "$name"; else step_pkg_tool_remove "$name"; fi
  printf '[done]\n'
}

# The npm deploy CLIs (decision 98): Vercel, Netlify and Firebase, global npm installs of their newest release.
# NpmTool.kt holds the same names. The app reads "installed" from npm's folder for the package, so an
# `npm i -g vercel` from a terminal shows on the tile too. npm 12 skips their install scripts with an allow-scripts
# warning; they run without them. npm's download cache stays in a temp folder and goes when done.
npm_tool_package() {
  case "$1" in
    vercel) printf 'vercel' ;;
    netlify) printf 'netlify-cli' ;;
    firebase) printf 'firebase-tools' ;;
    *) return 1 ;;
  esac
}

npm_tool_title() {
  case "$1" in
    vercel) printf 'the Vercel CLI' ;;
    netlify) printf 'the Netlify CLI' ;;
    firebase) printf 'the Firebase CLI' ;;
  esac
}

# npm_tool_bin <name>: its program in $PREFIX/bin; npm_tool_download_mb: npm's cache after an install, for the bar.
npm_tool_bin() { case "$1" in vercel) printf vercel ;; netlify) printf netlify ;; firebase) printf firebase ;; esac; }
npm_tool_download_mb() { case "$1" in vercel) printf 197 ;; netlify) printf 263 ;; firebase) printf 140 ;; esac; }

# npm_tool_config <name>: where the CLI keeps its login, settings, caches and update-check state, removed with it
# (decision 102).
npm_tool_config() {
  local config="${XDG_CONFIG_HOME:-$HOME/.config}" data="${XDG_DATA_HOME:-$HOME/.local/share}" cache="${XDG_CACHE_HOME:-$HOME/.cache}"
  local notifier="$config/configstore/update-notifier-$(npm_tool_package "$1").json"
  case "$1" in
    vercel) printf '%s ' "$data/com.vercel.cli" "$cache/com.vercel.cli" "$notifier" ;;
    netlify) printf '%s ' "$config/netlify" "$data/netlify" "$cache/netlify" "$HOME/.netlify" "$notifier" ;;
    firebase) printf '%s ' "$config/configstore/firebase-tools.json" "$cache/firebase" "$notifier" ;;
  esac
}

# npm_tool_logout <name>: its own sign-out, so the login doesn't outlive the tool. Offline it can't revoke; the local
# copy still goes with npm_tool_config.
npm_tool_logout() {
  case "$1" in
    vercel) printf 'logout' ;;
    netlify) printf 'logout' ;;
    firebase) printf 'logout --non-interactive' ;;
  esac
}

# watch_dir <pid> <dir> <total MB> <label>: the folder's size on the bar while the process runs (npm's cache fills as it
# downloads). Never past 95: unpacking comes after.
watch_dir() {
  local pid="$1" dir="$2" total="$3" label="$4" have pct last=-1
  while kill -0 "$pid" 2>/dev/null; do
    # npm makes its cache a moment after it starts; until then du fails, which mustn't end the script (pipefail).
    have="$(du -sk "$dir" 2>/dev/null | awk '{print $1 * 1024}' || true)"; have="${have:-0}"
    if [ "$have" -gt 0 ]; then
      pct=$(( have * 100 / (total * 1048576) )); [ "$pct" -gt 95 ] && pct=95
      [ "$pct" = "$last" ] || { last="$pct"; progress "$pct" "$label · $(mb "$have") of about $total MB"; }
    fi
    sleep 2
  done
}

step_npm_tool_install() {
  step "Installing $(npm_tool_title "$1")"
  npm_tool_npm_install "$1" "$(npm_tool_package "$1")"
}

# npm_global_install <name> <title> <Node major it needs> <download MB> <package spec> [npm options…]: `npm install -g`
# with a throwaway cache and the download on the bar. Wrangler's install and the npm tools' install and update use it.
npm_global_install() {
  local name="$1" title="$2" min="$3" mb="$4" spec="$5" pkg="${5%@*}" cache major pid
  shift 5
  cache="$TMPDIR/pocketrocket-npm-$name"
  major="$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null || echo 0)"
  [ "$major" -ge "$min" ] || fail "$title needs Node $min or newer, found ${major}"
  rm -rf "$cache"
  npm install -g --no-fund --no-audit --cache "$cache" "$@" "$spec" >>"$PR_LOG" 2>&1 &
  pid=$!
  watch_dir "$pid" "$cache" "$mb" "Downloading $pkg"
  if ! wait "$pid"; then rm -rf "$cache"; fail "npm install $pkg failed; see $PR_LOG"; fi
  rm -rf "$cache"
  hash -r 2>/dev/null || true
}

# npm_tool_npm_install <name> <package spec>: npm_global_install, then its launcher and --version. The install and the
# update (npm_tool_update_*) both run it, so they show the same progress.
npm_tool_npm_install() {
  local name="$1" spec="$2" pkg bin out
  pkg="$(npm_tool_package "$name")"; bin="$(npm_tool_bin "$name")"
  npm_global_install "$name" "$(npm_tool_title "$name")" 20 "$(npm_tool_download_mb "$name")" "$spec"
  npm_tool_fix_launcher "$bin"
  out="$("$PREFIX/bin/$bin" --version 2>&1)" || fail "$bin --version failed: $out"
  ok "$pkg $(printf '%s' "$out" | tail -n 1)"
}

# npm_tool_fix_launcher <bin>: points the launcher's `#!/usr/bin/env node` at $PREFIX/bin/node (Wrangler's too). The
# exec helper only reaches this script's children, not its own exec, and the app's logins and some Claude sessions run
# without it, so Android (no /usr/bin/env) would refuse the file. sed works on the file npm's link points at.
npm_tool_fix_launcher() {
  local target first
  target="$(readlink -f "$PREFIX/bin/$1")" || fail "$1 was not installed"
  [ -f "$target" ] || fail "$1 was not installed"
  IFS= read -r first <"$target" || true
  if [[ "$first" == '#!'*'env node'* ]]; then
    sed -i "1s|^#!.*|#!$PREFIX/bin/node|" "$target" || fail "could not fix $1's launcher"
  fi
}

step_npm_tool_remove() {
  local name="$1" pkg bin dir
  pkg="$(npm_tool_package "$name")"; bin="$(npm_tool_bin "$name")"
  step "Removing $(npm_tool_title "$name")"
  if [ -x "$PREFIX/bin/$bin" ]; then
    # shellcheck disable=SC2046
    timeout 20 "$PREFIX/bin/$bin" $(npm_tool_logout "$name") </dev/null >>"$PR_LOG" 2>&1 || true
  fi
  if [ -e "$PREFIX/lib/node_modules/$pkg" ]; then
    log_run npm uninstall -g "$pkg" || fail "npm uninstall $pkg failed"
  fi
  rm -f "$PREFIX/bin/$bin"   # a failed install can leave it with nothing to point at
  # shellcheck disable=SC2046
  for dir in $(npm_tool_config "$name"); do rm -rf "$dir"; done
  rmdir "$HOME/.config/configstore" "$HOME/.config" "$HOME/.local/share" "$HOME/.local" "$HOME/.cache" 2>/dev/null || true
  ok "$pkg removed"
}

# The npm tools' Updates rows (decision 103). npm_tool_update_check <name>: `npm view <package> version`, the newest
# release on npm's registry, for the app to compare with the package.json on the phone. Its own log
# (<name>-check.log), a throwaway npm cache, no lock, no steps.
npm_tool_update_check() {
  local name="$1" pkg cache="$TMPDIR/pocketrocket-npm-view-$1"
  pkg="$(npm_tool_package "$name")"
  PR_LOG="$PR_HOME/$name-check.log"; mkdir -p "$PR_HOME"; : >"$PR_LOG"
  rm -rf "$cache"
  if ! npm view --cache "$cache" "$pkg" version 2>>"$PR_LOG"; then rm -rf "$cache"; fail "npm view $pkg failed; see $PR_LOG"; fi
  rm -rf "$cache"
}

# npm_tool_update_step <name>: npm's newest release over the one on the phone (`npm install -g <package>@latest`), with
# the install's progress. Only for one that is installed; logins and config stay.
npm_tool_update_step() {
  local name="$1" pkg have
  pkg="$(npm_tool_package "$name")"
  step "Updating $(npm_tool_title "$name")"
  have="$(jq -r .version "$PREFIX/lib/node_modules/$pkg/package.json" 2>/dev/null || true)"
  [ -n "$have" ] && [ "$have" != null ] || fail "$(npm_tool_title "$name") is not installed (install it first)"
  info "$pkg $have -> latest"
  npm_tool_npm_install "$name" "$pkg@latest"
}

# --only <name> / <name>-update / <name>-remove for an npm tool: 2 steps, its own log; <name>-check prints a version.
main_npm_tool() {
  local option="$1" name="${1%-remove}"
  name="${name%-update}"; name="${name%-check}"
  export LD_PRELOAD="${LD_PRELOAD:-$PREFIX/lib/libtermux-exec.so}"   # their launchers start with #!/usr/bin/env node
  if [ "$option" = "$name-check" ]; then npm_tool_update_check "$name"; return 0; fi
  TOTAL_STEPS=2; PR_LOG="$PR_HOME/$name.log"
  step "Checking the device"; preflight_checks; ok "Termux on aarch64"
  case "$option" in
    "$name") step_npm_tool_install "$name" ;;
    "$name-update") npm_tool_update_step "$name" ;;
    *) step_npm_tool_remove "$name" ;;
  esac
  printf '[done]\n'
}

# The Flutter & Rust tool's options (decision 75). The version comes as the third argument or in
# POCKETROCKET_FLUTTER_VERSION (the app's way); flutter with no version installs the newest stable.
main_flutter_rust() {
  local option="$1" version="${2:-${POCKETROCKET_FLUTTER_VERSION:-}}"
  PR_LOG="$PR_HOME/flutter.log"
  case "$option" in
    flutter) TOTAL_STEPS=5 ;;
    flutter-rust) TOTAL_STEPS=6 ;;
    flutter-rust-remove) TOTAL_STEPS=3 ;;
    flutter-release) TOTAL_STEPS=3 ;;
    rust|rust-remove) TOTAL_STEPS=2; PR_LOG="$PR_HOME/rust.log" ;;
    *) TOTAL_STEPS=2 ;;
  esac
  step "Checking the device"; preflight_checks; ok "Termux on aarch64"
  case "$option" in
    flutter|flutter-rust)
      locate_glibc_tools
      step_flutter_helpers
      step_flutter_download "$version"
      step_flutter_dart
      step_flutter_setup
      [ "$option" = flutter ] || step_rust_install ;;
    flutter-default)
      flutter_version_name_ok "$version" || fail "no such Flutter version: $version"
      step "Making Flutter $version the default"
      flutter_set_default "$version" ;;
    flutter-remove-version) step_flutter_remove_version "$version" ;;
    flutter-remove) step_flutter_remove ;;
    flutter-release) locate_glibc_tools; step_flutter_release_install ;;
    flutter-release-remove) step_flutter_release_remove ;;
    rust) step_rust_install ;;
    rust-remove) step_rust_remove ;;
    flutter-rust-remove) step_flutter_remove; step_rust_remove ;;
  esac
  printf '[done]\n'
}

main() {
  case "${1:-}" in
    "") main_full ;;
    --only)
      case "${2:-}" in
        claude|dockito|chromium|chromium-remove|android-build|android-build-remove|android-ndk|android-ndk-remove|c-cpp|c-cpp-remove|wrangler|wrangler-remove|git-check|git-update|apt-refresh|clang-check|clang-update) ;;
        flutter|flutter-default|flutter-remove-version|flutter-remove|flutter-release|flutter-release-remove|rust|rust-remove|flutter-rust|flutter-rust-remove|rust-check|rust-update) ;;
        python|python-remove|bun|bun-remove|rust-wasm|rust-wasm-remove|python-check|python-update|bun-check|bun-update) ;;
        go|go-remove|deno|deno-remove|ruby|ruby-remove|php|php-remove|java|java-remove|golang-check|golang-update|deno-check|deno-update|ruby-check|ruby-update|php-check|php-update|openjdk-21-check|openjdk-21-update) ;;
        aws|aws-remove|flyctl|flyctl-remove|vercel|vercel-remove|netlify|netlify-remove|firebase|firebase-remove|awscli-check|awscli-update|flyctl-check|flyctl-update) ;;
        java-17|java-17-remove|java-21|java-21-remove|java-25|java-25-remove|kotlin|kotlin-remove|java-setup) ;;
        vercel-check|vercel-update|netlify-check|netlify-update|firebase-check|firebase-update|nodejs-lts-check|nodejs-lts-update|chromium-check|chromium-update) ;;
        *) fail "--only takes: vercel-check, vercel-update, netlify-check, netlify-update, firebase-check, firebase-update, nodejs-lts-check, nodejs-lts-update, chromium-check, chromium-update, aws, aws-remove, flyctl, flyctl-remove, vercel, vercel-remove, netlify, netlify-remove, firebase, firebase-remove, awscli-check, awscli-update, flyctl-check, flyctl-update, claude, dockito, chromium, chromium-remove, android-build, android-build-remove, android-ndk, android-ndk-remove, c-cpp, c-cpp-remove, wrangler, wrangler-remove, flutter [version], flutter-default <version>, flutter-remove-version <version>, flutter-remove, flutter-release, flutter-release-remove, rust, rust-remove, flutter-rust, flutter-rust-remove, python, python-remove, bun, bun-remove, rust-wasm, rust-wasm-remove, go, go-remove, deno, deno-remove, ruby, ruby-remove, php, php-remove, java, java-remove, java-17, java-17-remove, java-21, java-21-remove, java-25, java-25-remove, kotlin, kotlin-remove, java-setup, git-check, git-update, rust-check, rust-update, python-check, python-update, bun-check, bun-update, golang-check, golang-update, deno-check, deno-update, ruby-check, ruby-update, php-check, php-update, openjdk-21-check, openjdk-21-update, clang-check, clang-update or apt-refresh" ;;
      esac
      case "$2" in
        git-check|rust-check) apt_check "${2%-check}"; return 0 ;;
        git-update) main_apt_update Git "git --version" git; return 0 ;;
        # The standard libraries have to stay at the compiler's version, the wasm target's too (not installed: skipped).
        rust-update) main_apt_update Rust "rustc --version" rust rust-std-aarch64-linux-android rust-std-wasm32-unknown-unknown; return 0 ;;
        python-check|bun-check) apt_check "${2%-check}"; return 0 ;;
        python-update) main_apt_update Python "python3 --version" python python-pip uv python-ensurepip-wheels; return 0 ;;
        bun-update) main_apt_update Bun "bun --version" bun; return 0 ;;
        python|python-remove|bun|bun-remove|rust-wasm|rust-wasm-remove) main_pkg_tool "$2"; return 0 ;;
        # The language tiles (decision 98). Checks and updates go by package name, as AptUpdater asks.
        golang-check|deno-check|ruby-check|php-check) apt_check "${2%-check}"; return 0 ;;
        # The Java row follows 21, 25 and Kotlin, whichever are installed; never 17 (decision 103).
        # shellcheck disable=SC2086
        openjdk-21-check) apt_check $JAVA_UPDATED; return 0 ;;
        golang-update) main_apt_update Go "go version" golang; return 0 ;;
        deno-update) main_apt_update Deno "deno --version" deno; return 0 ;;
        ruby-update) main_apt_update Ruby "ruby --version" ruby; return 0 ;;
        php-update) main_apt_update PHP "php --version" php; return 0 ;;
        # The java group stays set to 17 by hand (java_keep_17), so an update of 21 or 25 doesn't move it.
        openjdk-21-update) main_apt_update "Java and Kotlin" "dpkg_versions $JAVA_UPDATED" openjdk-21 openjdk-21-x openjdk-25 openjdk-25-x kotlin; return 0 ;;
        go|go-remove|deno|deno-remove|ruby|ruby-remove|php|php-remove|java|java-remove) main_pkg_tool "$2"; return 0 ;;
        # The deploy CLIs (decision 98): two package tools, three npm ones.
        awscli-check|flyctl-check) apt_check "${2%-check}"; return 0 ;;
        awscli-update) main_apt_update "AWS CLI" "aws --version" awscli; return 0 ;;
        flyctl-update) main_apt_update flyctl "flyctl version" flyctl; return 0 ;;
        aws|aws-remove|flyctl|flyctl-remove) main_pkg_tool "$2"; return 0 ;;
        vercel|vercel-remove|netlify|netlify-remove|firebase|firebase-remove) main_npm_tool "$2"; return 0 ;;
        # One JDK or Kotlin on the Java page, and its Repair (decision 101).
        java-17|java-17-remove|java-21|java-21-remove|java-25|java-25-remove|kotlin|kotlin-remove) main_pkg_tool "$2"; return 0 ;;
        java-setup) main_java_setup; return 0 ;;
        # Every tool that can get updates has an Updates row (decision 103): the npm CLIs, Node.js and Chromium.
        vercel-check|vercel-update|netlify-check|netlify-update|firebase-check|firebase-update) main_npm_tool "$2"; return 0 ;;
        nodejs-lts-check|chromium-check) apt_check "${2%-check}"; return 0 ;;
        # C and C++ (decision 103): every package of the tool, through apt like the others.
        # shellcheck disable=SC2086
        clang-check) apt_check $CPP_PACKAGES; return 0 ;;
        # shellcheck disable=SC2086
        clang-update) main_apt_update "C and C++" "dpkg_versions $CPP_PACKAGES" $CPP_PACKAGES; return 0 ;;
        apt-refresh) apt_index_refresh; return 0 ;;
        nodejs-lts-update) main_apt_update "Node.js and npm" node_npm_versions nodejs-lts; return 0 ;;
        chromium-update) main_apt_update "Headless Chrome" chromium_version chromium; return 0 ;;
      esac
      case "$2" in
        flutter*|rust*)
          main_flutter_rust "$2" "${3:-}"
          return 0 ;;
      esac
      if [ "$2" = chromium ]; then
        TOTAL_STEPS=4; PR_LOG="$PR_HOME/chromium.log"
        step "Checking the device"; preflight_checks; ok "Termux on aarch64"
        step_chromium_repo
        step_chromium_install
        step_chromium_mcp
        return 0
      fi
      if [ "$2" = chromium-remove ]; then
        TOTAL_STEPS=3; PR_LOG="$PR_HOME/chromium.log"
        step "Checking the device"; preflight_checks; ok "Termux on aarch64"
        step_chromium_remove
        step_chromium_mcp_remove
        return 0
      fi
      if [ "$2" = android-build ]; then
        TOTAL_STEPS=4; PR_LOG="$PR_HOME/android-build.log"
        step "Checking the device"; preflight_checks; ok "Termux on aarch64"
        step_android_packages
        step_android_sdk
        step_android_wire
        return 0
      fi
      if [ "$2" = android-build-remove ]; then
        TOTAL_STEPS=3; PR_LOG="$PR_HOME/android-build.log"
        step "Checking the device"; preflight_checks; ok "Termux on aarch64"
        step_android_remove_files
        step_android_remove_packages
        return 0
      fi
      case "$2" in
        android-ndk|android-ndk-remove|c-cpp|c-cpp-remove) main_android_ndk "$2"; return 0 ;;
      esac
      if [ "$2" = wrangler ]; then
        TOTAL_STEPS=4; PR_LOG="$PR_HOME/wrangler.log"
        step "Checking the device"; preflight_checks; ok "Termux on aarch64"
        step_wrangler_install
        step_wrangler_workerd
        step_wrangler_check
        return 0
      fi
      if [ "$2" = wrangler-remove ]; then
        TOTAL_STEPS=2; PR_LOG="$PR_HOME/wrangler.log"
        step "Checking the device"; preflight_checks; ok "Termux on aarch64"
        step_wrangler_remove
        return 0
      fi
      TOTAL_STEPS=4; PR_LOG="$PR_HOME/update.log"
      step "Checking the device"; preflight_checks; locate_glibc_tools; ok "Termux on aarch64"
      if [ "$2" = dockito ]; then
        mkdir -p "$PR_DOCKITO_DIR"
        step "Downloading Dockito"; dockito_download
        step "Preparing Dockito for Android"; dockito_install
        step "Checking dockito --version"; dockito_check
        printf '[done]\n'
        return 0
      fi
      step_download
      step_patch_and_test
      claude_launchers
      step_final_check
      printf '[done]\n'
      ;;
    --switch)
      [ -n "${2:-}" ] || fail "--switch takes a version"
      TOTAL_STEPS=3; PR_LOG="$PR_HOME/update.log"
      step "Checking the device"; preflight_checks; locate_glibc_tools; ok "Termux on aarch64"
      step_switch "$2"
      claude_launchers
      step_final_check
      printf '[done]\n'
      ;;
    *) fail "unknown option: $1 (use --only claude, --only dockito, --only git-update, --only chromium, --only android-build, --only wrangler or --switch X.Y.Z)" ;;
  esac
}

main_full() {
  step_preflight
  step_base_packages
  step_extra_packages
  step_download
  step_patch_and_test
  step_defaults
  step_final_check
  step_dockito_full
  printf '[done]\n'
}

# Dockito's CLI in the first run (decision 40): one step, download, patch, smoke test and launchers. The app
# passes the release it read from Dockito's Homebrew formula; without one (offline), a Dockito that is
# already installed is kept, and otherwise the step fails so Retry tries again.
step_dockito_full() {
  step "Installing Dockito"
  mkdir -p "$PR_DOCKITO_DIR"
  local have
  if [ -z "${POCKETROCKET_DOCKITO_VERSION:-}" ]; then
    have="$(current_version "$PR_DOCKITO_DIR" || true)"
    if [ -n "$have" ] && [ -x "$PREFIX/bin/dockito" ]; then ok "Dockito $have already installed"; return 0; fi
    fail "could not read Dockito's release from its Homebrew formula (check the network and try again)"
  fi
  dockito_download
  dockito_install
  dockito_check
}

if [ "${POCKETROCKET_SETUP_SOURCE_ONLY:-0}" != 1 ]; then
  main "$@"
fi
