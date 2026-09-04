// The local webhook receiver the acceptance runs point at (docs/20 "웹훅 로컬 수신기"). It recomputes
// docs/04's Standard Webhooks signature the way core's `Signer` does, and checks the body against
// spec/webhook.payload.schema.json.
// run: node scripts/webhook-receiver.mjs --port 8787 --secret whsec_… [--log <dir>] [--fail-first N]
import { createHmac, timingSafeEqual } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { createServer } from "node:http";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import Ajv2020 from "../spec/node_modules/ajv/dist/2020.js";
import addFormats from "../spec/node_modules/ajv-formats/dist/index.js";

const root = dirname(fileURLToPath(import.meta.url));
// docs/04 does not write down a tolerance. This uses the ±5 minutes of the Standard Webhooks reference.
export const TOLERANCE_SEC = 300;

/** core `Signer.secretBytes`' rule: base64 when it is `whsec_`, otherwise the input string's UTF-8 bytes. */
export function secretBytes(stored) {
  if (!stored.startsWith("whsec_")) return Buffer.from(stored, "utf8");
  const body = stored.slice("whsec_".length);
  const decoded = Buffer.from(body, "base64");
  if (decoded.toString("base64") !== body) throw new Error("secret starts with 'whsec_' but is not base64");
  return decoded;
}

/** The same `v1,base64(HMAC-SHA256(secret, "{id}.{timestamp}.{body}"))` core `Signer.sign` writes. */
export function sign(secret, id, timestampSec, body) {
  return "v1," + createHmac("sha256", secret).update(`${id}.${timestampSec}.`).update(body).digest("base64");
}

const matches = (header, expected) =>
  header.split(" ").some((one) => {
    const a = Buffer.from(one, "utf8");
    const b = Buffer.from(expected, "utf8");
    return a.length === b.length && timingSafeEqual(a, b);
  });

/** Why the signature failed, or null when it passed. */
function verify(secret, headers, body, nowSec) {
  const id = headers["webhook-id"];
  const timestamp = headers["webhook-timestamp"];
  const signature = headers["webhook-signature"];
  if (!id || !timestamp || !signature) return "missing webhook-id/timestamp/signature";
  const ts = Number(timestamp);
  if (!Number.isInteger(ts)) return `bad webhook-timestamp ${timestamp}`;
  if (Math.abs(nowSec - ts) > TOLERANCE_SEC) return `timestamp ${ts} outside ±${TOLERANCE_SEC}s`;
  if (!matches(signature, sign(secret, id, ts, body))) return "signature mismatch";
  return null;
}

const read = (req) =>
  new Promise((resolve, reject) => {
    const chunks = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => resolve(Buffer.concat(chunks)));
    req.on("error", reject);
  });

const send = (res, status, body) => {
  res.writeHead(status, { "content-type": "application/json" });
  res.end(JSON.stringify(body));
};

/** The first drive upload's result. Without a `drive.upload` ahead of it they are all null (docs/04). */
const fileId = (payload) => payload.data.files.find((f) => f.drive)?.drive.fileId ?? "-";

/**
 * Starts the receiver on 127.0.0.1. The tests use this function directly; the CLI below wraps it.
 * [failFirst] answers 500 to the first N deliveries of one `webhook-id`, so a retry — and a duplicate
 * delivery — can be watched.
 */
export async function start({ port = 0, secret, log = null, failFirst = 0, now = () => Date.now() / 1000 }) {
  const key = secretBytes(secret);
  const ajv = new Ajv2020({ strict: true, allErrors: true });
  addFormats(ajv);
  const validate = ajv.compile(JSON.parse(readFileSync(join(root, "../spec/webhook.payload.schema.json"), "utf8")));
  if (log) mkdirSync(log, { recursive: true });

  const attempts = new Map(); // webhook-id -> deliveries received so far
  const accepted = new Map(); // webhook-id -> whether it was ever answered 200

  const server = createServer(async (req, res) => {
    if (req.method === "GET") {
      res.writeHead(200, { "content-type": "text/plain" });
      res.end(`deliveries=${[...attempts.values()].reduce((a, b) => a + b, 0)} unique=${attempts.size}\n`);
      return;
    }
    if (req.method !== "POST" || new URL(req.url, "http://127.0.0.1").pathname !== "/hook") {
      send(res, 404, { ok: false, error: "POST /hook only" });
      return;
    }
    const body = await read(req);
    const bad = verify(key, req.headers, body, now());
    if (bad) {
      console.log(`401 id=${req.headers["webhook-id"] ?? "-"} ${bad}`);
      send(res, 401, { ok: false, error: bad });
      return;
    }
    let payload;
    try {
      payload = JSON.parse(body.toString("utf8"));
    } catch (e) {
      console.log(`400 id=${req.headers["webhook-id"]} bad json: ${e.message}`);
      send(res, 400, { ok: false, error: `bad json: ${e.message}` });
      return;
    }
    if (!validate(payload)) {
      const errors = ajv.errorsText(validate.errors);
      console.log(`400 id=${req.headers["webhook-id"]} schema: ${errors}`);
      send(res, 400, { ok: false, error: errors });
      return;
    }

    const id = req.headers["webhook-id"];
    const attempt = (attempts.get(id) ?? 0) + 1;
    attempts.set(id, attempt);
    if (log) writeFileSync(join(log, `${id}-${attempt}.json`), JSON.stringify(payload, null, 2) + "\n");
    if (attempt <= failFirst) {
      console.log(`500 id=${id} attempt=${attempt} (--fail-first ${failFirst})`);
      send(res, 500, { ok: false, error: "fail-first" });
      return;
    }
    const dup = accepted.has(id) ? " dup" : "";
    accepted.set(id, true);
    console.log(
      `ok id=${id} recordingId=${payload.data.recording.recordingId} event=${payload.type}` +
        ` drive.fileId=${fileId(payload)} attempt=${attempt}${dup}`,
    );
    send(res, 200, { ok: true });
  });

  await new Promise((resolve) => server.listen(port, "127.0.0.1", resolve));
  return {
    url: `http://127.0.0.1:${server.address().port}/hook`,
    port: server.address().port,
    close: () => new Promise((resolve) => server.close(resolve)),
  };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const argv = process.argv.slice(2);
  const args = {};
  for (let i = 0; i < argv.length; i += 2) args[argv[i].replace(/^--/, "")] = argv[i + 1];
  if (!args.secret) {
    console.error("usage: node scripts/webhook-receiver.mjs --port 8787 --secret whsec_… [--log <dir>] [--fail-first N]");
    process.exit(2);
  }
  const receiver = await start({
    port: Number(args.port ?? 8787),
    secret: args.secret,
    log: args.log ?? null,
    failFirst: Number(args["fail-first"] ?? 0),
  });
  console.log(`listening ${receiver.url}`);
}
