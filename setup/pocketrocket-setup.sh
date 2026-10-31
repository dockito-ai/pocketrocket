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
#   --only android-ndk / android-ndk-remove  the optional Android NDK tool, on top of it (decision 50)
#   --only wrangler / wrangler-remove  the optional Wrangler tool, Settings > Tools (decisions 49, 82)
#   --only flutter-rust / flutter-rust-remove  the optional Flutter & Rust tool, all of it (decision 75)
#   --only flutter [version]  add a Flutter version (newest stable by default; POCKETROCKET_FLUTTER_VERSION
#                     also works, POCKETROCKET_FLUTTER_DEFAULT=1 makes it the default)
#   --only flutter-default <version> / flutter-remove-version <version> / flutter-remove
#   --only rust / rust-remove  Rust alone, Termux's package (rust-remove takes the wasm target too)
#   --only python / python-remove, bun / bun-remove, rust-wasm / rust-wasm-remove  the package tools (decision 92):
#                     Termux packages only, 2 steps; Python's uninstall leaves what Headless Chrome needs
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
  ok "chrome-devtools-mcp removed"
  printf '[done]\n'
}

# Android build, an optional tool (decision 45, docs/android-build.md): Java, Gradle and Termux's arm64 aapt2 and
# aidl from the Termux repos, the SDK platform and build-tools from Google, wired so terminals and sessions can build.
# Google's zips are pinned to the sha1 in Google's manifest (repository2-3.xml). sdkmanager is not used: since
# cmdline-tools 16111833 it hands off to an x86_64 binary that cannot run here. So the script writes the package.xml
# sdkmanager would have, and the Android Gradle Plugin treats the packages as installed.
ANDROID_BUILD_PACKAGES="openjdk-17 gradle aapt2 aapt aidl android-tools unzip"
ANDROID_SDK_DIR="${PREFIX:-}/opt/android-sdk"
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
ANDROID_MARKER=.pocketrocket-installed                 # AndroidBuildTool.INSTALLED_MARKER: the app's "installed" test
ANDROID_GRADLE_BEGIN="# >>> PocketRocket Android build (removed on uninstall)"
ANDROID_GRADLE_END="# <<< PocketRocket Android build"

