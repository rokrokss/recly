# Recly Privacy Policy

**Effective date: 2026-09-26**
**Contact: q0115643@gmail.com**

The public URL for the Google OAuth consent screen and app stores is <https://recly.dev/policy/privacy-policy>. The technical basis is `docs/recly.md` §15 (privacy and data flow). [한국어](https://recly.dev/policy/privacy-policy.ko)

---

## 1. Summary

Recly is a **recording app**. Recordings are uploaded to **your own Google Drive**, followed by the transcription method you choose in Settings.

**Recly has no servers.** There is no backend, no database, and no account system operated by the developer. The app does not send your recordings, transcripts, settings, or Google account data to the developer. Google and any transcription provider you choose process the data sent to them under their own policies and your account agreements.

## 2. What the app handles, and where it lives

| Data | Where it is stored |
|---|---|
| Audio files and metadata (title, timestamps, duration, device name) | Your device, and your own Google Drive after you connect it |
| Processing settings | On your device only (never sent to Drive) |
| Google access and refresh tokens | Your device's secure storage (Android Keystore-backed encrypted storage / Apple Keychain / Windows Credential Manager) |
| Any STT API keys you enter | The same secure storage. **They are not synced between devices and are never sent to Recly** (there is no server to receive them). Only when external transcription is selected, that API key is sent **straight to the provider you chose**, for authentication only (§3(2)) |
| The email address of the Google account selected on Android | **On the device only.** The Android phone keeps it in secure storage to pick the same account again on the next launch. iPhone, Mac and Windows request Drive access without an email or profile scope and do not store an account email. iPhone and Mac remove the previous version’s email hint and profile archive when migrating an existing connection. Disconnecting Drive removes the locally stored connection data |
| Execution state (job queue, retries, upload progress, opaque Drive owner identifier read via the Drive API) and iPhone transfer permissions | A local database on your device; transfer permissions are not included in settings exports |
| Diagnostic logs | Your device's system log. They leave the device only when you export them yourself |

## 3. Every case where data leaves your device

**(1) Your Google Drive.**
The app writes audio parts and `meta.json` into a recording folder. It uses only one permission — `drive.file` (files this app created) — and therefore **cannot see your other Drive files**. These files are yours and are visible only to you unless you share them.

**(2) An external transcription provider you chose — a third-party AI speech recognition service.**
Processing Settings offers on-device transcription, an external API, or Off. Only external mode, including existing queued external transcription, calls **the provider you selected, directly, with your own key**. Local mode does not fall back to an external provider.

- External transcription sends **the full joined audio file** of each recording to the provider you chose, a **third-party AI speech recognition service that Recly does not operate**. It is used only to transcribe that recording: the transcript comes back to your device and is saved with the recording in your Google Drive.
- On iPhone, nothing is sent to a provider until you allow it in the app — see *iPhone permission for external transcription* below.
- There is no intermediary server. The request goes from your device to the provider.
- How long that provider keeps the data and what it does with it is governed by **that provider's policy**, which Recly does not control. Review the provider's privacy policy before selecting the provider.
- If you choose local transcription or Off, no audio or text is sent to an external transcription provider.

The supported providers are listed below; availability may depend on your App Store region. **What is sent is the same whichever one you pick** — one audio track file, and the language and diarization options (the speaker-count hint) that ride on the same request. What happens to it afterwards — retention, training — differs by provider, so read that provider's own policy before you pick it.

| Configured `provider` | Company | Policy |
|---|---|---|
| `assemblyai` | AssemblyAI | <https://www.assemblyai.com/> |
| `clova` | NAVER Cloud CLOVA Speech | <https://www.ncloud.com/> |
| `rtzr` | Return Zero (RTZR) | <https://www.rtzr.ai/> |
| `openai` | OpenAI | <https://openai.com/> |
| `groq` | Groq | <https://groq.com/> |
| `together` | Together AI | <https://www.together.ai/> |
| `mistral` | Mistral AI | <https://mistral.ai/> |
| `elevenlabs` | ElevenLabs | <https://elevenlabs.io/> |
| `deepgram` | Deepgram | <https://deepgram.com/> |
| `azure` | Microsoft Azure AI Speech | <https://azure.microsoft.com/> |
| `daglo` | Daglo | <https://daglo.ai/> |
| `speechmatics` | Speechmatics | <https://www.speechmatics.com/> |
| `rev` | Rev AI | <https://www.rev.ai/> |
| `gladia` | Gladia | <https://www.gladia.io/> |

**(3) Your own paired devices — between watch and phone.**
When you record on a Galaxy Watch or an Apple Watch, the **audio files and their metadata** (title, timestamps, duration, checksums) move to the paired phone, because the watch records and transfers while the phone handles upload and transcription. **Transfer does not depend on transcription settings.** In the other direction the phone sends only small control messages: receipt confirmations and, to an Apple Watch, the app’s language setting. Processing settings are never sent to the watch.

