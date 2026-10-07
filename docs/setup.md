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
  connecting; the recordings upload once you connect. The row then shows the Google account, or
  **Drive connected** where the app has no account name (Windows always), next to
  **Disconnect Drive**.
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
  and Mac, and Qwen3-ASR 0.6B on Android and Windows. Speakers are separated on the device too (see
  the table below). iPhone and Mac need iOS or macOS 26 or later, supported hardware and language assets.
  Android phones and Windows PCs need a 64-bit device with at least 6 GiB of memory as the device
  reports it, which in practice means an 8 GB phone or PC, and a one-time download of about 1 GB.
- **External API.** The audio goes to a provider you choose, with your own key, at the provider's
  price. Pick the **Provider**, paste the **API key** and choose **Save key**; the row then reads
  **Saved on this device**. CLOVA Speech and Azure AI Speech also need an **Invoke URL**; RTZR takes
  the key as `client ID:client secret`. Speakers are separated automatically where the provider can.
  - Android, Mac and Windows: ElevenLabs, CLOVA Speech, AssemblyAI, RTZR, OpenAI, Groq, Together AI,
    Mistral AI, Deepgram, Azure AI Speech, Daglo, Speechmatics, Rev AI, Gladia.
  - iPhone: ElevenLabs, CLOVA Speech, AssemblyAI, RTZR, OpenAI, Groq, Deepgram, Azure AI Speech. On
    saving a key, the iPhone asks once whether recordings may be sent to that provider.
- **Off.** Recordings are uploaded and nothing is transcribed.

| On device | Speakers | **Speaker model** row |
|---|---|---|
| iPhone, Mac | separated | `pyannote community-1`, inside the app: nothing to download |
| Android, Windows | separated sentence by sentence | `pyannote 3.0 · ERes2Net`, about 41 MB, downloaded with the speech model; a device that already has the speech model shows **Download** on this row |

Without the speaker model, transcripts arrive without speakers.

### Where to get a key

Each provider issues its key on the page below (checked 2026-10-06). Each page asks you to sign in
or create an account first, and the provider bills that account at its own price.

