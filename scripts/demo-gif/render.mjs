#!/usr/bin/env node
// Renders the README demo (docs/design/demo.gif, demo.ko.gif and their .mp4) from demo.html.
// Usage: node scripts/demo-gif/render.mjs [en|ko ...]
// Needs Google Chrome and ffmpeg. App strings come from the Android resources, so the demo
// follows the app's wording; only the demo copy below (headlines, sample rows, transcript) lives here.
import { spawn, execFileSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '../..');
const OUT = join(ROOT, 'docs/design');
const CHROME = process.env.CHROME ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const FPS = 15;

const WEAR_KEYS = ['recording_idle', 'recording_active', 'recording_stopping', 'sending_badge', 'info_open'];
const APP_KEYS = ['jobs_title', 'jobs_summary', 'jobs_column_time', 'jobs_column_title', 'jobs_column_length',
  'jobs_column_status', 'job_state_receiving', 'job_state_running', 'job_state_done', 'jobs_untitled',
  'jobs_open_drive', 'detail_open', 'detail_rename', 'action_close'];

const BASE = '20261004T004102Z_watch_01K6X2QF';
const COPY = {
  en: {
    steps: [
      ['Double-press the home key', 'Galaxy Watch, set to Recly Record'],
      ['Recording starts at once', 'No app to open first'],
      ['The watch hands it to your phone', 'No Recly server in between'],
      ['Your phone uploads and transcribes', 'To your own Google Drive, on device or with your own key'],
      ['The transcript is in your Drive', ''],
    ],
    myDrive: 'My Drive', faceDate: 'Sun 4', newDate: 'Oct 4', skipSeconds: '+39 s', skipLater: 'later',
    oldRows: [
      { date: 'Oct 3', time: '18:20', title: 'Design review', len: '09:12' },
      { date: 'Oct 3', time: '10:05', title: 'Customer interview', len: '58:40' },
      { date: 'Oct 2', time: '21:47', title: 'Book club', len: '14:05' },
    ],
    transcript: [
      'Quick idea for onboarding. Show where the recordings go before asking for Drive access, so nobody thinks they need a new account.',
      'And move the help link under the record button. Check it with the team on Thursday.',
    ],
  },
  ko: {
    steps: [
      ['홈키를 두 번 누르면', '갤럭시워치에 Recly Record를 지정해 두면'],
      ['바로 녹음이 시작되고', '앱을 먼저 열 필요가 없습니다'],
      ['워치가 폰으로 넘기면', '중간에 Recly 서버는 없습니다'],
      ['폰이 올리고 전사합니다', '내 구글 드라이브로, 온디바이스 또는 내 API 키로'],
      ['전사본은 내 Drive에', ''],
    ],
    myDrive: '내 드라이브', faceDate: '4일 일요일', newDate: '10월 4일', skipSeconds: '+39초', skipLater: '잠시 후',
    oldRows: [
      { date: '10월 3일', time: '18:20', title: '디자인 리뷰', len: '09:12' },
      { date: '10월 3일', time: '10:05', title: '고객 인터뷰', len: '58:40' },
      { date: '10월 2일', time: '21:47', title: '독서 모임', len: '14:05' },
    ],
    transcript: [
      '온보딩 아이디어 하나. Drive 권한을 묻기 전에 녹음이 어디로 가는지 먼저 보여 주자. 새 계정이 필요하다고 오해하지 않게.',
      '그리고 도움말 링크는 녹음 버튼 아래로 옮기기. 목요일에 팀이랑 같이 확인.',
    ],
  },
};

