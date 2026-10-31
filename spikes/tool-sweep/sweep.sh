#!/data/data/app.pocket/files/usr/bin/bash
# Tool sweep harness (spike, see docs/tool-sweep.md).
#
#   bash sweep.sh [options] [tool...]   run recipes (default: all in tools/), one at a time
#   bash sweep.sh list                  list recipes with group and source
#   bash sweep.sh table                 render results as a markdown table (latest row per tool)
#
# Options: --no-fixed   skip the probe re-run under libtermux-exec (Phase 0 simulation)
#
# Per tool: install (timed), probe plain (what a Claude shell sees today), probe again with
# LD_PRELOAD=libtermux-exec.so (what Phase 0 will give), then remove everything this run added.
# Safety: nothing already installed is touched. apt packages are removed only if they were absent
# before this run (dpkg diff) and apt's own simulation shows nothing else would go with them.
# Non-apt installs live in $SWEEP_HOME/opt/<tool>; downloads in a fresh dir under $TMPDIR.
#
# Results (TSV): date tool group source verdict cause install_s probe_s download_kb installed_kb pre notes
#   verdict: works | works-with-fix | fails | n/a
#   cause:   A env shebang, B hardcoded /tmp, C glibc-only binary, D installer rejects android,
#            E no Android build, ? unclassified, - not applicable

set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
: "${PREFIX:?run inside Termux}"
SWEEP_HOME="${SWEEP_HOME:-$HOME/.cache/tool-sweep}"
RESULTS="${SWEEP_RESULTS:-$HERE/results/results.tsv}"
PRELOAD_LIB="$PREFIX/lib/libtermux-exec.so"
INSTALL_TIMEOUT="${INSTALL_TIMEOUT:-1800}"
PROBE_TIMEOUT="${PROBE_TIMEOUT:-300}"
# Never purged, even if a run somehow added them.
PROTECTED='^(python|python-pip|make|rust|rust-std-.*|nodejs-lts|nodejs|npm|gh|ripgrep|jq|curl|git|openssh|zellij|glibc|glibc-repo|glibc-runner|patchelf-glibc|apt|dpkg|bash|coreutils|termux-.*|libc\+\+|openssl|ca-certificates)$'

# ------------------------------------------------------------------ phase runner (internal)
if [ "${1:-}" = "_run" ]; then
  phase="$2"; TOOL="$3"
  . "$HERE/lib.sh"
  . "$HERE/tools/$TOOL.sh"
  tool_env
  cd "$WORK" || exit 99
  if ! declare -F "$phase" >/dev/null; then
    [ "$phase" = remove ] && exit 0
    echo "recipe has no $phase" >&2; exit 98
  fi
  set -eo pipefail
  "$phase"
  exit $?
fi

# ------------------------------------------------------------------ helpers
now() { date +%s; }
kb_of() { du -sk "$1" 2>/dev/null | cut -f1; }
snapshot() { dpkg-query -W -f '${Package}\t${Version}\t${Installed-Size}\n' | LC_ALL=C sort; }

meta() { # tool -> GROUP|SOURCE|PKGS|BINS
  ( . "$HERE/lib.sh"; TOOL="$1"; . "$HERE/tools/$1.sh"
    printf '%s|%s|%s|%s\n' "${GROUP:-?}" "${SOURCE:-?}" "${PKGS:-}" "${BINS:-}" )
}

classify() { # logfile -> cause letter
  local t; t="$(tail -c 6000 "$1")"
  if   grep -Eqi 'unable to locate package|no installation candidate|has no installation candidate|404 not found' <<<"$t"; then echo E
  elif grep -Eqi 'EBADPLATFORM|your platform is unsupported|unable to resolve @[^ ]*(android|arm64)|unsupported platform|not supported on android|unsupported os|platform.*android.*(not|unsupported)' <<<"$t"; then echo D
  elif grep -Eqi 'bad interpreter|/usr/bin/env|env: .*(no such file|not found)|sh: [0-9]+: [^ ]+: not found' <<<"$t"; then echo A
  elif grep -Eq '/tmp' <<<"$t" && grep -Eqi 'permission denied|EACCES|read-only|cannot create|no such file' <<<"$t"; then echo B
  elif grep -Eqi 'GLIBC_|ld-linux|cannot execute|exec format|required file not found|error while loading shared|libc\.so\.6|CANNOT LINK' <<<"$t"; then echo C
  else echo '?'; fi
}

