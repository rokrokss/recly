# Recly Privacy Policy

**Effective date: 2026-10-06**
**Contact: q0115643@gmail.com**

The public URL for the Google OAuth consent screen and app stores is <https://recly.dev/policy/privacy-policy>. The technical basis is `docs/recly.md` §15 (privacy and data flow). [한국어](https://recly.dev/policy/privacy-policy.ko)

---

## 1. Summary

Recly is a **recording app**. Recordings are uploaded to **your own Google Drive** — or, on iPhone and Mac, to **your own iCloud** if you choose it, or, on iPhone, Mac, Windows and the Android phone, copied into **a local folder you pick** — followed by the transcription method you choose in Settings.

**Recly has no servers.** There is no backend, no database, and no account system operated by the developer. The app does not send your recordings, transcripts, settings, or Google account data to the developer. Google, Apple (for iCloud), any transcription provider you choose and — if you run recly-events (§3) — OpenAI process the data sent to them under their own policies and your account agreements.

## 2. What the app handles, and where it lives

| Data | Where it is stored |
|---|---|
| Audio files and metadata (title, timestamps, duration, device name) | Your device, and your own Google Drive after you connect it — or, on iPhone and Mac, your own iCloud if you choose it as storage (§3(1b)), or, on iPhone, Mac, Windows and the Android phone, a local folder you pick (§3(1c)) |
| Processing settings | On your device only (never sent to Drive) |
| Google access and refresh tokens | Your device's secure storage (Android Keystore-backed encrypted storage / Apple Keychain / Windows Credential Manager) |
| Any STT API keys you enter | The same secure storage. **They are not synced between devices and are never sent to Recly** (there is no server to receive them). Only when external transcription is selected, that API key is sent **straight to the provider you chose**, for authentication only (§3(2)) |
| The email address of the Google account selected on Android | **On the device only.** The Android phone keeps it in secure storage to pick the same account again on the next launch. iPhone, Mac and Windows request Drive access without an email or profile scope and do not store an account email. iPhone and Mac remove the previous version’s email hint and profile archive when migrating an existing connection. Disconnecting Drive removes the locally stored connection data |
| Execution state (job queue, retries, upload progress, opaque Drive owner identifier read via the Drive API) and iPhone transfer permissions | A local database on your device; transfer permissions are not included in settings exports |
| Diagnostic logs | Your device's system log. They leave the device only when you export them yourself |

## 3. Every case where data leaves your device

**(1) Your Google Drive.**
The app writes audio parts and `meta.json` into a recording folder. It uses only one permission — `drive.file` (files this app created) — and therefore **cannot see your other Drive files**. These files are yours and are visible only to you unless you share them.

**(1b) Your iCloud — iPhone and Mac only.**
Google Drive is the default. On iPhone and Mac you can choose iCloud instead, under Settings → Storage. New recordings then go to the app's folder in your iCloud Drive, which the Files app and Finder show as "Recly": the same audio parts, `meta.json` and transcripts as in Drive, plus a small `.folder.json` file with the title, the recording's id and its progress marker. The app copies these files into that folder and Apple's system service uploads them; a file counts as uploaded only once iCloud reports it uploaded. Recly makes no network request of its own for iCloud. The files are stored in your own iCloud account under Apple's terms, and the developer has no access to them. Apple states that iCloud Drive is end-to-end encrypted when Advanced Data Protection is turned on.

- iCloud is not available on the Android phone, Galaxy Watch or Windows. An Apple Watch hands its recordings to the iPhone as described in (3) below, and the iPhone uploads them to the storage it uses.
- The choice applies to recordings started after it: a recording already started keeps its storage, and recordings already uploaded are never moved. One exception: if an upload stopped and you retry it after switching, the retry uses the storage chosen now.
- Choosing iCloud does not disconnect Google Drive; **Disconnect Drive** is under Settings → Storage when Google Drive is chosen. There is no in-app disconnect for iCloud: choosing Google Drive again applies to recordings started afterwards, and turning off iCloud Drive for Recly in the system settings makes the remaining iCloud uploads wait.
- If iCloud is unavailable (not signed in, or iCloud Drive turned off for Recly) or out of space, the recording waits on the device and the app says why.
- iCloud uploads are not tied to an Apple ID. If the device signs in to a different Apple ID, recordings still waiting to upload go to that account. Google Drive uploads, by contrast, stay with the Google account they started with.