# verify_sha1 <file> <expected sha1>: 0 only when the file's SHA-1 is the expected one.
verify_sha1() {
  local have
  have="$(sha1sum "$1" 2>/dev/null | awk '{print $1}')"
  [ -n "$have" ] && [ "$have" = "$2" ]
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

# android_gradle_props_remove <gradle.properties>: takes out only our block; deletes the file if nothing else is left.
android_gradle_props_remove() {
  [ -f "$1" ] || return 0
  sed -i.prbak -e "/^$ANDROID_GRADLE_BEGIN\$/,/^$ANDROID_GRADLE_END\$/d" "$1"
  rm -f "$1.prbak"
  if ! grep -q '[^[:space:]]' "$1"; then rm -f "$1"; fi
}

# android_gradle_props_add <gradle.properties> <aapt2 path>: the plugin's own aapt2 is x86_64, so point it at Termux's.
# A daemon idles for 3 hours by default, holding over 1 GB; on a phone it goes after 10 minutes.
android_gradle_props_add() {
  android_gradle_props_remove "$1"
  mkdir -p "$(dirname "$1")"
  printf '%s\n' "$ANDROID_GRADLE_BEGIN" "android.aapt2FromMavenOverride=$2" \
    "org.gradle.daemon.idletimeout=600000" "$ANDROID_GRADLE_END" >>"$1"
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
  packages_baseline "$ANDROID_BASELINE_FILE"
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
  touch "$ANDROID_SDK_DIR/$ANDROID_MARKER"
  ok "ANDROID_HOME, JAVA_HOME and aapt2 set for every session"
  printf '[done]\n'
}

step_android_remove_files() {
  step "Removing the Android SDK"
  # A running daemon would keep writing into the folders about to go.
  pkill -f GradleDaemon 2>/dev/null || true
  pkill -f KotlinCompileDaemon 2>/dev/null || true
  # The NDK tool sits on top of this one, so it goes with it (decision 50).
  android_ndk_remove
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

# Android NDK, an optional tool on top of Android build (decision 50, docs/android-build.md). Google publishes no arm64
# NDK, so Termux's clang, cmake, ninja and ndk-sysroot sit behind folders laid out the way the Android Gradle Plugin
# expects. There is one shim; each version below is a folder that only claims that revision, so a project's ndkVersion
# or its plugin's default is accepted. Every version is the same Termux compiler. docs/android-ndk-spike.md has the why.
ANDROID_NDK_PACKAGES="clang cmake ninja ndk-sysroot"
# The default NDK of each plugin: 8.0-8.3, 8.4-8.6, 8.7-8.13, 9.0-9.5. The one list of versions; the page reads the folders.
ANDROID_NDK_VERSIONS="25.1.8937393 26.1.10909125 27.0.12077973 28.2.13676358"
ANDROID_CMAKE_VERSION=3.22.1 # The plugin's default. Without this folder it would download Google's x86_64 CMake.
ANDROID_NDK_DIR="$ANDROID_SDK_DIR/ndk"
ANDROID_NDK_SHIM_DIR="$ANDROID_SDK_DIR/ndk-shim"
ANDROID_CMAKE_DIR="$ANDROID_SDK_DIR/cmake"
ANDROID_NDK_ADDED_FILE="$PR_HOME/android-ndk.packages"
ANDROID_NDK_BASELINE_FILE="$PR_HOME/android-ndk.baseline"
ANDROID_NDK_INIT_SCRIPT="$HOME/.gradle/init.d/pocketrocket-android-ndk.gradle"

android_ndk_write_toolchain() {
  cat >"$1" <<'EOF'
# Written by PocketRocket's Android NDK tool. Stands in for the NDK's toolchain file: arm64-v8a only, on an arm64 Android host.
get_filename_component(_NDK "${CMAKE_CURRENT_LIST_DIR}/../.." ABSOLUTE)
set(_TC "${_NDK}/toolchains/llvm/prebuilt/linux-x86_64")

if(NOT ANDROID_ABI)
  set(ANDROID_ABI arm64-v8a)
endif()
if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
  message(FATAL_ERROR "PocketRocket's Android NDK tool builds arm64-v8a only; ${ANDROID_ABI} cannot be built on the phone.")
endif()
if(NOT ANDROID_PLATFORM)
  set(ANDROID_PLATFORM android-24)
endif()
string(REPLACE "android-" "" _API "${ANDROID_PLATFORM}")
if(NOT ANDROID_STL)
  set(ANDROID_STL c++_shared)
endif()
if(ANDROID_STL STREQUAL "c++_static")
  message(FATAL_ERROR "Termux ships no static libc++ (libc++_static.a). Set -DANDROID_STL=c++_shared in externalNativeBuild.")
endif()

set(ANDROID TRUE)
set(CMAKE_SYSTEM_NAME Android)
set(CMAKE_SYSTEM_VERSION ${_API})
set(CMAKE_SYSTEM_PROCESSOR aarch64)
set(CMAKE_ANDROID_ARCH_ABI arm64-v8a)
set(ANDROID_NDK "${_NDK}")

set(CMAKE_C_COMPILER "${_TC}/bin/clang")
set(CMAKE_CXX_COMPILER "${_TC}/bin/clang++")
set(CMAKE_C_COMPILER_TARGET aarch64-none-linux-android${_API})
set(CMAKE_CXX_COMPILER_TARGET aarch64-none-linux-android${_API})
set(CMAKE_AR "${_TC}/bin/llvm-ar" CACHE FILEPATH "")
set(CMAKE_RANLIB "${_TC}/bin/llvm-ranlib" CACHE FILEPATH "")
set(CMAKE_STRIP "${_TC}/bin/llvm-strip" CACHE FILEPATH "")
set(CMAKE_SYSROOT "${_TC}/sysroot")

set(CMAKE_FIND_ROOT_PATH "${_TC}/sysroot")
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)

set(_F "-DANDROID -fPIC -ffunction-sections -fdata-sections -funwind-tables -fstack-protector-strong -no-canonical-prefixes -D_FORTIFY_SOURCE=2 -Wformat -Werror=format-security")
set(CMAKE_C_FLAGS_INIT "${_F}")
set(CMAKE_CXX_FLAGS_INIT "${_F}")
set(_L "--ld-path=${_TC}/bin/ld-no-rpath -Wl,--build-id=sha1 -Wl,--no-undefined -Wl,-z,max-page-size=16384 -Qunused-arguments")
set(CMAKE_SHARED_LINKER_FLAGS_INIT "${_L}")
set(CMAKE_MODULE_LINKER_FLAGS_INIT "${_L}")
set(CMAKE_EXE_LINKER_FLAGS_INIT "${_L}")

# Passed through to try_compile runs, which re-read this file.
set(CMAKE_TRY_COMPILE_PLATFORM_VARIABLES ANDROID_ABI ANDROID_PLATFORM ANDROID_STL)
EOF
}

# android_ndk_write_init_script <file>: Gradle's side of the tool, run before the plugin looks for the NDK.
# A project that names no STL gets c++_shared: the plugin packs libc++_shared.so only when the argument names it.
# A version not served stops the build with a plain message. Without this the plugin downloads Google's x86_64 NDK
# (2.4 GB on disk, measured) and then fails. Gradle still runs the plugin's own hook after a throw, so the throw alone
# does not stop that download; the version is pointed at a served folder first.
android_ndk_write_init_script() {
  mkdir -p "$(dirname "$1")"
  cat >"$1" <<EOF
// Written by PocketRocket's Android NDK tool. Removed on uninstall.
def served = "$ANDROID_NDK_VERSIONS".split(" ") as List
allprojects { project ->
    project.afterEvaluate {
        def android = project.extensions.findByName("android")
        if (android == null) return
        def cmake, ndkBuild, version, path, arguments
        try {
            cmake = android.externalNativeBuild.cmake.path
            ndkBuild = android.externalNativeBuild.ndkBuild.path
            version = android.ndkVersion
            path = android.ndkPath
            arguments = android.defaultConfig.externalNativeBuild.cmake.arguments
        } catch (MissingPropertyException ignored) {
            return
        }
        if (path != null || (cmake == null && ndkBuild == null)) return
        if (!served.contains(version)) android.ndkVersion = served.last()
        if (ndkBuild != null) {
            throw new GradleException("PocketRocket's Android NDK tool has no ndk-build. Move " + project.path + " to CMake, or build it off the phone.")
        }
        if (!served.contains(version)) {
            throw new GradleException("PocketRocket's Android NDK tool does not serve NDK " + version + ". It accepts " + served.join(", ") + " (all the same Termux compiler). Change ndkVersion in " + project.path + " to one of them, or remove it.")
        }
        if (!arguments.any { it.startsWith("-DANDROID_STL=") }) arguments.add("-DANDROID_STL=c++_shared")
    }
}
EOF
}

# android_ndk_make_shim: one shim, one folder per version, the CMake folder.
android_ndk_make_shim() {
  local shim="$ANDROID_NDK_SHIM_DIR" tc sr f b l v d
  android_ndk_remove_folders
  tc="$shim/toolchains/llvm/prebuilt/linux-x86_64"; sr="$tc/sysroot"
  mkdir -p "$shim/meta" "$shim/build/cmake" "$tc/bin" "$tc/lib" "$sr/usr/lib"
  # The plugin picks the host folder from the OS name, so an arm64 Linux-looking host still asks for linux-x86_64.
  ln -s linux-x86_64 "$shim/toolchains/llvm/prebuilt/linux-arm64"
  printf '{"min":21,"max":37,"aliases":{}}\n' >"$shim/meta/platforms.json"
  printf '{"arm64-v8a":{"bitness":64,"default":true,"deprecated":false,"proc":"aarch64","arch":"arm64","triple":"aarch64-linux-android","llvm_triple":"aarch64-none-linux-android"}}\n' >"$shim/meta/abis.json"
  printf '{}\n' >"$shim/meta/system_libs.json"
  android_ndk_write_toolchain "$shim/build/cmake/android.toolchain.cmake"

  # clang looks for its resource dir beside the path it was called by; the toolchain's -no-canonical-prefixes keeps that path.
  ln -s "$PREFIX/lib/clang" "$tc/lib/clang"
  for b in clang clang++ ld.lld llvm-strip llvm-objcopy llvm-ar llvm-ranlib llvm-nm llvm-readelf; do
    ln -s "$PREFIX/bin/$b" "$tc/bin/$b"
  done
  # Termux's clang always adds -rpath=<sysroot>/usr/lib, a path that means nothing outside this app. No flag removes it.
  cat >"$tc/bin/ld-no-rpath" <<EOF
#!$PREFIX/bin/bash
args=()
for a in "\$@"; do case "\$a" in -rpath=*) ;; *) args+=("\$a") ;; esac; done
exec "$PREFIX/bin/ld.lld" "\${args[@]}"
EOF
  chmod 700 "$tc/bin/ld-no-rpath"

  # Headers from Termux. Libraries are only the crt objects and libc++ from Termux and the phone's own system libraries,
  # so no other Termux library can be linked by accident.
  ln -s "$PREFIX/include" "$sr/usr/include"
  for f in crtbegin_so.o crtend_so.o crtbegin_dynamic.o crtend_android.o crt_pad_segment.o libunwind.a libatomic.a libcompiler_rt-extras.a libc++_shared.so; do
    ln -s "$PREFIX/lib/$f" "$sr/usr/lib/$f"
  done
  for l in c m dl log z android EGL GLESv1_CM GLESv2 GLESv3 OpenSLES jnigraphics mediandk nativewindow vulkan aaudio binder_ndk; do
    if [ -e "/system/lib64/lib$l.so" ]; then ln -s "/system/lib64/lib$l.so" "$sr/usr/lib/lib$l.so"; fi
  done
  ln -s . "$sr/usr/lib/aarch64-linux-android"

  # The plugin reads the revision from the version folder, so that file is real; the rest points at the one shim.
  for v in $ANDROID_NDK_VERSIONS; do
    d="$ANDROID_NDK_DIR/$v"; mkdir -p "$d"
    printf 'Pkg.Desc = PocketRocket: Termux clang behind the NDK layout, not Google NDK %s\nPkg.Revision = %s\n' "$v" "$v" >"$d/source.properties"
    for f in meta build toolchains; do ln -s "$shim/$f" "$d/$f"; done
  done

  d="$ANDROID_CMAKE_DIR/$ANDROID_CMAKE_VERSION"; mkdir -p "$d/bin"
  printf 'Pkg.Desc = PocketRocket: Termux CMake\nPkg.Revision = %s\n' "$ANDROID_CMAKE_VERSION" >"$d/source.properties"
  for b in cmake ctest cpack ninja; do ln -s "$PREFIX/bin/$b" "$d/bin/$b"; done
}