human() { awk -v k="${1:-}" 'BEGIN{ if(k==""||k=="-"){print "-"; exit} if(k>=1024) printf "%.0f MB", k/1024; else printf "%d KB", k }'; }

note_of() { { grep -Ei 'error|not found|unable|fail|denied' "$1" | head -n 2; [ "${PIPESTATUS[0]}" -eq 0 ] || tail -n 3 "$1"; } | tr '\n\t' '  ' | cut -c1-220; }

run_phase() { # phase mode timeout log   (mode: plain|fixed)
  local phase="$1" mode="$2" to="$3" log="$4"
  if [ "$mode" = fixed ]; then
    LD_PRELOAD="$PRELOAD_LIB" timeout "$to" bash "$HERE/sweep.sh" _run "$phase" "$TOOL" >"$log" 2>&1
  else
    env -u LD_PRELOAD timeout "$to" bash "$HERE/sweep.sh" _run "$phase" "$TOOL" >"$log" 2>&1
  fi
}

# Remove only the apt packages this run added. Refuses if apt would take anything else with them.
purge_added() { # file listing added package names
  local want=() p sim extra=""
  while read -r p; do
    [ -z "$p" ] && continue
    if [[ "$p" =~ $PROTECTED ]]; then echo "kept protected $p" >&2; else want+=("$p"); fi
  done <"$1"
  [ "${#want[@]}" -eq 0 ] && return 0
  sim="$(apt-get -s purge -y "${want[@]}" 2>&1)" || { echo "apt simulation failed; leaving ${want[*]}"; return 1; }
  while read -r p; do
    printf '%s\n' "${want[@]}" | grep -qx "$p" || extra="$extra $p"
  done < <(awk '/^Remv /{print $2}' <<<"$sim")
  if [ -n "$extra" ]; then echo "refused purge: would also remove:$extra"; return 1; fi
  apt-get purge -y "${want[@]}" >/dev/null 2>&1 || { echo "purge failed"; return 1; }
  return 0
}

append_row() {
  [ -s "$RESULTS" ] || printf 'date\ttool\tgroup\tsource\tverdict\tcause\tinstall_s\tprobe_s\tdownload_kb\tinstalled_kb\tpre\tnotes\n' >"$RESULTS"
  local IFS=$'\t'; printf '%s\n' "$*" >>"$RESULTS"
}

# ------------------------------------------------------------------ one tool
CUR_ACTIVE=""
cleanup_current() {
  [ -n "$CUR_ACTIVE" ] || return 0
  TOOL="$CUR_ACTIVE"
  if [ "${CUR_PRE:-no}" = no ]; then
    run_phase remove plain 120 "$LOGDIR/remove.log" || true
    snapshot | cut -f1 >"$LOGDIR/after.names"
    comm -13 "$LOGDIR/before.names" "$LOGDIR/after.names" >"$LOGDIR/added.names"
    purge_added "$LOGDIR/added.names" >"$LOGDIR/purge.log" 2>&1 || CUR_LEFT="$(tr '\n' ' ' <"$LOGDIR/purge.log" | cut -c1-120)"
    rm -rf "${TOOL_DIR:?}"
  elif [ -n "${TOOL_DIR:-}" ]; then
    rmdir "$TOOL_DIR/bin" "$TOOL_DIR" 2>/dev/null   # empty dirs tool_env made for an already-installed tool
  fi
  rm -rf "${DL:-/nonexistent-}" "${WORK:-/nonexistent-}"
  CUR_ACTIVE=""
}
trap 'cleanup_current; exit 130' INT TERM
trap 'cleanup_current' EXIT

