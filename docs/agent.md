# Automatic minutes with a ChatGPT agent

[한국어](agent.ko.md)

Stop recording, and once the transcript is in your Google Drive, your ChatGPT agent (a
[dot](https://help.openai.com/en/articles/20001530-getting-started-with-your-dot) or a Work chat)
starts on the minutes by itself, usually within a minute. `recly-events`, a small program you run
on your own computer, does this: it sees each new transcript and tells your agent through
[OpenAI's MCP Events](https://developers.openai.com/plugins/build/mcp-events) and your own
[OpenAI Secure MCP Tunnel](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels).
The agent then reads the transcript from recly-events, through the same tunnel.

For recordings in a local folder or iCloud, read by Claude or Codex on the same computer, see
[Local MCP server](mcp.md).

## Terms

| Term | Meaning |
|---|---|
| dot | A ChatGPT agent you create once, which can start work by itself when an event arrives. |
| Work chat | A chat in ChatGPT's Work mode on ChatGPT web. Like a dot, it can subscribe to events. |
| MCP event | A short message an app sends to ChatGPT that starts your agent. recly-events sends `recording.transcribed`. |
| Secure MCP Tunnel | OpenAI's relay that lets ChatGPT reach recly-events on your computer without a public address. |
| Tunnel key | A restricted OpenAI API key that may only read and use your tunnels. |

## What you need

- **Recly storing recordings in Google Drive.** Recordings in iCloud or a local folder are not seen.
- **A ChatGPT agent that can subscribe to events**: a dot, or a Work chat on ChatGPT web. At the
  time of writing, dots need ChatGPT Business Premium, or ChatGPT Pro outside the EEA, Switzerland
  and the UK.
- **An OpenAI Platform account** at [platform.openai.com](https://platform.openai.com), for the
  tunnel and its key.
- **A Mac, Linux or Windows computer that stays on**, running recly-events. Nothing is lost while
  it sleeps: the agent hears about the new transcripts when it wakes.

## Set it up

Follow [Running recly-events yourself](recly-events.md):

| Step | Command or place |
|---|---|
| [1. Install](recly-events.md#1-install) | The newest [`events-v…` release](https://github.com/rokrokss/recly/releases?q=events-v&expanded=true) |
| [2. Create the tunnel and its key](recly-events.md#2-create-the-tunnel-and-its-key) | OpenAI Platform |
| [3. Connect Google Drive and the tunnel](recly-events.md#3-connect-google-drive-and-the-tunnel) | `recly-events init --google --tunnel-id tunnel_…` |
| [4. Start it](recly-events.md#4-start-it) | `recly-events service install`; on Windows, Task Scheduler |
| [5. Add the app in ChatGPT](recly-events.md#5-add-the-app-in-chatgpt) | ChatGPT → Plugins |
| [6. Tell your agent once](recly-events.md#6-tell-your-agent-once) | Your dot or Work chat |
| [7. Test](recly-events.md#7-test) | `recly-events test` |

## What leaves your computer

recly-events finds new transcripts in your Drive by their names, IDs and links. The event tells your
agent the recording's name, title and Drive links, never what was said. The transcript goes to your
agent only when it asks with `get_transcript`, through your own tunnel. Nothing goes to the Recly
developer. The full list is in the
[privacy policy](https://recly.dev/policy/privacy-policy).