**(1c) A local folder you pick — iPhone, Mac, Windows and the Android phone.**
You can also choose **Local folder** under Settings → Storage and pick a folder on the device — for example a notes vault, or a folder another tool syncs. New recordings are then copied into that folder: the same audio parts, `meta.json` and transcripts as in Drive, plus the transcript as a Markdown (`.md`) file. Writing there sends nothing anywhere: Recly makes no network request for it, and no Google account is needed. If that folder is synced by another app or service (for example Obsidian Sync, Dropbox, Syncthing, OneDrive or iCloud Drive folders, or a network drive), that service and its terms decide where the files go next; the developer neither knows nor controls that. On iPhone, Recly can reach only the folder you picked in the Files picker, and you can take that access back in Settings → Privacy & Security → Files and Folders. On Android, Recly can reach only the folder you granted through the system folder picker; it does not ask for access to all files.

- The choice works like the others: it applies to recordings started after it, and copies already made are not moved. Choosing another folder sends later recordings there.
- If the folder cannot be reached (none picked, a removed drive, a deleted folder, or on iPhone or Android the access taken back), the recording waits on the device and the app says why.

**(2) An external transcription provider you chose — a third-party AI speech recognition service.**
Processing Settings offers on-device transcription, an external API, or Off. Only external mode, including existing queued external transcription, calls **the provider you selected, directly, with your own key**. Local mode does not fall back to an external provider.

- External transcription sends **the full joined audio file** of each recording to the provider you chose, a **third-party AI speech recognition service that Recly does not operate**. It is used only to transcribe that recording: the transcript comes back to your device and is saved with the recording in your Google Drive or iCloud.
- On iPhone, nothing is sent to a provider until you allow it in the app — see *iPhone permission for external transcription* below.
- There is no intermediary server. The request goes from your device to the provider.
- How long that provider keeps the data and what it does with it is governed by **that provider's policy**, which Recly does not control. Review the provider's privacy policy before selecting the provider.
- If you choose local transcription or Off, no audio or text is sent to an external transcription provider.

The supported providers are listed below. The iPhone app offers eight of them — see *Providers offered on iPhone* below — and availability may also depend on your App Store region. **What is sent is the same whichever one you pick** — one audio track file, and the language and diarization options (the speaker-count hint) that ride on the same request. What happens to it afterwards — retention, training — differs by provider, so read that provider's own policy before you pick it.

