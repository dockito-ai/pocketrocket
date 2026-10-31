GROUP="JS tooling"; SOURCE="npm"; PKGS=""; BINS="eslint"
install() { npm_add eslint; }
probe() {
  printf 'export default [{ rules: { semi: "error" } }];\n' >eslint.config.mjs
  printf 'const a = 1;\nconsole.log(a);\n' >ok.js
  printf 'const a = 1\nconsole.log(a)\n' >bad.js
  npm_run eslint ok.js
  if npm_run eslint bad.js >/dev/null 2>&1; then echo "eslint missed an error"; return 1; fi
}
