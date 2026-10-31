GROUP="Cloud / deploy"; SOURCE="npm"; PKGS=""; BINS="firebase"
install() { npm_add firebase-tools; }
probe() {
  export XDG_CONFIG_HOME="$WORK/xdgc"
  npm_run firebase login:list --non-interactive 2>&1 | need -i 'no authorized accounts|logged in'
}
