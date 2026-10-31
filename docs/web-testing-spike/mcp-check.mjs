// Talks to chrome-devtools-mcp over stdio the way Claude Code does, and runs the spike checks through its tools.
// Usage: node mcp-check.mjs <pageUrl> <outDir> -- <chrome-devtools-mcp args...>
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';

const sep = process.argv.indexOf('--');
const [pageUrl, outDir] = process.argv.slice(2, sep);
const mcp = spawn('chrome-devtools-mcp', ['--allow-unrestricted-paths', '--no-usage-statistics', ...process.argv.slice(sep + 1)], { stdio: ['pipe', 'pipe', 'inherit'] });
const waiting = new Map();
let id = 0;
createInterface({ input: mcp.stdout }).on('line', (line) => {
  const m = JSON.parse(line);
  if (m.id && waiting.has(m.id)) { waiting.get(m.id)(m); waiting.delete(m.id); }
});
const rpc = (method, params) => new Promise((ok, fail) => {
  const i = ++id;
  const timer = setTimeout(() => fail(new Error(`${method} timed out`)), 60000);
  waiting.set(i, (m) => { clearTimeout(timer); m.error ? fail(new Error(JSON.stringify(m.error))) : ok(m.result); });
  mcp.stdin.write(JSON.stringify({ jsonrpc: '2.0', id: i, method, params }) + '\n');
});
const text = (r) => r.content.filter((c) => c.type === 'text').map((c) => c.text).join('\n');
const call = async (name, args = {}) => {
  const t = performance.now();
  const r = await rpc('tools/call', { name, arguments: name === 'list_pages' ? args : { pageId: 1, ...args } });
  const out = text(r);
  console.log(`[${name}] ${Math.round(performance.now() - t)} ms${r.isError ? ' ERROR' : ''}: ${out.slice(0, 300).replace(/\n/g, ' | ')}`);
  return out;
};

let t = performance.now();
await rpc('initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'spike', version: '0' } });
mcp.stdin.write(JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) + '\n');
const tools = (await rpc('tools/list', {})).tools.map((x) => x.name);
console.log(`initialize+list ${Math.round(performance.now() - t)} ms, ${tools.length} tools`);

await call('list_pages');
await call('navigate_page', { type: 'url', url: pageUrl });
for (const [w, h] of [[390, 844], [1280, 800]]) {
  // emulate, not resize_page: resize_page needs Browser.getWindowForTarget, which WebView lacks.
  await call('emulate', { viewport: `${w}x${h}x1${w < 800 ? ',mobile' : ''}` });
  await call('take_screenshot', { filePath: `${outDir}/mcp-${w}.png` });
}
await call('list_console_messages');
const snap = await call('take_snapshot');
const uid = snap.match(/uid=(\S+) button "Press me"/)?.[1];
await call('click', { uid });
await call('evaluate_script', { function: "() => document.getElementById('result').textContent" });
mcp.kill();
process.exit(0);