| Configured `provider` | Company | Privacy policy | What its API terms say |
|---|---|---|---|
| `assemblyai` | AssemblyAI | <https://www.assemblyai.com/docs/data-retention-and-model-training> | Recly sends every request to AssemblyAI’s EU servers (`api.eu.assemblyai.com`), and AssemblyAI’s documentation states that files submitted to its European servers are not used for model training. Uploaded audio is deleted within 48 hours; transcripts are kept 30 days by default unless a shorter retention is set. |
| `clova` | NAVER Cloud CLOVA Speech | <https://privacy.navercloudcorp.com/en/ncp/PrivacyPolicy/ncp-p> | Its CLOVA Speech terms (<https://www.ncloud.com/policy/terms/clsph>) allow using audio to improve the engine only with the customer’s consent, and keep recognition results and execution logs for 7 days for dispute handling. |
| `rtzr` | Return Zero (RTZR) | <https://developers.rtzr.ai/privacy> | Does not use API audio or transcripts to train its models. Batch audio is deleted after recognition; transcripts are kept up to 3 days. |
| `openai` | OpenAI | <https://developers.openai.com/api/docs/guides/your-data> | Does not use API data for training unless you opt in, and keeps no abuse-monitoring copy for the transcription endpoint. |
| `groq` | Groq | <https://console.groq.com/docs/your-data> | Does not train on API data and keeps none by default; logs are kept up to 30 days only for troubleshooting or abuse investigation. |
| `together` | Together AI | <https://www.together.ai/privacy> | Stores requests and responses by default and may use them for product improvement (training only if you opt in); the account can turn storage off. |
| `mistral` | Mistral AI | <https://legal.mistral.ai/terms/privacy-policy/> | Keeps requests for 30 days for abuse monitoring. Free-mode data may be used for training unless you opt out; the paid default is not stated. |
| `elevenlabs` | ElevenLabs | <https://elevenlabs.io/privacy-policy> | May use data to improve its models by default unless you turn off “Improve the models for everyone” in your account’s Data use settings, which applies to data sent afterwards; keeps request history until you delete it. On iPhone, Recly lets you allow ElevenLabs only after you confirm that setting is off. |
| `deepgram` | Deepgram | <https://developers.deepgram.com/trust-security/your-data> | Keeps audio and transcripts to improve its models unless a request opts out. Recly opts out on every request, so Deepgram keeps them only while processing the request and does not use them for training. |
| `azure` | Microsoft Azure AI Speech | <https://learn.microsoft.com/en-us/azure/foundry/responsible-ai/speech-service/speech-to-text/data-privacy-security> | Does not use customer data to train its speech models and does not store audio for fast transcription; processes the data only to provide the service. |
| `daglo` | Daglo | <https://developers.daglo.ai/privacy> | Its API terms allow storing API input and using it for quality and performance improvement; audio is kept for 3 months. No opt-out is stated. |
| `speechmatics` | Speechmatics | <https://www.speechmatics.com/legal/privacy-policy> | Uses data for model improvement only if the account opts in, although its terms reserve a licence over transcripts; batch audio and transcripts are deleted after 7 days. |
| `rev` | Rev AI | <https://www.rev.com/legal/privacy> | May use content to train Rev’s own speech models by default (not generative AI); jobs are kept up to 30 days. No API opt-out was found. |
| `gladia` | Gladia | <https://www.gladia.io/privacy-notice> | Keeps data up to 12 months by default. Its documents differ on training; free and Starter plan data may be used for training. |

**How providers protect the audio (checked 2026-09-29).** Recly’s commitments in this policy cover Recly: it does not collect, sell or use your recordings. Once you allow a provider, that provider receives the audio under its own API terms, summarized above from its official pages. Provider terms change; the linked pages are authoritative.

**Providers offered on iPhone.** The iPhone app offers only providers that keep your recordings out of model training, and Recly confirms that they provide the same or equal protection of your recordings as this policy: each receives the audio only to transcribe it at your request, under its own privacy and security terms, and does not use it to train its models —

- OpenAI, Groq, Microsoft Azure AI Speech and Return Zero (RTZR), by default;
- Deepgram, through the opt-out Recly sends with every request;
- AssemblyAI, through its EU servers, which Recly uses for every request;
- NAVER CLOVA Speech, whose terms allow using audio to improve the engine only with the customer’s consent;
- ElevenLabs, once model training is turned off in your ElevenLabs account — the iPhone asks you to confirm this, and links to ElevenLabs’ instructions, before you can allow ElevenLabs.

Together AI, Mistral AI, Daglo, Speechmatics, Rev AI and Gladia are not offered on iPhone. They remain in the Android, Mac and Windows apps, where, as the table shows, some may keep the audio or use it to improve their models unless you change your account settings; read a provider’s entry before choosing it there. A custom endpoint you enter for OpenAI or Groq is run by whoever operates it; the iPhone permission screen shows it and asks you to check that operator’s policy. You can withdraw permission in Settings → Privacy at any time.

**(3) Your own paired devices — between watch and phone.**
When you record on a Galaxy Watch or an Apple Watch, the **audio files and their metadata** (title, timestamps, duration, checksums) move to the paired phone, because the watch records and transfers while the phone handles upload and transcription. **Transfer does not depend on transcription settings.** In the other direction the phone sends only small control messages: receipt confirmations and, to an Apple Watch, the app’s language setting. Processing settings are never sent to the watch.

