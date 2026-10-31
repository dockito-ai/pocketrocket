GROUP="Cloud / deploy"; SOURCE="npm"; PKGS=""; BINS="netlify"
install() { npm_add netlify-cli; }
probe() {
  export XDG_CONFIG_HOME="$WORK/xdgc"
  npm_run netlify status 2>&1 | need -i 'not logged in|logged in|netlify'
}
