# Recly Privacy Policy

> **Status: DRAFT — not in force.** The effective date below is rewritten on the day this policy takes effect.
>
> **The implementation prerequisites are met.** §7's recording deletion (job records included) and in-app disconnect, and §8's consent reminder on all four shells, are really in the app. What is left before publication is not code but two human items — (1) confirming the per-provider retention policy URLs in `docs/recly.md` §15 §3 (until then the in-app disclosure carries no link either), and (2) a legal review (§8's jurisdiction summary is a web summary, not legal advice — `docs/recly.md` §열린 결정).

**Effective date: 2026-08-29**
**Contact: q0115643@gmail.com**

This is the public URL the Google OAuth consent screen and the app stores point at. The technical basis is `docs/recly.md` §15 (privacy and data flow). [한국어](privacy-policy.ko.md)

---

## 1. Summary

Recly is a **recording app**. Recordings are uploaded to **your own Google Drive**, and what happens next is decided by the workflow you build.

**Recly has no servers.** There is no backend, no database, and no account system operated by the developer. As a result the developer **cannot collect, store, or see** your recordings, transcripts, workflows, or Google account data.

## 2. What the app handles, and where it lives

| Data | Where it is stored |
|---|---|
| Audio files and metadata (title, timestamps, duration, device name) | Your device, and your own Google Drive if you added an upload step |
| Workflow definitions | On your device only (never sent to Drive) |
| Google access and refresh tokens | Your device's secure storage (Android Keystore-backed encrypted storage / Apple Keychain / Windows Credential Manager) |
| Webhook signing keys and any STT API keys you enter | The same secure storage. **They are not synced between devices and are never sent to Recly** (there is no server to receive them). Only if you added a transcription step, that API key is sent **straight to the provider you chose**, for authentication only (§3(3)). A webhook signing key is never sent at all — it is only used to compute the signature |
| The email address of the Google account you signed in with | **On the device only.** The Android phone keeps it in secure storage to pick the same account again on the next launch; iPhone and Mac keep the value held by the Google Sign-In SDK as a hint that prefills the next sign-in. Windows stores none. Signing out removes it |
| Execution state (job queue, retries, upload progress) | A local database on your device |
| Diagnostic logs | Your device's system log. They leave the device only when you export them yourself |

## 3. Every case where data leaves your device

**(1) Your Google Drive.**
The app writes audio parts and `meta.json` into a recording folder. It uses only one permission — `drive.file` (files this app created) — and therefore **cannot see your other Drive files**. These files are yours and are visible only to you unless you share them.

**(2) A webhook address you configured.**
Only when you add a `webhook` step and enter a URL, the app sends one notification to that address. The body contains recording metadata and Drive file links; it does **not** contain the audio itself or the transcript text.

- **Requests are signed only if you configured a signing secret.** With a secret set, the request carries an HMAC-SHA256 (Standard Webhooks) signature header; without one it is sent **unsigned**.

That endpoint is operated by you, and what happens there is your responsibility.

**(3) A transcription provider you chose — only if you added that step.**
Transcription (STT) is **an optional step you may put into your workflow, not a fixed processing stage**. Only when you add such a step and enter your own API key does the device call **the provider you selected, directly, with your key**.

- Transcription step: **the full audio file** is sent to the STT provider you chose.
- There is no intermediary server. The request goes from your device to the provider.
- How long that provider keeps the data and what it does with it is governed by **that provider's policy**, which Recly does not control. Review the provider's privacy policy before adding the step.
- If you do not add this step, no audio or text is ever sent to that provider.

These are the fourteen providers you can choose from. **What is sent is the same whichever one you pick** — one audio track file, and the language and diarization options (the speaker-count hint) that ride on the same request. What happens to it afterwards — retention, training — differs by provider, so read that provider's own policy before you pick it.

| `provider` in the workflow | Company | Policy |
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

**(4) Your own paired devices — between watch and phone.**
When you record on a Galaxy Watch or an Apple Watch, the **audio files and their metadata** (title, timestamps, duration, checksums) move to the paired phone, because the watch neither uploads nor runs workflows. **This transfer happens even when your workflow has no Drive upload step at all.** In the other direction, the phone sends the watch a **workflow summary** — the id and name of each workflow, so the watch can offer a list. The steps inside a workflow are never sent.

- The transport is the operating system's device-pairing channel (the Wear OS Data Layer, Apple's WatchConnectivity). No Recly server is involved; none exists.
- **Both devices are yours.** Recly runs no server that relays this transfer. The channel itself belongs to the operating system, though: recordings are only sent while the two devices are near each other (on both Wear OS and Apple), but small items such as workflow names may be relayed through Google Play services' infrastructure when the devices are apart — that handling is governed by Google's privacy policy.
- Once the phone confirms receipt, the watch deletes its own copy — no recording history accumulates on the watch.
- API keys and tokens are never sent over this path.