android_ndk_remove_folders() {
  rm -rf "${ANDROID_NDK_SHIM_DIR:?}" "${ANDROID_NDK_DIR:?}" "${ANDROID_CMAKE_DIR:?}/${ANDROID_CMAKE_VERSION:?}"
  rmdir "$ANDROID_CMAKE_DIR" 2>/dev/null || true
}

step_android_ndk_packages() {
  step "Installing clang, CMake and Ninja"
  [ -f "$ANDROID_SDK_DIR/$ANDROID_MARKER" ] || fail "Install the Android tool first."
  apt_refresh
  packages_baseline "$ANDROID_NDK_BASELINE_FILE"
  # shellcheck disable=SC2086
  apt_install_progress $ANDROID_NDK_PACKAGES
  local b
  packages_record "$ANDROID_NDK_BASELINE_FILE" "$ANDROID_NDK_ADDED_FILE"
  for b in bin/clang bin/cmake bin/ninja lib/crtbegin_so.o lib/libc++_shared.so; do
    [ -e "$PREFIX/$b" ] || fail "$b is missing after install"
  done
  ok "packages installed"
}

step_android_ndk_wire() {
  step "Setting up the NDK folders"
  android_ndk_make_shim
  android_ndk_write_init_script "$ANDROID_NDK_INIT_SCRIPT"
  touch "$ANDROID_NDK_SHIM_DIR/$ANDROID_MARKER"
  ok "NDK versions ready: $ANDROID_NDK_VERSIONS"
  printf '[done]\n'
}