function androidStrings(file, keys) {
  const xml = readFileSync(file, 'utf8');
  const out = {};
  for (const key of keys) {
    const m = xml.match(new RegExp(`<string name="${key}">([\\s\\S]*?)</string>`));
    if (!m) throw new Error(`${key} missing in ${file}`);
    out[key] = m[1].replace(/\\(['"])/g, '$1').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');
  }
  return out;
}

function stringsFor(lang) {
  const dir = lang === 'en' ? 'values' : `values-${lang}`;
  return {
    wear: androidStrings(join(ROOT, `android/wear/src/main/res/${dir}/strings.xml`), WEAR_KEYS),
    app: androidStrings(join(ROOT, `android/app/src/main/res/${dir}/strings.xml`), APP_KEYS),
    copy: { ...COPY[lang], folder: 'recly/2026/2026-10', base: BASE, recordingId: '01K6X2QF8N3DW5ZJ7T4M2CQH9R' },
  };
}

async function cdp(wsUrl) {
  const ws = new WebSocket(wsUrl);
  await new Promise((ok, fail) => { ws.onopen = ok; ws.onerror = fail; });
  let seq = 0;
  const pending = new Map();
  ws.onmessage = (e) => {
    const msg = JSON.parse(e.data);
    if (msg.id && pending.has(msg.id)) {
      const { ok, fail } = pending.get(msg.id);
      pending.delete(msg.id);
      msg.error ? fail(new Error(msg.error.message)) : ok(msg.result);
    }
  };
  const send = (method, params = {}) => new Promise((ok, fail) => {
    const id = ++seq;
    pending.set(id, { ok, fail });
    ws.send(JSON.stringify({ id, method, params }));
  });
  return { send, close: () => ws.close() };
}

async function evaluate(page, expression) {
  const r = await page.send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description ?? 'evaluate failed');
  return r.result.value;
}

async function renderFrames(htmlFile, framesDir) {
  const profile = mkdtempSync(join(tmpdir(), 'recly-demo-chrome-'));
  const port = 9300 + Math.floor(Math.random() * 500);
  const chrome = spawn(CHROME, ['--headless=new', '--no-first-run', '--no-default-browser-check', '--hide-scrollbars',
    `--user-data-dir=${profile}`, `--remote-debugging-port=${port}`, '--remote-allow-origins=*',
    '--window-size=960,540', pathToFileURL(htmlFile).href], { stdio: 'ignore' });
  try {
    let target;
    for (let i = 0; i < 100 && !target; i++) {
      await new Promise((r) => setTimeout(r, 100));
      try {
        const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
        target = list.find((t) => t.type === 'page');
      } catch { /* Chrome is still starting */ }
    }
    if (!target) throw new Error('Chrome did not start');
    const page = await cdp(target.webSocketDebuggerUrl);
    await page.send('Emulation.setDeviceMetricsOverride', { width: 960, height: 540, deviceScaleFactor: 2, mobile: false });
    for (let i = 0; i < 100 && (await evaluate(page, 'document.readyState')) !== 'complete'; i++) {
      await new Promise((r) => setTimeout(r, 50));
    }
    await evaluate(page, 'document.fonts.ready.then(() => true)');
    const end = await evaluate(page, 'window.END');
    const count = Math.round(end * FPS);
    for (let i = 0; i < count; i++) {
      await evaluate(page, `render(${i / FPS})`);
      const shot = await page.send('Page.captureScreenshot', { format: 'png' });
      writeFileSync(join(framesDir, `f${String(i).padStart(4, '0')}.png`), Buffer.from(shot.data, 'base64'));
    }
    page.close();
    return count;
  } finally {
    chrome.kill();
    rmSync(profile, { recursive: true, force: true });
  }
}

function encode(framesDir, name) {
  const input = ['-y', '-loglevel', 'error', '-framerate', String(FPS), '-i', join(framesDir, 'f%04d.png')];
  execFileSync('ffmpeg', [...input, '-vf',
    'scale=960:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=128:stats_mode=diff[p];' +
    '[b][p]paletteuse=dither=bayer:bayer_scale=3:diff_mode=rectangle', '-loop', '0', join(OUT, `${name}.gif`)]);
  execFileSync('ffmpeg', [...input, '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', '18', '-movflags', '+faststart',
    join(OUT, `${name}.mp4`)]);
}

const langs = process.argv.slice(2).length ? process.argv.slice(2) : ['en', 'ko'];
const template = readFileSync(join(HERE, 'demo.html'), 'utf8');
for (const lang of langs) {
  const work = mkdtempSync(join(tmpdir(), `recly-demo-${lang}-`));
  const framesDir = join(work, 'frames');
  mkdirSync(framesDir);
  const json = JSON.stringify(stringsFor(lang)).replace(/</g, '\\u003c');
  const htmlFile = join(work, 'demo.html');
  writeFileSync(htmlFile, template.replace('/*STRINGS*/', json));
  const frames = await renderFrames(htmlFile, framesDir);
  const name = lang === 'en' ? 'demo' : `demo.${lang}`;
  encode(framesDir, name);
  console.log(`${lang}: ${frames} frames → docs/design/${name}.gif, ${name}.mp4 (frames kept in ${framesDir})`);
}
