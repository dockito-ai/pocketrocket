GROUP="JS tooling"; SOURCE="npm"; PKGS=""; BINS="vite"
install() { npm_add vite; }
probe() {
  mkdir -p proj && printf '<!doctype html><script type="module" src="/m.js"></script>\n' >proj/index.html
  printf 'console.log("vite ok");\n' >proj/m.js
  npm_run vite build proj --outDir "$WORK/dist" --emptyOutDir
  ls dist/assets/*.js >/dev/null
}
