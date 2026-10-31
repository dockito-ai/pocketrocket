GROUP="JS tooling"; SOURCE="termux pkg"; PKGS="yarn"; BINS="yarn"
install() { apt_add yarn; }
probe() {
  printf '{"name":"t","version":"1.0.0"}\n' >package.json
  yarn --cache-folder "$WORK/yc" add is-odd
  node -e 'console.log(require("is-odd")(3))' | need true
}