**There is nothing else.** No path other than these four exists by which data leaves your device.

## 4. What is not collected

- No analytics, usage statistics, or behavioral logging.
- No automatic crash reporting.
- No advertising identifiers and no ads.
- No Recly account: no sign-up, and no email or profile data reaching the developer. The only permission the app asks Google for is `drive.file`; it does not request profile or contacts scopes. But **signing in with Google necessarily tells the app which account it is, and that account's email address stays on the device** — to pick the same account next time, and as described in the §2 table it is not transmitted to Recly or anywhere else.
- **The developer (Recly) collects nothing about you and sells, shares, or transfers nothing to third parties** — there is no data in the developer's hands to begin with. What does happen is **the transfers you direct**: as set out in §3, to your own Google Drive, to a webhook receiver you configured, to the STT provider you chose, and between your own paired devices (watch ↔ phone). Those happen because you asked for them; they are not the developer handing your data to a third party.

## 5. Limited Use of Google user data

Recly's use and transfer of information received from Google APIs adheres to the **Google API Services User Data Policy**, including the Limited Use requirements. Drive data is used only to provide the features you requested (uploading your recordings and syncing your workflow definitions), is never used for advertising, and is not read by humans — there is no server that could read it.

## 6. Security

- API keys and tokens are stored in the operating system's secure storage (Android Keystore-backed encrypted storage, Apple Keychain, Windows Credential Manager).
- All outbound communication uses HTTPS, except a `127.0.0.1`/`localhost` receiver you configure yourself.
- On **Android, iPhone and Apple Watch**, recordings live in the per-app area the operating system isolates (the app container) and other apps cannot reach them.
- **macOS and Windows have no such isolation.** The Mac app is distributed directly and therefore does not run inside a sandbox; its files are in `~/Library/Application Support/app.recly.mac/`, and on Windows in `%LOCALAPPDATA%\Recly\` — **other programs running under the same user account can read them.** What protects them there is the operating system's user-account permissions (file access control) and, if you have turned it on, disk encryption (FileVault on macOS, BitLocker on Windows). API keys and tokens are not kept with those files; they are in the Keychain and Credential Manager instead.
- No secure storage is complete without device-level security. Please use a device lock and disk encryption.

## 7. Retention and deletion

- **Automatic deletion**: local audio is deleted only after an upload has been confirmed. If the upload failed, or if your workflow has no upload step, the originals stay on the device. **They also stay when the upload stopped because your Google Drive is full** — the app tells you there is no space, and picks the upload back up when you have made room and pressed "Retry". There is no time-based automatic deletion.
- **Deleting a recording**: all four apps (Android phone, iPhone, Mac, Windows) can delete a recording from the list. Each time, a confirmation dialog **asks what to do about Drive, and the default is to keep it there** — the irreversible choice is never the default. If some parts have not reached Drive yet, the dialog says how many first.
- **What a deletion removes**: that device's whole recording folder (audio files, `meta.json`, local transcript copies) and **every record of that recording** — not only the recording and part records but the **job records** too (`job` and `step_run`: the copy of the workflow definition that recording ran, the per-step execution state, failure messages, upload and transcription progress, and step outputs). Those are what an earlier build left sitting in the database, invisible in the list; they no longer stay behind. Choosing "also delete the Drive folder" removes that recording's Drive folder as well.
- **A recording that is being processed is not deleted**: if one of its jobs is running, the deletion is refused with "try again once it has finished". If Drive refuses the folder deletion, the files on your device are still removed and the app tells you so.
- **Signing out**: all four apps have "Sign out", which removes this device's Google token (on Android, the stored account email as well). It does **not** remove recordings, job state, or the API keys you entered.
- **Disconnecting**: all four apps now have a **"Disconnect" in Settings, separate from signing out.** It revokes the Google grant and clears this device's tokens, the API keys and webhook signing keys you entered, the job records and the sync state. You can additionally tick "also delete the recordings on this device"; **if you leave it unticked the recordings stay** (a decision about an account does not delete an original that has not been uploaded). One exception: a recording whose job was **running at that moment** is kept together with its job records, and the app tells you how many — disconnect again once it has finished and they go too. **It never deletes anything in Drive** — those files are yours.
  - The confirmation dialog says first that **every device signed in with the same account loses access, not just this one**, that the workflow definitions in the application data folder may go, how many recordings have not reached Drive yet, and that this device's keys and queue are wiped; it also links to your Google account settings (<https://myaccount.google.com/permissions>).
  - **If the revocation fails, the app says so** — this device's data is cleared, but Recly is still listed on your Google account, so you have to remove it yourself at the link above.
  - Google documents the workflow definition in the application data folder as deleted **when a user uninstalls the app from their Drive**; what happens on a plain revocation is not stated by Google.
- **Copies held by an STT provider**: Recly cannot delete these for you. Contact that provider directly.

### What survives uninstalling the app

| Platform | Uninstalling | What is left, and how to remove it |
|---|---|---|
| Android phone · Galaxy Watch | **App data goes with it** — recordings, the local database and the encrypted store holding tokens and API keys all live in the app's private area and the OS removes them with the app | Nothing. Files in Drive stay, because they are yours |
| iPhone · Apple Watch | The app container (recordings, database, settings) is removed | **Apple does not guarantee that Keychain items are deleted** (service `app.recly.secrets`, or `app.recly.watch.*` on the watch — the API keys and webhook signing keys you entered, plus the Google Sign-In SDK's token item). If they persist, reinstalling the same app can read them again. They are not synced to another device or to iCloud (the items are `ThisDeviceOnly`). iOS and watchOS give you no way to delete Keychain items **by hand**, so **run "Disconnect" inside the app before you delete it** — that removes this device's Keychain items (tokens, the API keys and webhook signing keys you entered). The watch has no sign-in and no key entry, so its item (`app.recly.watch.*`) holds only an install identifier and there is no in-app path that clears it; once the app is gone, erasing the device is the only certain removal for whatever is left |
| macOS | **Only the `.app` bundle is removed** | (1) Delete `~/Library/Application Support/app.recly.mac/` (recordings, `rec.db`, `device.id`) yourself. (2) In Keychain Access delete the items whose service is `app.recly.mac.secrets` (the API keys and webhook signing keys you entered) plus the item the Google Sign-In SDK created (the Google token). (3) **App settings and the last account's email hint stay in `UserDefaults`** — recording mode, the consent reminder, language and accessibility settings, plus the email address used to prefill your next sign-in (`app.recly.auth.lastAccount`). Clear them with `defaults delete app.recly.mac` in Terminal |
| Windows | **Only the installed files are removed** | (1) Delete `%LOCALAPPDATA%\Recly\` (recordings, `rec.db`, `device.id`) yourself. (2) In Credential Manager → Windows Credentials delete the `app.recly.windows/tokens/…` and `app.recly.windows/secrets/…` entries. (3) **App settings stay in the registry** — the consent reminder, language, theme and accessibility settings live under `HKCU\Software\JavaSoft\Prefs\app\recly\windows`; delete that key in Registry Editor. The Windows app stores no account email, so there is none to remove |

### Clearing it from inside the app first

Most of the manual cleanup above becomes unnecessary if you **run "Disconnect" inside the app once before you delete it** — that clears the Keychain and Credential Manager items (tokens, the API keys and webhook signing keys you entered) and this device's job records. To remove the recordings as well, tick "also delete the recordings on this device" in that dialog, or delete them one by one from the list. App settings (macOS `UserDefaults`, the Windows registry) and the macOS and Windows data folders still remain, so follow the rest of the table for those.

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
