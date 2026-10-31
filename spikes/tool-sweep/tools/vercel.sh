GROUP="Cloud / deploy"; SOURCE="npm"; PKGS=""; BINS="vercel"
install() { npm_add vercel; }
probe() {
  # No account on a test run: reaching the API and getting a token error proves the CLI runs and has network.
  export XDG_DATA_HOME="$WORK/xdg" XDG_CONFIG_HOME="$WORK/xdgc"
  npm_run vercel whoami --token invalid-token 2>&1 | need -i 'token|credentials|not valid|invalid'
}