sweep_one() {
  local tool="$1" m group source pkgs bins pre=no
  [ -f "$HERE/tools/$tool.sh" ] || { echo "no recipe: $tool" >&2; return 1; }
  m="$(meta "$tool")" || { echo "bad recipe: $tool" >&2; return 1; }
  IFS='|' read -r group source pkgs bins <<<"$m"
  echo "== $tool ($group, $source)"

  TOOL="$tool"
  LOGDIR="$(mktemp -d "${TMPDIR:-/tmp}/tool-sweep-log.XXXXXX")"
  DL="$(mktemp -d "${TMPDIR:-/tmp}/tool-sweep-dl.XXXXXX")"
  WORK="$(mktemp -d "${TMPDIR:-/tmp}/tool-sweep-work.XXXXXX")"
  TOOL_DIR="$SWEEP_HOME/opt/$tool"
  export TOOL DL WORK TOOL_DIR SWEEP_HOME
  rm -rf "$TOOL_DIR"

  # Already installed? (apt packages by dpkg, otherwise commands on the plain PATH.)
  snapshot >"$LOGDIR/before.tsv"; cut -f1 "$LOGDIR/before.tsv" >"$LOGDIR/before.names"
  if [ -n "$pkgs" ]; then
    pre=yes; for p in $pkgs; do grep -qx "$p" "$LOGDIR/before.names" || pre=no; done
  elif [ -n "$bins" ]; then
    pre=yes; for b in $bins; do command -v "$b" >/dev/null 2>&1 || pre=no; done
  fi
  CUR_PRE="$pre"; CUR_LEFT=""; CUR_ACTIVE="$tool"
  [ "$pre" = yes ] && echo "   already on the phone: install and remove skipped"

  local dl_kb=0 inst_kb="" t0 t1 inst_s=0 probe_s=0 verdict cause="-" notes="" imode=plain

  # Install (plain first; if that fails, retry under the preload and say so).
  if [ "$pre" = no ]; then
    if [ -n "$pkgs" ]; then
      local uris
      uris="$(apt-get install --print-uris -y -qq $pkgs 2>/dev/null | awk "/^'/{s+=\$3} END{print int(s/1024)}")"
      dl_kb=$(( ${uris:-0} ))
    fi
    t0=$(now)
    if ! run_phase install plain "$INSTALL_TIMEOUT" "$LOGDIR/install.log"; then
      local plain_cause; plain_cause="$(classify "$LOGDIR/install.log")"
      cp "$LOGDIR/install.log" "$LOGDIR/install.plain.log"
      rm -rf "$TOOL_DIR" "$DL"; mkdir -p "$DL"
      if run_phase install fixed "$INSTALL_TIMEOUT" "$LOGDIR/install.log"; then
        imode=fixed; notes="install only works under preload ($plain_cause)"; cause="$plain_cause"
      else
        t1=$(now); inst_s=$((t1-t0))
        cause="$(classify "$LOGDIR/install.plain.log")"
        notes="install failed: $(note_of "$LOGDIR/install.plain.log")"
        verdict=fails
      fi
    fi
    t1=$(now); inst_s=$((t1-t0))
    dl_kb=$(( dl_kb + $(kb_of "$DL") ))
    snapshot >"$LOGDIR/after.tsv"
    local apt_kb tool_kb
    apt_kb="$(comm -13 "$LOGDIR/before.names" <(cut -f1 "$LOGDIR/after.tsv") | while read -r p; do awk -F'\t' -v p="$p" '$1==p{print $3}' "$LOGDIR/after.tsv"; done | awk '{s+=$1} END{print s+0}')"
    tool_kb="$(kb_of "$TOOL_DIR")"
    inst_kb=$(( apt_kb + ${tool_kb:-0} ))
    local up
    up="$(LC_ALL=C join -t$'\t' -j1 "$LOGDIR/before.tsv" "$LOGDIR/after.tsv" | awk -F'\t' '$2!=$4' | wc -l)"
    [ "$up" -gt 0 ] && notes="$notes; upgraded $up existing pkg(s) (not reverted)"
  fi

  # Probe: plain, then with the Phase 0 helper.
  if [ -z "${verdict:-}" ]; then
    local plain_ok=0 fixed_ok=-1
    t0=$(now)
    run_phase probe plain "$PROBE_TIMEOUT" "$LOGDIR/probe.plain.log" && plain_ok=1
    t1=$(now); probe_s=$((t1-t0))
    if [ "$FIXED" = 1 ]; then
      fixed_ok=0
      rm -rf "$WORK"; mkdir -p "$WORK"
      run_phase probe fixed "$PROBE_TIMEOUT" "$LOGDIR/probe.fixed.log" && fixed_ok=1
    fi
    if   [ $plain_ok = 1 ]; then
      verdict=works
      [ $fixed_ok = 0 ] && notes="$notes; fails under preload: $(note_of "$LOGDIR/probe.fixed.log")"
    elif [ $fixed_ok = 1 ]; then
      verdict=works-with-fix; cause="$(classify "$LOGDIR/probe.plain.log")"
      notes="$notes; plain: $(note_of "$LOGDIR/probe.plain.log")"
    else
      local cp cf; cp="$(classify "$LOGDIR/probe.plain.log")"; cf="$(classify "$LOGDIR/probe.fixed.log" 2>/dev/null || echo '?')"
      verdict=fails; cause="$cf"; [ "$cf" = '?' ] && cause="$cp"
      [ "$FIXED" = 0 ] && cause="$cp"
      if [ "$FIXED" = 1 ]; then notes="$notes; $(note_of "$LOGDIR/probe.fixed.log")"; else notes="$notes; $(note_of "$LOGDIR/probe.plain.log")"; fi
      [ "$cp" = A ] && [ "$cause" != A ] && notes="also A under plain shell; $notes"
    fi
  fi

  cleanup_current
  [ -n "$CUR_LEFT" ] && notes="$notes; LEFTOVER: $CUR_LEFT"
  notes="$(printf '%s' "$notes" | sed 's/^[; ]*//; s/; ;/;/g')"
  [ "$imode" = fixed ] && [ "$verdict" = works ] && verdict=works-with-fix
  echo "   -> $verdict ${cause} install ${inst_s}s probe ${probe_s}s down $(human "$dl_kb") installed $(human "${inst_kb:-}")"
  append_row "$(date +%F)" "$tool" "$group" "$source" "$verdict" "$cause" "$inst_s" "$probe_s" "$dl_kb" "${inst_kb:--}" "$pre" "$notes"
  rm -rf "$LOGDIR"
}

