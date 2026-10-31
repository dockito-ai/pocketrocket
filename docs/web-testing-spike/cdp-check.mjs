// Drives a CDP endpoint through the spike checks: navigate, screenshots at 390 and 1280 px, the console error,
// a button click. Usage: node cdp-check.mjs http://127.0.0.1:9222 http://127.0.0.1:5173/ outDir
import { writeFileSync } from 'node:fs';

const [browserUrl, pageUrl, outDir = '.'] = process.argv.slice(2);
const now = () => performance.now();
const ms = (t) => `${Math.round(now() - t)} ms`;

const pages = await (await fetch(`${browserUrl}/json/list`)).json();
const target = pages.find((p) => p.type === 'page');
if (!target) throw new Error('no page target: ' + JSON.stringify(pages));
const ws = new WebSocket(target.webSocketDebuggerUrl.replace(/ws:\/\/[^/]+/, browserUrl.replace('http', 'ws')));
await new Promise((ok, fail) => { ws.onopen = ok; ws.onerror = fail; });

let id = 0;
const waiting = new Map();
const events = [];
ws.onmessage = ({ data }) => {
  const m = JSON.parse(data);
  if (m.id && waiting.has(m.id)) { waiting.get(m.id)(m); waiting.delete(m.id); } else events.push(m);
};
const send = (method, params = {}, timeout = 20000) => new Promise((ok, fail) => {
  const i = ++id;
  const timer = setTimeout(() => fail(new Error(`${method} timed out`)), timeout);
  waiting.set(i, (m) => { clearTimeout(timer); m.error ? fail(new Error(`${method}: ${m.error.message}`)) : ok(m.result); });
  ws.send(JSON.stringify({ id: i, method, params }));
});
const until = async (pred, timeout = 10000) => {
  const t = now();
  while (now() - t < timeout) { const e = events.find(pred); if (e) return e; await new Promise((r) => setTimeout(r, 20)); }
  throw new Error('event timed out');
};
const evaluate = async (expression) => (await send('Runtime.evaluate', { expression, returnByValue: true })).result.value;

await send('Page.enable');
await send('Runtime.enable');
await send('Log.enable');

let t = now();
await send('Page.navigate', { url: pageUrl });
await until((e) => e.method === 'Page.loadEventFired');
while (!(await evaluate(`location.href === ${JSON.stringify(pageUrl)} && document.readyState === 'complete'`))) {
  await new Promise((r) => setTimeout(r, 20));
}
console.log('navigate+load', ms(t));

for (const width of [390, 1280]) {
  await send('Emulation.setDeviceMetricsOverride', { width, height: width === 390 ? 844 : 800, deviceScaleFactor: 1, mobile: width === 390 });
  await new Promise((r) => setTimeout(r, 300));
  t = now();
  const shot = await send('Page.captureScreenshot', { format: 'png' });
  const png = Buffer.from(shot.data, 'base64');
  writeFileSync(`${outDir}/shot-${width}.png`, png);
  const cols = await evaluate("getComputedStyle(document.querySelector('.grid')).gridTemplateColumns");
  console.log(`screenshot ${width}px`, ms(t), `${png.readUInt32BE(16)}x${png.readUInt32BE(20)}`, `innerWidth=${await evaluate('innerWidth')}`, `grid=${cols}`);
}

const err = events.find((e) => e.method === 'Runtime.consoleAPICalled' && e.params.type === 'error');
console.log('console error:', err ? err.params.args.map((a) => a.value).join(' ') : 'NOT SEEN');

const box = await evaluate("(() => { const r = document.getElementById('go').getBoundingClientRect(); return [r.x + r.width / 2, r.y + r.height / 2]; })()");
t = now();
for (const type of ['mousePressed', 'mouseReleased']) {
  await send('Input.dispatchMouseEvent', { type, x: box[0], y: box[1], button: 'left', clickCount: 1 });
}
console.log('click result:', await evaluate("document.getElementById('result').textContent"), ms(t));
// WebView has answered the checks and then left this call hanging. Don't fail the run for it.
await send('Emulation.clearDeviceMetricsOverride', {}, 3000).catch((e) => console.log('clear viewport:', e.message));
ws.close();