| Provider | Where to get the key |
|---|---|
| AssemblyAI | [Dashboard → API Keys](https://www.assemblyai.com/dashboard/api-keys) |
| Azure AI Speech | In the Azure portal, your Speech or Foundry resource → **Keys and Endpoint**: **KEY 1** is the key and **Endpoint** is the Invoke URL ([guide](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/fast-transcription-create)) |
| CLOVA Speech | NAVER Cloud console → CLOVA Speech → Domain → **Launch Builder** → **Settings** → **Integration information**: the Secret Key is the key, next to the Invoke URL ([guide](https://guide.ncloud-docs.com/docs/en/clovaspeech-builder-long#check-api-call-information)) |
| Daglo | [Developers console](https://developers.daglo.ai/console) → the token menu |
| Deepgram | [Console](https://console.deepgram.com/) → your project's **Settings** → **API Keys** |
| ElevenLabs | [API keys](https://elevenlabs.io/app/developers/api-keys) |
| Gladia | [API keys](https://app.gladia.io/apikeys) |
| Groq | [API keys](https://console.groq.com/keys) |
| Mistral AI | [API keys](https://console.mistral.ai/api-keys) |
| OpenAI | [API keys](https://platform.openai.com/api-keys) |
| Rev AI | [Access token](https://www.rev.ai/access-token) |
| RTZR | [Console](https://developers.rtzr.ai/console/): issue a client ID and secret, then enter them as `client ID:client secret` |
| Speechmatics | [API keys](https://portal.speechmatics.com/settings/api-keys/) |
| Together AI | [API keys](https://api.together.ai/settings/projects/~current/api-keys) |

Keys from the separate EU data-residency accounts of ElevenLabs and Rev AI do not work: Recly calls
their standard hosts.

**Spoken language** sets the language of your recordings: **Automatic**, **Korean and English**
for mixed speech, or one language. On-device transcription needs a language chosen.

Keys stay on the device that saved them and are never synced: enter them on each device.

### Vocabulary

**Recording processing** → **Vocabulary**, under **Spoken language**: names and terms to spell
right. Type one and press Enter or **Add**, or paste a list, one per line. Up to 50, 40 characters
each.

| Transcription | The list |
|---|---|
| External API | sent with the audio to the provider, where its API takes one; otherwise the row says the provider does not use it |
| On device, Android and Windows | used on the device |
| On device, iPhone and Mac | not used |

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
- **iPhone**: the Record tab, Siri or Shortcuts, the Action button, the Control Center control, or
  the **Record** widget on the Home Screen or Lock Screen.
- **Mac**: the menu-bar icon → **Start recording**, ⌥⌘R, or Shortcuts. When a meeting app starts
  using the microphone, Recly asks **Are you in a meeting?** and records only if you say so.
- **Windows**: the tray icon → **Start recording**, Ctrl+Alt+R, or **Record the detected meeting**
  when Recly notices a meeting.

### Mark a moment

| App | While recording |
|---|---|
| Android phone, iPhone | the square **Highlight** button beside the record button |
| Galaxy Watch | **Highlight** under the stop button |
| Apple Watch | **Highlight** under the stop button, or Double Tap (watchOS 11 and later) |
| Mac | **Highlight** in the menu-bar popover, or the Shortcuts action **Add Highlight** |
| Windows | **Highlight** in the tray popup |

Later, in a recording's **Details**: **More** → **Add highlight at …**, or **Highlight** by the
player on Mac and Windows. Tap a highlight on the waveform or in the transcript to go to it or
remove it. Highlights are kept in the recording's `….meta.json`, where agents read them.

### Keyboard shortcuts

| Keys | Where | What |
|---|---|---|
| ⌥⌘R | Mac, any app | start or stop recording |
| Ctrl+Alt+R | Windows, any app | start or stop recording |
| ⌘F, Ctrl+F | Details window | search, or find in the open transcript |
| ⌘I | Mac Details window | import audio |

Turn ⌥⌘R or Ctrl+Alt+R off under **Settings** → **Capture** → **Keyboard shortcut**.

Recly asks whether you told the people in the room: before the first recording on the phones, and
before every recording on the desktops (**Consent check before recording** in Settings turns it off).
After you stop, the phones and
desktops ask for a **Recording title** and the **People in the room** (which helps the provider
separate speakers). Choose **Save**, even with the title left empty. **Cancel discards the
recording.**

## 6. Find your recordings

- **In the app**: the **List** tab on the phones, the Mac popover or the Windows tray popup. Open a
  recording for **Details**: the player and the transcript. **Open in Drive**, **Show in Finder**
  (Mac) and **Open the folder** (Windows) take you to the files. Devices on the same Google account
  list each other's recordings. **Search titles and transcripts** sits above the list on the phones
  and in the Mac and Windows **Details** windows.
- **In your storage**: one folder per recording, for example
  `recly/memo/2026-10/20261006T010000Z_phone_01J9ABCD/`, holding the audio in parts of about 15
  minutes, `….meta.json`, and with transcription `….transcript.txt` and `….transcript.json`. Change the
  folder pattern under **Recording processing** → **Storage folder**.

| In **Details** | What it does |
|---|---|
| **Share** (Windows: **Export…**) | the transcript as `.txt`, `.md`, `.srt` or `.vtt`, the audio as `.m4a`, or **Copy all** |
| **More** → **Edit transcript** | fix the text; tap a speaker to rename them or move a line to another speaker. Saving rewrites the transcript files and does not start your ChatGPT agent again |
| **More** → **Transcribe again** | transcribe with the current settings; replaces your edits |
| **More** → **Rename** | change the title |
| speed (`1×`) | 0.75× to 2×, and **Skip silence** |

### Import audio

| App | How |
|---|---|
| Android phone | the import button at the top of the list, or share an audio or video file to **Recly** |
| iPhone | the import button at the top of the list, or **Import to Recly** in another app's share sheet |
| Mac | **Import audio…** (⌘I) in the **Details** window, or drop files on its list |
| Windows | **Import audio…** in the **Details** window or the tray menu, or drop files on the window |

The file's sound becomes a recording of this device (**IMPORTING** in the list), then uploads and
is transcribed like any other. A video's picture is not kept.

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
- **No On device choice**: iPhone and Mac need iOS or macOS 26 or later and supported hardware;
  Android phones and Windows PCs a 64-bit device with 6 GiB+ reported memory (an 8 GB device in
  practice).
