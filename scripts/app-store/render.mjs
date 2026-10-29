#!/usr/bin/env node
// Renders the README's and recly.dev's iPhone promo images (README.md) from promo.html and the captures in
// build/app-store-assets/captures/<lang>/, into docs/design/screenshots/<lang>/0N-*.png and .webp.
// Usage: node scripts/app-store/render.mjs. Needs Google Chrome, ffmpeg and cwebp.
// The page is drawn at twice its size and scaled down, so the phone's 1206 px screen loses detail once.
// It reports what spills in document.body.dataset.m; a spill stops the run.
import { spawn, execFileSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '../..');
const CHROME = process.env.CHROME ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const W = 540, H = 1173, SCALE = 2;
const NAMES = { 1: '01-record-for-your-ai', 2: '02-transcribe', 3: '03-notes', 4: '04-list' };

function cdp(url) {
  const ws = new WebSocket(url);
  let next = 0;
  const pending = new Map();
  ws.onmessage = (e) => {
    const msg = JSON.parse(e.data);
    if (msg.id && pending.has(msg.id)) {
      const { ok, fail } = pending.get(msg.id);
      pending.delete(msg.id);
      msg.error ? fail(new Error(msg.error.message)) : ok(msg.result);
    }
  };
  const opened = new Promise((r) => { ws.onopen = r; });
  const send = async (method, params = {}) => {
    await opened;
    return new Promise((ok, fail) => {
      const id = ++next;
      pending.set(id, { ok, fail });
      ws.send(JSON.stringify({ id, method, params }));
    });
  };
  return { send };
}

async function evaluate(page, expression) {
  const r = await page.send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description ?? 'evaluate failed');
  return r.result.value;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const profile = mkdtempSync(join(tmpdir(), 'recly-promo-chrome-'));
const port = 9300 + Math.floor(Math.random() * 500);
const chrome = spawn(CHROME, ['--headless=new', '--no-first-run', '--no-default-browser-check', '--hide-scrollbars',
  '--allow-file-access-from-files', `--user-data-dir=${profile}`, `--remote-debugging-port=${port}`,
  '--remote-allow-origins=*', 'about:blank'], { stdio: 'ignore' });
try {
  let target;
  for (let i = 0; i < 100 && !target; i++) {
    await sleep(100);
    try { target = (await (await fetch(`http://127.0.0.1:${port}/json/list`)).json()).find((t) => t.type === 'page'); } catch { /* starting */ }
  }
  if (!target) throw new Error('Chrome did not start');
  const page = cdp(target.webSocketDebuggerUrl);
  await page.send('Page.enable');
  await page.send('Emulation.setDeviceMetricsOverride', { width: W, height: H, deviceScaleFactor: SCALE, mobile: false });
  // Transparent outside the card's rounded corners, like the images it replaces.
  await page.send('Emulation.setDefaultBackgroundColorOverride', { color: { r: 0, g: 0, b: 0, a: 0 } });
  for (const lang of ['en', 'ko']) {
    for (const [n, name] of Object.entries(NAMES)) {
      const url = `${pathToFileURL(join(HERE, 'promo.html')).href}?lang=${lang}&shot=${n}`;
      await page.send('Page.navigate', { url });
      for (let i = 0; i < 200 && (await evaluate(page, 'document.readyState').catch(() => '')) !== 'complete'; i++) await sleep(50);
      await evaluate(page, 'document.fonts.ready.then(() => true)');
      await evaluate(page, 'Promise.all([...document.images].map(i => i.decode().catch(() => null))).then(() => true)');
      await sleep(150);
      const m = JSON.parse((await evaluate(page, 'document.body?.dataset.m')) ?? '{"out":[]}');
      if (m.out?.length) throw new Error(`${lang} ${name}: ${m.out.join('; ')}`);
      const shot = await page.send('Page.captureScreenshot', { format: 'png', clip: { x: 0, y: 0, width: W, height: H, scale: 1 } });
      const dir = join(ROOT, 'docs/design/screenshots', lang);
      mkdirSync(dir, { recursive: true });
      const raw = join(dir, `${name}.raw.png`), png = join(dir, `${name}.png`);
      writeFileSync(raw, Buffer.from(shot.data, 'base64'));
      execFileSync('ffmpeg', ['-loglevel', 'error', '-y', '-i', raw, '-vf', `scale=${W}:${H}:flags=lanczos`, '-pix_fmt', 'rgba', png]);
      rmSync(raw);
      execFileSync('cwebp', ['-quiet', '-q', '82', '-m', '6', png, '-o', join(dir, `${name}.webp`)]);
      console.log(`${lang} ${name}: head ends at ${m.headBottom}px`);
    }
  }
} finally {
  chrome.kill();
  rmSync(profile, { recursive: true, force: true, maxRetries: 10, retryDelay: 200 });
}