# Folders, the Gradle init script and exactly the packages this tool brought in. Uninstalling Android runs this first.
android_ndk_remove() {
  android_ndk_remove_folders
  rm -f "$ANDROID_NDK_INIT_SCRIPT"; rmdir "$(dirname "$ANDROID_NDK_INIT_SCRIPT")" 2>/dev/null || true
  # An install that stopped inside apt left a baseline but no list yet. (Only then: installs before the baseline was
  # dropped after recording kept theirs, and it would now count what other tools added since.)
  if [ -f "$ANDROID_NDK_BASELINE_FILE" ] && [ ! -f "$ANDROID_NDK_ADDED_FILE" ]; then
    packages_record "$ANDROID_NDK_BASELINE_FILE" "$ANDROID_NDK_ADDED_FILE"
  fi
  android_purge_added "$ANDROID_NDK_ADDED_FILE"
  rm -f "$ANDROID_NDK_ADDED_FILE" "$ANDROID_NDK_BASELINE_FILE"
}

# Packages on the Updates screen (decision 41): Git, and Rust once the Flutter & Rust tool has it (decision 75).
# apt-cache policy prints "Installed: X" and "Candidate: Y".
apt_policy_field() { apt-cache policy "$1" 2>/dev/null | awk -v k="$2:" '$1 == k { print $2; exit }'; }

