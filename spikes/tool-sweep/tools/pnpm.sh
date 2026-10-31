GROUP="JS tooling"; SOURCE="termux pkg"; PKGS="pnpm"; BINS="pnpm"
install() { apt_add pnpm; }
probe() {
  export PNPM_HOME="$WORK/pnpm" npm_config_store_dir="$WORK/store"
  printf '{"name":"t","version":"1.0.0"}\n' >package.json
  pnpm add is-odd
  node -e 'console.log(require("is-odd")(3))' | need true
}