# ------------------------------------------------------------------ table
render_table() {
  [ -s "$RESULTS" ] || { echo "no results"; return 1; }
  echo '| Tool | Group | Verdict | Cause | Source | Download | Installed | Install | Probe | Notes |'
  echo '|---|---|---|---|---|---|---|---|---|---|'
  awk -F'\t' '
    function h(k){ if(k==""||k=="-") return "-"; if(k>=1024) return sprintf("%.0f MB",k/1024); return sprintf("%d KB",k) }
    NR>1 { if(!($2 in seen)){ order[++n]=$2; seen[$2]=1 } row[$2]=$0 }
    END { for(i=1;i<=n;i++){ split(row[order[i]],f,"\t")
      note=f[12]; gsub(/\|/,"/",note)
      pre=(f[11]=="yes")?" (preinstalled)":""
      printf "| %s | %s | %s | %s | %s | %s | %s | %ss | %ss | %s%s |\n", f[2],f[3],f[5],f[6],f[4],h(f[9]),h(f[10]),f[7],f[8],note,pre } }' "$RESULTS"
}

# ------------------------------------------------------------------ main
FIXED=1
tools=()
for a in "$@"; do
  case "$a" in
    --no-fixed) FIXED=0 ;;
    list) for f in "$HERE"/tools/*.sh; do t="$(basename "$f" .sh)"; echo "$t|$(meta "$t")"; done | tr '|' '\t'; exit 0 ;;
    table) render_table; exit $? ;;
    *) tools+=("$a") ;;
  esac
done
if [ "${#tools[@]}" -eq 0 ]; then
  for f in "$HERE"/tools/*.sh; do tools+=("$(basename "$f" .sh)"); done
fi
mkdir -p "$(dirname "$RESULTS")" "$SWEEP_HOME/opt"
for t in "${tools[@]}"; do sweep_one "$t"; done
