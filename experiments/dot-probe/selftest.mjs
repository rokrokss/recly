#!/usr/bin/env node
// End-to-end self-test of probe.mjs without ChatGPT: runs the OAuth flow, subscribes a
// local callback receiver, fires an event and checks the Standard Webhooks signature.
// Starts its own probe instance on spare ports with a throwaway data directory.

import { spawn } from "node:child_process";
import http from "node:http";
import { createHash, createHmac, randomBytes } from "node:crypto";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const PORT = 28787, ADMIN = 28788, RECV = 28799;
const BASE = `http://127.0.0.1:${PORT}`;
const dataDir = mkdtempSync(join(tmpdir(), "dot-probe-test-"));
const secret = `whsec_${randomBytes(32).toString("base64")}`;
let failures = 0;
const check = (ok, label, extra = "") => { console.log(`${ok ? "PASS" : "FAIL"}  ${label}${extra ? `  ${extra}` : ""}`); if (!ok) failures++; };

const received = [];
const receiver = http.createServer((req, res) => {
  const chunks = [];
  req.on("data", (c) => chunks.push(c));
  req.on("end", () => {
    const body = Buffer.concat(chunks).toString("utf8");
    const id = req.headers["webhook-id"], ts = req.headers["webhook-timestamp"];
    const expected = `v1,${createHmac("sha256", Buffer.from(secret.slice(6), "base64")).update(`${id}.${ts}.${body}`).digest("base64")}`;
    const signed = String(req.headers["webhook-signature"] ?? "").split(" ").includes(expected);
    const payload = JSON.parse(body);
    received.push({ signed, payload, subscription: req.headers["x-mcp-subscription-id"] });
    if (payload.type === "verification") { res.writeHead(200, { "Content-Type": "application/json" }); return res.end(JSON.stringify({ challenge: payload.challenge })); }
    res.writeHead(204); res.end();
  });
}).listen(RECV, "127.0.0.1");

const probe = spawn(process.execPath, [join(HERE, "probe.mjs")], {
  env: { ...process.env, PORT: String(PORT), ADMIN_PORT: String(ADMIN), DATA_DIR: dataDir, ALLOW_LOCAL_CALLBACKS: "true", AUTH_MODE: "oauth", BASE_URL: "" },
  stdio: ["ignore", "pipe", "pipe"],
});
let probeOut = "";
probe.stdout.on("data", (d) => { probeOut += d; });
probe.stderr.on("data", (d) => { probeOut += d; });

