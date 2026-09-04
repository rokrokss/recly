// run: node --test scripts/webhook-receiver.test.mjs
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { secretBytes, sign, start } from "./webhook-receiver.mjs";

const root = dirname(fileURLToPath(import.meta.url));
const example = () => JSON.parse(readFileSync(join(root, "../spec/examples/webhook.payload.json"), "utf8"));
const SECRET = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";
const NOW = 1_774_490_700;

/** The app's side (core `WebhookRunner`): the same headers the receiver verifies. */
function post(url, body, { id = "01J9STEPR0N0123456789ABCDE", timestamp = NOW, secret = SECRET } = {}) {
  const raw = Buffer.from(body, "utf8");
  return fetch(url, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "webhook-id": id,
      "webhook-timestamp": String(timestamp),
      "webhook-signature": sign(secretBytes(secret), id, timestamp, raw),
    },
    body: raw,
  });
}

async function withReceiver(options, run) {
  const receiver = await start({ secret: SECRET, now: () => NOW, ...options });
  try {
    await run(receiver);
  } finally {
    await receiver.close();
  }
}

test("signs the same as core's SignerTest vector", () => {
  assert.equal(
    sign(secretBytes(SECRET), "msg_p5jXN8AQM9LWM0D4loKWxJek", 1614265330, Buffer.from('{"test": 2432232314}')),
    "v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=",
  );
});

test("a signed request is 200", async () => {
  await withReceiver({}, async (receiver) => {
    const res = await post(receiver.url, JSON.stringify(example()));
    assert.equal(res.status, 200);
    assert.deepEqual(await res.json(), { ok: true });
    assert.match(await (await fetch(`http://127.0.0.1:${receiver.port}/`)).text(), /deliveries=1 unique=1/);
  });
});

test("a changed body is 401", async () => {
  await withReceiver({}, async (receiver) => {
    const raw = JSON.stringify(example());
    const id = "01J9STEPR0N0123456789ABCDE";
    const res = await fetch(receiver.url, {
      method: "POST",
      headers: {
        "webhook-id": id,
        "webhook-timestamp": String(NOW),
        "webhook-signature": sign(secretBytes(SECRET), id, NOW, Buffer.from(raw)),
      },
      body: raw.replace("주간 회의", "다른 회의"),
    });
    assert.equal(res.status, 401);
  });
});

test("an old timestamp is 401", async () => {
  await withReceiver({}, async (receiver) => {
    const res = await post(receiver.url, JSON.stringify(example()), { timestamp: NOW - 301 });
    assert.equal(res.status, 401);
    assert.match((await res.json()).error, /outside/);
  });
});

test("a correctly signed body that does not match the schema is 400", async () => {
  await withReceiver({}, async (receiver) => {
    const bad = example();
    delete bad.data.recording.tracks;
    const res = await post(receiver.url, JSON.stringify(bad));
    assert.equal(res.status, 400);
    assert.match((await res.json()).error, /tracks/);
  });
});

test("anything but JSON is 400", async () => {
  await withReceiver({}, async (receiver) => {
    const res = await post(receiver.url, "{not json");
    assert.equal(res.status, 400);
    assert.match((await res.json()).error, /bad json/);
  });
});

test("--fail-first 1 answers the same webhook-id 500 and then 200", async () => {
  await withReceiver({ failFirst: 1 }, async (receiver) => {
    const body = JSON.stringify(example());
    assert.equal((await post(receiver.url, body)).status, 500);
    // A retry is the same webhook-id with a new timestamp and signature (docs/04).
    const retry = await post(receiver.url, body, { timestamp: NOW + 30 });
    assert.equal(retry.status, 200);
    assert.match(await (await fetch(`http://127.0.0.1:${receiver.port}/`)).text(), /deliveries=2 unique=1/);
  });
});