# apt_check <package>: refreshes the index and prints the package's policy. Its own log (<package>-check.log, so a
# check never wipes the install or update log); no lock, no steps.
apt_check() {
  PR_LOG="$PR_HOME/$1-check.log"; mkdir -p "$PR_HOME"; : >"$PR_LOG"
  export DEBIAN_FRONTEND=noninteractive
  apt_refresh
  apt-cache policy "$1" 2>/dev/null
}

# main_apt_update <name> <version command> <package> [packages that move with it]: --only <package>-update, with the
# device check, the package's own log and its version at the end.
main_apt_update() {
  local name="$1" check="$2"; shift 2
  TOTAL_STEPS=2; PR_LOG="$PR_HOME/$1.log"
  step "Checking the device"; preflight_checks; ok "Termux on aarch64"
  step_apt_update "$name" "$@"
  # shellcheck disable=SC2086
  ok "$($check 2>&1)"
  printf '[done]\n'
}

# step_apt_update <name> <package> [packages that move with it]: upgrades them alone, plus only what the new
# versions need, then checks the package is at its candidate.
step_apt_update() {
  local name="$1" pkg="$2" have want
  shift
  step "Updating $name"
  apt_refresh
  have="$(apt_policy_field "$pkg" Installed)"; want="$(apt_policy_field "$pkg" Candidate)"
  [ -n "$have" ] && [ "$have" != "(none)" ] || fail "$pkg is not installed (run the setup or install it first)"
  if [ -z "$want" ] || [ "$want" = "(none)" ] || [ "$have" = "$want" ]; then
    ok "$pkg $have is the newest"
  else
    info "$pkg $have -> $want"
    apt_package_upgrade_progress "$@"
    have="$(apt_policy_field "$pkg" Installed)"
    [ "$have" = "$want" ] || fail "$pkg is still $have after the upgrade, expected $want"
  fi
  hash -r 2>/dev/null || true
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
WRANGLER_DIR="${PREFIX:-}/lib/node_modules/wrangler"
WRANGLER_MARKER=".pocketrocket-installed"
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
  local major cache="$TMPDIR/wrangler-npm-cache"
  major="$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null || echo 0)"
  [ "$major" -ge "$WRANGLER_NODE_MIN" ] || fail "Wrangler needs Node $WRANGLER_NODE_MIN or newer, found ${major}"
  rm -rf "$cache"
  log_run npm install -g --ignore-scripts --no-fund --no-audit --cache "$cache" "wrangler@${WRANGLER_VERSION}" \
    || { rm -rf "$cache"; fail "npm install wrangler failed"; }
  rm -rf "$cache"
  wrangler_patch_workerd "$WRANGLER_DIR/node_modules/workerd/lib/main.js" \
    || fail "wrangler ${WRANGLER_VERSION} has a workerd this setup does not know"
  # The app's processes and Claude sessions don't all have termux-exec, so `#!/usr/bin/env node` can't be left to it.
  sed -i "1s|^#!.*|#!$PREFIX/bin/node|" "$WRANGLER_DIR/bin/wrangler.js" || fail "could not fix wrangler's launcher"
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
  : > "$WRANGLER_DIR/$WRANGLER_MARKER"
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
FLUTTER_MARKER="$FLUTTER_HOME/.pocketrocket-installed"  # FlutterTool.INSTALLED_MARKER
FLUTTER_PACKAGES="which unzip"                          # flutter doctor needs which; the Dart SDK download needs unzip
FLUTTER_ADDED_FILE="$PR_HOME/flutter.packages"
FLUTTER_BASELINE_FILE="$PR_HOME/flutter.baseline"
FVM_VERSION=4.3.2
FVM_SHA256=df05c7682eeac21b08939e7be131687dc3d54fd5575b5e4bca931717ddeb0a01   # GitHub's published digest of the asset
FVM_URL="https://github.com/leoafarias/fvm/releases/download/$FVM_VERSION/fvm-$FVM_VERSION-linux-arm64.tar.gz"
FVM_DIR="${PREFIX:-}/opt/fvm"
RUST_PACKAGES="rust"                                    # brings rust-std-aarch64-linux-android with it
RUST_ADDED_FILE="$PR_HOME/rust.packages"                # FlutterTool.RUST_MARKER: what the install added; means installed
RUST_BASELINE_FILE="$PR_HOME/rust.baseline"

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

# packages_baseline <file> / packages_record <baseline> <added>: what an install brings in, so its uninstall removes
# only that. The baseline lives only from a run's apt step to its record (a Retry after apt stopped reuses it). The
# record adds what this run brought to the list, never drops what an earlier run added, and deletes the baseline, so
# a later run never counts what other tools installed in between. Same helpers as the Android tools.
packages_baseline() { [ -f "$1" ] || android_installed_packages >"$1"; }
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
# FlutterRust.flutterInstalled: the marker, or what a failed install left (FVM, or the package list).
flutter_present() { [ -f "$FLUTTER_MARKER" ] || [ -d "$FVM_DIR" ] || [ -f "$FLUTTER_ADDED_FILE" ] || [ -f "$FLUTTER_BASELINE_FILE" ]; }

# The launchers: flutter from setup/flutter, with dart and fvm links to it. As fvm it runs FVM's own Dart (prepared
# like Flutter's) and makes ready the versions FVM installs or runs.
flutter_launchers() {
  install_launcher flutter
  ln -sf flutter "$PREFIX/bin/dart"
  rm -f "$PREFIX/bin/fvm"
  ln -s flutter "$PREFIX/bin/fvm"
}

step_flutter_helpers() {
  step "Installing FVM and helpers"
  local out tgz="$TMPDIR/fvm-$FVM_VERSION.tar.gz" d="$TMPDIR/fvm-unpack"
  # shellcheck disable=SC2086
  [ -z "$(missing_packages $FLUTTER_PACKAGES)" ] || apt_refresh
  packages_baseline "$FLUTTER_BASELINE_FILE"
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
  POCKETROCKET_FLUTTER_PREPARE="$FVM_DIR/src" "$PREFIX/bin/flutter" >>"$PR_LOG" 2>&1 || fail "could not prepare FVM for this phone"
  out="$(fvm --version 2>&1)" || fail "fvm --version failed: $out"
  # Versions come from the tool page; FVM's mirror of the whole Flutter history would add gigabytes.
  log_run fvm config --no-use-git-cache --no-update-check || warn "could not turn off FVM's git cache"
  ok "FVM $out"
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
  : >"$FLUTTER_MARKER"
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
  printf '%s\n' "$FLUTTER_HOME" "$FVM_DIR" "$PREFIX/bin/flutter" "$PREFIX/bin/dart" "$PREFIX/bin/fvm" \
    "$HOME/.pub-cache" "$HOME/.dart-tool" "$HOME/.dartServer" "$HOME/.dart" "$HOME/.flutter" "$HOME/.flutter-devtools" \
    "$HOME/.config/flutter" "$HOME/.config/fvm" "$HOME/.fvm"
}

step_flutter_remove() {
  step "Removing Flutter, Dart and FVM"
  local p
  pkill -f "$FLUTTER_VERSIONS/" 2>/dev/null || true   # an analysis server left running would keep writing
  while IFS= read -r p; do rm -rf "${p:?}"; done < <(flutter_remove_paths)
  rmdir "$PREFIX/opt" "$HOME/.config" 2>/dev/null || true
  # A run that stopped inside apt left a baseline it didn't record yet.
  if [ -f "$FLUTTER_BASELINE_FILE" ]; then packages_record "$FLUTTER_BASELINE_FILE" "$FLUTTER_ADDED_FILE"; fi
  # shellcheck disable=SC2086
  if android_build_present; then packages_hand_over "$FLUTTER_ADDED_FILE" "$ANDROID_ADDED_FILE" $ANDROID_BUILD_PACKAGES; fi
  android_purge_added "$FLUTTER_ADDED_FILE"
  rm -f "$FLUTTER_ADDED_FILE" "$FLUTTER_BASELINE_FILE"
  ok "Flutter removed"
}

step_rust_install() {
  step "Installing Rust"
  local out
  apt_refresh
  packages_baseline "$RUST_BASELINE_FILE"
  # shellcheck disable=SC2086
  apt_install_progress $RUST_PACKAGES
  hash -r 2>/dev/null || true
  out="$(cargo --version 2>&1)" || fail "cargo --version failed: $out"
  out="$(rustc --version 2>&1)" || fail "rustc --version failed: $out"
  # Written last: the app reads this file as "installed".
  packages_record "$RUST_BASELINE_FILE" "$RUST_ADDED_FILE"
  ok "$out"
}

step_rust_remove() {
  step "Removing Rust"
  if [ -f "$RUST_BASELINE_FILE" ]; then packages_record "$RUST_BASELINE_FILE" "$RUST_ADDED_FILE"; fi
  if [ ! -s "$RUST_ADDED_FILE" ] && pkg_installed rust; then info "Rust was on the phone before this tool, so it stays"; fi
  # Rust's extra targets go with it (the wasm target has no dependency on rust, so apt would leave it).
  pkg_tool_purge rust-wasm
  android_purge_added "$RUST_ADDED_FILE"
  rm -f "$RUST_ADDED_FILE" "$RUST_BASELINE_FILE"
  rm -rf "$HOME/.cargo"
  ok "Rust removed"
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
    *) return 1 ;;
  esac
}

