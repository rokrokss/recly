# dot probe

Throwaway experiment, not part of Recly. Question it answers: **does a ChatGPT dot subscribe to a
custom MCP event and act on a signed webhook delivery?** (openai/codex#49665 reports subscriptions
never being created for custom developer-mode servers; @hunter reported Work chats work.)

## Findings (2026-10-03, ChatGPT Pro, Tailscale Funnel)

- Both a dot and a Work chat on web call `events/subscribe`; callback verification and deliveries
  return 200 in under a second, and each delivery starts a run.
- The Work chat's run sees the event `data`. The dot's run does not: its trigger message says the
  data comes in an `automations.mcp_event` tool result, but none is attached and the tool is not
  callable. Reported as openai/codex#50714.
- Workaround that works on both surfaces: treat the event as a signal and let the agent read through
  tools (`list_recent_recordings`, then `get_transcript`). The dot called them 14 s and 24 s after a
  delivery and wrote the expected minutes.
- Setup traps: ChatGPT discovers OAuth from the `401` + `WWW-Authenticate` on an unauthenticated
  `GET /mcp` (a `405` fails); an incomplete `/.well-known/openid-configuration` stops it before
  client registration (return 404); a first Funnel takes ~10 minutes of public DNS propagation;
  a pinned `lookup` must answer `options.all` with an array.

## Findings (2026-10-05, ChatGPT Pro, events-only server)

The server now holds no transcripts. An event names the transcript file in the user's Google Drive,
and the only tools are an event inbox: `get_pending_events` and `acknowledge_events`.

- **Port**: a ChatGPT app with a `:10000` Funnel URL failed with "service temporarily unavailable"
  and no request reached the server; the same server behind a port-443 URL (TryCloudflare) was
  discovered and created at once.
- **Secure MCP Tunnel, no authentication** (`AUTH_MODE=none`, `tunnel-client` 0.0.14, restricted
  runtime key with Tunnels Read + Use, tunnel associated with the personal ChatGPT workspace): the
  dot's tool calls reached the server (openai/tunnel-client#60 did not occur on this account),
  `events/subscribe` arrived without `ttlMs`, and callback verification and delivery returned 200.
- **End to end with a real recording** (46 min, watch): delivery at 05:54:42 UTC → the dot's event
  run called `get_pending_events` 26 s later → it opened the `.transcript.txt` through the ChatGPT
  Google Drive app, posted timestamped minutes covering the whole meeting in the dot conversation,
  and called `acknowledge_events` 4 min 6 s after the delivery.

`probe.mjs` is one file with Node built-ins only:

- MCP 2.0 (`2026-07-28`) server at `/mcp`: `server/discover`, `tools/list`, `tools/call`
  (`get_pending_events`, `acknowledge_events`), `events/list`, `events/subscribe`,
  `events/unsubscribe`. With `AUTH_MODE=none` it serves no OAuth metadata (for a Secure MCP Tunnel).
- One event, `recording.transcribed` (optional `device` filter), delivered as a Standard Webhooks
  signed POST after a signed callback challenge, as in the
  [MCP Events guide](https://developers.openai.com/plugins/build/mcp-events).
- A minimal OAuth 2.1 server (`/register` DCR, `/authorize` with a PIN page, `/token` with PKCE
  S256, refresh tokens, RFC 9207 `iss`), following the
  [auth guide](https://developers.openai.com/plugins/build/auth). `AUTH_MODE=none` turns it off.
- Admin port on 127.0.0.1 only: `GET /status`, `POST /fire`.
- Every request goes to `logs/requests.jsonl`; state (PIN, clients, tokens, subscriptions) to
  `data/state.json`. Both are gitignored.

## Run

```sh
node selftest.mjs        # local end-to-end check, no ChatGPT needed
node probe.mjs           # public port 8787, admin port 8788; prints the PIN
```

Expose port 8787 over HTTPS (Tailscale Funnel or a Cloudflare quick tunnel), then:

```sh
node probe.mjs status          # PIN, OAuth clients, active tokens, subscriptions
node probe.mjs fire "Weekly sync"   # deliver recording.transcribed to every subscription
tail -f logs/requests.jsonl    # everything ChatGPT sent
```

## Test in ChatGPT

1. Settings → Security and login → Developer mode on (web).
2. Plugins → Add → Create MCP App: URL `https://<public-host>/mcp`, auth OAuth. Approve with the
   PIN when the authorize page opens. Check that the plugin page lists `recording.transcribed`.
3. In each surface ask: "Subscribe to recording.transcribed from Recly dot probe. When an event
   arrives, call get_transcript and write meeting minutes (decisions, action items with owners)."
   - Work chat on ChatGPT web
   - Work chat in the desktop app with Cloud selected
   - the dot
4. Watch the server console for `★ SUBSCRIBED`, then `node probe.mjs fire` and check the answer.

Record per surface: subscribe received? verification OK? delivery HTTP status? did it write the
minutes, and how long after the delivery?
