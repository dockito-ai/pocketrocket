GROUP="JS tooling"; SOURCE="npm"; PKGS=""; BINS="prettier"
install() { npm_add prettier; }
probe() {
  printf 'const   a={b:1,\n c:2}\n' >a.js
  npm_run prettier a.js | need 'const a = \{ b: 1, c: 2 \};'
}
