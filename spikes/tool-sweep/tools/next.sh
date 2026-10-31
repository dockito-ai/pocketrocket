GROUP="JS tooling"; SOURCE="npm"; PKGS=""; BINS="next"
install() { npm_add next react react-dom; }
probe() {
  export NEXT_TELEMETRY_DISABLED=1
  mkdir -p app
  printf 'export default function L({children}){return <html><body>{children}</body></html>}\n' >app/layout.js
  printf 'export default function P(){return <h1>hi</h1>}\n' >app/page.js
  printf '{"name":"t","version":"1.0.0"}\n' >package.json
  ln -s "$TOOL_DIR/node_modules" node_modules
  npm_run next build
  ls .next >/dev/null
}
