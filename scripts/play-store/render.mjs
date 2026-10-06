#!/usr/bin/env node
// Renders the Google Play graphics (README.md) from screenshot.html, feature.html, docs/design/icon.svg
// and the app captures in build/play-store-assets/captures/<lang>/, into build/play-store-assets/out/,
// and the top-level README's Clients image from clients.html into docs/design/screenshots/<lang>/.
// Usage: node scripts/play-store/render.mjs. Needs Google Chrome and ffmpeg.
// Every page reports what spills in document.body.dataset.m; a spill stops the run.
import { spawn, execFileSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, readdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '../..');
const WORK = join(ROOT, 'build/play-store-assets');
const OUT = join(WORK, 'out');
const CHROME = process.env.CHROME ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';

const PHONE = { 1: 'watch', 2: 'transcript', 3: 'drive', 4: 'record', 5: 'list' };
const WEAR = [['wear-rec', 'recording'], ['wear-idle', 'ready'], ['wear-tile', 'tile']];

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
  return { send, close: () => ws.close() };
}

async function evaluate(page, expression) {
  const r = await page.send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description ?? 'evaluate failed');
  return r.result.value;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function withChrome(fn) {
  const profile = mkdtempSync(join(tmpdir(), 'recly-play-chrome-'));
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
    return await fn(page);
  } finally {
    chrome.kill();
    rmSync(profile, { recursive: true, force: true });
  }
}

// One page → one PNG. `alpha` keeps the alpha channel over a transparent page background (the icon, which Play
// asks for as 32-bit PNG, and the README image's rounded corners); everything else is flattened to 24-bit RGB,
// which Play requires for screenshots and the feature graphic.
async function shoot(page, url, width, height, out, alpha = false) {
  await page.send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false });
  await page.send('Emulation.setDefaultBackgroundColorOverride', alpha ? { color: { r: 0, g: 0, b: 0, a: 0 } } : {});
  await page.send('Page.navigate', { url });
  for (let i = 0; i < 200 && (await evaluate(page, 'document.readyState').catch(() => '')) !== 'complete'; i++) await sleep(50);
  await evaluate(page, 'document.fonts.ready.then(() => true)');
  await evaluate(page, 'Promise.all([...document.images].map(i => i.decode().catch(() => null))).then(() => true)');
  await sleep(150);
  const m = JSON.parse((await evaluate(page, 'document.body?.dataset.m')) ?? '{"out":[]}');
  if (m.out?.length) throw new Error(`${url}: ${m.out.join('; ')}`);
  const shot = await page.send('Page.captureScreenshot', { format: 'png', clip: { x: 0, y: 0, width, height, scale: 1 } });
  mkdirSync(dirname(out), { recursive: true });
  const raw = `${out}.raw.png`;
  writeFileSync(raw, Buffer.from(shot.data, 'base64'));
  execFileSync('ffmpeg', ['-loglevel', 'error', '-y', '-i', raw, '-pix_fmt', alpha ? 'rgba' : 'rgb24', out]);
  rmSync(raw);
  return m;
}

const pageUrl = (file, query) => `${pathToFileURL(join(HERE, file)).href}?${query}`;
rmSync(OUT, { recursive: true, force: true });
await withChrome(async (page) => {
  for (const lang of ['en', 'ko']) {
    for (const [n, name] of Object.entries(PHONE)) {
      const file = join(OUT, lang, 'phone', `0${n}-${name}.png`);
      const m = await shoot(page, pageUrl('screenshot.html', `lang=${lang}&shot=${n}`), 1440, 2560, file);
      console.log(`${lang} phone ${n} ${name}: head ends at ${m.headBottom}px`);
    }
    for (const v of ['a', 'b']) {
      await shoot(page, pageUrl('feature.html', `lang=${lang}&variant=${v}`), 1024, 500, join(OUT, lang, `feature-graphic-${v}.png`));
      console.log(`${lang} feature graphic ${v}`);
    }
  }
  // The SVG has a viewBox and no size, so it fills the 512 px viewport.
  await shoot(page, pathToFileURL(join(ROOT, 'docs/design/icon.svg')).href, 512, 512, join(OUT, 'icon-512.png'), true);
  console.log('icon 512');
  // Not a Play graphic: the README's Clients row, from the same captures.
  for (const lang of ['en', 'ko']) {
    await shoot(page, pageUrl('clients.html', `lang=${lang}`), 1600, 840, join(ROOT, 'docs/design/screenshots', lang, 'clients.png'), true);
    console.log(`${lang} README clients → docs/design/screenshots/${lang}/clients.png`);
  }
});

// Wear OS: the app's own screens exactly as captured (1:1, 454 px), only flattened to 24-bit.
for (const lang of ['en', 'ko']) {
  mkdirSync(join(OUT, lang, 'wear'), { recursive: true });
  WEAR.forEach(([capture, name], i) => {
    execFileSync('ffmpeg', ['-loglevel', 'error', '-y', '-i', join(WORK, 'captures', lang, `${capture}.png`), '-pix_fmt', 'rgb24',
      join(OUT, lang, 'wear', `0${i + 1}-${name}.png`)]);
  });
  console.log(`${lang} wear: ${readdirSync(join(OUT, lang, 'wear')).join(', ')}`);
}