# pkg_tool_companions <name>: packages that come with the tool's own (python recommends its ensurepip wheels) and go
# with it on uninstall.
pkg_tool_companions() {
  case "$1" in
    python) printf 'python-ensurepip-wheels' ;;
  esac
}

pkg_tool_title() {
  case "$1" in
    python) printf 'Python, pip and uv' ;;
    bun) printf 'Bun' ;;
    rust-wasm) printf 'the wasm target' ;;
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
  if [ -f "$baseline" ]; then packages_record "$baseline" "$added"; fi
  # shellcheck disable=SC2046
  { if [ -f "$added" ]; then cat "$added"; fi; printf '%s\n' $(pkg_tool_packages "$name"); } | sed '/^$/d' | sort -u >"$list"
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
  packages_baseline "$PR_HOME/$name.baseline"
  # shellcheck disable=SC2046
  apt_install_progress $(pkg_tool_packages "$name")
  packages_record "$PR_HOME/$name.baseline" "$PR_HOME/$name.packages"
  hash -r 2>/dev/null || true
  [ "$name" != python ] || uv_config_add "$UV_CONFIG"
  out="$(pkg_tool_check "$name" 2>&1)" || fail "$(pkg_tool_title "$name") does not run: $out"
  ok "$out"
}

step_pkg_tool_remove() {
  local name="$1"
  step "Removing $(pkg_tool_title "$name")"
  pkg_tool_purge "$name"
  [ "$name" != python ] || uv_config_remove "$UV_CONFIG"
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

# The Flutter & Rust tool's options (decision 75). The version comes as the third argument or in
# POCKETROCKET_FLUTTER_VERSION (the app's way); flutter with no version installs the newest stable.
main_flutter_rust() {
  local option="$1" version="${2:-${POCKETROCKET_FLUTTER_VERSION:-}}"
  PR_LOG="$PR_HOME/flutter.log"
  case "$option" in
    flutter) TOTAL_STEPS=5 ;;
    flutter-rust) TOTAL_STEPS=6 ;;
    flutter-rust-remove) TOTAL_STEPS=3 ;;
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
        claude|dockito|chromium|chromium-remove|android-build|android-build-remove|android-ndk|android-ndk-remove|wrangler|wrangler-remove|git-check|git-update) ;;
        flutter|flutter-default|flutter-remove-version|flutter-remove|rust|rust-remove|flutter-rust|flutter-rust-remove|rust-check|rust-update) ;;
        python|python-remove|bun|bun-remove|rust-wasm|rust-wasm-remove|python-check|python-update|bun-check|bun-update) ;;
        *) fail "--only takes: claude, dockito, chromium, chromium-remove, android-build, android-build-remove, android-ndk, android-ndk-remove, wrangler, wrangler-remove, flutter [version], flutter-default <version>, flutter-remove-version <version>, flutter-remove, rust, rust-remove, flutter-rust, flutter-rust-remove, python, python-remove, bun, bun-remove, rust-wasm, rust-wasm-remove, git-check, git-update, rust-check, rust-update, python-check, python-update, bun-check or bun-update" ;;
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
      if [ "$2" = android-ndk ]; then
        TOTAL_STEPS=3; PR_LOG="$PR_HOME/android-ndk.log"
        step "Checking the device"; preflight_checks; ok "Termux on aarch64"
        step_android_ndk_packages
        step_android_ndk_wire
        return 0
      fi
      if [ "$2" = android-ndk-remove ]; then
        TOTAL_STEPS=2; PR_LOG="$PR_HOME/android-ndk.log"
        step "Checking the device"; preflight_checks; ok "Termux on aarch64"
        step "Removing clang, CMake and Ninja"
        android_ndk_remove
        ok "NDK tool removed"
        printf '[done]\n'
        return 0
      fi
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