- The transport is the operating system's device-pairing channel (the Wear OS Data Layer, Apple's WatchConnectivity). No Recly server is involved; none exists.
- **Both devices are yours.** Recly runs no server that relays this transfer. The channel itself belongs to the operating system, though: recordings are only sent while the two devices are near each other (on both Wear OS and Apple), but small items such as receipt confirmations may be relayed through Google Play services' infrastructure when the devices are apart — that handling is governed by Google's privacy policy.
- Once the phone confirms receipt, the watch deletes its own copy — no recording history accumulates on the watch.
- API keys and tokens are never sent over this path.

Apple model downloads and the StoreKit region lookup are described below. Recly adds no developer-operated endpoint for these features.

### iPhone permission for external transcription

When you save External API in **Settings → Recording processing** with a provider you have not allowed on this iPhone, the app first asks **“Send recordings to {provider}?”** It names the provider as a third-party AI speech recognition service, shows its endpoint, what is sent (the full audio of each recording, with the language and speaker options) and why (transcription), links to the provider’s privacy policy and says how to withdraw. **Don’t allow** leaves the settings unsaved; **Allow & save** saves them. Nothing is sent to a provider you have not allowed. The question comes with that save, once per provider and endpoint — not while you record. If permission is missing later, for example after you withdraw it, recordings (including those received from Apple Watch) wait, and **Settings → Privacy** lets you allow the transfer again. Recording itself and local transcription do not require this permission.

Permission is remembered for the same provider and configured endpoint, independently of API keys. Changing the provider or destination, withdrawing permission, using a new device, or materially changing the data or purpose requires permission again. Permission is not exported with settings. On iPhone it is bound to a device-only Keychain marker, so restoring the database onto a different phone does not restore permission. **Withdraw permission** stops subsequent requests, including transcription status queries; an already dispatched request may finish. It does not delete data already sent or stored API keys. Google Drive access uses its separate Google authorization flow. Other platforms use their processing settings flow.

**App Store region.** On iPhone and iPad, Recly reads the current App Store country or region through Apple StoreKit to determine whether the OpenAI transcription integration is available. This integration is disabled for the China mainland storefront. If the region cannot be verified, affected transcription waits. Recly does not send audio, transcripts, processing settings or third-party API keys as part of this StoreKit lookup, and does not store the storefront country on disk. Apple manages the underlying account and storefront service. Local recording and Google Drive upload are independent of this check.

### On-device transcription

On iPhone and Mac with iOS or macOS 26 or later, new recording settings default to local transcription, and Apple Speech analyzes audio on the device. Other devices have no on-device engine yet, so their new settings start with transcription off. Preparing a missing language model is a separate action in Settings; Apple’s system service downloads the model assets. Recly does not send your recording to an external speech API for this local analysis. Original audio and finished transcripts still upload to your Google Drive as separate steps in the recording flow.

On those devices, choose an external provider if you want transcripts. Recly never silently switches local transcription to a paid or cloud API, and external transcription sends audio only to the provider you selected. Processing settings and API keys remain on the device; exported settings contain key references, not key values.

## 4. What is not collected

- No analytics, usage statistics, or behavioral logging.
- No automatic crash reporting.
- No advertising identifiers and no ads.
- No Recly account: no sign-up, and no email or profile data reaching the developer. iPhone, Mac and Windows use OAuth to request only `drive.file`, without requesting identity scopes (`openid`, `email`, `profile`). Android uses Google account selection and stores the selected email locally before requesting Drive access, as described in §2. Migrating an existing iPhone or Mac connection removes local profile data; it does not revoke permissions previously granted to Google. Disconnecting Drive revokes the Google authorization.
- **The developer (Recly) collects nothing about you and sells, shares, or transfers nothing to third parties** — there is no data in the developer's hands to begin with. What does happen is **the transfers you direct**: as set out in §3, to your own Google Drive, to the STT provider you chose, and between your own paired devices (watch ↔ phone). Those happen because you asked for them; they are not the developer handing your data to a third party.

## 5. Limited Use of Google user data

Recly's use and transfer of information received from Google APIs adheres to the **Google API Services User Data Policy**, including the Limited Use requirements. Drive data is used only to provide the features you requested (uploading, listing and retrieving your recordings and transcripts), is never used for advertising, and is not read by humans — there is no server that could read it.

## 6. Security

