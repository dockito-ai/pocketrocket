GROUP="JS tooling"; SOURCE="npm"; PKGS=""; BINS="playwright"
# Browser binaries are not downloaded for Android; the probe drives Termux's chromium if present.
install() { npm_add playwright-core; }
probe() {
  local chrome; chrome="$(command -v chromium || command -v chromium-browser)" || { echo "no chromium on the phone"; return 1; }
  cat >a.js <<JS
const { chromium } = require("$TOOL_DIR/node_modules/playwright-core");
(async () => {
  const b = await chromium.launch({ executablePath: "$chrome", args: ["--no-sandbox"] });
  const p = await b.newPage();
  await p.setContent("<h1>hi</h1>");
  console.log("title:", await p.textContent("h1"));
  await b.close();
})().catch(e => { console.error(e.message); process.exit(1); });
JS
  node a.js | need 'title: hi'
}