async function waitUp() {
  for (let i = 0; i < 50; i++) { try { if ((await fetch(`${BASE}/health`)).ok) return; } catch {} await new Promise((r) => setTimeout(r, 100)); }
  throw new Error("probe did not start");
}
const meta = { "io.modelcontextprotocol/protocolVersion": "2026-07-28", "io.modelcontextprotocol/clientCapabilities": {} };
let rpcId = 0;
async function rpc(method, params = {}, token) {
  const r = await fetch(`${BASE}/mcp`, { method: "POST", headers: { "Content-Type": "application/json", "MCP-Protocol-Version": "2026-07-28",
    "Mcp-Method": method, ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify({ jsonrpc: "2.0", id: ++rpcId, method, params: { ...params, _meta: meta } }) });
  return { status: r.status, headers: r.headers, json: r.status === 200 ? await r.json() : null };
}

try {
  await waitUp();
  const anon = await rpc("server/discover");
  check(anon.status === 401 && /resource_metadata=/.test(anon.headers.get("www-authenticate") ?? ""), "unauthenticated MCP gets 401 + WWW-Authenticate");

  const prm = await (await fetch(`${BASE}/.well-known/oauth-protected-resource/mcp`)).json();
  const asm = await (await fetch(`${BASE}/.well-known/oauth-authorization-server`)).json();
  check(prm.resource === `${BASE}/mcp` && prm.authorization_servers[0] === BASE, "protected resource metadata");
  check(asm.code_challenge_methods_supported.includes("S256") && asm.registration_endpoint, "authorization server metadata");

  const redirect = `http://127.0.0.1:${RECV}/cb`;
  const client = await (await fetch(asm.registration_endpoint, { method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ client_name: "selftest", redirect_uris: [redirect], token_endpoint_auth_method: "client_secret_post" }) })).json();
  check(client.client_id && client.client_secret, "dynamic client registration");

  const verifier = randomBytes(32).toString("base64url");
  const challenge = createHash("sha256").update(verifier).digest("base64url");
  const authParams = { response_type: "code", client_id: client.client_id, redirect_uri: redirect, code_challenge: challenge,
    code_challenge_method: "S256", state: "xyz", resource: `${BASE}/mcp`, scope: "recly.events" };
  const page = await fetch(`${asm.authorization_endpoint}?${new URLSearchParams(authParams)}`);
  check(page.status === 200 && (await page.text()).includes("PIN"), "authorize page renders");
  const pin = JSON.parse(readFileSync(join(dataDir, "data", "state.json"), "utf8")).pin;
  const wrong = await fetch(asm.authorization_endpoint, { method: "POST", redirect: "manual", headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ ...authParams, pin: "000000" }) });
  check(wrong.status === 401, "wrong PIN is refused");
  const approved = await fetch(asm.authorization_endpoint, { method: "POST", redirect: "manual", headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ ...authParams, pin }) });
  const location = new URL(approved.headers.get("location"));
  check(approved.status === 302 && location.searchParams.get("state") === "xyz" && location.searchParams.get("iss") === BASE, "authorize redirects with code, state, iss");

  const badPkce = await fetch(asm.token_endpoint, { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "authorization_code", code: "nope", code_verifier: "x", client_id: client.client_id, client_secret: client.client_secret, redirect_uri: redirect }) });
  check(badPkce.status === 400, "unknown code is refused");
  const tok = await (await fetch(asm.token_endpoint, { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "authorization_code", code: location.searchParams.get("code"), code_verifier: verifier,
      client_id: client.client_id, client_secret: client.client_secret, redirect_uri: redirect, resource: `${BASE}/mcp` }) })).json();
  check(tok.access_token && tok.refresh_token, "token exchange with PKCE");

  // CIMD path, as ChatGPT uses it: client_id is its metadata document URL, public client.
  const cimdId = "https://chatgpt.com/oauth/client.json";
  const cimdRedirect = "https://chatgpt.com/connector_platform_oauth_redirect";
  const cimdVerifier = randomBytes(32).toString("base64url");
  const cimdParams = { response_type: "code", client_id: cimdId, redirect_uri: cimdRedirect, state: "cimd",
    code_challenge: createHash("sha256").update(cimdVerifier).digest("base64url"), code_challenge_method: "S256", resource: `${BASE}/mcp` };
  check(asm.client_id_metadata_document_supported === true, "metadata advertises CIMD");
  const cimdPage = await fetch(`${asm.authorization_endpoint}?${new URLSearchParams(cimdParams)}`);
  check(cimdPage.status === 200, "CIMD authorize page renders for ChatGPT's client_id");
  const cimdApproved = await fetch(asm.authorization_endpoint, { method: "POST", redirect: "manual", headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ ...cimdParams, pin }) });
  const cimdLocation = new URL(cimdApproved.headers.get("location") ?? "http://x/");
  check(cimdApproved.status === 302 && cimdLocation.origin === "https://chatgpt.com" && cimdLocation.searchParams.get("iss") === BASE, "CIMD authorize redirects to chatgpt.com with iss");
  const evil = await fetch(`${asm.authorization_endpoint}?${new URLSearchParams({ ...cimdParams, redirect_uri: "https://evil.example/cb" })}`);
  check(evil.status === 400, "CIMD rejects a redirect_uri outside the document");
  const cimdTok = await fetch(asm.token_endpoint, { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "authorization_code", code: cimdLocation.searchParams.get("code"), code_verifier: cimdVerifier,
      client_id: cimdId, redirect_uri: cimdRedirect, resource: `${BASE}/mcp` }) });
  check(cimdTok.status === 200 && (await cimdTok.json()).access_token, "CIMD public-client token exchange with PKCE");
  const oidc = await fetch(`${BASE}/.well-known/openid-configuration`);
  check(oidc.status === 404, "no OIDC discovery document");

  const discover = await rpc("server/discover", {}, tok.access_token);
  check(discover.json?.result?.capabilities?.events !== undefined && discover.json.result.supportedVersions[0] === "2026-07-28", "server/discover advertises events");
  const list = await rpc("events/list", {}, tok.access_token);
  check(list.json?.result?.events?.[0]?.name === "recording.transcribed", "events/list");
  const tools = await rpc("tools/list", {}, tok.access_token);
  check(tools.json?.result?.tools?.map((t) => t.name).sort().join() === "acknowledge_events,get_pending_events", "tools/list has only the event inbox");

  const subParams = { name: "recording.transcribed", arguments: {}, delivery: { mode: "webhook", url: `http://localhost:${RECV}/hook`, secret }, cursor: null }; // hostname, so delivery goes through DNS lookup
  const sub = await rpc("events/subscribe", subParams, tok.access_token);
  check(sub.json?.result?.id?.startsWith("sub_") && received.some((r) => r.payload.type === "verification" && r.signed), "events/subscribe with signed verification challenge");
  const verifications = received.length;
  const again = await rpc("events/subscribe", subParams, tok.access_token);
  check(again.json?.result?.id === sub.json.result.id && received.length === verifications, "repeat subscribe refreshes the same id without a new challenge");
  const badSecret = await rpc("events/subscribe", { ...subParams, delivery: { ...subParams.delivery, secret: "whsec_short" } }, tok.access_token);
  check(badSecret.json?.error, "short signing secret is refused");

  const fired = await (await fetch(`http://127.0.0.1:${ADMIN}/fire`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ title: "Selftest" }) })).json();
  await new Promise((r) => setTimeout(r, 200));
  const event = received.find((r) => r.payload.eventId === fired.event.eventId);
  check(fired.results[0]?.status === 204 && event?.signed && event.subscription === sub.json.result.id, "fired event arrives signed with X-MCP-Subscription-Id");

  check(fired.event.data.drive?.transcriptTxtFileId && fired.event.data.excerpt === undefined, "event carries Drive references, no transcript text");

  const pending = await rpc("tools/call", { name: "get_pending_events", arguments: {} }, tok.access_token);
  const pendingEvents = pending.json?.result?.structuredContent?.events ?? [];
  check(pendingEvents.length === 1 && pendingEvents[0].eventId === fired.event.eventId && pendingEvents[0].data.drive.transcriptTxtUrl, "get_pending_events returns the delivered event with its data");
  const ack = await rpc("tools/call", { name: "acknowledge_events", arguments: { eventIds: [fired.event.eventId] } }, tok.access_token);
  const empty = await rpc("tools/call", { name: "get_pending_events", arguments: {} }, tok.access_token);
  check(ack.json?.result?.structuredContent?.acknowledged === 1 && empty.json?.result?.structuredContent?.events?.length === 0, "acknowledged events are not returned again");

  const unsub = await rpc("events/unsubscribe", { name: "recording.transcribed", arguments: {}, delivery: { mode: "webhook", url: `http://localhost:${RECV}/hook` } }, tok.access_token);
  const after = await (await fetch(`http://127.0.0.1:${ADMIN}/fire`, { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" })).json();
  check(unsub.json?.result && after.results.length === 0, "unsubscribe stops delivery");

  const refreshed = await (await fetch(asm.token_endpoint, { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "refresh_token", refresh_token: tok.refresh_token, client_id: client.client_id, client_secret: client.client_secret }) })).json();
  check(refreshed.access_token && refreshed.access_token !== tok.access_token, "refresh token grant");
} catch (e) {
  check(false, "selftest crashed", e.stack);
} finally {
  probe.kill(); receiver.close();
  rmSync(dataDir, { recursive: true, force: true });
  if (failures) console.log(`\n--- probe output ---\n${probeOut}`);
  console.log(failures ? `\n${failures} check(s) failed` : "\nall checks passed");
  process.exit(failures ? 1 : 0);
}