- API keys and tokens are stored in the operating system's secure storage (Android Keystore-backed encrypted storage, Apple Keychain, Windows Credential Manager).
- All outbound communication uses HTTPS.
- On **Android, iPhone and Apple Watch**, recordings live in the per-app area the operating system isolates (the app container) and other apps cannot reach them.
- **macOS and Windows have no such isolation.** The Mac app is distributed directly and therefore does not run inside a sandbox; its files are in `~/Library/Application Support/app.recly.mac/`, and on Windows in `%LOCALAPPDATA%\Recly\` — **other programs running under the same user account can read them.** What protects them there is the operating system's user-account permissions (file access control) and, if you have turned it on, disk encryption (FileVault on macOS, BitLocker on Windows). API keys and tokens are not kept with those files; they are in the Keychain and Credential Manager instead.
- No secure storage is complete without device-level security. Please use a device lock and disk encryption.

## 7. Retention and deletion

- **Automatic local cache cleanup**: after every job for a recording has completed and all its audio has been uploaded, local audio is eligible for cleanup after seven days, measured from the later of the last job update or newest cached audio file. Audio fetched again for playback starts a new cache window. Recordings with unfinished, failed or permission-blocked jobs retain their audio. Metadata and transcript copies remain until you delete the recording. Drive copies are not removed by this cleanup.
- **Deleting a recording**: all four apps (Android phone, iPhone, Mac, Windows) can delete a recording from the list. Each time, a confirmation dialog **asks what to do about Drive, and the default is to keep it there** — the irreversible choice is never the default. If some parts have not reached Drive yet, the dialog says how many first.
- **What a deletion removes**: that device's whole recording folder (audio files, `meta.json`, local transcript copies) and **every record of that recording** — not only the recording and part records but the **job records** too (`job` and `step_run`: the copy of the processing plan that recording ran, the per-step execution state, failure messages, upload and transcription progress, and step outputs). Those are what an earlier build left sitting in the database, invisible in the list; they no longer stay behind. Choosing "also delete the Drive folder" removes that recording's Drive folder as well.
- **A recording that is being processed is not deleted**: if one of its jobs is running, the deletion is refused with "try again once it has finished". If Drive refuses the folder deletion, the files on your device are still removed and the app tells you so.
- **Disconnect Drive**: open Settings → Google Drive → Disconnect Drive and confirm. It revokes Google authorization and clears this device's Google credentials, completed job records and cached Drive references. Unfinished work and its Drive owner identifier stay on your device so the same account can resume it without repeating successful steps. Work for a different account stays paused. **Recordings, Drive files, processing settings, API keys and iPhone transfer permissions remain.** Delete recordings separately from the recording list.
  - Revocation removes Recly's Drive authorization for this Google account across all clients in the same Google Cloud project. Other devices using this account must reconnect Recly to use Drive again. Google can take time to apply the revocation; this does not immediately sign out the interface on every device. Drive work on this device pauses until the same account reconnects. If Google authorization could not be revoked, Settings shows a link to Google permission settings (<https://myaccount.google.com/permissions>), including after this device has disconnected locally. The link is hidden when no revocation failure remains.
  - **If revocation fails, the app says so** and keeps a route to Google permission settings. If local cleanup fails, Google Drive settings offer a retry.

- **Copies held by an STT provider**: Recly cannot delete these for you. Contact that provider directly.

### What survives uninstalling the app

| Platform | Uninstalling | What is left, and how to remove it |
|---|---|---|
| Android phone · Galaxy Watch | **App data goes with it** — recordings, the local database and the encrypted store holding tokens and API keys all live in the app's private area and the OS removes them with the app | Nothing. Files in Drive stay, because they are yours |
| iPhone · Apple Watch | The app container (recordings, database, settings and iPhone transfer permissions) is removed | Keychain items can persist after uninstalling. On iPhone, **delete each API key from Settings → Recording processing → External API → API keys, then revoke Google access before uninstalling**. Revoking Google access removes Google credentials but does not delete those keys. Recly’s own secret items are device-only. A non-credential permission-binding marker (`app.recly.privacy`) may also remain; without the deleted local permission records it grants no access. The watch stores an install identifier, with no sign-in or API-key entry; it has no in-app control for deleting that Keychain identifier |
| macOS | **Only the `.app` bundle is removed** | (1) Delete `~/Library/Application Support/app.recly.mac/` (recordings, `rec.db`, `device.id`) yourself. (2) In Keychain Access delete the items whose service is `app.recly.mac.secrets` (the API keys you entered) plus `app.recly.drive.oauth` (the Google tokens) and any legacy `auth` item created by the previous Google Sign-In SDK. (3) **App settings stay in `UserDefaults`** — recording mode, the consent reminder, language and accessibility settings. An older version may also have left an email hint (`app.recly.auth.lastAccount`) before migration. Clear them with `defaults delete app.recly.mac` in Terminal |
| Windows | **Only the installed files are removed** | (1) Delete `%LOCALAPPDATA%\Recly\` (recordings, `rec.db`, `device.id`) yourself. (2) In Credential Manager → Windows Credentials delete the `app.recly.windows/tokens/…` and `app.recly.windows/secrets/…` entries. (3) **App settings stay in the registry** — the consent reminder, language, theme and accessibility settings live under `HKCU\Software\JavaSoft\Prefs\app\recly\windows`; delete that key in Registry Editor. The Windows app stores no account email, so there is none to remove |

### Clearing it from inside the app first

Before uninstalling, delete each saved API key from Settings → Recording processing → External API → API keys, then use **Disconnect Drive** in Settings → Google Drive to clear Google credentials. Unfinished job records remain until you delete their recordings. To remove recordings too, delete individual recordings from the list. To stop future third-party transfers on iPhone, use Settings → Privacy → Withdraw permission. Processing settings and desktop data folders remain until separately removed as described above. Data already held by Drive or a transcription provider is managed separately with that service.

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