- The transport is the operating system's device-pairing channel (the Wear OS Data Layer, Apple's WatchConnectivity). No Recly server is involved; none exists.
- **Both devices are yours.** Recly runs no server that relays this transfer. The channel itself belongs to the operating system, though: recordings are only sent while the two devices are near each other (on both Wear OS and Apple), but small items such as receipt confirmations may be relayed through Google Play services' infrastructure when the devices are apart — that handling is governed by Google's privacy policy.
- Once the phone confirms receipt, the watch deletes its own copy — no recording history accumulates on the watch.
- API keys and tokens are never sent over this path.

Speech model downloads and the StoreKit region lookup are described below. Recly adds no developer-operated endpoint for these features.

### iPhone permission for external transcription

When you save External API in **Settings → Recording processing** with a provider you have not allowed on this iPhone, the app first asks **“Send recordings to {provider}?”** It names the provider as a third-party AI speech recognition service, shows its endpoint, what is sent (the full audio of each recording, with the language and speaker options) and why (transcription), links to the provider’s privacy policy and says how to withdraw. For ElevenLabs it also says that ElevenLabs uses recordings to improve its models unless this is turned off in your ElevenLabs account, links to ElevenLabs’ instructions, and keeps **Allow & save** unavailable until you confirm you have turned it off. **Don’t allow** leaves the settings unsaved; **Allow & save** saves them. Nothing is sent to a provider you have not allowed. The question comes with that save, once per provider and endpoint — not while you record. If permission is missing later, for example after you withdraw it, recordings (including those received from Apple Watch) wait, and **Settings → Privacy** lets you allow the transfer again. Recording itself and local transcription do not require this permission. A provider not offered on iPhone cannot be saved there, including from an imported settings file, and a recording queued for one stops with a message saying so.

Permission is remembered for the same provider and configured endpoint, independently of API keys. Changing the provider or destination, withdrawing permission, using a new device, or materially changing the data or purpose requires permission again. Permission is not exported with settings. On iPhone it is bound to a device-only Keychain marker, so restoring the database onto a different phone does not restore permission. **Withdraw permission** stops subsequent requests, including transcription status queries; an already dispatched request may finish. It does not delete data already sent or stored API keys. Google Drive access uses its separate Google authorization flow. Other platforms use their processing settings flow.

**App Store region.** On iPhone and iPad, Recly reads the current App Store country or region through Apple StoreKit to determine whether the OpenAI transcription integration is available. This integration is disabled for the China mainland storefront. If the region cannot be verified, affected transcription waits. Recly does not send audio, transcripts, processing settings or third-party API keys as part of this StoreKit lookup, and does not store the storefront country on disk. Apple manages the underlying account and storefront service. Local recording and Google Drive upload are independent of this check.

### On-device transcription

On iPhone and Mac with iOS or macOS 26 or later, new recording settings default to local transcription, and Apple Speech analyzes audio on the device. On Android phones and Windows PCs with enough memory (about 8 GB or more), they default to local transcription with the open Qwen3-ASR model, which runs in the app on the device. Other devices have no on-device engine, so their new settings start with transcription off. Downloading a missing model is always your action — in Settings, on a recording waiting for the model, or on a one-time prompt; until then, recordings wait instead of being transcribed: on Apple devices Apple’s system service downloads the model assets; on Android and Windows the app downloads the model files (about 1 GB) from Hugging Face and GitHub. That download sends no recording, transcript, setting or key — those hosts see your IP address and which public files were requested — and each file is checked against a fixed checksum before use. Recly does not send your recording to an external speech API for this local analysis. Original audio and finished transcripts still upload to your Google Drive or iCloud as separate steps in the recording flow.

On those devices, choose an external provider if you want transcripts. Recly never silently switches local transcription to a paid or cloud API, and external transcription sends audio only to the provider you selected. Processing settings and API keys remain on the device; exported settings contain key references, not key values.

### recly-events, an optional program you run yourself

`recly-events` is a program from the Recly project that tells your ChatGPT agent (a dot or a Work chat) about each new transcript. You can run it yourself, or turn it on in the Mac or Windows app under Settings → Agent connection, which runs the copy the app includes. It is off by default, and the phone and watch apps do not include it. Nothing in this subsection happens unless you run it or turn it on.

