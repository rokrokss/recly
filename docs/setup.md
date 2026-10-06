# Setting up Recly

[한국어](setup.ko.md)

Once Recly is [installed](install.md), it records right away. Settings decide two things: where
recordings go, and how they are transcribed. Out of the box they go to Google Drive, in
`recly/memo/<year>-<month>`, and are transcribed on the device where the device can, otherwise not
at all. The watches have no settings: they hand their recordings to the phone, which uses its own.

## 1. Open Settings

- **Android phone, iPhone**: the **Settings** tab.
- **Mac**: the menu-bar icon → **Settings** at the bottom of the popover. Recly has no window and no
  Dock icon.
- **Windows**: the tray icon → **Settings**, which opens **Recly Settings**.

## 2. Choose where recordings go

The **Storage** section at the top offers:

| Choice | On | What it needs |
|---|---|---|
| **Google Drive** (the default) | every app | **Connect Drive**, then sign in with Google |
| **iCloud** | iPhone, Mac | iCloud Drive turned on for Recly in the system settings |
| **Local folder** | iPhone, Mac, Windows, Android phone | **Choose folder** |

- **Google Drive.** Choose **Connect Drive** and sign in. Google asks to let Recly see only the files
  it creates (`drive.file`): Recly cannot see anything else in your Drive. You can record before
  connecting; the recordings upload once you connect. The row then reads **Drive connected**.
- **iCloud.** There is no button in the app. On an iPhone, open Settings → [your name] → iCloud →
  Drive, turn on **Sync this iPhone**, then turn on Recly under **Saved to iCloud** → **See All**. On
  a Mac, turn on **Sync this Mac**, then Recly under **Apps Syncing to iCloud Drive**; **Open iCloud
  Settings** in Recly takes you there. The recordings appear in a **Recly** folder in Files or Finder.
- **Local folder.** Choose **Choose folder** and pick any folder on the device, for example an
  Obsidian vault or a folder another tool syncs. No Google account is needed. Each transcript is also
  written as a `.transcript.md`. The folder belongs to this device only: other devices do not list
  its recordings.

A choice applies to recordings started after it; nothing already recorded moves. Switching away
from Google Drive does not disconnect it. The automatic minutes with a
[ChatGPT agent](agent.md) need Google Drive.

## 3. Choose how to transcribe

Under **Recording processing** → **Transcription**:

- **On device.** No audio leaves the device and nothing is paid per minute. Download the speech model
  once: **Download model** in the settings, or on the **Transcribe on this device** card that appears
  on the Record tab, the Mac popover or the Windows tray popup. It is Apple's speech model on iPhone
  and Mac, and Qwen3-ASR 0.6B (about 1 GB) on Android and Windows. On-device transcription does not
  separate speakers, and it is offered only on iOS and macOS 26 or later, and on Android phones and
  Windows PCs with at least 6 GB of memory.
- **External API.** The audio goes to a provider you choose, with your own key, at the provider's
  price. Pick the **Provider**, paste the **API key** and choose **Save key**; the row then reads
  **Saved on this device**. CLOVA Speech and Azure AI Speech also need an **Invoke URL**; RTZR takes
  the key as `client ID:client secret`. Speakers are separated automatically where the provider can.
  - Android, Mac and Windows: ElevenLabs, CLOVA Speech, AssemblyAI, RTZR, OpenAI, Groq, Together AI,
    Mistral AI, Deepgram, Azure AI Speech, Daglo, Speechmatics, Rev AI, Gladia.
  - iPhone: ElevenLabs, CLOVA Speech, AssemblyAI, RTZR, OpenAI, Groq, Deepgram, Azure AI Speech. On
    saving a key, the iPhone asks once whether recordings may be sent to that provider.
- **Off.** Recordings are uploaded and nothing is transcribed.

**Spoken language** sets the language of your recordings: **Automatic**, **Korean and English**
for mixed speech, or one language. On-device transcription needs a language chosen.

Keys stay on the device that saved them and are never synced: enter them on each device.

## 4. Allow what the app asks for

| App | When | What |
|---|---|---|
| Android phone | first recording | notifications, then the microphone |
| Galaxy Watch | first start | the microphone and notifications |
| iPhone | first recording | the microphone |
| Apple Watch | first recording | the microphone |
| Mac | first recording | the microphone and system audio, which records the other side of a call; notifications at the first meeting it notices |
| Windows | never asks | if Windows blocks the microphone, Recly says so and opens the Windows setting |

If you said no, Recly shows where to turn it back on.

## 5. Record

- **Galaxy Watch**: the app or the tile, or a double press of the home key set to
  **Recly Record** ([install guide](install.md#galaxy-watch)). Turn off battery optimisation for
  Galaxy Wearable on the phone, or transfers stop.
- **Apple Watch**: the app or the complication; on an Ultra, the Action button.
- **Android phone**: the Record tab, the Quick Settings tile, the home-screen widget or the app icon's
  shortcut.
- **iPhone**: the Record tab, Siri or Shortcuts, the Action button, or the Control Center control.
- **Mac**: the menu-bar icon → **Start recording**. When a meeting app starts using the microphone,
  Recly asks **Are you in a meeting?** and records only if you say so.
- **Windows**: the tray icon → **Start recording**, or **Record the detected meeting** when Recly
  notices a meeting.

Recly asks whether you told the people in the room: before the first recording on the phones, and
before every recording on the desktops (**Consent check before recording** in Settings turns it off).
After you stop, the phones and
desktops ask for a **Recording title** and the **People in the room** (which helps the provider
separate speakers). Choose **Save**, even with the title left empty. **Cancel discards the
recording.**

## 6. Find your recordings

- **In the app**: the **List** tab on the phones, the Mac popover or the Windows tray popup. Open a
  recording for **Details**: the player, the transcript, search and **Copy all**. **Open in Drive**,
  **Show in Finder** (Mac) and **Open the folder** (Windows) take you to the files. Devices on the same
  Google account list each other's recordings.
- **In your storage**: one folder per recording, for example
  `recly/memo/2026-10/20261006T010000Z_phone_01J9ABCD/`, holding the audio in parts of about 15
  minutes, `….meta.json`, and with transcription `….transcript.txt` and `….transcript.json`. Change the
  folder pattern under **Recording processing** → **Storage folder**.

## 7. Let your AI write the notes

Recly stops at the transcript and hands it to the AI you already use:

- **Automatically, with ChatGPT**: [Automatic minutes with a ChatGPT agent](agent.md) (Mac and
  Windows apps, Google Drive).
- **On request, with any agent**: the [example skills](https://github.com/rokrokss/recly/blob/main/skills/README.md)
  for Claude, ChatGPT, Codex and others.

## Another device

**Settings file** → **Export settings** writes `recly-settings.json`: the folder, the transcription
method, the language and the provider, but never a key. On the other device, **Import settings**,
check the values and save, then enter the key there. The storage choice stays per device.

## If something is off

- **A watch recording does not arrive**: the watch needs the phone app; on Galaxy, turn off battery
  optimisation for Galaxy Wearable. "Waiting to send" on the watch means the phone has not taken it
  yet.
- **Uploads wait on mobile data**: **Upload on Wi-Fi only** is on in the phone's settings.
- **No On device choice**: the device is below iOS or macOS 26, or has less than 6 GB of memory.
- **Mac, "You are listening on the built-in speaker"**: use headphones, so the other side of a call
  stays off your microphone track.
