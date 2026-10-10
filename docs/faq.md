# Frequently asked questions

[한국어](faq.ko.md)

Short answers, each with a link to the guide that has the detail. The [terms](#terms) at the end
explain the words they use.

## Is Recly free?

Yes. The apps are free and open source, under
[AGPL-3.0-or-later](https://github.com/rokrokss/recly/blob/main/LICENSE), and there is no monthly
fee. On-device transcription costs nothing and has no minute limit: no audio leaves the device and
nothing is paid per minute. An external provider transcribes with your own key, at the provider's
price. See [how to transcribe](setup.md#3-choose-how-to-transcribe).

## How is it different from a Plaud?

A Plaud sells you a recorder, transcription and AI notes. Recly uses the watch, phone or computer
you already own as the recorder, transcribes on the device or with your own API key, and keeps the
files in your own storage. The notes come from the AI you already use: a
[ChatGPT agent](agent.md) that writes the minutes by itself, or the
[example skills](https://github.com/rokrokss/recly/blob/main/skills/README.md) for Claude, ChatGPT,
Codex and others.

## Do I need a watch?

No. The Android phone, iPhone, Mac and Windows apps record on their own. A watch adds a recorder on
your wrist: a Galaxy Watch hands its recordings to the Android phone, and an Apple Watch to the
iPhone. See [how to record](setup.md#5-record).

## Can I use the watch without the phone?

No. The watch cannot upload on its own: it hands its recordings to the phone app, so install both.
A recording waits on the watch until the phone has taken it. If you sideload, the watch and phone
apps must come from the same place. See the [install guide](install.md).

## Do I need a Google account?

No. Google Drive is the default, but on iPhone and Mac you can store recordings in your own iCloud,
and on iPhone, Mac, Windows and the Android phone in a local folder you pick, with no Google
account. The [automatic minutes with a ChatGPT agent](agent.md) are the exception: they need
recordings stored in Google Drive. See [where recordings go](setup.md#2-choose-where-recordings-go).

## Where do my recordings go, and can Recly see them?

Recly has no server. Data goes only to the storage you choose (your Google Drive, your iCloud on
iPhone and Mac, or a local folder that stays on your device), to the transcription provider you
chose with your own key, between your own paired watch and phone, and, if you set up automatic
minutes, to your ChatGPT agent through OpenAI: the recording's name, title and Drive links, and its
transcript when the agent asks for it; never the audio. In Google Drive, Recly asks only for `drive.file`, so it sees only the
files it created. Nothing goes to the Recly developer, and the
[privacy policy](https://recly.dev/policy/privacy-policy) lists every path.

## Do my other devices see my recordings?

Phones and desktops connected to the same Google account list each other's recordings in Google
Drive. An iPhone and a Mac that both store recordings in iCloud, on the same Apple ID, list each
other's too. A local folder belongs to its own device: other devices do not list its recordings.
See [find your recordings](setup.md#6-find-your-recordings).

## Why is there no iCloud on Android or Windows?

Recly saves to iCloud through the iCloud Drive folder that Apple's system keeps for the app and
uploads by itself, and only iPhone and Mac have that. On Android and Windows, choose Google Drive or
a local folder, which can be a folder another tool syncs. See [where recordings go](setup.md#2-choose-where-recordings-go).

## Which devices can transcribe on the device?

On iPhone and Mac, Recly uses Apple's speech model, which needs iOS or macOS 26 or later, supported
hardware and language assets. On Android and Windows it uses Qwen3-ASR 0.6B. Android phones and
Windows PCs need a 64-bit device with at least 6 GiB of memory as the device reports it, which in
practice means an 8 GB phone or PC, and a one-time download of about 1 GB. The watches leave
transcription to their phone, and other devices can use an external provider. See
[how to transcribe](setup.md#3-choose-how-to-transcribe).

## Are speakers separated on my device?

Yes. iPhone and Mac separate speakers with a model inside the app. Android phones and Windows PCs
separate them sentence by sentence, with a speaker model of about 41 MB that downloads with the
speech model; without it, transcripts arrive without speakers. With an external provider, speakers
are separated where the provider can. The **People in the room** you enter after you stop helps
either way, and in a recording's **Details** you can name the speakers or move a line to another
one. See [how to transcribe](setup.md#3-choose-how-to-transcribe).

## What is a highlight and where does it go?

A moment you mark while recording: **Highlight** on the phone, on either watch (or Double Tap on an
Apple Watch with watchOS 11 or later), in the Mac popover or the Windows tray popup. Add or remove
highlights later in a recording's **Details**. They are saved in the recording's `….meta.json` next
to the audio, and in the `.md` transcript of a local folder, but not in the `.txt` transcript. The
ChatGPT agent and the [local MCP server](mcp.md) see them with the transcript, so your agent can weigh
what you marked. See [mark a moment](setup.md#mark-a-moment).

## What files can I import?

Audio and video files the device can decode: what Android's, Apple's or, on Windows, the bundled
ffmpeg's decoders open. Recly keeps only the sound, converted to its own format, as a recording of
this device that uploads and is transcribed like any other; a video's picture is not kept. A file
with no audio, or one the device cannot decode, such as a copy-protected track, is refused with
**Could not import this file**. See [import audio](setup.md#import-audio).

## Can my AI write the notes by itself?

Yes, with a ChatGPT agent. When a transcript lands in your Google Drive, recly-events, a small
program you run on your own computer, tells your agent, and the agent writes the minutes by itself,
usually within a minute. You need recordings stored in Google Drive; a ChatGPT dot (at the time of
writing, ChatGPT Business Premium, or ChatGPT Pro outside the EEA, Switzerland and the UK) or a Work
chat on ChatGPT web; an OpenAI Platform account for the tunnel and its key; and a Mac, Linux or
Windows computer that stays on. [Run recly-events yourself](recly-events.md); the
[ChatGPT agent guide](agent.md) explains what it does. With other agents, the
[example skills](https://github.com/rokrokss/recly/blob/main/skills/README.md) write notes when you
ask, and for recordings in a local folder or iCloud, Claude and Codex read them through the
[local MCP server](mcp.md).

## Does editing a transcript start my agent again?

No. Fixing the text or naming a speaker rewrites the transcript files in your storage, and
recly-events, which runs the automatic minutes, does not announce an edit as a new transcript.
**Transcribe again** does start your agent: a new transcription is announced like the first one. One
exception: recly-events run with
[a Google client of your own](https://github.com/rokrokss/recly/blob/main/events/README.md#using-a-google-client-of-your-own)
cannot tell an edit apart, and announces it too.

## Does Recly record without telling anyone?

Nothing is covert: recording is always visible, and the Mac and Windows apps ask before they record
a meeting they notice. Recly also asks whether you told the people in the room: before the first
recording on the phones, and before every recording on the desktops; Settings can turn the question
off. It cannot tell them for you, and the rules differ by country and region: see
[your responsibility when recording](https://recly.dev/policy/privacy-policy#8-your-responsibility-when-recording)
in the privacy policy, and [how to record](setup.md#5-record).

## Why does Windows warn me, and is the Windows app ready?

The MSI is not code-signed, so SmartScreen shows "Windows protected your PC": choose
**More info** → **Run anyway** ([install guide](install.md#windows)).

**Beta.** The Windows app is built and tested in CI but has not yet been checked on a real
Windows PC. Please report what you find in [Issues](https://github.com/rokrokss/recly/issues).

## How do I update the Mac and Windows apps?

They do not update themselves. Install the newer DMG or MSI from the
[latest release](https://github.com/rokrokss/recly/releases/latest) over the old one; the MSI
upgrades the existing install. To hear about new releases, choose **Watch** → **Custom** →
**Releases** on [rokrokss/recly](https://github.com/rokrokss/recly) on GitHub. See
[updating](install.md#updating).

## How do I report a bug safely?

Open an issue in [Issues](https://github.com/rokrokss/recly/issues). Never attach recordings,
transcripts or keys: describe the problem instead, and redact anything private you paste. Report a
security vulnerability privately, as
[SECURITY.md](https://github.com/rokrokss/recly/blob/main/SECURITY.md) describes, never in a public
issue.

## Terms

| Term | Meaning |
|---|---|
| dot | A ChatGPT agent you create once, which can start work by itself when an event arrives. |
| Work chat | A chat in ChatGPT's Work mode on ChatGPT web. Like a dot, it can subscribe to events. |
| MCP | Model Context Protocol, the standard ChatGPT uses to talk to outside apps such as recly-events. |
| MCP event | A short message an app sends to ChatGPT that starts your agent. recly-events sends `recording.transcribed`. |
| Secure MCP Tunnel | OpenAI's relay that lets ChatGPT reach recly-events on your computer without a public address. |
| Tunnel key | A restricted OpenAI API key that may only read and use your tunnels. |
| `drive.file` | Google's narrowest Drive permission: Recly sees only the files it created. |
| Sideload | Installing an app from a downloaded file (an APK) instead of from Google Play. |
| ADB | Android Debug Bridge, the command-line tool in [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools) that installs apps on a phone or watch over USB or Wi-Fi. |
| Linger | A systemd setting that keeps your user services running after you log out. |