- **Google Drive, metadata only.** It signs in with the same Google authorization as the Recly apps (`drive.file`), so Google shows it only the files Recly created. About every 10 seconds it asks Drive what changed and reads the names, IDs, links and folder descriptions (recording titles) of those files. Once per sign-in it also reads the Drive account's opaque identifier — not its name or email — so the Mac or Windows app can tell whether it is the account the app uploads to. It never downloads a recording or a transcript. If you set it up with a Google client of your own instead, Google lets it see the metadata (not the contents) of every file in your Drive (`drive.metadata.readonly`); it ignores everything except Recly's transcripts and their folders.
- **Your ChatGPT account.** When a new transcript appears, it sends a notice — the recording's name, title, start time, device type, and Drive file IDs and links — to your ChatGPT account through OpenAI's Secure MCP Tunnel and the event address ChatGPT gave it. Your agent then reads the transcript from your Drive with ChatGPT's own Google Drive connector; that processing is governed by OpenAI's terms and privacy policy and your ChatGPT settings.
- **Nothing reaches the developer.** It runs on your computer and talks only to Google and OpenAI. Its settings, its Google token and that account identifier, your OpenAI tunnel key and its list of notices stay in its own folder on that computer, readable only by your user account.
- **Stopping it.** Turn off Settings → Agent connection, or run `recly-events service uninstall`, and delete its folder, then remove the tunnel and key in your OpenAI Platform settings and the app in ChatGPT. Disconnecting Google Drive in any Recly app also ends recly-events' Google access.

## 4. What is not collected

- No analytics, usage statistics, or behavioral logging.
- No automatic crash reporting.
- No advertising identifiers and no ads.
- No Recly account: no sign-up, and no email or profile data reaching the developer. iPhone, Mac and Windows use OAuth to request only `drive.file`, without requesting identity scopes (`openid`, `email`, `profile`). Android uses Google account selection and stores the selected email locally before requesting Drive access, as described in §2. Migrating an existing iPhone or Mac connection removes local profile data; it does not revoke permissions previously granted to Google. Disconnecting Drive revokes the Google authorization.
- **The developer (Recly) collects nothing about you and sells, shares, or transfers nothing to third parties** — there is no data in the developer's hands to begin with. What does happen is **the transfers you direct**: as set out in §3, to your own Google Drive (or, on iPhone and Mac, your own iCloud), to the STT provider you chose, between your own paired devices (watch ↔ phone), and — if you run recly-events — notices to your own ChatGPT account. Those happen because you asked for them; they are not the developer handing your data to a third party.

## 5. Limited Use of Google user data

Recly's use and transfer of information received from Google APIs adheres to the **Google API Services User Data Policy**, including the Limited Use requirements. Drive data is used only to provide the features you requested (uploading, listing and retrieving your recordings and transcripts, and — in recly-events, if you run it — telling the ChatGPT agent you connected that a new transcript exists), is never used for advertising, and is not read by humans — there is no server that could read it.

## 6. Security

