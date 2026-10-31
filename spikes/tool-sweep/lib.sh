# Helpers shared by the recipes in tools/. Sourced by sweep.sh, never run directly.
#
# Recipe contract (tools/<name>.sh), sourced in a fresh shell per phase:
#   GROUP   Languages | JS tooling | Databases | Cloud / deploy | Utilities | Known gaps
#   SOURCE  "termux pkg" | "official linux-arm64 (glibc)" | "npm" | "pip"
#   PKGS    Termux packages the recipe installs (empty if none). Used to decide
#           "already installed" and to size the download.
#   BINS    commands the tool provides. Used for "already installed" when PKGS is empty.
#   install()  puts the tool on the phone; non-zero exit = install failed
#   probe()    does one small real task in $PWD (a fresh work dir); non-zero = failed
#   remove()   optional extra cleanup. The sweep itself removes the apt packages it
#              added (dpkg diff), $TOOL_DIR, $DL and $WORK, so most recipes omit it.
#
# Environment provided by the sweep:
#   TOOL      recipe name
#   TOOL_DIR  private install dir for non-apt installs ($SWEEP_HOME/opt/$TOOL); bin/ is on PATH
#   DL        fresh download dir under $TMPDIR (removed afterwards; sized as "download")
#   WORK      fresh work dir for probes (also the cwd of probe)

GLIBC_LD="${GLIBC_LD:-$PREFIX/glibc/lib/ld-linux-aarch64.so.1}"
PATCHELF="${PATCHELF:-$PREFIX/glibc/bin/patchelf}"

tool_env() {
  mkdir -p "$TOOL_DIR/bin"
  export PATH="$TOOL_DIR/bin:$TOOL_DIR/node_modules/.bin:$PATH"
}

say() { printf '[%s] %s\n' "${TOOL:-sweep}" "$*" >&2; }

# Termux package(s) via apt. No sudo, Termux owns $PREFIX.
apt_add() {
  DEBIAN_FRONTEND=noninteractive apt-get install -y \
    -o Dpkg::Options::=--force-confold -o Dpkg::Options::=--force-confdef "$@"
}

# fetch URL DEST : official https downloads only; DEST lives in $DL.
fetch() { curl -fL --retry 3 --connect-timeout 20 -sS -o "$2" "$1"; }

# sha256_check FILE EXPECTED
sha256_check() {
  local got; got="$(sha256sum "$1" | cut -d' ' -f1)"
  [ "$got" = "$2" ] || { say "sha256 mismatch for $1: $got"; return 1; }
}

# glibc_bin FILE [NAME] : make an official linux-arm64 ELF runnable and put it on PATH.
# Static binaries (Go) have no interpreter and are left alone.
glibc_bin() {
  local f="$1" name="${2:-$(basename "$1")}"
  chmod +x "$f"
  if LD_PRELOAD= "$PATCHELF" --print-interpreter "$f" >/dev/null 2>&1; then
    LD_PRELOAD= "$PATCHELF" --set-interpreter "$GLIBC_LD" "$f"
  fi
  mkdir -p "$TOOL_DIR/bin"
  ln -sf "$f" "$TOOL_DIR/bin/$name"
}

# npm_add PKG... : install into $TOOL_DIR only; the npm cache goes to $DL so it counts as download.
npm_add() {
  mkdir -p "$TOOL_DIR"
  npm install --prefix "$TOOL_DIR" --no-audit --no-fund --cache "$DL/npm-cache" "$@"
}

# npm_run BIN ARGS... : run a locally installed bin the way `npx` does.
npm_run() { npm exec --prefix "$TOOL_DIR" -- "$@"; }

# pip into a private venv at $TOOL_DIR/venv (needs python).
pip_add() {
  [ -d "$TOOL_DIR/venv" ] || python -m venv "$TOOL_DIR/venv"
  "$TOOL_DIR/venv/bin/pip" install --cache-dir "$DL/pip" "$@"
}

# expect_out CMD... : run, require exit 0 and print output; used with grep in probes.
need() { # need [-i] PATTERN <<< output (reads all input, so no SIGPIPE under pipefail)
  local f=-E; [ "$1" = -i ] && { f=-Ei; shift; }
  grep $f -- "$1" >/dev/null || { say "expected output matching: $1"; return 1; }
}

# bg_cleanup PID... : kill background servers started by a probe.
bg_cleanup() { kill "$@" 2>/dev/null; wait "$@" 2>/dev/null; true; }
