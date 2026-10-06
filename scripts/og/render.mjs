#!/usr/bin/env node
// Renders the recly.dev link preview from og.html: docs/assets/og.png (English) and og.ko.png
// (Korean), 1200×630, and the GitHub social preview, 1280×640, into build/og/ for uploading in the
// repository's Settings → General → Social preview (GitHub has no API for it).
// Usage: node scripts/og/render.mjs [--all <dir>]. --all draws every variant in both languages
// into <dir> instead, to compare them. Needs Google Chrome and ffmpeg, like scripts/play-store.
import { spawn, execFileSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '../..');
const CHROME = process.env.CHROME ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const VARIANT = 'c';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

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

async function withChrome(fn) {
  const profile = mkdtempSync(join(tmpdir(), 'recly-og-chrome-'));
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
    const exited = new Promise((r) => chrome.once('exit', r));
    chrome.kill();
    await Promise.race([exited, sleep(5000)]);
    rmSync(profile, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
  }
}

// One page → one 24-bit PNG. A copy that spills past its margins stops the run.
async function shoot(page, query, width, height, out) {
  const url = `${pathToFileURL(join(HERE, 'og.html')).href}?${query}&w=${width}&h=${height}`;
  await page.send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false });
  await page.send('Page.navigate', { url });
  for (let i = 0; i < 200 && (await evaluate(page, 'document.readyState').catch(() => '')) !== 'complete'; i++) await sleep(50);
  await evaluate(page, 'document.fonts.ready.then(() => true)');
  await sleep(150);
  const m = JSON.parse((await evaluate(page, 'document.body?.dataset.m')) ?? '{"out":[]}');
  if (m.out?.length) throw new Error(`${query}: ${m.out.join('; ')}`);
  const shot = await page.send('Page.captureScreenshot', { format: 'png', clip: { x: 0, y: 0, width, height, scale: 1 } });
  mkdirSync(dirname(out), { recursive: true });
  const raw = `${out}.raw.png`;
  writeFileSync(raw, Buffer.from(shot.data, 'base64'));
  execFileSync('ffmpeg', ['-loglevel', 'error', '-y', '-i', raw, '-pix_fmt', 'rgb24', out]);
  rmSync(raw);
  console.log(`${query} ${width}×${height} → ${out}`);
}

const all = process.argv.indexOf('--all');
await withChrome(async (page) => {
  if (all !== -1) {
    const dir = resolve(process.argv[all + 1] ?? join(ROOT, 'build/og/all'));
    for (const variant of ['a', 'b', 'c']) {
      for (const lang of ['en', 'ko']) await shoot(page, `variant=${variant}&lang=${lang}`, 1200, 630, join(dir, `og-${variant}-${lang}.png`));
    }
    return;
  }
  await shoot(page, `variant=${VARIANT}&lang=en`, 1200, 630, join(ROOT, 'docs/assets/og.png'));
  await shoot(page, `variant=${VARIANT}&lang=ko`, 1200, 630, join(ROOT, 'docs/assets/og.ko.png'));
  await shoot(page, `variant=${VARIANT}&lang=en`, 1280, 640, join(ROOT, 'build/og/github-social-preview.png'));
});