- API keys and tokens are stored in the operating system's secure storage (Android Keystore-backed encrypted storage, Apple Keychain, Windows Credential Manager).
- All outbound communication uses HTTPS.
- On **Android, iPhone and Apple Watch**, recordings live in the per-app area the operating system isolates (the app container) and other apps cannot reach them.
- **macOS and Windows have no such isolation.** The Mac app is distributed directly and therefore does not run inside a sandbox; its files are in `~/Library/Application Support/app.recly.mac/`, and on Windows in `%LOCALAPPDATA%\Recly\` — **other programs running under the same user account can read them.** What protects them there is the operating system's user-account permissions (file access control) and, if you have turned it on, disk encryption (FileVault on macOS, BitLocker on Windows). API keys and tokens are not kept with those files; they are in the Keychain and Credential Manager instead.
- **If you choose iCloud** (iPhone and Mac), the uploaded copy is also in the Recly folder of your iCloud Drive, outside the app container: you can open it in the Files app or Finder, and on a Mac it is at `~/Library/Mobile Documents/iCloud~app~recly/Documents/`.
- **If you choose a local folder** (iPhone, Mac, Windows and the Android phone), the copies are wherever you put them, outside the app's own area: whatever can read that folder — other programs under your user account, other apps you granted it, a sync service you use for it — can read them.
- No secure storage is complete without device-level security. Please use a device lock and disk encryption.

## 7. Retention and deletion

- **Automatic local cache cleanup**: after every job for a recording has completed and all its audio has been uploaded (for iCloud, once iCloud reports it uploaded), local audio is eligible for cleanup after seven days, measured from the later of the last job update or newest cached audio file. Audio fetched again for playback starts a new cache window. Recordings with unfinished, failed or permission-blocked jobs retain their audio. Metadata and transcript copies remain until you delete the recording. Drive, iCloud and local folder copies are not removed by this cleanup.
- **Deleting a recording**: all four apps (Android phone, iPhone, Mac, Windows) can delete a recording from the list. Each time, a confirmation dialog **asks what to do about Drive, and the default is to keep it there** — the irreversible choice is never the default. If some parts have not reached Drive yet, the dialog says how many first. For a recording stored in iCloud (iPhone and Mac), the same question is about its iCloud folder, again with keeping it as the default. Deleting the iCloud folder removes it from every device. For a recording stored in a local folder, the question is whether to delete it from that folder, again with keeping it as the default.
- **What a deletion removes**: that device's whole recording folder (audio files, `meta.json`, local transcript copies) and **every record of that recording** — not only the recording and part records but the **job records** too (`job` and `step_run`: the copy of the processing plan that recording ran, the per-step execution state, failure messages, upload and transcription progress, and step outputs). Those are what an earlier build left sitting in the database, invisible in the list; they no longer stay behind. Choosing "also delete the Drive folder" — or "the iCloud folder", or "from the local folder" — removes that recording's folder there as well.
- **A recording that is being processed is not deleted**: if one of its jobs is running, the deletion is refused with "try again once it has finished". If Drive, iCloud or the local folder refuses the folder deletion, the files on your device are still removed and the app tells you so.
- **Disconnect Drive**: open Settings → Google Drive (on iPhone and Mac: Settings → Storage) → Disconnect Drive and confirm. It revokes Google authorization and clears this device's Google credentials, completed job records and cached Drive references. Unfinished work and its Drive owner identifier stay on your device so the same account can resume it without repeating successful steps. Work for a different account stays paused. **Recordings, Drive files, processing settings, API keys and iPhone transfer permissions remain.** Delete recordings separately from the recording list.
  - Revocation removes Recly's Drive authorization for this Google account across all clients in the same Google Cloud project. Other devices using this account must reconnect Recly to use Drive again. Google can take time to apply the revocation; this does not immediately sign out the interface on every device. Drive work on this device pauses until the same account reconnects. If Google authorization could not be revoked, Settings shows a link to Google permission settings (<https://myaccount.google.com/permissions>), including after this device has disconnected locally. The link is hidden when no revocation failure remains.
  - **If revocation fails, the app says so** and keeps a route to Google permission settings. If local cleanup fails, Google Drive settings offer a retry.

- **Copies held by an STT provider**: Recly cannot delete these for you. Contact that provider directly.

### What survives uninstalling the app

| Platform | Uninstalling | What is left, and how to remove it |
|---|---|---|
| Android phone · Galaxy Watch | **App data goes with it** — recordings, the local database and the encrypted store holding tokens and API keys all live in the app's private area and the OS removes them with the app | Nothing. Files in Drive, or in a local folder you picked, stay, because they are yours |
| iPhone · Apple Watch | The app container (recordings, database, settings and iPhone transfer permissions) is removed | Keychain items can persist after uninstalling. On iPhone, **delete each API key from Settings → Recording processing → External API → API keys, then revoke Google access before uninstalling**. Revoking Google access removes Google credentials but does not delete those keys. Recly’s own secret items are device-only. A non-credential permission-binding marker (`app.recly.privacy`) may also remain; without the deleted local permission records it grants no access. The watch stores an install identifier, with no sign-in or API-key entry; it has no in-app control for deleting that Keychain identifier. If you chose iCloud, the Recly folder in your iCloud Drive is not deleted with the app: delete Recly's data in Settings → [your name] → iCloud → Storage (or Manage Account Storage), or delete the folder in the Files app, where deleted files stay in Recently Deleted for 30 days. If you chose a local folder, the recordings copied there stay where they are |
| macOS | **Only the `.app` bundle is removed** | (1) Delete `~/Library/Application Support/app.recly.mac/` (recordings, `rec.db`, `device.id`) yourself. (2) In Keychain Access delete the items whose service is `app.recly.mac.secrets` (the API keys you entered) plus `app.recly.drive.oauth` (the Google tokens) and any legacy `auth` item created by the previous Google Sign-In SDK. (3) **App settings stay in `UserDefaults`** — recording mode, the consent reminder, language and accessibility settings, and the path of a local folder you picked. An older version may also have left an email hint (`app.recly.auth.lastAccount`) before migration. Clear them with `defaults delete app.recly.mac` in Terminal. (4) If you chose iCloud, the Recly folder in your iCloud Drive is not deleted with the app: delete Recly's data in System Settings → [your name] → iCloud → Manage, or delete the folder in Finder. (5) If you chose a local folder, the recordings copied there stay where they are. (6) If you turned on Agent connection, delete `~/Library/Application Support/recly-events` (its Google token, tunnel key and list of notices) |
| Windows | **Only the installed files are removed** | (1) Delete `%LOCALAPPDATA%\Recly\` (recordings, `rec.db`, `device.id`) yourself. (2) In Credential Manager → Windows Credentials delete the `app.recly.windows/tokens/…` and `app.recly.windows/secrets/…` entries. (3) **App settings stay in the registry** — the consent reminder, language, theme and accessibility settings and the path of a local folder you picked live under `HKCU\Software\JavaSoft\Prefs\app\recly\windows`; delete that key in Registry Editor. The Windows app stores no account email, so there is none to remove. (4) If you chose a local folder, the recordings copied there stay where they are. (5) If you turned on Agent connection, delete `%AppData%\recly-events` (its Google token, tunnel key and list of notices) |

### Clearing it from inside the app first

Before uninstalling, delete each saved API key from Settings → Recording processing → External API → API keys, then use **Disconnect Drive** in Settings → Google Drive (on iPhone and Mac: Settings → Storage) to clear Google credentials. Unfinished job records remain until you delete their recordings. To remove recordings too, delete individual recordings from the list. To stop future third-party transfers on iPhone, use Settings → Privacy → Withdraw permission. Processing settings and desktop data folders remain until separately removed as described above. Data already held by Drive, iCloud or a transcription provider is managed separately with that service.

## 8. Your responsibility when recording

Recly does not, and cannot, automatically notify other participants that a recording is taking place. Laws about recording conversations differ by country and region — for example, Korea permits recording a conversation you are part of but criminalizes recording conversations between others, while several U.S. states and the EU require all-party consent or prior notice. **It is your responsibility to know the law that applies to you and to obtain any consent required.** Nothing here is legal advice.

So the app asks you once, before a recording starts, **whether you told the participants.** That reminder is in **all four apps — Mac, Windows, iPhone and the Android phone** — with the same question, the same body text and the same link to a summary of the rules by jurisdiction. Without a confirmation the recording does not start; choosing "Do not ask again", or switching the setting off, stops it asking, and you can switch it back on in Settings.

- **When it appears differs by device.** The Mac asks for each recording it starts from a detected meeting; Windows asks for every recording. The phones (iPhone and Android) have no way to tell a meeting from anything else, so they ask **once, before the first recording**, and the setting text says so.
- **The Galaxy Watch and Apple Watch do not show it** — their screens are small and the paired phone is the reference. Your responsibility when recording from a watch is the same.
- The reminder does not determine your jurisdiction, does not notify participants for you, and pressing "I told them" is not legal evidence. The Google consent screen shown when you connect your account is **a different consent** — your own consent to Drive access.

## 9. Children

Recly is not directed at children and does not knowingly collect personal information from them — it does not collect personal information at all.

## 10. Changes to this policy

If this policy changes, the effective date above is updated and the change is kept in the repository history. Any change that creates a new path for data to leave the device is also announced inside the app.

## 11. Contact

q0115643@gmail.com
