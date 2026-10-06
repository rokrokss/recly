# Recly

**Whichever device records, a fixed process (original upload · transcription · result upload) runs when the recording ends.**

This one document is Recly as it is now. It is neither a history nor a lane log; it states, as they are, the contracts and
rules the current code keeps. The machine-readable source of truth is `spec/*.json` (the recording processing settings,
meta and transcript schemas); this document defines what those schemas mean and the rules the schemas do not hold.

**Section numbers are a contract.** A `docs/NN "subsection"` citation in a code comment points to §NN of this document and
that subsection heading. Section numbers and subsection headings do not change without a reason.

| Section | Contents |
|---|---|
| [0](#0-product-definition--rules-formerly-docs00) | Product definition · principles · rules (formerly ADRs) |
| [1](#1-architecture-formerly-docs01) | Architecture |
| [2](#2-workflow-contract-formerly-docs02) | Workflow contract — the internal step model only (the user document was retired, 2026-09-24) |
| [3](#3-recording--storage-formerly-docs03) | Recording · storage · retention · deletion |
| [4](#4-webhooks-formerly-docs04) | Webhooks — retired (2026-09-24) |
| [5](#5-workflow-storage--secrets-formerly-docs05) | Recording processing settings · secrets (workflow storage and export/import were retired, 2026-09-24) |
| [6](#6-authentication-formerly-docs06) | Google authentication |
| [7](#7-i18n-formerly-docs07) | Localization |
| [8](#8-transcription-formerly-docs08) | `transcribe` |
| [9](#9-design-system-blueprint-formerly-docs09) | Design system "Blueprint" |
| [10](#10-core-kmp-formerly-docs10) | Shared core (KMP) |
| [11](#11-android--wear-formerly-docs11) | Android phone · Galaxy Watch |
| [12](#12-macos-formerly-docs12) | macOS |
| [13](#13-ios--watchos-formerly-docs13) | iPhone · Apple Watch |
| [14](#14-windows-formerly-docs14) | Windows |
| [15](#15-privacy--data-flows-formerly-docs15) | Privacy · data flows |
| [16](#16-personas--pricing-formerly-docs16--proposal-undecided) | Personas · pricing (proposal, undecided) |
| [20](#20-verification-status-formerly-docs20) | Verification status |
| [21](#21-conventions-formerly-docs21) | Conventions — what was left on purpose |
| [Open decisions](#open-decisions) | Remaining user decisions |

---

## 0. Product definition · rules (formerly docs/00)

**Applied 2026-09-24:** A recording runs only the fixed processing plan (§5 "Fixed processing settings") — `drive.upload` →
`transcribe` or `local.transcribe` → `transcript.publish`. User-editable workflows (the document, the editor, the picker, the
per-device default pointer, export/import, schema 1/2 migration, the watch workflow list) and webhooks are **retired**, and
there is no compatibility layer that reads earlier settings. In the rules table below, entries that decision invalidated are
marked "retired". The phone screen has three tabs: Record, List, Settings.

### One-line definition

A recorder that leaves the original recordings and the transcription results in **the user's own Google Drive**. iPhone and
Mac can choose the user's own iCloud instead of Drive, and iPhone, Mac, Windows and the Android phone a local folder the user picks (ADR-024). Processing runs in the order recording, original upload,
transcription, result upload. The watch handles recording and transfer to the phone; the phone and desktop handle
post-processing.

### Principles

1. **The device that recorded runs it.** The watch hands off to the phone. **There is no Recly server.**
2. **Settings belong to the device.** The recording processing settings, transcription keys and preserved earlier
   definitions are all device-local and are not synchronized. Moving them between devices is the settings export/import
   (§5). Only recordings and result files go up to Drive — and those are the **recording list** the devices share
   (ADR-023, §3 "Recordings from other devices"). Runtime state such as retries and upload sessions lives only locally on
   each device.
3. **The original is not deleted before the ack.**
4. **The files in Drive are the interface.** Transcription is a choice of local, external API or OFF; from summarization on,
   the work belongs to the user's agent (§16, `skills/recly-notes/`). The default is "my Drive + my automation". Drive is the
   source archive the app writes, so agents only read it. The repository's two skills are **examples** — one way to write
   minutes and keep those notes and later edits in the user's Notion (`skills/recly-notion/`) — and they assume the user
   adapts them to their own format and note app or builds their own (2026-10-01).
5. **There is no stealth mode.** Recording is always indicated while it runs, and on desktop the order is detect → confirm →
   record.

### Rules (formerly ADRs)

These are the decisions code comments cite as `ADR-0NN`. The numbers are a stable contract and stay; the content states only
the current rule.

| Number | Rule |
|---|---|
| ADR-001 | The device records and the original goes to the user's Drive. Transcription and delivery are **optional steps the user puts into their own workflow**, not fixed post-processing. Summarization is not in the pipeline — the pipeline takes on only what a subscription agent cannot do (STT), and what an agent can do (text summarization) is done with an agent skill (`skills/recly-notes/`). (Revised 2026-09-24: user workflows and webhooks retired — transcription is the local, external API or OFF choice in the recording processing settings, §5) |
| ADR-002 | The Android phone, iPhone, macOS and Windows run workflows. Galaxy Watch hands files to the phone over the Data Layer, Apple Watch over WatchConnectivity. **The watches have no authentication or network code.** Standalone upload from a cellular watch is out of scope |
| ADR-003 | When the phone and the watch record at the same time, there are two files and they are not linked. `recordingId` is an independent per-device ULID, and there is no session linking (session id) |
| ADR-004 | The workflow engine, Drive client, webhooks, sync and job queue live in a single Kotlin Multiplatform `core/`. Workflow logic is not written twice. (Webhooks retired 2026-09-24) |
| ADR-005 | Shells: Android/Wear are Kotlin + Compose, iOS · watchOS · macOS are SwiftUI + KMP XCFramework, Windows is Compose Desktop (JVM) + a Rust capture helper |
| ADR-006 | Audio is AAC-LC `.m4a`, 16 kHz mono 32 kbps, nominal 900-second segments. Mobile and watch have one track, `mono`; desktop has three tracks, `mic` · `sys` · `mix` |
| ADR-007 | Workflow definitions are a single **device-local document**, with no backend and no sync. Moving them between devices is the settings export/import (§5) — the file format is the document serialization as is (retired 2026-09-24) |
| ADR-008 | Workflow JSON carries only the secret's **name** (`secretRef`); the value is in each device's secure storage. On a device without the value, that step fails with `MISSING_SECRET`. Values are not synchronized and never go into an export file — keys are entered on each device. (2026-09-24: workflow JSON retired — the same rule applies to `secretRef` in the recording processing settings) |
| ADR-009 | The only OAuth scope is `drive.file`, and the consent screen is in Production. The full `drive` scope is never requested |
| ADR-010 | Webhook signatures follow Standard Webhooks as is (retired 2026-09-24) |
| ADR-011 | **No bot joins the meeting.** Detection uses microphone use and meeting apps, and the user records with one tap. There is no automatic recording. There are no per-participant streams; two tracks, "me vs. the other side", are the limit |
| ADR-012 | There are three step types: `drive.upload` · `webhook` · `transcribe` (`schema: 3`). There is no `deliver.*` (Notion · Telegram). (2026-09-24: `webhook` retired. The user picks no steps; the fixed plan builds `drive.upload` · `transcribe` or `local.transcribe` · `transcript.publish`) |
| ADR-013 | There are no per-step conditions (`if`). There is only the one workflow-level `minDurationSec` |
| ADR-014 | The Drive layout is one folder per recording — the part files and `meta.json` under `{folder}/{base}/` |
| ADR-015 | Resumable upload **separates the protocol (pure functions in the core) from the transport (the platform)**. Apple replaces the transport with a background `URLSession` |
| ADR-016 | Each device picks **one workflow in use** — a pointer that exists only locally and is not synchronized, and every recording on this device (manual, meeting detection, watch) runs it. Picking in the picker is what changes this pointer; there is no temporary per-recording choice (2026-09-02: the earlier "Default (name)" entry and the "default" wording were retired — the same workflow showed up as two entries). The shared document has no `enabled` · `isDefault` · `trigger.sources`, and there is neither a source filter nor a latest-`updatedAt` fallback. If there is no selection, or the workflow it points to is gone, nothing runs and the shell asks "Choose a workflow". The workflow in use can be deleted only after another workflow is selected first (retired 2026-09-24) |
| ADR-017 | The local original is deleted **only when the upload succeeded**. A webhook-only workflow, or a failed upload passed over with `continue`, keeps the original. **Revised 2026-09-03**: even after a successful upload it stays for **7 days** (fixed value, no settings UI) — local parts are a cache with an expiry. The retention sweep on every job pass deletes the parts of a recording that meets "all jobs DONE + every upload succeeded + 7 days past both the file mtime and the last DONE time". When the detail screen plays a part that is not local, it downloads the part again from Drive by the upload output's `fileId` and stores it under the same name (sha256 verified), and that part gets another 7 days. A part that was never uploaded is kept forever |
| ADR-018 | The product name is **Recly** (identifier `recly`). Android `app.recly` · Kotlin package `recly.core`, Apple bundles `app.recly`/`app.recly.watch`/`app.recly.mac`, XCFramework `ReclyCore`. Contracts the user does not see (file name `{base}`, webhook `user-agent: rec/…`, log events `rec.*`, device storage paths `files/rec` · `rec.db`, Data Layer paths `/rec/…`) stay `rec` |
| ADR-019 | Windows encoding uses the **bundled ffmpeg** (LGPL dynamic linking, unmodified, separate process). The Media Foundation AAC MFT accepts only 44.1/48 kHz input and 96 kbps or higher output, so it cannot produce ADR-006's 16 kHz · 32 kbps. The MF path remains as `--encoder mf` and is not the default |
| ADR-020 | The top of the default Drive folder is **`recly/`** — the `drive.upload` `folder` default `recly/{{yyyy}}/{{yyyy}}-{{MM}}`, and `recly/memo/{{yyyy}}-{{MM}}` for the "Memo" default workflow |
| ADR-021 | In `transcribe` (STT + speaker diarization), **the device running the job calls the provider API directly with the user's key**. There is no intermediate server, relay or callback URL |
| ADR-022 | **There is no telemetry.** No analytics, usage statistics, crash reporting, remote log collection, remote config, A/B testing or advertising identifiers. No shell has a Firebase/Crashlytics/Sentry/AppCenter-family dependency. Logs stay only in the device-local platform log and leave the device only when the user exports them |
| ADR-023 | **Drive is the source of truth for the recording list** (2026-09-04). Recordings uploaded by another device on the same account also appear in this device's list — with no separate index file or server, the app lists in Drive the `{base}/` folders (ADR-014) it stamped with a `recordingId` and reads their `meta.json` (§3 "Recordings from other devices"). Such a row has no Job and no original on this device; playback downloads from Drive and caches. A folder that has no `meta.json` yet also appears in the list — as a provisional "Uploading on another device" row. When it disappears from Drive, the row disappears too. Rows this device created itself are left alone whatever Drive says. A device that chose iCloud lists the iCloud folder by the same rules (ADR-024). A local folder is never listed |
| ADR-024 | **Each device picks its storage** (2026-10-02). The default is Google Drive; iPhone and Mac can switch to iCloud (the app's iCloud Drive folder) in Settings, and iPhone, Mac, Windows and the Android phone to a local folder the user picks (2026-10-03). The watches hand their recordings to their phone, which uploads them to its own storage. A recording uploads to the storage fixed when it started, and an uploaded recording is not moved. iCloud adds one folder-properties file to the same layout as Drive, and an upload ends when iCloud says it has received it; a local folder gets the same layout plus a Markdown transcript, is this device's alone (never listed), and an upload ends with the copy (§3 "Storage location") |

Reversing these rules does not end with editing this document. Reversing ADR-022 in particular means also changing
`docs/policy/privacy-policy.md`, the Play "Data safety" form and the App Store privacy labels.

---

## 1. Architecture (formerly docs/01)

### System picture

```
 ┌─ Capture ─────────────────────────────────────────────────────────────────┐
 │ Galaxy Watch   Android phone Apple Watch   iPhone   macOS         Windows │
 │ MediaRecorder  MediaRecorder AVAudioEngine AVAudioEngine  CoreAudio tap  WASAPI │
 └──────┬─────────────┬───────────┬────────────┬──────────┬────────────┬─────┘
        │ Data Layer  │           │ WCSession  │          │            │
        │ ChannelClient           │ transferFile          │            │
        ▼             │           ▼            │          │            │
 (phone receives·ack) │   (phone receives·ack) │          │            │
                      ▼                        ▼          ▼            ▼
 ┌─ Executor (device-local) ──────────────────────────────────────────────────┐
 │  Job(recording × workflow) → step 1 → step 2 → … ; state in local SQLite   │
 │  KMP core: workflow · job · drive · transcribe · sync · storage            │
 └───────────────┬───────────────────────────────────────────┬────────────────┘
                 │                                           │
                 ▼                                           ▼
   Google Drive  {folder}/{base}/ parts + meta.json      STT provider (for external API transcription)
   or iCloud (iPhone·Mac) or a local folder (iPhone·Mac·Windows·Android, ADR-024) — same layout
```

### Components

| Component | Location | Responsibility |
|---|---|---|
| Recorder | Per platform | Microphone capture (system audio only in desktop meeting mode), writing segment files, detecting silenced input, writing `meta.json` |
| Transfer | Wear/watchOS ↔ phone | Transferring parts and meta, sha256 verification, ack, deletion on the watch side after the ack |
| Core | `core/` KMP | Recording processing settings and compiling the fixed plan, job queue, step execution, Drive resumable, transcription provider adapters, settings export/import |
| Scheduler adapter | Per platform | Calls the core's `runDueJobs()` from WorkManager / background URLSession + BGTask / a tray or menu bar timer |
| Auth adapter | Phone · desktop | Google OAuth, supplying the access token (`TokenProvider`) |
| UI | Per platform | Recording start/stop, recording processing settings (phone · desktop), recording list and status |

### Recording lifecycle

```
 start ──► Recording{id, source, workflowId, startedAt}   (local DB, status=recording)
   │        parts written: p001, p002 … (sha256 and bytes recorded per segment)
   │        meta.json updated (at every segment boundary)
 stop ───► finalize: endedAt, durationSec, status=finalized
   │
   ├─ phone·desktop ─► Job created {recordingId, workflowId, steps[] state=PENDING}
   │                   Scheduler adapter calls runDueJobs()
   │                   steps run in order · each step's state persisted · on failure, retry after backoff
   │                   all steps finished → Job DONE
   │                   → retention rule (ADR-017): once all of this recording's Jobs are DONE and one of them
   │                     has SUCCEEDED every drive.upload, **after 7 days** the retention sweep on each pass
   │                     deletes the local parts (otherwise kept + "Not uploaded" in the list; meta.json and
   │                     the DB row are always kept). Playback in the detail screen downloads a deleted part
   │                     again from Drive and caches it for 7 days
   │
   └─ watch ──────► Transfer queue {recordingId, parts[]}
                    when the phone connects, transfer per part → the phone verifies sha256, then acks
                    all acked → the watch deletes, the phone creates the Job (same as above)
```

Invariants:

- Part files are not deleted until every Job of the same recording is DONE and one of them has succeeded in all of its
  `drive.upload` steps (ADR-017, §3 Retention · deletion).
- Watch parts are not deleted before the phone's ack.
- A `Job` is unique by `(recordingId, workflowId)`. Running a workflow again on the same recording does not create a new
  Job; it resumes the existing Job from its failed step.

### Identifiers · time

- `recordingId` · `workflowId` · `jobId` · `stepRunId`: ULID (26 characters, Crockford base32). Generated in the core.
- `deviceId`: a UUID v4 generated at install, kept in secure storage (`{dataDir}/device.id` on macOS only). A reinstall
  gets a new value.
- All times are UTC ISO-8601 (`2026-08-26T01:00:00.000Z`). The time zone for display is the meta's `timezone`.
- Times in file names are compact UTC (`20260826T010000Z`).

### Repository layout

```
rec/
  core/                       KMP module (:core) — §10
  android/
    app/                      phone app
    wear/                     Galaxy Watch app
    recording/                MediaRecorder-based SegmentedRecorder + RecorderService (shared by phone and watch)
    datalayer/                phone↔watch Data Layer paths and JSON contract (used by both sides)
  apple/
    Rec.xcworkspace
    RecKit/                   Swift package: Recorder, MacCapture, Detect, Transfer, Auth, Transport, Workflow, CoreBridge
    RecMac/ RecPhone/ RecWatch/
  windows/
    app/                      Compose Desktop
    capture-helper/           Rust (wasapi) — capture and detection, reports status as JSON lines
  spec/                       JSON Schema + examples (the contract)
  scripts/                    icon rendering
  docs/                       this document + privacy policy + icon master
```

Build tools: the Gradle wrapper (`core` · `android:*` · `windows/app`), Xcode (`apple`), Cargo (`windows/capture-helper`),
Node (`spec` validation). The root `settings.gradle.kts` includes `:core`, `:android:*` and `:windows:app`, and `apple`
references the `:core:assembleXCFramework` output as a SwiftPM binary target.

### The core ↔ shell boundary

**What the shells give the core** (`CoreDeps`):

- `SecureStore` — reading/writing tokens and secrets (per namespace)
- `TokenProvider` — returns a valid access token (refreshing on expiry is the shell's job)
- `FileSystem` — okio `FileSystem` + the app data directory
- `Transport` — Ktor by default. Apple can replace it with a background URLSession implementation (ADR-015)
- `UbiquityContainer` — the app's iCloud container (optional). Only iPhone and Mac builds signed with the iCloud
  entitlement provide it (§3 "Storage location")
- `LocalFolder` — the local folder the user picked (optional). The Mac and Windows provide it through the core's
  `PathFolder`, the iPhone through a bookmark of the folder picked in Files (`PickedFolder`), the Android phone through the
  Storage Access Framework (§3 "Storage location")
- `AudioTools` — `concat` (lossless remux of parts, §8)
- `Clock`, `Logger`, `DeviceInfo{deviceId, platform, name}`, the `io` dispatcher

**What the core gives the shells** (`ReclyCore(deps, driverFactory)` — the shell opens the SQLDelight driver and passes it
in):

- `recordings` — registering a recording, adding parts, finalize, list, `delete`
- `processingSettings` — reading, saving and importing the recording processing settings (§5 "Fixed processing settings").
  The old `workflows` (document · selection rules) was retired (2026-09-24)
- `jobs` — `enqueue(recordingId, chosenWorkflowId?)`, `runDueJobs(now)`, `retry(jobId)`, observing status
- `secrets` — `SecretsRepository.put/delete/get/names` (the **only** entry point for writing values, §5)
- `secretSync` — `setup`/`disable`/`status`
- `transfer` — helpers for watch→phone receiver-side verification and ack (sending is a platform API, so it is in the
  shell)
- `disconnect(alsoDeleteRecordings)` — the local-cleanup half of disconnecting

The shells have only audio capture, transfer APIs, the scheduler and the UI. **Workflow semantics never live in a shell.**

Every `suspend` entry point exposed to the shells carries `@Throws(Throwable::class)` — Kotlin/Native kills the process when
an undeclared exception escapes an exported suspend function.

---

## 2. Workflow contract (formerly docs/02)

> **Retired (2026-09-24)**: The user-editable workflow document (the local document, the export file `recly-workflows.json`,
> schema 1..3) and its schema and example (`spec/workflow.schema.json`, `spec/examples/workflows.json`) were removed. A
> recording runs only the fixed processing plan (§5 "Fixed processing settings"). The `Workflow`/`Step` model remains only as
> the **internal representation** of that plan and of the job snapshot (`job.workflow_json`); it is neither a user document
> nor a public schema. The common step fields, the meaning of `drive.upload` and `transcribe`, and the template variables
> apply to that internal plan unchanged. The subsections on the document, selection and webhooks are a record.

### Document structure

> **Retired (2026-09-24)**: There is no user document (§2 preamble). What follows is a record.

```json
{
  "schema": 2,
  "revision": 12,
  "updatedAt": "2026-08-26T01:00:00.000Z",
  "updatedBy": "3f1c…-deviceId",
  "workflows": [ …workflow ]
}
```

| Field | Meaning |
|---|---|
| `schema` | Currently **3**. When the parser meets a document **higher** than the app supports, it returns `Invalid(UnsupportedSchema)` and the import writes nothing. The UI shows "update the app". Old documents at `MIN_SCHEMA` (=1)..2 (stored locally or imported files) are read under the current rules and saved at the current schema (§5 Schema) |
| `revision` | +1 on every write. Merge decisions use each workflow's `updatedAt`; `revision` is for diagnostics |
| `updatedBy` | The `deviceId` of the device that wrote last |

### Workflows

> **2026-09-24**: There are no user-made workflows. The fixed plan's `Workflow` is an internal value with a fixed id
> (`ProcessingPlan.ID`), `name`, `minDurationSec` (`storage.minDurationSec` in the recording processing settings) and
> `steps`. The description of the device pointer (ADR-016) below is retired.

```json
{
  "id": "01J9ABCDEF0123456789ABCDEF",
  "name": "Meeting",
  "updatedAt": "2026-08-26T01:00:00.000Z",
  "minDurationSec": 30,
  "steps": [ …step ]
}
```

| Field | Meaning |
|---|---|
| `name` | 1–40 characters. Used in the UI as the profile name and shown as is on the watch button |
| `minDurationSec` | A recording shorter than this creates no Job and ends as `SKIPPED_SHORT` (the files are kept locally. 2026-09-02: the list's "Upload now" is gone, so there is no manual upload path — if a length should be uploaded, lower this value). Default 0 |
| `steps` | 1–10, run in order |

The document holds **only definitions**. Which workflow this device uses by default is a device-local pointer and is not
synchronized (ADR-016, principle 2 "one definition, per-device state"). `enabled` · `isDefault` · `trigger` (=`sources`)
disappeared in schema 3 — those fields in old documents are discarded on read, and only `trigger.minDurationSec` moves up
(§5 Schema).

### Steps

Common fields:

| Field | Default | Meaning |
|---|---|---|
| `id` | required | `^[a-z][a-z0-9_]{0,31}$`, unique within the workflow |
| `type` | required | `drive.upload` \| `transcribe` \| `local.transcribe` \| `transcript.publish` (`webhook` retired 2026-09-24) |
| `onError` | `abort` | `abort`: later steps do not run, Job FAILED. `continue`: only this step is left FAILED and the next step proceeds |
| `retry.maxAttempts` | 8 | 1–20 |
| `retry.initialDelaySec` | 30 | Starting value of the exponential backoff |
| `retry.maxDelaySec` | 3600 | Backoff cap. Jitter ±20% |

#### `drive.upload`

```json
{ "id": "up", "type": "drive.upload",
  "folder": "recly/{{yyyy}}/{{yyyy}}-{{MM}}",
  "includeMeta": true }
```

- `folder` — a path template relative to the My Drive root. Missing folders are created. Default
  `recly/{{yyyy}}/{{yyyy}}-{{MM}}` (ADR-020).
- The actual layout is under `{folder}/{base}/` (ADR-014, §3 Drive layout).
- Every track in the recording is uploaded. There is no option to choose.
- `includeMeta` — whether to upload `meta.json`. Default true.
- Success condition: every part + the meta is in Drive and `md5Checksum` matches the local copy.
- Idempotent: if the same `{base}` folder already exists it is reused, and a file with the same name and the same md5 is
  skipped.

#### `webhook`

> **Retired (2026-09-24)**: The webhook step was removed (§4). A `webhook` step in a job an earlier build queued does not run either. What follows is a record.

```json
{ "id": "hook", "type": "webhook",
  "url": "https://example.com/rec",
  "secretRef": "hook_main" }
```

- `url` — `https://` required. As an exception, `http://127.0.0.1` and `http://localhost` are allowed (local n8n and the
  like).
- `secretRef` — the signing key name. Without it, the request is sent without signature headers.
- Payload, signature and retry rules are in §4.
- If a `drive.upload` precedes this step, the payload's `files[].drive` is filled in; otherwise `drive` is null. If an
  earlier `transcribe` succeeded, its result files also go into `files[]`.

#### `transcribe`

```json
{ "id": "stt", "type": "transcribe", "provider": "rtzr", "secretRef": "rtzr_key",
  "language": "ko", "diarize": true, "speakers": { "min": 2, "max": 6 } }
```

Fields, providers, result files and polling rules are in §8. Ordering constraint: `transcribe` needs a `drive.upload` before
it (validation error `TranscribeNeedsUpload`).

### Template variables

> **2026-09-24**: The folder in the recording processing settings (`storage.folder`) cannot use `title` or `workflowName` (§5 "Fixed processing settings").

Only the names below are allowed inside `{{ }}`. An unknown variable is a validation error.

| Variable | Value |
|---|---|
| `yyyy` `MM` `dd` `HH` `mm` | The recording's `startedAt` converted to the meta's `timezone` |
| `title` | The meta `title`, or the workflow `name` if there is none |
| `source` | `watch` / `phone` / `desktop` |
| `recordingId` | The full ULID |
| `workflowName` | The workflow `name` |
| `device` | The meta `deviceName` |

When used in a path, `/`, `\` and control characters are replaced with `_`, and leading and trailing whitespace is trimmed.

### Selection rules (ADR-016)

> **Retired (2026-09-24)**: There is no workflow to select. Every recording runs the fixed plan, and the device pointer, the
> seeding guess and the workflow list UI rules are gone. What follows is a record.

1. The `workflowId` the caller passes at recording start (or stop), if it **resolves** in the document. The shells **send
   this slot empty** (2026-09-02: the temporary per-recording choice is gone from the UI) — it stays as a core rule so tests
   and harnesses can run a specific workflow.
2. If it is absent or does not resolve, **the workflow in use on this device** (the device pointer), if it resolves.
3. If neither, no Job is created and the recording stays in the list in the `NO_WORKFLOW` state.

There is neither a source filter nor a latest-`updatedAt` fallback. The device pointer and the definitions are both local.
**The delete button of the workflow in use is disabled**, and no separate explanatory text is attached to the row. Deletion
processing also checks the latest device pointer. If there is only one workflow, a new one has to be created and selected
before the existing one can be deleted. When an import replaces the document and the selected definition disappears, the
pointer is cleared and the list and recording screens show "Choose a workflow".

The top of the workflow list shows no count. The create button is `+ New workflow`, offering the symbol and the label as one
touch target. The shared secrets list offers only viewing names and deleting; adding is offered only in the transcription
and webhook steps inside a workflow. On mobile, a row's delete button and form actions are placed on the right. Desktop
applies the same creation path and deletion restriction.

The seeding device's initial pointer is a **revocable guess**: when the shell calls `seed(preferred starter)` it takes effect
immediately (the first device has to work offline too) but is recorded as a guess, and the first decisive sync settles it —
**adopting** the remote document withdraws the guess (a pointer the user already changed is left alone), and this device's
push/publish confirms it. Adoption also clears `seededHere`, so a `seed()` after adoption makes no guess at all. A recording
queued **before** the default is settled (right after recovery or adoption, for example) is parked as `NO_WORKFLOW` and
follows the existing manual-run path — there is no re-queuing.

### Validation rules

> **2026-09-24**: With no user document, the document-level rules (local cache protection, unknown fields, migration ·
> `MigrationBlocked`) and the `webhook.url` scheme rule are retired. Validation of the recording processing settings is
> defined by §5 "Fixed processing settings" and `spec/recording-settings.schema.json`.

- Passes the schema + workflow `id` is a ULID + `name` 1–40 characters + `minDurationSec >= 0` + step `id`s unique +
  template variables valid + the `webhook.url` scheme rule + the `transcribe` ordering constraint +
  `transcribe.provider` is a known value (`UnknownProvider`) + `invokeUrl` only on `clova`.
- A document that fails validation **does not overwrite the local cache**; the UI shows an error (this keeps sync from
  spreading a broken definition).
- Unknown fields are ignored but not preserved on rewrite (forward compatibility comes only through the `schema` version).
  So if an old document has fields the typed model would drop, it is rejected instead of migrated (`MigrationBlocked` in
  §5 Schema). The exception is fields **known to be retired** — schema 1..2's `enabled` · `isDefault` · `trigger` are
  dropped by a name the code knows, so they are not a reason to reject (§5 Schema).

### Example

> **Retired (2026-09-24)**: `spec/examples/workflows.json` was deleted. The settings examples are `spec/examples/recording-settings*.json`.

`spec/examples/workflows.json` — three workflows: "Meeting" (Drive + webhook), "Memo" (Drive only), "Minutes" (Drive +
`transcribe`).

---

## 3. Recording · storage (formerly docs/03)

Schema: [`spec/recording.meta.schema.json`](../spec/recording.meta.schema.json).

### Audio settings

| Item | Value |
|---|---|
| Codec / container | AAC-LC / `.m4a` (MPEG-4) |
| Sample rate | 16,000 Hz (if the device does not support it, fall back to 44,100 Hz and record that in the meta) |
| Channels | Mono |
| Bitrate | 32 kbps |
| Segment | Nominally 900 seconds. Boundaries are lossless — Android uses `setMaxFileSize(900 s × bitrate × 1.07)` + `setNextOutputFile` (`setMaxDuration` is not used because it **stops** the recording instead of rolling over to the next file; the actual length is therefore ± a few % by bytes), Apple swaps the `AVAudioFile`, and on Windows the helper cuts the PCM and hands it to ffmpeg. Each part's `durationSec` is canonical; `audio.segmentSec` is the nominal value. Unavoidable gaps are recorded in the meta's `gaps` |
| **Tracks** | Mobile · watch: `mono`. Desktop: `mic`, `sys`, `mix` — each with the settings above, and they share **the same start time and segment boundaries** |

Size: 14.4 MB per track per hour, 3.6 MB per segment. Desktop with 3 tracks: 43 MB per hour. Because one segment is 3.6 MB,
it also passes the 25 MB upload limit of the OpenAI family.

### Naming rules

```
base  = {yyyyMMdd}T{HHmmss}Z_{source}_{first 8 chars of recordingId}
part  = {base}_p{NNN}_{track}.m4a
meta  = {base}.meta.json
```

Examples:

```
20260826T010000Z_watch_01J9ABCD_p001_mono.m4a
20260826T010000Z_desktop_01J9ZZ12_p003_sys.m4a
20260826T010000Z_desktop_01J9ZZ12.meta.json
```

- The time is `startedAt` in UTC. `NNN` starts at 1 and is not numbered independently per track — **the same time span gets the same number**.
- File names contain no user strings such as the title or the device name (for path safety and machine parsing). The title goes into the meta and into the Drive folder's
  `description`.
- Watch recordings use `_watch_`, matching `"source": "watch"` in the meta.

### Metadata

One `meta.json` per recording. During recording it is rewritten at every segment boundary — **after a crash, everything up to the last boundary is recoverable**,
and the last segment, if the process died before stop, cannot be read because its container was never closed, so recovery quarantines it as `{file}.corrupt` and
does not register it. A recording with no readable part at all (only quarantined files left, or nothing at all) leaves nothing the app can
do, so its row and its directory are deleted, quarantined files included (2026-09-04 user decision; before that it was kept as room for manual recovery, but
all that remained was a `recording` row the user could not touch).
After `stop`, `status: finalized`.

```json
{
  "schema": 1,
  "recordingId": "01J9ABCDEF0123456789ABCDEF",
  "source": "desktop",
  "platform": "macos",
  "deviceId": "7c1e4b2a-0d3f-4a7e-9b1c-2f5e8d6a4c10",
  "deviceName": "MacBook Pro",
  "workflowId": "01J9ABCDEF0123456789ABCDEF",
  "title": "Weekly meeting",
  "startedAt": "2026-08-26T01:00:00.000Z",
  "endedAt": "2026-08-26T02:00:12.400Z",
  "durationSec": 3612.4,
  "timezone": "Asia/Seoul",
  "audio": { "codec": "aac-lc", "container": "m4a", "sampleRateHz": 16000, "channels": 1, "bitrateKbps": 32, "segmentSec": 900 },
  "tracks": ["mic", "sys", "mix"],
  "parts": [
    { "part": 1, "track": "mic", "file": "20260826T010000Z_desktop_01J9ABCD_p001_mic.m4a",
      "bytes": 3601234, "sha256": "…", "startOffsetSec": 0, "durationSec": 900 }
  ],
  "gaps": [ { "startSec": 1800.0, "endSec": 1800.3, "reason": "segment_restart" } ],
  "silenced": [ { "startSec": 120.0, "endSec": 125.5, "reason": "mic_taken" } ],
  "context": {
    "app": "us.zoom.xos",
    "participants": 3
  },
  "drive": {
    "folderId": "1AbCdEfGhIjKlMnOpQrStUvWxYz0123456",
    "folderUrl": "https://drive.google.com/drive/folders/1AbCdEfGhIjKlMnOpQrStUvWxYz0123456"
  },
  "status": "finalized"
}
```

| Field | Notes |
|---|---|
| `source` | `watch` · `phone` · `desktop` — matches the workflow trigger |
| `platform` | `wearos` · `android` · `watchos` · `ios` · `macos` · `windows` |
| `title` | Optional. Entered by the user after stop in the phone and desktop shells, or absent (on the watch it is always absent because there is no input UI — it can be added later on the phone). It can be changed at any time on the detail screen and spreads to every device through the Drive folder's `description` (§3 "Recordings from other devices" — Titles) |
| `parts` | The part list. `parts[].sha256` is computed right after a segment is finalized and is used to verify transfer and upload. `startOffsetSec` is the reference for the recording's timeline |
| `gaps` | Spans where audio is missing because of a segment restart, an interruption, tap re-creation and so on |
| `silenced` | Spans where the microphone was taken away, such as Android `isClientSilenced` or an Apple interruption. **Known limitation**: on Android, if stop is delayed (when unregistered parts are left over and handed to recovery), this span stays only in the log and does not go into the meta |
| `context` | Optional. `app` is the bundle id of the detected meeting app, desktop only. `participants` (integer, head count including the user) is filled from the selection in the dialog after stop — the speaker-count hint for `transcribe` (§8). **There is no `context.calendar`** — calendar reading was removed from the whole product |
| `drive` | Optional. This recording's Drive folder `folderId` · `folderUrl` (`webViewLink`). Right after `drive.upload` creates or finds the folder, and **before** it uploads the meta, it writes them to the row and to the local `meta.json` (`RecordingRepository.setDriveFolder`), so the copy in Drive and the devices that adopted it have the same values. Agent skills use it as the "Recording" link on the Notion page (2026-09-05). Absent before upload, or for a folder whose link was not received. Absent for iCloud recordings — there is no web link (§3 "Storage location") |
| `status` | `recording` → `finalized` → (watch) `transferred` |

The participant options are `2 · 3 · 4 · 5 · 6+ · Unknown`, and the default is "Unknown" (unknown) — if the user picks nothing, the field is omitted.
No value (nil) is what "unknown" means, and the workflow's `speakers` default applies. `6+` pairs with §8 capping the speaker hint at
10.

### Local storage

| Platform | Path |
|---|---|
| Android / Wear | `context.filesDir/recordings/{base}/` |
| iOS / watchOS / macOS | `Application Support/app.recly.mac/recordings/{base}/` (app id directory; on iOS `isExcludedFromBackup = true`) |
| Windows | `%LOCALAPPDATA%\Recly\recordings\{base}\` |

For a recording **received** from the watch, the meta arrives last, so `{base}` cannot be known in advance, and the phone puts it in `recordings/{recordingId}/`
(the directory name is stored in the DB row; the Drive layout is still `{base}`). On receipt, `upsertRecording` replaces the whole row but
keeps `drive_folder_id` — even if the watch resends after the phone has already uploaded, the folder is not forgotten.

The recording directory is stored in the DB as `recordings/{directory name}`, relative to the current app data root. Even when an iOS · watchOS update
changes the app container UUID, lookup · transcription · playback · renaming · deletion are handled from the current root. An absolute
path left by a previous version is resolved to the current container only when it lies under the same app's
`Containers/Data/Application/{UUID}/Library/Application Support/{app id}/recordings/`. Existing files are not moved or deleted, and external paths are left as they are.

### Retention · deletion

#### Automatic retention (ADR-017)

Part files are deleted only when **all** of the following are true — ① the Job is DONE, ② the workflow has at least one `drive.upload` step
and all of them SUCCEEDED (a failed upload skipped with `continue` is retained), ③ **every** Job of the same recording is
DONE (FAILED · SKIPPED_SHORT · NEEDS_AUTH · NEEDS_SPACE also block deletion — `retry()` needs the parts), ④ **7 days** have passed since both the file's
mtime and the last DONE time (`Retention.sweep` in every job pass re-evaluates this). Otherwise the files are retained and the list shows "Not uploaded".
**The watch deletes as soon as the phone acks**.

**What is not deleted**: `meta.json`, the DB rows (`recording` · `part` (marked `deleted=1`) · `job` · `step_run`), and
the local copies made by `transcribe` (`{base}.transcript.json/.txt` — they are the detail screen's input, so they stay regardless of the
part retention rule, §8). So even after the parts are deleted, everything the list and the detail need is
still local. This automatic deletion has a window of **7 days after a successful upload** (2026-09-03, fixed value, no setting): there is still
no rule that deletes just because time has passed; the retention sweep in every job pass deletes only when "the upload is done and 7 days have passed". In
between, playback in the detail uses the local parts as they are; after they are deleted, it downloads them from Drive and keeps them another 7 days. Deletion by the user
is always immediate.

#### Deleting in the app — "Delete recording"

The delete action on a list row. **One recording at a time**, and every time it asks what to do about Drive.

| Target | Action |
|---|---|
| Local parts · `meta.json` · result files (transcript) · the recording directory | Always deleted |
| DB rows (`recording`, `part`, `job`, `step_run`) | Always deleted |
| The `{base}/` folder in Drive and the files in it | **The user chooses. The default is "Keep in Drive"** |

- The confirmation dialog shows both branches on one screen: `Delete local only` (the default) / `Also delete the Drive folder`. The default is "keep"
  because the files in Drive already **belong to the user**, and downstream automation may already have consumed that folder.
  **The irreversible option is never the default.** A recording whose row has no Drive link (it never reached Drive) has nothing to
  choose, so the two branches are not shown (Android · Apple 2026-09-29).
- If there is audio that has not been uploaded yet (the retention rule is keeping it), the dialog says so first — `Audio not yet in Drive is deleted with it.` It does not say how many parts
  (2026-09-29: the part count is data the user does not see).
- If there is a running (`RUNNING`) Job, deletion does not happen and is refused with "Try again after the run finishes".
  `WAITING`/`NEEDS_AUTH`/`NEEDS_SPACE`/`FAILED` may be deleted, because the Job is deleted along with the recording.
- **"Also delete from Drive"** is `files.delete`. **The folder id is read only from what the recording's `drive.upload` step left
  behind** — `output_json.folderId` (when the step finished or is parked), otherwise the resume state `state_json.folderId`.
  **`drive_folder_cache` is not used**: that cache is keyed by the rendered *path* (`recly/memo/2026-08`), so the folder is a parent shared by
  every other recording of that month, and deleting one recording must not delete it. If the call fails, the local deletion still goes ahead and
  leaves "Could not delete from Drive" — once the local copy is gone there is nothing left to retry from, so the user is also shown the Drive
  link.
- For an **iCloud recording** (§3 "Storage location"), the same dialog speaks of iCloud instead of Drive (`Also delete the iCloud folder`), and the folder is
  deleted on every device through a coordinated delete. The failure message is likewise `Deleted here, but iCloud refused`.
- For a **local folder recording** (§3 "Storage location"), the dialog speaks of the local folder (`Also delete from the local folder`), and the
  recording's folder in it is deleted. The failure message is `Deleted here, but not from the local folder`.
- **Drive is the only path by which a deletion reaches other devices.** A recording deleted with "Also delete the Drive folder" disappears, at the next fetch, from the list of
  every other device that **adopted** that folder ("Recordings from other devices" below). The row on the device that **made** the recording stays —
  it is that device's original and Job record, and Drive has no authority to delete it. "Delete local only" reaches no device.
- **Deleting an adopted row** is a Drive deletion only. This device holds nothing but a cache, so "Delete local only" would be undone by the next
  fetch; the dialog does not offer that option, says "Recorded on another device. Deleting removes it from Drive and from every device.",
  and then calls `delete(id, deleteDrive = true)`. The folder id is `recording.drive_folder_id`.

**Core rule** — `RecordingRepository.delete(recordingId, deleteDrive): DeleteResult`:

- There are only three results. `Deleted(driveDeleted, driveError)` / `Busy` (there is a `RUNNING` Job, so **nothing is touched**) /
  `NotFound`.
- **Finding the row · the `RUNNING` check · reading the folder id · deleting from the four tables (`step_run` · `job` · `part` · `recording`) are one transaction.**
  The schema has neither FKs nor CASCADE, so the tables are deleted one at a time by name (`step_run` goes first because its query reaches
  in through the `job` rows that still exist). The directory is deleted right after the commit, inside the same lock section — this keeps a cancellation from
  slipping in between the commit and the file deletion and leaving a directory that no row points to.
- SQLite's single writer separates this transaction from `JobStore.claimRunning`: one of the two commits first, so the outcome is either **"`Busy` because the Job
  is `RUNNING`" or "nothing to claim because the row is already gone"**, never both.
- Drive `files.delete` is called after the commit. A failure is only reported as `Deleted.driveError`; it does not undo the local deletion.

#### Detaching from the account — Sign out vs Disconnect

This is about Google Drive — iCloud and the local folder have no in-app disconnect (§3 "Storage location"). The Google Drive settings offer **a single “Disconnect” button**. The connected account and the button are shown in the same
row and text size as the surrounding settings. Pressing the button opens one confirmation, and confirming performs **the Google grant revocation and the local cleanup on this device**
together. There is no “Disconnect this device only” option and no scope-selection menu.
The confirmation says, in one paragraph, that Recly's Drive access is revoked on every device connected with the same Google account, that pending work continues when the user reconnects to the same account, and that recordings and settings stay.
Recording deletion and the count of items not yet uploaded are not shown in this confirmation. Recordings are deleted only from the list.
The not-connected row carries “Record locally. Connect Drive to upload.” as small secondary text.
The Google permissions management link is normally hidden. It is shown, with an error notice, only when there is a record of a failed Google revocation (`revokeDebt`);
when only the local cleanup is unfinished, the existing “Disconnect” button retries.
Even if the disconnect fails or is interrupted because the app quit, the button keeps the name **“Disconnect”**. While processing, only a waiting notice is shown;
on failure, only an error telling the user to try again in settings. A retry continues the existing processing steps,
and the user is never asked to pick a separate “finish” action or a revocation · local-cleanup step.
As soon as confirm is pressed, the confirmation closes and the button changes to **“Disconnecting…”** and is disabled. This progress state lasts
from the Google revocation through the local cleanup. Even if the credentials or the saved processing step change midway,
it does not turn into the “Connect Drive” button before completion. On completion it switches to the not-connected screen; on failure, to the existing button, which can retry.
The internal `signOut` remains as an on-device credential cleanup used for things like auth recovery, but it is not exposed as a separate user action.

The recording list does not show with separate text whether a recording is stored locally. Recordings that download only the tracks needed for playback and recordings that keep the original tracks are shown by the same list criteria. The display text for `NEEDS_AUTH` is a gray “Upload waiting”, which is separate from local storage · playback. Individual recording rows and their expanded areas do not repeat the Drive connection explanation · button. The states, logs and error codes themselves are not changed.

| | Sign out (this device) | Disconnect |
|---|---|---|
| Purpose | Stop using it on this device | Keep Recly from accessing my Drive |
| Tokens | Delete this device's access/refresh token | Same + cancel (revoke) the Google grant |
| Other devices | No effect | **Recly's connection on the same Google account is cut as well** — revoke withdraws the account's approval **per Cloud project**, so even when pressed on the phone it removes the grants received by the Mac · PC clients of the same project (§6) |
| Recording files in Drive | Unchanged | **Unchanged** — they are the user's files and the app has no reason to delete them |
| Recording processing settings | Unchanged | **Unchanged.** They are this device's settings, not something derived from the account (§5), and once deleted they cannot be recovered from anywhere |
| Local secrets (STT keys) | Kept | **Kept** — for the same reason. The decision to detach the account is no reason to delete keys the user entered |
| Local Job · step_run | Kept (continue running after signing in again) | **Unfinished work is kept and paused**, and resumes when the user reconnects to the same Drive account. Finished work records, `drive_folder_cache`, and the "Delete local only" records `remote/ignored/*` are cleared. |
| Local recording files · `meta.json` | Kept | **Kept.** This action does not delete originals that have not been uploaded yet (principle 3). The confirmation briefly says that recordings and settings stay. Recordings are deleted separately, from the list |

- **The disconnect dialog must show that Recly's Drive access is revoked on every device connected with the same Google account.** Google can take time to apply a revocation, so the dialog does not tell the user that other devices' screens switch to not connected immediately. A revocation failure is reported separately through the existing error · recovery path.
  **Only when there is a record of a failed Google revocation does Drive settings show a link for removing the access directly in the Google account settings
  (<https://myaccount.google.com/permissions>).** Even when the local cleanup has finished and the account shows as not connected, the link stays as long as the failure record remains.
- The internal sign-out path does **not** call revoke — the reason and the citations are in §6 (iOS · macOS `signOut` vs `disconnect`,
  the Windows revoke subsection).

**Disconnect has two halves**: the grant revoke (the job of the shell's platform SDK) and the local cleanup (`ReclyCore.disconnect(alsoDeleteRecordings):
DisconnectResult`, the core's job). The core has no means of calling revoke, so the shell calls both **together**.

- **It runs while quiesced.** All of the local cleanup is inside `Executor.quiesced` — a Job that is already running finishes its current step and
  stops, and only after that is anything deleted. The cached access token is invalidated first (`tokenProvider.invalidate()`), and then
  the `tokens` namespace is cleared — in the reverse order, a token the shell held in memory would carry over into the next run.
- **Only four things are deleted**: the `tokens` namespace, finished work records (`job` · `step_run`), the Drive folder cache, and the "Delete local only" records (`kv` `remote/ignored/*`, §3 "Recordings from other devices"). The recording processing settings and the `secrets`
  namespace are left as they are (table above).
- **Resuming unfinished work (2026-09-21)**: applies to work that includes a Drive step. Step outputs · upload sessions · transcription request IDs · retry counts · wait times are kept. `job.drive_account_id` stores the identifier confirmed through Drive `about.get(fields=user(permissionId))`, and the state before the disconnect is kept in `disconnected_status`. With the same account, the previous state is restored and steps that succeeded are not run again. With a different account, the previous work keeps waiting as `NEEDS_AUTH` and sends no upload · transcription. If the account cannot be confirmed, nothing resumes. Work from a previous version whose account was never confirmed is restored only when the identifier of the existing Drive folder's owner matches; if there is no data to confirm the owner, it keeps waiting. Step records that a previous build already deleted cannot be restored.
- **`DisconnectResult(deletedRecordings, busyRecordings)`.** The delete option is kept for the core API's compatibility, but the revocation UI of all four shells always calls with `alsoDeleteRecordings=false`. When an internal API uses the delete option, the ids of recordings that could not be deleted because of a `RUNNING` Job
  go into `busyRecordings`. Those recordings and **their Job rows are kept**, and the screen says so —
  pressing again after the Job finishes deletes them then.
- **`DisconnectPhase` — `NONE` → `REVOKE_PENDING` → `REVOKED_CLEANUP_OWED` → `NONE`.** It is kept in the shell's settings store
  (the retry may happen in the next launch, and by then the token is already gone). The order is the point: `REVOKE_PENDING` is written **before
  calling** revoke (revoke deletes this device's credentials, so if it were written afterwards, it would be lost along with those credentials).
  `REVOKED_CLEANUP_OWED` is written after revoke returns and **before calling the local cleanup**, and it is cleared to `NONE` only when the cleanup finishes and `busyRecordings`
  is empty. If an exception is thrown, the phase stays as it is — that is the honest state. If the phase · debt is not written
  to the store, the credentials are not touched.
- **A retry does not delete someone else's grant.** A retry from `REVOKED_CLEANUP_OWED` **skips** revoke and only does the overdue local
  cleanup (that grant is already gone). A retry from `REVOKE_PENDING` asks the token store — if a refresh token is still
  there, revoke did not happen, so it calls revoke again; if not, it moves on to the cleanup. While a phase is pending, **sign-in and sign-out are
  held back** (if another account came into the empty slot, that account's grant would be deleted).
- **revoke debt.** When revoke fails, that fact is recorded in the store **before the tokens are deleted** (the phase alone cannot catch it:
  the failure branch also deletes the refresh token, so if the process dies in between, the phase is `REVOKE_PENDING` but there is no token, and that is
  misread as "revoke happened"). While the debt stands, the screen says "Google still lists Recly" plus a link to the permissions
  page, and **only the user's confirmation clears the debt** — not even a later successful revoke clears it. The app does not hold the account's
  identity, so it cannot tell whether the grant it just deleted belongs to the account the debt points to.
- **`DisconnectGate` — starting a recording is refused while a disconnect is in progress.** The "recording right now" value seen when the dialog opened
  goes stale while waiting for revoke (a network round trip). Meanwhile a tile · widget · shortcut · tray menu · meeting detection can start a new recording,
  and that recording has no Job yet, so it is not caught by the core's `Busy` check — the cleanup would end up deleting the files the recorder
  is writing. So there is an app-wide gate, and the disconnect holds it from before revoke until after the cleanup. A start is **refused, not
  queued** ("Disconnecting"), and it says why (§12: it does not stop anything on the user's behalf). The confirm button is also disabled while recording, and
  the value is read once more at the moment of confirmation.
- The gate decision and the actual capture start must be **the same critical section** — if execution suspends after the decision but before the capture opens (for
  example for a workflow list lookup), the disconnect can take the gate in between. Windows achieves this with `DisconnectGate.ifOpen { start() }`,
  Android with a structure in which each side writes its flag before reading the other's and there is no suspension point in between, and Apple with a MainActor
  `tryLock`. Apple has two more rules — if the restoration result is uncertain (`GoogleRestoration.failed`), the phase
  is kept and the user is asked to try again, and if the `UserDefaults` flush fails, the credentials are not touched.

### Drive layout (ADR-014)

```
My Drive/
  {folder template result}/           e.g. recly/memo/2026-08/
    {base}/                           e.g. 20260826T010000Z_desktop_01J9ABCD/
      {base}_p001_mic.m4a
      {base}_p001_sys.m4a
      {base}_p001_mix.m4a
      …
      {base}.meta.json
      {base}.transcript.json / .txt   (when a transcribe step was added)
```

- The folders are created by the app under the `drive.file` scope, so the app can find them again. Folder IDs are cached in the local DB.
- The `{base}` folder records `title` in its `description` and `recordingId` · `workflowId` in its `appProperties`. `description` is
  **the canonical title**, which later renames update ("Recordings from other devices" — Titles, below).
- Upload completion is decided by comparing the Drive file's `md5Checksum` with the local md5 (sha256 is for transfer verification; md5 is the value Drive provides).
- Part upload order: per track, starting from part 1. `meta.json` is uploaded last, so that "the meta exists → complete" can serve as a downstream trigger
  condition.

### Recordings from other devices (ADR-023)

**Showing finished recordings that have no work record (2026-09-21).** When a recording has finished and there is no Job on this device, the list and the
detail show its state as `DONE` · “Done”. The same goes for recordings whose work records were deleted by a disconnect. `NO_JOB` is
not exposed and not counted among pending items. This display does not newly decide that a Drive upload succeeded and does not
create a Job. If a real Job exists, its waiting · in-progress · failed state stays as it is, and the receiving · remote-upload · transcription
progress markers also follow the existing rules.

Phones and desktops signed in to the same account see **the same recording list**. There is no server and no index file — Drive already is that
index: the layout above leaves one `{base}/` folder per recording, stamped with `recordingId` in its `appProperties`, so a single folder
query (`mimeType = folder and trashed = false` — under `drive.file` only folders the app created come back, and among them the ones that have
`appProperties.recordingId` are recording folders; Drive rejects `appProperties has { key=… }` without a value with a 400, confirmed on a real account 2026-09-04) returns
every recording of this account.
It does not matter that the folder template differs per workflow, because no path is walked. Core `RemoteRecordings.pull()`:

1. List the folders and group them by `recordingId`. Finished rows already adopted are skipped, and the provisional rows of step 3 below are completed.
   **If a finished recording made on this device has no Job, the Drive copy is verified and restored.** Only when every part's number · track · file name · size ·
   SHA-256 matches the local meta and every Drive file ID is present are `drive_synced = 1` and each part's `drive_file_id`
   stored. The local meta · files · directory · `remote = 0` are kept. No new work record is created, and upload · transcription are not
   run again. An existing Job that is in progress or has failed is not overwritten, because its state is canonical.
   A restored recording shows as `DONE`, and if a valid `transcribe` progress marker remains, it shows as transcribing.
   If there is no local audio, playback downloads it from Drive with the restored file IDs. A copy that is incomplete or has different content is not treated
   as complete. If a restored folder is missing from this account's full list, only the Drive verification state · file IDs are cleared and the original is kept.
   Right after a reconnect, the fetch runs without the rate limit. A disconnect is serialized with any fetch in progress and resets the verification state · file IDs · fetch-throttle
   time, so verification results from the previous account are not used for a connection to another account. The startup recovery scan does not re-queue
   Job-less recordings whose Drive folder is known; it leaves them to this fetch.
2. For an unknown id, list the folder's children once and download `{base}.meta.json` (if there are two folders with the same id — a re-run to a different path —
   the newest one that is complete), and **adopt** the `recording` row (`RecordingRepository.adopt`): `remote = 1`,
   the folder in `drive_folder_id`, and `part` rows that are `deleted = 1` from the start, with `drive_file_id`. The directory is `recordings/{recordingId}/`,
   as for watch receipt, and `meta.json` is written there. **The meta builds paths** (the directory name · part file names), **so it is
   accepted only if it follows the schema**: only when `recordingId` equals the folder's, is in ULID format, and every `parts[].file` is exactly the name the naming rules
   give — otherwise it is rejected with `remote.meta.mismatch`.
3. **A folder without `meta.json` means "another device is uploading"** (2026-09-04). The meta is uploaded last (Drive layout above), so
   that folder is a recording being uploaded right now, and if it were put off to the next fetch, the user would see an empty list until a 20-minute upload finished.
   So a **provisional row** is opened from what the listing gave alone: `remote = 1`, that folder in `drive_folder_id`, and a `meta` with `source` read from the folder name
   `{yyyyMMddTHHmmssZ}_{source}_{ulid8}` (`phone` if unknown) and `startedAt` read from the ULID timestamp of `recordingId`,
   the title from the folder's `description`, empty `tracks` · `parts`, and `status = recording`. `meta.json` is written in the same place as for
   adoption. What the shell sees is `RecordingRecord.remoteUploading`. If the folder's `recordingId` is not a ULID, no row is
   opened — that value is the directory name. **Abandoned uploads**: a folder without a meta whose `createdTime` is more than 24 hours old is
   not adopted, and a provisional row already opened is deleted, once it reaches that age, the same way as when its folder disappears (`drop`). When a later fetch finds
   `meta.json` in that folder, it **swaps the provisional row for the real meta** (`finalized` · parts · `drive_file_id`) — this is the exception
   in step 1.
4. **The folder's `pending` marker**: a device that still has work left after the upload writes that onto the folder — `appProperties.pending` is
   the `type`s of the steps after the upload, joined with commas (`transcribe,transcript.publish`, `local.transcribe,transcript.publish`, or an empty string if there are none),
   and `appProperties.pendingAt` is the time it was written. It is written right after `drive.upload` creates or finds the folder (before the first
   byte), every time the runner finishes a step (what remains), and when the job becomes `DONE` · terminal `FAILED` (an empty value). The `appProperties` of `files.update`
   is a merge, so the folder's `recordingId` stays. The marker is **advisory**, so a failure is logged as
   `drive.marker.failed` and skipped. The reader is this fetch: it fills `recording.remote_pending` of every **remote** row with
   its folder's marker (`RecordingRecord.remotePending`), and the value is NULL if the marker is missing or empty or `pendingAt` is older than **8 hours** (the
   longest provider result timeout in §8). A row with `remote = 0` also receives it if its Drive copy was verified without a Job. For a row with a Job, its own job is canonical.
5. If the **folder** of a previously adopted row is no longer listed (another device deleted it, the user deleted it in Drive, or a re-run
   replaced it with another folder), the row and its cache are deleted (`drop` — inside the transaction it checks that `drive_folder_id` is that folder before deleting, so
   if a watch transfer has made the same id this device's own in the meantime, the row is not touched). Deletion comes before adoption, so if the same id
   remains in another folder, it is adopted again from that folder. If a **newer folder completes later** than the adopted row's folder (a re-run
   finished), the row moves there — the new folder's children are looked at once more only when there are two or more folders for that id. All of this happens only when the listing succeeded
   to the end — a failed fetch deletes nothing.
6. **"Delete local only" is remembered.** If a recording this device uploaded is deleted while its Drive folder is kept, the folder keeps being listed, so otherwise
   the next fetch would revive the recording just deleted as one from "another device". So `delete(deleteDrive = false)` leaves, inside the delete transaction,
   `remote/ignored/{recordingId}` → folder id in `kv`, and the fetch does not adopt that id while that folder is listed
   (`adopt` itself also checks this record inside its transaction, so a deletion that slips in during a fetch is not revived either). When the folder
   disappears from Drive, the record is deleted too. "Disconnect" clears all of these records — when the account is attached again, the device sees everything in
   Drive, like a new device. The folder id of a recording this device uploaded is also written **to the row's
   `drive_folder_id`** the moment `drive.upload` creates or finds the folder — because "Delete local only" must know which folder it kept
   even after a disconnect has cleared the queue (the step outputs) (for recordings uploaded before that, it is read from the step output; if there is none, the recording is deleted without a record — and shows up again at the next fetch).

Properties of an adopted row:

- **It has no Job, and none is ever created.** `enqueue` returns `PartsPurged` (Drive already has it, and this device has no original to
  send). Even if the recovery scan sees it as a "finalized row without a Job" and calls enqueue, the answer is the same. Its state in the list is **`DONE`**, not `NO_JOB`
  — it looks exactly like a finished recording (2026-09-04 user decision: a permanent "another device" state is meaningless), and there are no actions other than details · delete ·
  open in Drive (no retry · upload). Only the delete dialog knows that it belongs to another device (the `remote` flag).
- **It has a Drive link** (2026-09-05 user decision). It is a row read from that folder, so the folder is known by definition — the shell's `link` is
  the upload step output's `folderWebViewLink`, otherwise `RecordingRecord.driveFolderUrl` (the meta's `drive.folderUrl`, and if that is also
  missing, the canonical URL built from the folder id, `https://drive.google.com/drive/folders/{id}`). Recordings this device uploaded take the same
  fallback, so they can be opened from the moment the folder exists. The exception is a placeholder row that another device is still uploading (§9 principle 2:
  the two in-progress rows have no actions).
- **`uploaded()` is true.** It came from Drive, so by definition it is uploaded. The "parts not uploaded" in the delete warning is 0.
- **Playback and transcripts come from Drive.** `AudioParts` downloads by `part.drive_file_id` instead of the upload output, and `RecordingResults`
  finds `{base}.transcript.json` in the folder by name (if another device transcribes later, it shows up the next time the recording is opened). Downloaded parts are
  deleted by the retention sweep after 7 days like any cache — there is no Job, so they age by the file mtime alone (the adoption branch of `claimPurge`).
- **The title's canonical source is the Drive folder's `description`** ("Titles" below).
- **A provisional row has the same properties.** It is `remote = 1`, so it has no Job, `uploaded()` is true, and delete · `enqueue` behave as for an adopted
  row. The only difference is that it has no parts yet (there is nothing to play), and renaming is refused because of `status = recording`
  — it works from the moment the meta arrives.

What the shell uses to draw "what is happening right now" is three values of `RecordingRecord`. None of the three is a Job on this device,
so they cannot be read from the queue.

| Value | Meaning |
|---|---|
| `receiving` | A watch transfer is coming in (`remote = 0` · `source = watch` · `status = recording`) — the phone never records with `source = watch`, so this shape can only be a transfer. The row's `startedAt` is **the ULID timestamp of the `recordingId` the watch created** (§1 "Identifiers · time"): even when a 20-minute recording is handed over after it ends, it sits in its proper place in the list |
| `remoteUploading` | Another device is uploading (`remote = 1` · `status = recording`) — the provisional row of step 3 above |
| `remotePending` | What another device still has to do after the upload (`transcribe` and so on) — the marker of step 4 above |

#### Titles

Right after stop, the title popup in all four shells has a wide `Save` button (minimum width 120pt/dp) at the bottom right and a `Cancel` button just to its left.
`Cancel` (including back navigation and closing the popup) is **the action that discards the recording that just ended**. It is not an action that only skips the title. It deletes the local files · meta · DB rows
of the one pending recording ID, and does not create a Job or wake the workflow. Only one of save · cancel can take
that recording, so a save action that arrives late after a cancel does not start an upload. Other recordings and Drive are not
touched, and a refused · failed deletion is shown as an error. The placeholder of the title field is the existing translation `Untitled`.
The input starts empty, and the placeholder is never saved as the actual title. No separate hint is shown for an empty
input. Pressing `Save` with an empty title keeps the date · time name, and the selected head count is applied as well.

Separately from the title entry right after stop (`updateTitle`, only until the Job runs), **the name can be changed at any time on the detail screen**
(`ReclyCore.rename(recordingId, title)`; for this device's recordings and adopted ones alike; an empty string means "Untitled" = the timestamp
name). The rules that keep a title, once uploaded, from differing between devices:

- **Local is immediate.** The row · `meta.json` are written right away, and `title/pending/{recordingId}` → title is left in `kv`. The list
  updates shortly through `recordings.observe()`.
- **Pushing to Drive** (`RemoteRecordings.pushTitles`): the folder `description` is changed with `files.update`, and `{base}.meta.json` in the folder
  is overwritten with the local meta (`updateMedia`). The pending entry is deleted only when **both** succeeded — if the title was changed again in the meantime,
  the value differs and the entry stays (`kvDeleteIfValue`). Only one push runs at a time (a mutex, so that two renames in a row do not reach Drive in
  reversed order). It is tried once right after `rename`, and again at the end of every fetch (job pass). A recording whose folder is not
  known yet (before upload) waits, and so does a recording whose folder is known but that has no `meta.json` yet (upload in progress) — the
  upload in progress may upload a meta with the old title, and a later pass has to fix that. If the folder returns 404, another device deleted it, so the pending entry is
  dropped. When a recording is deleted (by any deletion), its pending entry is deleted too — so that a stale title is not pushed
  if the same folder is adopted again later.
- **Pulling from Drive.** The `description` in the folder list the fetch receives is the title (no extra request; Drive returns only what is listed in `fields`,
  so `RECORDING_FOLDER_FIELDS` includes `description`). If it differs from the row's title, the row is changed
  (`applyTitle`; for adopted rows and this device's own alike). But if that recording has a pending push, **this device's change wins**. If the row
  knows its own folder, it reads that folder's value; if not (uploaded before this version), it reads the newest folder's and **remembers that folder on the row**
  (`rememberFolder`; a folder it already knew is not overwritten) — that gives the recording's renames somewhere to go. If `description` is
  empty, nothing happens — clearing a title does not propagate between devices (only renames propagate).
- If two devices change it at the same time, the one that wrote to Drive later remains. Nothing beyond that.

When to fetch: **every job pass** (the end of `ReclyCore.runDueJobs`, 2-minute throttle) and **when the list comes on screen** (the shell calls
`pullRemoteRecordings(force = true)`, in the background without blocking the screen; on phones, every time the list tab is opened). Phones (Android · iPhone) also run the same fetch
when the list is **pulled down**, and then wait until the fetch finishes — both phones use the platform's pull-to-refresh indicator
as it is. This is the only exception to the ban on system spinners (§9 "Screen principles" 1) (2026-09-29 user decision): when the fetch finishes quickly, the square
loader and `Loading…` flash on and off, whereas the platform indicator follows the finger down and slips back up silently. The throttle is **30 seconds while another device
is doing something** — if at least one row is `remoteUploading` or has `remotePending` (asked of the DB at fetch time; no new state is
added), a list that said something is in progress must be able to change its answer soon. Besides `jobs.observe()`, the shell's list also
subscribes to `recordings.observe()` (changes to the recording table), so adoption · provisional rows · completion · marker changes · deletion show up immediately.
With no sign-in, the fetch is silently skipped (`skipped = "auth"`).

**Premise**: under the `drive.file` scope, files created by different client IDs (the phone's Android client, the Mac's iOS client…) must be
visible to each other within the same Cloud project. The code assumed this even before — it finds the month folder `recly/memo/2026-09` with
`findChild` and reuses it, so otherwise a folder with the same name would have been created again on every device. The official documentation does not state the unit,
so **this is confirmed on a real account**: it holds if Drive has only one folder for the same month. If the files are not visible, this fetch just returns an empty
list and breaks nothing, and the only alternative is the full `drive` scope, which conflicts with ADR-009.

### Storage location (ADR-024)

**2026-10-02 user decision**: by default, recordings and results go to Google Drive, and iPhone · Mac can switch to **iCloud**
in settings. Android phones · Galaxy Watch · Windows cannot use iCloud — iCloud has no official API for Android · Windows, and this difference
is accepted. Apple Watch still hands its recordings to the iPhone (ADR-002), and the iPhone uploads them to its own storage. First run is the same as before.

**2026-10-03 user decision**: Mac, Windows and the Android phone can also choose a **local folder** — a folder the user picks on the device, such as
an Obsidian vault or a folder another tool syncs — as a third storage, with no Google account needed; the iPhone was added the same day, so an Apple
Watch recording reaches an Obsidian vault through its iPhone. Its rules are under "Local folder" below; the watches do not offer it.

- **Where to choose**: in builds that can offer iCloud or a local folder, the top section of settings becomes `Storage` and has the chips `Google Drive` ·
  `iCloud` (product names, so they are not translated; `iCloud` only where it is offered) · `Local folder` (only where it is offered, "Local folder" below). A tap saves immediately (`ProcessingSettingsRepository.setStorage`, §5).
  Below the chips, only the row for the chosen storage appears, in the same shape — for Google Drive, `Drive connected` · `Disconnect` or
  `Drive not connected` · `Connect Drive`; for iCloud, `iCloud connected` or `iCloud not connected` plus where to turn it on (the menu names of system settings
  as they are — iPhone: `Sync this iPhone` in `Settings → [your name] → iCloud → Drive` and Recly under `Saved to iCloud → See All`; Mac:
  `Sync this Mac` in `System Settings → … → Drive` and Recly under `Apps Syncing to iCloud Drive`). The app has no way to sign the user in to iCloud
  or to turn on iCloud Drive, so the iCloud row has no connect button. On the Mac, when not connected, `Open iCloud Settings` (System Settings'
  Apple Account → iCloud) is added; on the iPhone there is no public link that opens the iCloud screen, so only the path is written (a private `App-Prefs:` link
  violates review guideline 2.5.1). The status is checked again when the settings screen appears, when the app becomes active again, and when `NSUbiquityIdentityDidChange` is received
  — turning it on in settings and coming back changes it right away. Once it becomes usable, the first level of the storage folder (`recly`) is created right away, so that even before the first
  recording a Recly folder shows in the Files app · Finder and Recly shows in the system's list of iCloud apps. The list · notifications keep
  using the short sentence of `ICLOUD_UNAVAILABLE`. Switching to iCloud does not disconnect Drive — Drive is still needed to download and
  delete earlier recordings that are in Drive. That disconnect is in the Drive row under the Google Drive chip. In builds that offer neither — an iPhone build without the iCloud
  entitlement — the section is `Google Drive` as before.
- **Fixed per recording**: the storage is `storage.provider` of the recording processing settings, and it is fixed along with the settings when the recording starts (§5). Recordings already
  started and recordings being uploaded go to their original storage. Uploaded recordings are not moved (no migration). A retry the user presses
  does the remaining work with the current settings (§5), so a failed upload retried after switching storage goes to the new storage.
- **The place on the iCloud side**: under `Documents` of the container `iCloud.app.recly` (the "Recly" folder in the Files app · Finder), the same
  layout as Drive — parts, `{base}.meta.json` and result files inside `{folder template}/{base}/`. What Drive keeps on the folder itself (the title in
  `description`; `recordingId` · `workflowId` · `pending` · `pendingAt` in `appProperties`) is kept in `{base}.folder.json` inside the folder, and
  because the list reads that file, `createdTime` is written there too. If the folder already exists, that file is not overwritten (just as Drive writes these only when it creates
  the folder). File · folder ids are `icloud:` + the path under `Documents` (`StorageKind.ofId`), so ids left in rows and step outputs
  find their own storage even when the setting changes.
- **Upload and completion**: the upload step (`drive.upload`, `store: icloud`) **copies** the files into the container (coordinated writes),
  and the system uploads them. The step ends when iCloud says it has received every file (`ubiquitousItemIsUploaded`). Until then it is
  `WAITING`, rechecked every 30 seconds, and it does not use up attempts (`ICLOUD_UPLOADING`). In the list it looks the same as a Drive upload —
  badge `UPLOADING`, no reason line under the row, top status `UPLOADING`, not counted among pending items. The reason line of `Waiting for iCloud` (`ICLOUD_UNAVAILABLE`)
  is in the warning color of waiting, not the red of failure. The originals' 7-day
  window (ADR-017) starts after that (principle 3) — the reason for copying instead of moving is that if the user signs out of iCloud before the upload without choosing "Keep a
  Copy", the files in the container are removed from the device. Transcription results are written to the same folder and do not wait for the
  upload.
- **When it cannot be used**: if the user is not signed in to iCloud, Recly's iCloud Drive is turned off, or the build has no entitlement, upload and result publishing
  are `WAITING`, rechecked every 5 minutes (`ICLOUD_UNAVAILABLE`, list `Waiting for iCloud`, `Retry` available). Why not `NEEDS_AUTH`:
  there is nothing the user can do inside the app, and when iCloud comes back the work continues on its own.
- **Space**: if the system cannot upload because the account is out of space (`NSCocoaErrorDomain` 4354), it is `NEEDS_SPACE`, the same as Drive
  (`ICLOUD_STORAGE_FULL`, list `No space in iCloud`). The banner button is `Retry` — there is no public link that opens the iCloud storage screen
  in settings.
- **List (ADR-023)**: the fetch lists Drive (if signed in) and iCloud (if the currently chosen storage is iCloud) together and merges them.
  A device that has not chosen iCloud does not touch the container — otherwise a "Recly" folder would appear in the iCloud Drive of someone who never asked for it.
  Rows of a storage that could not be listed this time are not deleted or changed (adopted-row drop, "Delete local only" records, restored copies,
  progress markers — all of them). iCloud syncs files one at a time in no particular order, so even with `meta.json` present, a recording is complete only when **every part the meta names
  is listed at its size**. Otherwise it is a provisional "another device is uploading" row, and the 24-hour rule is measured by the
  `createdTime` in `{base}.folder.json`. Lists are merged only between devices that use the same storage — if iPhone and Mac both use iCloud, they see the same list; if one of them
  uses Drive, neither sees the other's new recordings.
- **Titles**: the canonical source is `description` in `{base}.folder.json`, and a rename fixes that file and `meta.json` together (the same rule as §3
  "Titles"). If two devices change the same file at the same time, iCloud picks one version as current, and the shell deletes the other versions
  when it reads (`NSFileVersion` — the one written later remains).
- **Progress marker**: `appProperties.pending`/`pendingAt` in `{base}.folder.json`. A step that runs again every 30 seconds while waiting for upload confirmation
  does not rewrite the marker — every write makes every device read that file again.
- **Playback · deletion**: parts deleted by the retention sweep are fetched from the container (a download is requested and waited for up to 2 minutes) and their sha256 is checked.
  "Also delete the iCloud folder" deletes the folder on every device through a coordinated delete. What the user deletes in the Files app stays in iCloud Drive's
  "Recently Deleted" for 30 days (Apple), but whether the same holds for what the app deletes has not been checked — the dialog's default is, as with Drive,
  the option that does not delete.
- **No web link**: an iCloud folder has no fixed web address, so there is no `meta.drive` and no `Open in Drive`. The Mac has `Show in
  Finder`; the iPhone has no action in that place.
- **Not tied to an account (Known limitation)**: Drive work is tied to the Drive account and stops under a different account (§3 "Detaching from
  the account"), but iCloud work is not tied. If the device signs in with a different Apple ID, the remaining uploads go to the new account. There is no in-app
  disconnect either — whether to use iCloud is decided by the user in system settings (iPhone: Settings › [your name] › iCloud, Mac: System Settings › [your name] › iCloud).
- **Build**: iCloud is off in a fresh checkout. It needs the container registration and the App ID capability (`app.recly`, `app.recly.mac`) and a Developer ID provisioning profile
  for Mac Release (§12), and `apple/Config/Local.xcconfig` turns it on
  (`RECLY_ICLOUD_CONTAINER`, `RECLY_PHONE_ENTITLEMENTS`, `RECLY_MAC_ENTITLEMENTS`). In a build that does not turn it on, `ReclyICloudContainer` in Info.plist
  is empty and iCloud is not offered.
- **Agents**: none of Claude · ChatGPT · Gemini has an iCloud connector (checked 2026-10-02). iCloud recordings are read through the Mac's local folder
  (`~/Library/Mobile Documents/iCloud~app~recly/Documents/recly/`) (`skills/recly-notes`).
- **Verification**: it goes as far as the core JVM tests (`ICloudFilesTest` · `ICloudUploadTest` · `ICloudJobTest` · `ICloudRemoteRecordingsTest` ·
  `StorageSettingTest`) and RecKit `ICloudStorageTests`. Actual sync between two real devices on the same Apple ID has not been
  checked (§20). In particular, when two devices each create the same month folder (`recly/memo/2026-10`) before syncing, it is unknown whether iCloud merges them into one
  or splits them like `2026-10 2` — the list does not walk paths but looks for the folder property files, so even if they split, the list is correct; the Files app
  just shows two folders.

#### Local folder

- **Where**: iPhone, Mac, Windows and the Android phone (2026-10-03). The `Local folder` chip in `Storage`, and under it one row in the shape of the Drive
  row — `No folder chosen` · `Choose folder`, or where the folder is (Mac · Windows the path, iPhone the folder's name as Files shows it, Android a
  readable name of the tree such as `Documents/Notes`) ·
  `Change folder`; a folder that cannot be reached adds `This folder cannot be reached. Choose it again.` and offers `Choose folder`. Picking a folder
  releases the uploads waiting for one at once (`ReclyCore.resumeFolderWaits`) and runs the queue. The status is checked again when settings appear and
  when the app becomes active again.
- **How the shell reaches it**: `CoreDeps.localFolder` (`LocalFolder`). The Mac and Windows hand the core's `PathFolder` a function that returns the
  picked path — the Mac app is not sandboxed (§12), so a path is enough. The Android phone uses the Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE`
  with a persisted read · write grant; an existing file is written over in place, since `createDocument` never replaces and would make `name (1)`, and
  every file is made as `application/octet-stream` so the provider keeps its name exactly). The iPhone is sandboxed: the folder picked in Files
  (`On My iPhone/Obsidian`, a folder in iCloud Drive, another app's provider) is kept as a `.minimalBookmark`, resolved into a security-scoped URL
  whose access is held while the app runs, and every read and write goes through `NSFileCoordinator` (RecKit `PickedFolder`; Apple, "Providing
  access to directories", checked 2026-10-03) — a coordinated read also brings down a file that is only in iCloud. The user can take that access
  back in Settings › Privacy & Security › Files and Folders, which reads as a folder that cannot be reached. Which folder was picked is kept on the
  device — Mac `UserDefaults` `storage.localFolder`, iPhone `UserDefaults` `storage.localFolderBookmark` (with its name in `storage.localFolderName`),
  Windows the shell's preferences, Android the app settings — not in the processing settings, and not exported.
- **Layout**: the same as Drive — inside `{folder template}/{base}/`: the parts, `{base}.meta.json`, and with transcription on
  `{base}.transcript.json/.txt` plus `{base}.transcript.md` (§8 "Result files"). Nothing else: there is no folder-properties file, because the folder
  is never listed and the title is already in `meta.json`, which a rename rewrites together with the `.md`. File · folder ids are `folder:` + the
  path under the picked folder (`StorageKind.ofId`).
- **Upload and completion**: the upload step (`drive.upload`, `store: folder`) **copies** the files, reads each one back for its md5, and is done
  right away — the copy is the upload. It makes no network request, so the copy and the transcript publishing run in the offline pass as well (§5
  "Fixed processing settings") — on Android they wait for neither a connection nor Wi-Fi only. The originals' 7-day window (ADR-017) then starts as it does
  after a Drive upload, and playback after the sweep reads the parts back from the folder. The app deletes nothing in the folder except through "Also delete from the local folder".
- **When it cannot be used**: no folder picked, the folder gone (a removed drive, a deleted or renamed folder), or the Android grant taken back —
  upload and result publishing are `WAITING`, rechecked every 5 minutes (`FOLDER_UNAVAILABLE`, list `Waiting for the local folder`, in the warning
  color of waiting, not the red of failure). Picking the folder again resumes them at once.
- **Changing the folder**: later writes go to the new folder under the same relative paths — including the rest of a recording whose upload was cut
  short. Files already copied stay where they are. Because ids keep only the relative path, a folder the user moved and picked again is still found
  for playback and deletion.
- **This device's alone**: a local folder is never listed (§3 "Recordings from other devices") — pointing two devices at one folder that another tool
  syncs does not merge their lists, and a row of this device is never dropped for not being listed. Drive's "Disconnect" leaves local folder jobs as
  they are, as it does iCloud's.
- **No web link**: no `meta.drive` and no `Open in Drive`. The Mac has `Show in Finder` and Windows `Open the folder` for the recording's folder (in
  the folder picked now); the iPhone and the Android phone have no action there.
- **Known limitations**: a full disk is an ordinary retryable failure, not `NEEDS_SPACE`. On Windows, Controlled Folder Access can refuse writes to
  protected folders such as Documents.
- **Verification**: core JVM tests (`FolderUploadTest` · `FolderStorageTest` · `StorageSettingTest`), RecKit `LocalFolderStorageTests` and, on
  the iOS simulator, `PickedFolderTests` (pick, copy, read back, a relaunch through the bookmark, a folder gone), Windows
  `ShellStorageTest` · `PreferenceSettingsTest`, Android `FolderLabelTest`, and on the `rec36` emulator the real external-storage provider (2026-10-03):
  picking `Documents/…`, a recording landing under its exact names, a rename rewriting `meta.json` in place, a deleted folder making the upload wait
  with `attempts=0`, a new pick releasing it within a second, playback read back from the folder, and both delete options (`SafFolderTest`, opt-in,
  needs a folder picked first). Not checked: a real Windows PC (paths, the `JFileChooser`, Controlled Folder Access, a removed USB drive), a grant
  revoked by the system on Android, the transcript through a real engine on Android, and on a real iPhone: writing while the phone is locked (a
  background pass), a folder in iCloud Drive or another app's provider, and Obsidian picking up what was written (§20).

### Watch → phone transfer contract

- Unit: one part file at a time + `meta.json` last. Android Data Layer paths:
  `/rec/part/{recordingId}/{part}/{track}/{sha256}/{file}`, `/rec/meta/{recordingId}` — the file name is carried in the path because
  the meta (= `{base}`) arrives last. Apple carries the same values in the `metadata`
  dictionary of `WCSession.transferFile`.
- **The channel is closed after `onOutputClosed`** (2026-09-04, found on a real Watch7). The Task of `ChannelClient.sendFile` ends when the
  **request is accepted**, not when the transfer completes, and the official documentation says not to close the channel right after that and to learn of completion through `onOutputClosed`.
  Closing right away put the CLOSE in line behind the data, and the phone received `onInputClosed(CLOSE_REASON_REMOTE_CLOSE)`,
  discarded the file and did not ack (a 3.8 MB part was sent 6 times over an afternoon, 0 acks). The watch registers the callback before the first byte and
  waits for `CLOSE_REASON_NORMAL` before closing (any other reason is a link failure = `STALLED`). The phone accepts both `NORMAL` · `REMOTE_CLOSE`
  as "everything arrived", and sha256 is the final judge — a disconnect · timeout · local close means a truncated file, which is discarded.
- **The phone's listener service is a new instance for every event** (2026-09-04, found on a real Z Fold7). Play Services delivers
  `onChannelOpened` · `onInputClosed` · `onChannelClosed` each to a newly created `WearableListenerService` instance and
  destroys it 1.5 seconds later. So the list of "channels being received" written to an instance field in `onChannelOpened` was empty at `onInputClosed`,
  and files fully received into the cache were ignored without any log (the second cause of the 0 phone acks). The staging file path is
  a function of the channel path alone (`cache/rec-transfer/{recordingId}/{file}`), and `onInputClosed` computes it again — no in-memory state
  is kept between the two callbacks.
- For each part, the phone verifies the sha256 and sends an ack `{recordingId, part, track, ok}`.
- The watch only **records** part acks and keeps the files; only after receiving `ack-meta ok:true` does it delete the parts · meta · directory · local row
  (if a later part or the meta gets a fatal nack, the parts acked earlier must still be there for resending · recovery to be possible).
  A `SHA256_MISMATCH` nack resends that part once; the second time it is fatal.
- The phone registers the recording in `recordings` and creates the Job when it receives the meta. The `recordingId` in the meta body must match the one in the path
  (mismatch → `RECORDING_ID_MISMATCH` nack, no core call), and **`ack-meta ok:true` is sent only after enqueue and waking the runner
  have finished** — if something fails before that, it does not ack and leaves it to the watch's resend (`acceptMeta` · `enqueue` are idempotent).
  If 24 hours pass with only parts and no meta, the orphan parts are deleted.
- The temporary row before the meta **belongs to the receiver**: the phone's `RecordingRecovery` skips `recording` rows for which `TransferReceiver.receiving(recordingId)`
  is true (rows with the `transfer.pending.*` mark the receiver left on the first part), and such a row is closed only by the meta arriving or by the
  24-hour orphan cleanup. A temporary row has empty `tracks` and zero-length parts, so finalizing it the way crash recovery does would freeze those empty values into the
  meta, and the Job would fail with `NO_INPUT_TRACK` (2026-10-01, found on a real iPhone that restarted between the parts and the meta).
  `RecordingRecord.receiving` (judging by the row's shape) is not used instead because the watch also revives its **own** `source = watch`
  recordings through the same recovery.
- **Known limitation**: if `ack-meta ok:true` is lost, the watch resends the meta, and even if the phone has already uploaded and cleaned up and
  returns `Incomplete(all parts)`, the watch still has the parts (no deletion before ok), so it resends the parts · meta and the two
  converge (the phone's `acceptPart` overwrites, `enqueue` returns `AlreadyDone`). There is no case in which the watch treats `ack-meta ok:false` as
  completion; if a requested part is missing locally (deleted externally), it is marked failed with `PART_MISSING_LOCALLY` and the rest is kept.
- During the transfer, the recording start time · `recordingId` are the values the watch created, used as they are (the phone does not regenerate them). The temporary row
  before the meta arrives (`TransferReceiver.placeholder`) likewise reads its start time from **the ULID timestamp of `recordingId`**
  — using the arrival time would make just that recording jump to the top of the list on a phone that received a 20-minute recording after it ended. What the shell uses to recognize this row
  is `RecordingRecord.receiving` (the table at the end of §3 "Recordings from other devices").

---

## 4. Webhooks (formerly docs/04)

> **Retired (2026-09-24)**: webhooks were removed from the product — the `webhook` step, the completion webhook setting, the payload and signature (Standard Webhooks),
> `spec/webhook.payload.schema.json` and the local receiver script are all gone. Webhooks of jobs queued by earlier builds are not sent
> either. This section is a record. **Exception**: the backoff formula in "Response handling" and the "a 429's Retry-After … capped at maxDelaySec" rule
> remain valid as the retry rules of every step (`Executor` cites them). The shape of a result file entry (`files[]`,
> `track: "transcript"`) also remains as step output (§8 "Result files").

### Request

> **Retired (2026-09-24)** — §4 preamble.

```
POST {url}
content-type: application/json
user-agent: rec/{version} ({platform})
webhook-id: {stepRunId ULID}
webhook-timestamp: {unix seconds}
webhook-signature: v1,{base64(HMAC-SHA256(secret, "{webhook-id}.{webhook-timestamp}.{body}"))}
```

- The signature is [Standard Webhooks](https://www.standardwebhooks.com/) as is (ADR-010). Without a `secretRef`, the
  `webhook-signature` header is omitted.
- The secret is the raw bytes in secure storage. In the UI, a generate button creates 32 random bytes and shows them as `whsec_` + base64
  (the Standard Webhooks convention). Signing uses the bytes left after stripping the `whsec_` prefix and base64-decoding. A generated value is **shown only
  once, at that moment**.
- On a retry, `webhook-id` stays the same and `webhook-timestamp` and the signature are made anew. The receiver dedupes by `webhook-id`.
- Timeout 30 seconds. Redirects are not followed.

### Payload

> **Retired (2026-09-24)** — §4 preamble.

```json
{
  "type": "recording.completed",
  "id": "01J9STEPR0N0123456789ABCDE",
  "timestamp": "2026-08-26T02:05:00.000Z",
  "data": {
    "recording": {
      "recordingId": "01J9ABCDEF0123456789ABCDEF",
      "source": "desktop", "platform": "macos",
      "title": "Weekly meeting",
      "startedAt": "2026-08-26T01:00:00.000Z", "endedAt": "2026-08-26T02:00:12.400Z",
      "durationSec": 3612.4, "timezone": "Asia/Seoul",
      "tracks": ["mic", "sys", "mix"],
      "context": { "app": "us.zoom.xos", "participants": 3 }
    },
    "files": [
      { "part": 1, "track": "mix", "name": "20260826T010000Z_desktop_01J9ABCD_p001_mix.m4a",
        "bytes": 3601234, "sha256": "…",
        "drive": { "fileId": "1AbC…", "webViewLink": "https://drive.google.com/file/d/1AbC…/view" } },
      { "part": 1, "track": "meta", "name": "20260826T010000Z_desktop_01J9ABCD.meta.json",
        "bytes": 2210, "sha256": "…", "drive": { "fileId": "…", "webViewLink": "…" } }
    ],
    "folder": { "path": "recly/memo/2026-08/20260826T010000Z_desktop_01J9ABCD",
                "drive": { "folderId": "1XyZ…", "webViewLink": "https://drive.google.com/drive/folders/1XyZ…" } },
    "workflow": { "id": "01J9ABCDEF0123456789ABCDEF", "name": "Meeting" },
    "device": { "id": "7c1e4b2a-…", "platform": "macos", "name": "MacBook Pro" }
  }
}
```

- `files[].drive` and `folder.drive` are filled only when a successful `drive.upload` exists **before** this step. **Otherwise null.**
  `files` holds only the files that upload step uploaded.
- The meta file is included in `files[]` as `track: "meta"`.
- If a preceding `transcribe` step succeeded, its result files are added as `track: "transcript"` (two entries, `{base}.transcript.json` and
  `{base}.transcript.txt`) (§8). If it failed or there is no such step, there are no entries. There is no separate object such as
  `data.transcript` — the receiver only needs to look at `files[]` and Drive.
- `type` is only `recording.completed`. It was there from the start so that receivers branch on `type` when `recording.failed` and others are
  added later.

### Response handling

> **Retired (2026-09-24)**: the table below is webhook-only, so it is a record. The backoff formula below the table and the table's
> "a 429's Retry-After … capped at maxDelaySec" remain valid for every step (§4 preamble).

| Response | Handling |
|---|---|
| 2xx | Success. Body ignored |
| 408 · 425 · 429 · 5xx · network error · timeout | Retry (the `retry` rules). **If a 429 has `Retry-After`, that value takes precedence, capped at `maxDelaySec`** |
| Other 4xx | The step is FAILED immediately (terminal), `onError` applies. The core message is `WEBHOOK_HTTP:{status}` |

Backoff: `min(initialDelaySec × 2^(attempt-1), maxDelaySec)` ± 20% jitter. With the defaults: 30s, 60s, 120s, … capped at 3600s,
8 attempts.

### Receiver-side verification

> **Retired (2026-09-24)** — §4 preamble.

```js
import { Webhook } from "standardwebhooks";
const wh = new Webhook(process.env.REC_SECRET); // "whsec_…"
const payload = wh.verify(rawBody, {
  "webhook-id": req.headers["webhook-id"],
  "webhook-timestamp": req.headers["webhook-timestamp"],
  "webhook-signature": req.headers["webhook-signature"],
});
```

The n8n Webhook node has no signature verification, so either verify with the library above in a Code node or, for a local n8n,
use the `http://127.0.0.1` exception and leave it unsigned.

The repository's local receiver `scripts/webhook-receiver.mjs` was deleted on 2026-09-24 (§20 "Local webhook receiver").

---

## 5. Workflow storage · secrets (formerly docs/05)

> **2026-09-24**: workflow storage (the local document · device default pointer · schema migration · export/import · save/edit · first-run
> seed) was **retired**. What remains valid in this section is "Not synchronized", "Fixed processing settings", "Secrets"
> and "Tokens".

### Not synchronized

**Both the recording processing settings and secret values are per device.** Even when two devices use the same account, they do not see each other's settings,
and a change made on one does not appear on the other by itself. Nothing is kept in Drive `appDataFolder` —
no settings file and no `secrets.enc`, and so no pull/push, merge, freeze or `dirty` marker (replaces ADR-007).
The only thing Recly writes to Drive is recording files, and those go to the user folder under the `drive.file` scope (§3).

The one way to move settings between devices is **export/import** of the recording processing settings (see "Fixed processing settings" below).
Secret values never leave in any file — a new device has no value for the `secretRef`, and the UI shows "No key on this
device".

What is not synchronized likewise includes: Job state, tokens, app settings (language · Wi-Fi only) (Wi-Fi only exists only in the phone
shells: Android WorkManager UNMETERED, iOS allowsCellularAccess; 2026-09-03) (ADR-008). **The recording list is the
exception** — not because it is synchronized, but because the recording folders in Drive are the list (ADR-023, §3 "Recordings from other devices").

### Local state (`sync_state`)

> **Retired (2026-09-24)**: the two rows below (`localDoc` · `deviceDefaultWorkflowId`) are not used. The key currently used in `sync_state` is
> the recording processing settings `processing/settings` (see "Fixed processing settings" below). What follows is a record.

One key/value table with just two rows, both belonging to this device.

| Key | Value |
|---|---|
| `localDoc` | This device's workflow document. Running, editing and display all read this one document (`WorkflowStore`) |
| `deviceDefaultWorkflowId` | This device's default workflow id (ADR-016). It is not inside the document, so an exported file does not carry over another device's choice |

Even when the document cannot be read (a `schema` written by a later version, a step type this build does not know), **the row is left untouched**: being
unable to read something is not the same as it not existing, and seeding the default workflows over it would erase the user's bytes. Such a device runs with the 2 default
workflows and the row stays as is.

### Schema

> **Retired (2026-09-24)**: the schema verdicts for the workflow document and the 1·2 migrations were removed. The format of the recording processing settings is
> `spec/recording-settings.schema.json` v1 (see "Fixed processing settings" below). What follows is a record.

The supported schema is **3** (§2). The verdict the parser reaches applies equally to the local document and to an imported file.

| `schema` | Name | Behavior |
|---|---|---|
| `> 3` | **Newer** | `ParseResult.UnsupportedSchema` → not read. For an import this is the error corresponding to "Update the app"; for the local document, it runs on the defaults and leaves the row, as above |
| `1..2` | **Outdated** | Read with the current rules and raised to the current schema (`ParseResult.Ok.migratedFrom`). The next save writes it back in the current schema |
| `1..2` + retired fields | **Field-retirement migration** | `workflows[i].enabled` · `.isDefault` · `.trigger`, which ADR-016 removed, are **dropped knowingly, by name**. `trigger.minDurationSec` moves up to the top level of the workflow and `trigger.sources` is dropped |
| `1..2` + unknown fields | **MigrationBlocked** | If there are fields the typed model would drop (`ParseResult.MigrationBlocked.fields`), it does not migrate and rejects the document — the moment it is written back, someone else's data would disappear |
| `< 1` | — | `Invalid` |

The lowest readable value is `WorkflowParser.MIN_SCHEMA` (=1). Schema 1 is schema 2 without `transcribe`, and schema 2 is
schema 3 with three more retired fields, so reading with the current parser is the same as reading with each schema's own rules.

### Export · import

> **Retired (2026-09-24)**: workflow export/import (`recly-workflows.json`) was removed. Today's export/import covers
> only the recording processing settings (see "Fixed processing settings" below). What follows is a record.

These are two items in the settings screen, and on the core side there are only `WorkflowRepository.exportJson()` / `importJson(json)`. Picking and
writing the file is the shell's job (Android SAF, iPhone share sheet, Mac save panel, Windows file chooser).

- **Export**: writes the saved document **exactly as serialized**. There is no separate format; the document itself is the format
  (`spec/workflow.schema.json`). The default file name is `recly-workflows.json`. **The device default workflow pointer is not included,
  and neither are secret values** — the file holds only `secretRef` names (ADR-008).
- **Import**: parses and validates the file (§2), then **replaces the whole document.** There is no merge — the two devices' documents are not
  copies of each other, so there is no base to merge against. That is why a confirmation dialog first asks "Replace with N workflows".
  A file written in an old schema is migrated **exactly like** the local document, and a file that fails parsing or validation writes nothing and
  returns the parser's error list as is (the same display as editor validation).
- Even if an import removes this device's default workflow, the pointer is left as is. A pointer with nothing to point to is not an error but
  the state in which `WorkflowSelector` picks nothing, and the shell asks the user to pick again (ADR-016).

### Saving · editing

> **Retired (2026-09-24)**: the workflow editor and its save rules were removed. The concurrent-edit rule for saving settings is the
> revision comparison in "Fixed processing settings" below. What follows is a record.

- A save happens in one go in `WorkflowRepository.save`, in the order validate (§2 parser round trip) → replace `localDoc`. A failure is a save failure,
  and a failed save writes nothing.
- A save stamps the document envelope (`revision += 1`, `updatedAt = now`, `updatedBy = deviceId`), because an exported file must be able to tell
  when and from which device it came.
- Even on one device, two editors (two desktop windows, an import and an editor) can touch the same workflow. `WorkflowMutator`
  rereads the document inside a mutex and rejects with `MutationResult.Stale` if the `updatedAt` from when the editor opened has changed
  — there is no 3-way merge, so making the user reopen is the honest choice.

### First run

> **Retired (2026-09-24)**: there is no seeding of the default workflow ("Memo") and no stamping of the device pointer. First run is the recording processing settings'
> `initialize()` (see "Fixed processing settings" below). What follows is a record.

- There is only one default workflow, "Memo" (Drive, `recly/memo/{{yyyy}}-{{MM}}`, mono) (user decision of 2026-09-04; the "Meeting"
  starter was removed). It is seeded with a fixed ULID (`00000000000000000000RECMEM`) and `updatedAt = 1970-01-01T00:00:00.000Z` — meaning
  nobody has edited it yet; the first edit stamps a real time. The name is created in the app language at the time of the first seed (en: "Memo",
  §7 rule 6).
- `WorkflowRepository.seed(preferredDefaultId)` seeds the document if there is none, and stamps the default the shell picked
  ("Memo" on every device) **only when the pointer is empty**. It never moves a choice the user already made, and once stamped, it is never
  reverted — the remote document that would have justified a revert no longer exists.
- Which path seeded the document first does not matter (a background enqueue's `current()` can run before the shell's `seed()`).
  The only condition is "is the pointer empty".

### Fixed processing settings

2026-09-24: recording is fixed to **record → upload the original → transcribe → upload the transcript** (`ProcessingPlan`:
`drive.upload` → `transcribe` or `local.transcribe` → `transcript.publish`; with transcription OFF, only `drive.upload`).
The phone offers three tabs (Record, List, Settings), the desktop its existing menu/tray plus processing settings, and the watch only recording and transfer.
There are no user-editable workflows and no webhooks, and no compatibility layer that reads or migrates earlier workflow documents or settings.
The overall direction and the conditions for a real-device release follow the [fixed processing flow plan](research/2026-09-24-fixed-recording-flow-plan.md).

- `ReclyCore.processingSettings` uses a separate key `processing/settings` in `sync_state`. There is no new table and no DB version change.
- The format is [`spec/recording-settings.schema.json`](../spec/recording-settings.schema.json) v1. It sets the storage location · minimum length,
  `local`/`external`/`off`, the language, and the external provider/key reference/endpoint/model.
  There is no global step array and no user workflow selection. Key **values** are never read, saved or exported.
- There is no post-completion integration (webhook). The settings format (`spec/recording-settings.schema.json`) has no `webhook` field.
- Export offers only the current recording settings. There is no export of earlier settings and no internal backup for recovery.
- Starting from Siri/Shortcuts (`StartRecordingIntent`, no parameters) also uses the fixed settings.
- **A recording pins the settings in force when it starts, and a retry the user presses does the remaining work with the current settings** (2026-09-25).
  Automatic runs and automatic retries follow the job snapshot — saving settings alone never sends a waiting recording to a different provider.
  A manual retry (`JobService.retry`, failed or waiting state) rebuilds the plan with the current settings and merges it: succeeded steps keep
  their row and their definition from that time (the upload is not repeated), and the remaining steps start over with the new definition — a step whose definition
  changed also drops its earlier submission state (`state_json`) — steps missing from the new plan are deleted unless already finished, and newly added
  steps are added. The recording's pinned settings (`processing/recording/{id}`) are also changed to those settings. Retrying a failed recording after changing the key or
  fixing the CLOVA address runs with the new key and address.
- **Storage** (2026-10-02, §3 "Storage location"): the settings' `storage.provider` (`drive` by default · `icloud` · `folder`). The single entry point for changing it is
  `setStorage`, which saves it immediately as the next revision. iCloud is accepted only on a device where the shell provided a container, and the local folder only
  where it provided one (`CoreDeps.localFolder`) — otherwise `Invalid`. Which folder was picked is the shell's, kept on the device, and not in this document.
  Saving the form and importing keep the storage this device has — it is a choice about the account, so each device decides it. If the
  storage changes while the form is open, the form takes the new revision and keeps its draft as is (`storageChanged`).
- Speaker diarization and the minimum/maximum speaker count are not exposed in the UI. A new plan requests diarization automatically on providers/models that support it
  and infers the speaker count with the default range. A stored past `diarize=false` or speaker-count hint does not constrain a new plan.
  Groq and explicitly chosen non-diarizing OpenAI models use plain transcription. If the OpenAI model is left empty, the existing adapter's
  diarization-capable default model is used. An explicitly set model, provider or key is never replaced automatically.
  Local checks `supportsDiarization` at run time and reflects it in the actual request, and Apple SpeechTranscriber, which does not support it, still transcribes.
- If no settings are stored, `initialize()` creates the **Ready** state with the defaults (local transcription, memo folder `recly/memo/{{yyyy}}-{{MM}}`).
  It does not inspect old workflow documents or device pointers — there is no migration or review (`NeedsReview`) state.
  On builds and devices where the shell did not pass a local engine (`LocalTranscriptionEngine.installed == false`: Apple below OS 26, Android · Windows with less than
  6 GiB of memory, Android in a 32-bit process, desktops without the sherpa-onnx native library), it prepares OFF instead of local — a method that always fails is never the default. The shell then hides the local option,
  and if local is already stored, shows it only together with a notice that it cannot be used.
- Folder templates do not allow `{{workflowName}}` or `{{title}}` (there are no workflow names).
- Settings that break constraints such as endpoint HTTPS or model length are rejected on save and import. Input is never truncated or silently
  corrected.
- Save and import compare the current revision to prevent concurrent-edit overwrites. An imported revision/device is not adopted;
  the settings are saved as a new revision of this device. Future versions, unknown fields, explicit nulls and corrupt documents are not replaced with defaults.
- Switching to OFF/local preserves valid existing external settings. Incomplete input drafts are managed in the shell, and the stored settings hold
  only values that passed validation, including inactive fields. Changing the method does not erase key values.
- Per-provider `invokeUrl` · `model` are also remembered per provider, like keys (`transcription.providerDetails`, 2026-09-28). Choosing another provider
  and coming back restores them, whether mid-edit or after saving. One provider's values are never carried to another provider. Values that cannot be saved
  (an address with a placeholder left in it, and so on) are not remembered. The `invokeUrl` field is left empty, and the address format
  (`https://clovaspeech-gw.ncloud.com/external/v1/{appId}/{invokeKey}` and so on) appears only as placeholder text.
- `read()`/`observe()` have no initialization side effects. The shell calls `initialize()` at startup. The watch does not run this
  initialization.

- A new recording pins the settings revision in `processing/recording/<id>` when it starts. A watch recording pins the phone's settings after the receipt is validated.
  A settings change does not alter existing recordings or in-progress jobs.
- The runner uses the existing persistent queue with an internal fixed ID (`ProcessingPlan.ID`) and the `local.transcribe`/`transcript.publish` steps.
  It first saves the completed result locally and atomically, so a failed result upload does not rerun the API/model.
- A local run shows `Transcribing on this device`, a wait for automatic resume shows `Transcription pending`, and no forced-retry button is offered.
  A local transcription waiting out its retry interval after a failure (a wait with `last_error` left) is not a wait for automatic resume. Like other steps, it
  shows `Retry pending`, the error and `Retry` (2026-09-27).
  In the settings' API key list, stored keys can be deleted one by one, with a confirmation before deletion.
- The local settings have the same shape in all 4 shells (2026-09-26): a `Speech recognition model` row with the model name (Apple `Apple Speech`, Android · Windows
  `Qwen3-ASR 0.6B` — product names, so not translated); if the model is missing, `To transcribe on this device, download this model once (about 1GB).`
  (Apple leaves out the size because it cannot know it) and a `Download model` button; while downloading, the §9 square loader and `Downloading model…`;
  and `On-device transcription does not separate speakers.` Once downloaded, the notice and the button disappear and only the name row remains.
  `Download model` cannot be pressed while a recording is starting, in progress or ending. On a metered connection (mobile data), Android asks first
  (`Download over mobile data?` — Cancel · `Download on Wi-Fi` · Download), and Windows asks when the capture helper's `--network-cost`
  answers metered (`Download over a metered connection?`; if unknown, it does not ask).
- When the transcription method is switched to `On device` and the recording language is `Automatic` or `Korean and English`, the language changes to the app language, or to
  English if the model lacks that language — the local list has only explicit languages, so leaving it as is would make `Save` impossible to press. An explicit language the
  user chose is kept even if the model lacks it, with a line saying it is not supported (Apple · Android, 2026-09-29).
- **Waiting for and downloading the model (2026-09-26).** A recording that cannot be transcribed locally because the model is missing is not a failure but a `NEEDS_MODEL` wait (§10).
  The list row shows `Waiting for speech model` (badge `NEEDS_MODEL`, warning tone) and a `Download model` chip, and the banner shows the same reason and the download
  action — the download happens right there instead of sending the user to settings. In local mode, when the engine exists, the model does not, and no recording is waiting yet,
  a `Transcribe on this device` card appears once above the recording screen (Mac popover, Windows tray) (`Not now` · `Download model`;
  `Not now` is remembered on the device and the card does not appear again). The body says only what gets downloaded — Android · Windows `On-device transcription needs Qwen3-ASR (988 MB), an open-source speech recognition model.`,
  Apple `On-device transcription needs Apple’s speech recognition model.`
  (2026-09-27). The user chooses the transcription method, so the card promises no outcome such as nothing being sent out once the model is downloaded. Once a recording is waiting, the card hides and the banner (with the count) is the only notice.
  Settings, banner, row and card share one download state per shell and show progress as `Downloading model… N%` (Android · Windows also show
  bytes, like `412 MB of 988 MB`; Apple shows only % because it cannot know the size). While downloading, the banner shows the progress and `Cancel download`,
  and waiting rows hide the chip (progress appears in the banner alone). When stopped, the first action of an expanded row is the emphasized `Download model`. With a partial
  download it is `Resume download`, and cancelling (`Cancel download` — distinct from the form's `Cancel`) keeps the downloaded part. On Android it is a WorkManager foreground job (progress and cancel in the notification), so
  the download continues after leaving the app, and choosing `Download on Wi-Fi` waits for an unmetered connection (`Waiting for Wi-Fi`).
- Apple's local engine is the iOS/macOS 26 `SpeechTranscriber`/`SpeechAnalyzer`, and the user requests the language asset download in settings.
  For a shared asset installation already handed to the OS, the OS manages when it completes and when it is retried.
  The model counts as present when `AssetInventory.status` answers `installed` or `SpeechTranscriber.installedLocales` lists the locale — on
  macOS 26.6 the status still answered `supported` minutes after the system had finished the install, while `installedLocales` already listed it (2026-10-05).
  When a model download finishes, only `NEEDS_MODEL` waits for the same language resume automatically;
  completed uploads, saved transcription progress, other failures and the disconnected state are kept. The download is requested from the system regardless of thermal and power state
  (thermal and power limits apply only to transcription, 2026-09-27). If the request ends and the model is still missing (except on cancel), `The model didn’t finish downloading. Try again.`
  is shown — if only the button came back, pressing it would look as if it did nothing.
  One file is fed to one analyzer, and PCM is read through a bounded buffer. Multiple recording parts are joined losslessly, and temporary files are cleaned up on success, failure or cancellation.
  At thermal state `serious` or above it waits/stops (relaxed 2026-09-27 — `fair` is common even while charging, and the user cannot guess why it stopped).
  Low Power Mode does not stop it. Confirmed segments are saved, so once the device cools, it resumes automatically at the next chance to run.
- Local computation runs one at a time per device and yields when a new recording starts. A platform run expiry/cancellation also cancels the native analyzer.
  The local-only pass does not run Drive authentication, network requests or result publishing — except the upload and transcript steps bound for a
  local folder (§3 "Storage location"), which write to the device and make no request, so they need no connection and ignore Wi-Fi only. The Wi-Fi
  constraint on network uploads stays as is.
- The local engine on Android · Windows is Qwen3-ASR 0.6B INT8 (`Qwen3Asr`) on sherpa-onnx 1.13.8 (2026-09-26).
  The model (about 1 GB, 7 files) is not bundled in the app and is downloaded only through the user's download action (settings, a waiting recording or the banner, the first-run card) — it is pinned by commit, size and SHA-256, downloaded in 8 MB range
  requests that resume after an interruption, and only files whose hash matches are moved into place (`LocalModelStore`; paths in §15). The folder is Android
  `noBackupFilesDir/models/` and Windows `{dataDir}/models/`, a separate one per model revision. Once downloaded, it is never downloaded again —
  an update that changes only the runtime uses the same folder, and when a new model has been fully downloaded, the previous revision's folder is deleted.
  Silero VAD cuts the recording into speech segments, and segments are split into pieces of 20 seconds or less for decoding — for input beyond the model's context,
  sherpa-onnx returns empty text instead of an error. Confirmed segments are saved per piece, so cancel, resume and waiting take effect at piece boundaries.
  Timing is per segment and there is no diarization. It runs on 2 CPU threads. Android waits at thermal state `SEVERE` or above (battery saver
  does not stop it, 2026-09-27); Windows has no thermal signal to wait on. Languages are chosen only from those this model knows (Ukrainian excluded; they are passed to the prompt by their English
  names). Windows extracts the native libraries from the jar into `{dataDir}/native/{revision}/` and uses them from there — the sherpa-onnx default
  loader extracts into a new temporary folder on every run, and a loaded DLL cannot be deleted. That folder is user-writable, so on every load
  it compares SHA-256 against the copy inside the app, rewrites it on a mismatch, and deletes previous revision folders (`Natives`). The Android APK includes sherpa-onnx only for the 64-bit
  ABIs (`arm64-v8a` · `x86_64`) and leaves out the C · C++ API libraries that JNI does not use — in a 32-bit process
  the local engine is not created. Inference runs inside the app process and stops when capture starts.
  **Real-device heat, long recordings and accuracy have not been verified** (§20). There is no automatic fallback to heavy CPU inference or an external API.
- Local transcription v2 states `speakerIdentification` and `timing` explicitly. Without diarization support it writes `speakers=[]`, `speaker=""`, and
  it does not present the result as having identified a single speaker. Reading v1 external API results and existing files is kept.

### Secrets

- Name: `^[a-z][a-z0-9_]{0,31}$`. The recording processing settings hold only the name (`secretRef`) (ADR-008). The name is **the provider id as is** (`elevenlabs`, `clova` …, 2026-09-25) — each provider keeps its own key, so switching providers never sends another provider's key, and the settings screen has no name field. The settings screen and the transcription node on the recording screen show the provider by `SttProviders.displayName` (ElevenLabs, CLOVA Speech …, proper nouns that are not translated), while transcript files, logs and settings documents use the id as is.
- Storage:

| Platform | Implementation | Details |
|---|---|---|
| Android phone · Galaxy Watch | `androidx.security:security-crypto` 1.1.0 `EncryptedSharedPreferences` | The master key is `MasterKey` (`AES256_GCM`, wrapped by Android Keystore), key names `AES256_SIV` · values `AES256_GCM`, file `rec_secure`, entry names `{ns}/{key}`. The only thing the watch holds is the installation's device UUID (ADR-002) |
| iPhone · Apple Watch · macOS | Keychain generic password | One item per `(ns, key)`, service is the app-name prefix + ns, accessibility `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` |
| Windows | Credential Manager | `CredReadW`/`CredWriteW`/`CredDeleteW`/`CredEnumerateW` via JNA, `CRED_TYPE_GENERIC`, `CRED_PERSIST_LOCAL_MACHINE` (not uploaded to roaming profiles) |

`EncryptedSharedPreferences` · `MasterKey` are deprecated upstream, but **we keep using them** (the code records that fact with
`@Suppress("DEPRECATION")`). With a direct DataStore + Keystore implementation, (a) the seal/unseal code and key
rotation would fall on us, (b) there are zero users to migrate, and (c) `EncryptedSharedPreferences` decrypts even the key names, so
`names(ns)` works without a separate name index (a separate index drifts from the real store through restores and crashes). If the library is
actually removed, we move then.

On the Windows development host (macOS), `DevFileSecureStore` (plaintext base64 JSON) is used instead — it is **development only** and
is never selected on Windows.

- The entry point for values is **`ReclyCore.secrets`** (`SecretsRepository.put/delete/get/names`). The core owns a single namespace,
  and it must have a single entry point, so shells do not write to `SecureStore` directly.
- **Values never leave the device.** Not in files, not in exports, not to the watch. On a new device the user enters them
  again — in exchange, a key leaking from one device does not leak the other devices along with it.
- If the value is missing at run time, that step is FAILED immediately with `MISSING_SECRET` (no retry), and `onError` applies.
- UI: the shared secret (API key) list offers only name lookup and deletion, and appears inside the external transcription item only when external transcription is chosen (2026-09-25; changing the method does not delete keys).
  If the chosen provider's key is stored, an **"API key … ✓ Saved on this device"** row with `Replace key` · `Delete` is shown instead of the input field —
  the ✓ is the same character as the chips' selection mark, in the success color (§9 "Every state is color + text"). The input field is not filled with a fake mask (`••••`),
  and the mask is not used as placeholder text: placeholder text disappears as soon as typing starts and screen readers do not read it properly (W3C WAI · NN/g),
  and Compose M3 keeps the placeholder of a labeled field transparent until focus. Pressing `Replace key` brings up an empty input field and `Cancel`.
  The list below (`Other providers’ keys`) shows only the other providers' keys. The value of a new key is entered in the external transcription item of the recording
  processing settings. The webhook signing key generation action (en `Generate a webhook signing key`) was retired (2026-09-24).
- **An unreadable secure store fails closed.** If the shell's Keychain/Keystore/Credential Manager refuses the list query
  itself (`errSecMissingEntitlement`, `errSecInteractionNotAllowed` on a locked device, an ACL denial), that exception passes through the core
  unchanged — reading it as "none" would wrongly put "No key on this device" on steps that have a `secretRef`, and
  the `tokens` cleanup of `ReclyCore.disconnect` would report a namespace that still holds tokens as emptied. So `disconnect`
  throws, which leaves the shell still owing the cleanup (`REVOKED_CLEANUP_OWED`) and lets it show a retry.
- **Deleting a key runs only after confirmation** (2026-09-10). The key name is shown first.
  Cancelling keeps the key. The confirmation screen does not show the key value. This is common to Android · iPhone · macOS · Windows.
- **"Disconnect" does not delete secrets** (§3). The values are not derived from the account but are this device's settings, and once deleted they cannot be
  recovered from anywhere.

### Tokens

- The Google access/refresh token lives in the same store as secrets, in a different namespace (`tokens`). It is not synchronized.
- The core calls only `TokenProvider.accessToken()`. Refreshing and prompting the user to sign in again are the shell's job (§6).

---

## 6. Authentication (formerly docs/06)

This section is about Google Drive authentication. iCloud (§3 "Storage location") has no app authentication — the system uses the device's iCloud account — and
neither does the local folder.

### GCP project

1. Create the project and enable the Drive API.
2. OAuth consent screen: External, **published to Production** (under Testing, the refresh token expires after 7 days). The app name, logo and privacy
   URL are required, and after publishing, an app with only non-sensitive scopes passes without verification.
3. Scope: `https://www.googleapis.com/auth/drive.file` **only.** It is non-sensitive and sees only the files the app created.
   Nothing is kept in appdata any more, so `drive.appdata` is not requested (§5). **Do not add
   other scopes** (calendar and the like are sensitive → they trigger the verification process).
4. Client IDs (one per platform, Google policy):

| Client | Type | Identifier |
|---|---|---|
| Android | Android | Package name + signing SHA-1 (for debug and release each) |
| iOS | iOS | Bundle ID `app.recly` |
| macOS | iOS type (keeps the existing client and callback scheme) | Bundle ID `app.recly.mac` |
| Windows / JVM | Desktop app | loopback redirect `http://127.0.0.1:{port}` |
| Wear OS · watchOS | None | The phone acts on its behalf |

The client files (`google-services.json`, `GoogleService-Info.plist`, `client_secret*.json`) are in `.gitignore` and
are injected locally at build time. If `GIDClientID` in the `Info.plist` of the two Apple apps is a placeholder, the sign-in button is disabled and the Job of a stopped
recording is parked at `NEEDS_AUTH`.

### Android

- Sign-in: Credential Manager `GetSignInWithGoogleOption(serverClientId)` — only the "Sign in with Google" button flow
  is used. Sign-in happens only when the user presses **Connect Drive** in Settings. Google classifies the bottom sheet (`GetGoogleIdOption`) as
  an automatic prompt the app raises on its own and the button flow as the counterpart of a button the user pressed (the Firebase quickstart and
  Flutter `google_sign_in` draw the same line); this app never raises sign-in on its own, so it does not use the bottom sheet.
  The old "allowed accounts → all-accounts bottom sheet → button" ladder never reached the button on Android 17 devices, because the system
  `CredentialSelectorActivity` waited without ever being drawn (2026-10-02). Restoring the account and the Drive permission when the app is
  reopened does not go through this sign-in.
- If the button flow fails with `NoCredentialException`, the result is `SignInResult.NoAccount`. The UI opens the system add-account screen with
  `Intent(Settings.ACTION_ADD_ACCOUNT, EXTRA_ACCOUNT_TYPES=["com.google"])`,
  and on return retries sign-in **only once** (to prevent a loop).
- After a cancellation (`GetCredentialCancellationException`) or a Play Services failure, no other account-picker screen is opened.
- No nonce is used. The ID token is used only to identify the account and is never sent to a server, so there is nothing to bind a replay attack to.
- The add-account branch is logged as `auth.signIn.fallback=addAccount`.
- Authorization: `Identity.getAuthorizationClient(activity).authorize(AuthorizationRequest{scopes: drive.file})` →
  `accessToken` (1 hour). Silent for an account that has already allowed it.
- Refresh: the app does not hold a refresh token itself. `TokenProvider` calls `authorize()` again when the token is within 60 seconds of expiry.
  If `hasResolution() == true` (re-consent needed) comes back in **a WorkManager context with no activity**, the Job is set to `NEEDS_AUTH`
  and a notification asks the user to open the app. **When the app comes to the foreground** (`MainActivity.onStart`), if the user is signed in and there is a `NEEDS_AUTH` Job,
  the activity calls `authorize()` again without the user pressing anything (2026-09-04) — for an account that has already allowed it,
  this passes silently and the Job returns to `PENDING`; if consent is really needed, the consent screen appears right there. If the user cancels, the banner
  stays and the next start asks again. The buttons on the banner and the row make the same call (when signed out, they lead to sign-in in Settings).
- The Drive row in Settings shows connected (account email + `Disconnect Drive`) only when **both the account and the Drive permission are present** (2026-09-29).
  The state where the consent screen was closed after sign-in, leaving only the email saved and no permission, shows, as on iPhone, `Drive not connected` + a `Connect Drive` row,
  and that button tries again (starting from account selection; there is no separate "Allow again" row). Permission = an access token is in secure storage
  (`AndroidTokenProvider.held`). While a disconnect is owed (`disconnectPhase.owed`), it is the disconnect row regardless of the permission.
- **Closing** the account picker or the consent screen **says nothing** (`SignInResult.Cancelled` · `AuthorizeResult.Cancelled`).
  A failure shows only `Could not connect Drive` in the danger color under the row (the same sentence as on iPhone), and
  the Play Services reason is not put on screen but logged (`auth.signIn.failed` · `auth.authorize.failed`, `reason`).
  If the device has no Google account, the same spot shows `Add a Google account, then connect Drive again`. Success is told with no sentence, by the row
  changing to the account (the same as on iPhone).
- Storage: only the access token + its expiry time, plus the account email for picking the same account again (`account/email`), in secure storage.
  Signing out deletes both.

### iOS · macOS

- Uses AppAuth for **OAuth Authorization Code + PKCE (S256) requesting only `drive.file`**.
  `openid` · `email` · `profile` are not requested. The flow is `Connect Drive` → choose a Google account and allow the Drive permission → back to the app,
  with no separate consent to sharing name and email. It follows Google's consent format for a single non-sign-in scope.
- Authentication happens in the system authentication session (`ASWebAuthenticationSession`). The existing app window is used as the presentation anchor, and
  the app shows no notice window of its own such as “Continue signing in in your browser”. Google's authentication screen and the operating system's confirmation remain.
- The existing iOS-type client ID and the `/oauth2callback` of the reversed client ID scheme are kept.
  The `GIDClientID` key in `Info.plist` is a name kept for compatibility with the existing settings and release checks.
- AppAuth handles the random `state` and PKCE verification, the code exchange and token refresh. It requests `access_type=offline`, and
  the connection is shown as complete only after the response's actual Drive permission, client ID and refresh token are verified and saving to the keychain succeeds.
  A denied permission or a cancellation returns to the unconnected state, and a save failure is never shown as a successful connection.
- Credentials are kept as AppAuth state in the `app.recly.drive.oauth` keychain item. The Mac uses the same login keychain as before,
  and the iPhone uses a this-device-only keychain that is accessible after the device is unlocked. A token rotation result is saved before it is returned.
  A token within 60 seconds of expiry is refreshed, and a 401 clears the cache, forces a refresh and then follows the core's retry rules.
- The old GoogleSignIn `auth` keychain item is read with GTMAppAuth, and only credentials that have the Drive permission are migrated.
  The old item and the email sign-in hint are deleted only after the new item is saved. The profile and ID token are not copied into the new item.
  The migration neither revokes Google permissions nor requests new consent. So a previously granted profile permission itself
  is not revoked automatically at Google; when the user disconnects and connects again, the Drive-only request is used.
- A new connection does not request, store or show the account email, and Settings shows `Drive connected`.
- A missing keychain item and a read failure are told apart. A read failure is never treated as unconnected in a way that skips revoking the permission.
  Authentication or refresh results that arrive late during a disconnect are not saved.
- Disconnecting POSTs the refresh token to `https://oauth2.googleapis.com/revoke` and then cleans up the keychain and local work.
  No token is passed in a redirect. The existing disconnect phases, failure recovery and record of revocation failures are kept,
  and a keychain deletion failure is left as a local cleanup failure to retry. Revocation applies per Google Cloud project.
- **Background URLSession**: the token is refreshed before each chunk transfer, and **a 401 replans that chunk** (ADR-015).
- App Review 4.8: Recly has no account creation or authentication of its own; Google authentication is used for access to the user's Drive.
  Recording, and playing back recordings stored on the device, work without a Google connection. Under the current contract, transcription is preceded by a Drive upload.
  The review notes present the actual build's unconnected record · save · play path.

### Windows (JVM)

- Not in the core but in Kotlin in `windows/app`: a Ktor server on a temporary 127.0.0.1 port → the system browser opens
  `https://accounts.google.com/o/oauth2/v2/auth` (PKCE S256, `access_type=offline`) → the code is received →
  exchanged at `https://oauth2.googleapis.com/token`. The refresh token lives in Windows Credential Manager, and refresh is
  `JvmTokenProvider` (60 seconds before expiry).
- The redirect is `http://127.0.0.1:{port chosen by the OS}`. Not `localhost` — RFC 8252 §8.3 "the use of `localhost`
  is NOT RECOMMENDED … avoids inadvertently listening on network interfaces other than the loopback interface".
  The port is not registered (§7.3 "MUST allow any port to be specified at the time of the request"; Desktop-type
  clients do not take redirect URIs at all). The server opens only when sign-in starts and closes when the response arrives.
- `state` is a 128-bit random value, and a mismatch is rejected (§8.9). The redirect is accepted **only once** — any program on this
  device can knock on that port. A second request gets only the "This sign-in has already been handled" page.
- `prompt` is **not attached on the first sign-in** ("the user will be prompted only the first time your project requests
  access", "include `prompt=consent` only when necessary"). Signing in again on a device that already has a refresh token
  means switching accounts, so only then is it `prompt=select_account`.
- `include_granted_scopes` is not used ("Incremental authorization is not supported for installed apps or
  devices"). There is no `login_hint` either — no profile scope is requested (ADR-009), so the app never knows an address to fill in.
  **Windows does not store the account email.**
- Internal sign-out is **local only** and does not call `https://oauth2.googleapis.com/revoke`: "Revocation removes all OAuth
  2.0 scopes previously granted to a **project**, invalidating any issued access or refresh tokens for all clients
  registered under that project." The remaining grant expires after 6 months without use. Only the app's "Disconnect" calls `/revoke`.
- `invalid_grant` during refresh means the grant is dead → discard what is stored and sign in again (`AuthRequiredException`). The same code at the exchange
  step means a PKCE mismatch, so the two are told apart by which call failed.
- A Desktop-type `client_secret` is not a secret ("Installed apps … cannot keep secrets"). Still, it differs per developer,
  so it is not committed.
- Opening the browser has 30 seconds, waiting for consent 5 minutes. `Desktop.browse` blocks, so it is opened in a separate scope and only the **waiting side**
  gives up. The success page is a single inline HTML page — if it loaded even one external resource, this URL, which contains the code,
  would leak out as the `Referer`.

### Wear OS · watchOS

No authentication. The watch does not access Drive (ADR-002). (Sending the workflow summary was retired on 2026-09-24.)

### The core interface

```kotlin
interface TokenProvider {
    /** A valid access token. Throws AuthRequired when refresh fails or re-consent is needed. */
    suspend fun accessToken(): String
    /** Lets the shell force a refresh after a 401 */
    suspend fun invalidate()
}
```

When the core gets a 401, it calls `invalidate()` and retries once; on another 401 it sets the step to `NEEDS_AUTH` (no retry count is
used up; it resumes when the user signs in).

### Refresh token limits

100 per account per client ID. 6 devices × a few reinstalls leave plenty of room. A token expires after 6 months without use — phones and desktops
are fine, and a device that has not been used for a long time signs in again.

---

## 7. i18n (formerly docs/07)

The UI supports **12 languages**: English (en), Korean (ko), Japanese (ja), Simplified Chinese (zh-Hans) · Traditional Chinese (zh-Hant),
Spanish (es), French (fr), German (de), Portuguese (pt), Arabic (ar), Hindi (hi), Russian (ru).
The default language follows the system language and can be changed inside the app (setting name **App language**). The transcription language is a
separate setting from the UI language (§8). Its setting name is **Spoken language** — the language spoken in the recording, not a translation target.

### Rules

1. **The base language is English** (the default value of the resource keys). Region tags are normalized to a supported language, and only an unsupported language falls back to English.
   For Chinese, an explicit Hans/Hant script takes precedence; without one, TW/HK/MO map to Traditional and everything else to Simplified. Arabic gets an RTL layout.
2. **Language setting values**: `system` (default) and the 12 language tags above. **It is a per-device setting and is not synchronized** (it does not go into
   the recording processing settings). It is stored by platform convention (Android `LocaleManager.applicationLocales` + DataStore, Apple `UserDefaults`,
   Windows `java.util.prefs`). **The settings UI is one row that shows the current language as its value**, and on all 4 shells the list opens from the
   dropdown at the end of the row (value + `▾`, §9 principle 4) (2026-09-29; the phone used a dialog) — however many languages are added, the row stays one line. The list
   has **no** `System default` item: only each language's own name (`English`, `한국어`, `日本語`, `العربية`, etc.); while nothing has been chosen yet,
   the app follows the system language and **that language appears selected** (the row shows its name too) — in exchange, we accept that there is no item that
   returns to following the OS after an explicit choice. The names are not translated in any language. A choice takes
   effect immediately (rule 3), and flags are not used.
3. **Runtime switching**: changing the setting changes the UI without a restart (Android `setApplicationLocales` recreates the activity,
   SwiftUI uses the `\.locale` environment value, Compose recomposes from the locale state). AppKit/UIKit notices (NSAlert,
   UNNotification), the tray menu, Live Activity and complication text are looked up explicitly in the current app language
   (`String(localized:bundle:locale:)`, Android `createConfigurationContext`).
4. **Every user-visible string is a resource**: UI text, notifications, dialogs, error guidance, accessibility labels,
   widgets/complications, the tray. **Not made into resources**: log events and fields (`rec.*`, `shell.*`), file and folder naming rules,
   the recording processing settings JSON, code identifiers. Numbers are numbers — a value such as a participant count is written as a number, not as a sentence, and
   the only values that become sentences are `Unknown` and `6+`.
5. **User strings the core produces**: instead of natural language, the core puts a **message key** (`CoreMessage` enum: `NEEDS_AUTH`,
   `DRIVE_REAUTH`, `MISSING_SECRET`, `FROZEN`, `STALE`, `DRIVE_STORAGE_FULL`, `AUTH_REJECTED`, …) into
   `StepRun.lastError` and exceptions, and each platform translates the key. An argument is attached as `NAME:{arg}` and what the provider said as
   `NAME|{detail}`; the part after `|` is not translated and is shown as is, in monospace, under the sentence. The shells
   read it with `CoreMessageRef.parse`. Old sentences already stored in the DB are shown as they are (compatibility).
6. **Retired (2026-09-24)** — The **default workflow name** is created in the app language at the time of the first seed (en: "Memo"). Changing the language
   later does not change the name, because it is user data. The seed ID is fixed (§5).
7. **Dates, times and numbers use the platform locale formatters** — the pattern itself is a resource, so a Korean device reads "8월 28일 15:04"
   and an English device reads "Aug 28, 3:04 PM". File name timestamps (ISO) do not change.
8. **Jurisdiction-specific consent notices** (§12) are per-language resources, but the links and the list of jurisdictions are shared.
9. **Completeness tests**: each platform has a test that "every key exists in all 12 supported languages" and a check that "no Hangul literal remains
   in UI source" (allow list: logs, tests, comments). RecKit additionally confirms by scanning that "every key a view draws is in the
   catalog" (it fails the day a key with a mismatched spelling, such as a curly apostrophe, is used).
10. **Device name substitution rule**: the word a sentence uses when it refers to this device is fixed per shell — Android and iPhone use
    `phone`, macOS **Mac**, Windows **PC**, and a sentence that does not specify which device it is (the core's
    `CoreMessage`, RecKit sentences shared by several shells) uses `this device`. Sentences with the same meaning must differ across shells
    only in this one word and be otherwise identical letter for letter — "This phone's secrets" / "This Mac's secrets" / "This PC's
    secrets", "Delete on this phone only" / "…this Mac only" / "…this PC only". The watch has no sentences of its own and
    follows the phone's.
11. **Cross-shell dictionary**: a sentence that appears in two or more shells is **identical letter for letter** in those shells (the node
    notation policy of §9 screen principle 1, the words of the status badges, editor field labels, the guidance on the secrets screen, etc.). `CrossShellDictionaryTest` (Android
    jvm test) reads the Windows `.properties` and the RecKit · RecMac · RecPhone catalogs directly and compares en and ko. Format
    arguments are written differently per platform (`%1$s` ↔ `%@`), so they are normalized before comparison. **A dictionary sentence that only one shell says so far** is
    locked by that shell's own test (this test rejects a one-shell line), and it moves here once the other shells have the key —
    `Receiving from the watch` · `Uploading on another device` · `Transcribing on another device` from 2026-09-04 (§9 screen principle 2) were added by three shells
    on the same day, so they are in this dictionary from the start.

The existing English and Korean resources are kept. The shared dictionary for the additional languages lives in `localization/translations/`, and
`scripts/localize.py` generates the Apple String Catalog, Android XML and Windows properties from it.
`python3 scripts/localize.py --check` checks for missing messages, format arguments and that the generated output matches.
Only technical identifiers and the languages' own names are explicitly exempted in `localization/unchanged.json`.
Quantity sentences are written in a form whose grammar does not break with the number, and Android uses the `other` plural resource.

### Platform mapping

| Platform | Resources | Language setting UI | Switching |
|---|---|---|---|
| Android phone | `values/strings.xml` (en) + per-language `values-*/`, `android:localeConfig` | Settings → language row → dropdown | Platform `LocaleManager.applicationLocales` (API 33+; the app is a plain ComponentActivity, so the AppCompat path is a no-op) |
| Galaxy Watch | Same approach, system language only (no settings UI) | — | System |
| iPhone · Apple Watch · macOS | String Catalog (`Localizable.xcstrings`) — RecKit has the shared catalog and each app has its own | Settings → language row → dropdown | SwiftUI `\.locale`; AppKit/UIKit strings use an explicit locale lookup; `AppleLanguages` is not touched |
| Windows desktop | `strings_{language}.properties` + `Str` enum keys | Settings window → language row → dropdown | Locale `StateFlow` → recomposition, tray menu rebuilt |
| Core | `CoreMessage` keys | — | — |

### Windows desktop design notes

- **The resource format is `.properties` + a `Str` enum.** Many of the sentences the tray app says are built outside composition —
  `TrayIcon.displayMessage` balloons, the sign-in redirect page Ktor serves, `Recents.stateLabel`, the helper's
  `--self-test` report. A plain table that can be read immediately from any thread is the only shape that fits. It is read directly as UTF-8
  (`Properties.load(InputStream)` is ISO-8859-1).
- **Type-safe keys:** the `Str` enum is the key list, and a property key is the enum name lowercased with dots
  (`STATUS_WAITING` → `status.waiting`). A string that is not in the table does not compile in the first place, and `StringTableTest` cross-checks the en and ko
  tables against the enum.
- **Argument format:** positional arguments of `String.format(locale, …)` (`%1$s`, `%1$d`) — the same notation as the Android side, so translations
  carry over as is and a translation can change the order.
- **Nesting · verbatim:** `UiMessage` has two cases, `Res(key, args)` / `Text(verbatim)`, and an argument can itself be a `UiMessage`.
  They are resolved together at draw time, so changing the language redraws the whole thing.
- **Switching:** `Localization` holds a single `StateFlow<Strings>`, and composition reads it with `collectAsState()` and passes it down as a
  parameter. Outside composition, code reads `localization.current` on the spot. The tray menu is a pure list built by
  `trayMenu()`, and `Main.kt` only carries it over to AWT items.
- **Storage:** a single `language` key in the `app/recly/windows` node of `java.util.prefs`.

---

## 8. Transcription (formerly docs/08)

`transcribe` (STT + speaker diarization) is the step that goes into the fixed plan when the recording processing settings choose an external API as the transcription method (§5
"Fixed processing settings"; when local, it is `local.transcribe`). It runs where the other steps run, on **the device that runs the
job** (phone · Mac · Windows), and there is no server. The device calls the STT API directly with the user's key and writes the result to the Drive recording folder.

### Principles

- **Serverless** — every call goes device → an API on the user's account (Drive, STT). No intermediate relay, no callback URL. So STT
  providers are used only through a **pollable asynchronous API or a synchronous API** (callback-only modes are not used).
- **BYO key** — the key is a `secretRef` in the existing secret store (§5). If it is missing, `MISSING_SECRET`.
- **Drive is the bus** — result files are placed in the recording folder `{folder}/{base}/` (for a recording that chose iCloud or a local folder, the same folder
  there, §3 "Storage location"). Other devices, agents and the user only need to
  look at those files. There is no separate device-to-device communication.
- **One track, one file** — one file, joined from the parts of one track (`mono` or `mix`) by remux, is uploaded. Per-part transcription
  is not used, because the speaker labels would differ from part to part.

### Step definition

#### `transcribe`

```json
{ "id": "stt", "type": "transcribe",
  "provider": "clova",
  "secretRef": "clova_key",
  "invokeUrl": "https://clovaspeech-gw.ncloud.com/external/v1/1234/abcd…",
  "language": "ko",
  "diarize": true,
  "speakers": { "min": 2, "max": 6 } }
```

| Field | Default | Meaning |
|---|---|---|
| `provider` | required | `assemblyai` \| `clova` \| `rtzr` \| `openai` \| `groq` \| `together` \| `mistral` \| `elevenlabs` \| `deepgram` \| `azure` \| `daglo` \| `speechmatics` \| `rev` \| `gladia`. A value the client does not know is a validation error (`UnknownProvider`) |
| `secretRef` | required | The provider key. See the provider table for the value format |
| `invokeUrl` | — | Its meaning differs per provider (`WorkflowParser.invokeUrlUse`). **Required**: `clova` (per-app invoke URL) · `azure` (resource endpoint). **Optional**: `openai` · `groq` · `together` · `mistral` · `speechmatics` — empty means the provider's default endpoint; a value takes its place (self-hosting · region). **Others**: a value is a validation error. A trailing `/` is trimmed. The required providers have a template (`WorkflowParser.invokeUrlTemplate`: `clova` → `https://clovaspeech-gw.ncloud.com/external/v1/{appId}/{invokeKey}`, `azure` → `https://{resourceName}.cognitiveservices.azure.com`) that the settings screen fills into an empty field, and a URL with a `{…}` left in it is a validation error (`InvokeUrlPlaceholder`) |
| `language` | `ko` | `ko` \| `en` \| `ko-en` \| `auto`. A value the provider cannot accept is mapped by the adapter to the closest value |
| `diarize` | `true` | Whether to request speaker diarization |
| `speakers.min` / `speakers.max` | 1 / 10 | Speaker count hint. If the meta `context.participants` is present, it **overrides** them with `min = max = participants` (information from the time of recording is more accurate than the workflow default). The upper bound is 10 people, so `6+` means the whole range above it |
| `model` | provider default | Provider-specific model name. A free string; the provider does the validation. `clova` · `assemblyai` · `azure` · `rev`, which have a fixed model or no model to choose, do not read this value (`SttProviders.acceptsModel`), so the fixed processing plan does not send it and the settings screen hides the input field. When the provider is changed on the settings screen, the model and `invokeUrl` are filled with the values remembered for that provider (empty if there are none; placeholder text for the address format of a required provider) — so that another provider's name or address does not carry over (§5 `providerDetails`) |

Input track: `mono` if the recording's `tracks` has `mono`, otherwise `mix`. If neither exists, the step is
`FAILED(NO_INPUT_TRACK)` (non-retryable).

Precondition: `drive.upload` must come **before** this step (`TranscribeNeedsUpload`) — because the folder the result files go into is that
step's output.

Output: `{ transcript: { jsonFileId, txtFileId, language, speakerCount, durationSec, provider, model }, files: [ … ] }`
— `files[]` is the `name/bytes/sha256/drive{fileId,webViewLink}` of the result files (json · txt) (the shape the webhook payload
read; webhooks were retired on 2026-09-24).

### Providers

The list for a new external transcription setting, and its default choice, follow the order **ElevenLabs → CLOVA → AssemblyAI → RTZR → OpenAI → Groq →
Together → Mistral → Deepgram → Azure → Daglo → Speechmatics → Rev → Gladia**. A saved provider is not changed.
iPhone (App Store) shows only the eight in §15 "iPhone providers", in the same order (2026-09-29).

The transcription languages are the 20 choices `ko`, `en`, `ja`, `zh-cn`, `zh-tw`, `es`, `fr`, `de`, `pt`, `ar`, `hi`, `ru`, `it`, `id`, `tr`,
`vi`, `th`, `nl`, `pl`, `uk`, plus the existing `ko-en` · `auto`. The initial language of a new setting is decided from the device language.
Only the choices the provider and model support are shown. CLOVA keeps Korean · English · mixed · Japanese · Simplified/Traditional Chinese, and Daglo keeps the verified
Korean · English · mixed. Mistral is limited to 13 languages, RTZR sommers to Korean · Japanese, and English-only Groq models and special Deepgram models to English.
Apple local queries `SpeechTranscriber.supportedLocale(equivalentTo:)` on the actual device, and whether the model is installed is shown separately.
If an API accepts only ISO language codes, the two Chinese choices are sent as `zh`, and as `cmn` for Speechmatics · Rev. The Chinese script is not force-converted.
The rationale and implementation scope follow the [global language settings record](research/2026-09-24-global-language-settings.md).

| provider | Auth (`secretRef` value format) | Submit | Polling | Diarization · hints | Language mapping | Default model |
|---|---|---|---|---|---|---|
| `elevenlabs` (Scribe) | Header `xi-api-key`; value = key | `POST https://api.elevenlabs.io/v1/speech-to-text` multipart(`file`, `model_id`, `language_code`, `diarize`, `num_speakers`, `timestamps_granularity=word`, `tag_audio_events=false`), synchronous | None (synchronous) | `diarize`, `num_speakers` (only when a single value is known) | `ko`→`ko`, `en`→`en`, `ko-en`→`ko`, `auto`→omitted | `scribe_v2` |
| `clova` (Naver CLOVA Speech long-form) | Header `X-CLOVASPEECH-API-KEY`; value = key string. `invokeUrl` is a step field | `POST {invokeUrl}/recognizer/upload` multipart(`media`, `params` JSON), `completion: "sync"` (≤2 h; the result is the response body) | None (synchronous). HTTP timeout 15 minutes | `diarization.enable`, `speakerCountMin/Max` (≤10) | `ko`→`ko-KR`, `en`→`en-US`, `ko-en`→`enko`, `auto`→`ko-KR` | — |
| `assemblyai` | Header `authorization`; value = key | Default base URL `https://api.eu.assemblyai.com/v2` (EU region — excluded from training, §15 "Provider retention policies", 2026-09-29). `POST /v2/upload` (bytes) → `upload_url`; `POST /v2/transcript` `{audio_url, speech_models:["universal-3-5-pro","universal-2"], language_code, speaker_labels, speakers_expected?}` | `GET /v2/transcript/{id}` → `queued`/`processing`/`completed`/`error`. `Waiting(30s)` | `speaker_labels`, `speakers_expected` (when min=max) | `ko`→`ko`, `en`→`en`, `ko-en`→`ko`, `auto`→`language_detection: true` | `universal-3-5-pro` → `universal-2` (the documented default routing, 2026-09-25 — a language the first model cannot handle, such as Korean, falls through to the second model. The transcript file's `model` is the response's `speech_model_used`) |
| `rtzr` (Return Zero) | Value = `{clientId}:{clientSecret}`. `POST /v1/authenticate` → JWT (6 h), cached (step state) | `POST /v1/transcribe` multipart(`file`, `config` JSON) → `id` | `GET /v1/transcribe/{id}` → `transcribing`/`completed`/`failed`. `Waiting(30s)` | `use_diarization`, `diarization.spk_count` (passed only when min=max) | `sommers` supports only `ko`/`ja` → `ko`→`sommers`; `en` · `ko-en` · `auto` use `model_name: "whisper"` + `language: "en"`/`"multi"`/`"detect"` | `sommers` |
| `openai` | Header `Authorization: Bearer {key}` | `POST {base}/audio/transcriptions` multipart(`file`, `model`, `language`), synchronous (the response body is the result). base defaults to `https://api.openai.com/v1`, replaceable with `invokeUrl` | None (synchronous) | Decided by the model name — if the name contains `diarize`, `response_format=diarized_json` + `chunking_strategy=auto`; if it starts with `whisper`, `verbose_json` + `timestamp_granularities[]=segment`; otherwise `json`. No speaker count hint | `ko`→`ko`, `en`→`en`, `ko-en`→`ko`, `auto`→omitted (automatic detection) | `gpt-4o-transcribe-diarize` with `diarize`, otherwise `whisper-1` |
| `groq` | Header `Authorization: Bearer {key}` | `POST {base}/audio/transcriptions` multipart, synchronous. base defaults to `https://api.groq.com/openai/v1`, replaceable with `invokeUrl` | None (synchronous) | **None** — Groq has no speaker diarization, so `diarize` is ignored and speakers are `null`. `verbose_json` fixed | Same as above | `whisper-large-v3-turbo` |
| `together` | Header `Authorization: Bearer {key}` | `POST {base}/audio/transcriptions` multipart, synchronous. base defaults to `https://api.together.ai/v1`, replaceable with `invokeUrl` | None (synchronous) | `diarize=true` + `response_format=verbose_json` + `timestamp_granularities=segment`, plus `min_speakers`/`max_speakers` (range) | Same as above, but `auto`→`language=auto` (without `language` this API uses `en` — API reference, 2026-09-25) | `openai/whisper-large-v3` |
| `mistral` (Voxtral) | Header `Authorization: Bearer {key}` | `POST {base}/audio/transcriptions` multipart, synchronous. base defaults to `https://api.mistral.ai/v1`, replaceable with `invokeUrl` | None (synchronous) | `diarize=true` + `timestamp_granularities=segment`. No speaker count hint | Same as above | `voxtral-mini-latest` |
| `deepgram` | Header `Authorization: Token {key}` | `POST https://api.deepgram.com/v1/listen?…` query (`model`, `smart_format=true`, `punctuate=true`, `utterances=true`, model-improvement opt-out `mip_opt_out=true`), the body is the raw file bytes, synchronous | None (synchronous) | Sends only `diarize_model=latest` (sending the old `diarize` flag along with it is rejected). No speaker count hint | `ko`→`language=ko`, `en`→`language=en`, `ko-en`→`language=ko`, `auto`→`detect_language=true` | `nova-3` |
| `azure` (Fast transcription) | Header `Ocp-Apim-Subscription-Key`; value = key. `invokeUrl` is the resource endpoint (step field, **required**) | `POST {invokeUrl}/speechtotext/transcriptions:transcribe?api-version=2025-10-15` multipart(`audio`, `definition` JSON), synchronous | None (synchronous) | `diarization.enabled` + `maxSpeakers` (clamped to the 2..35 the API accepts). With `diarize: false`, `diarization` is left out entirely | `ko`→`["ko-KR"]`, `en`→`["en-US"]`, `ko-en`→`[]` (multiple locales make the whole file be processed as one language, so mixed speech is left to the multilingual model with no locale — recommended by the fast transcription guide, 2026-09-25), `auto`→`[]`. `profanityFilterMode: "None"` (the default `Masked` masks profanity) | — |
| `daglo` (Daglo) | Header `Authorization: Bearer {key}` | `POST https://apis.daglo.ai/stt/v1/async/transcripts` multipart(`file`, `sttConfig` JSON) → `rid` | `GET https://apis.daglo.ai/stt/v1/async/transcripts/{rid}` → `transcribed` (done) / `input_error` · `transcript_error` · `file_error` (terminal failure) / anything else is in progress | `speakerDiarization.enable`, `speakerCountHint` (when a single value is known and is 2 or more) | `ko`→`ko-KR`, `en`→`en-US`, `ko-en`→`mixed`, `auto`→`ko-KR` | `general` |
| `speechmatics` | Header `Authorization: Bearer {key}`. base defaults to `https://eu1.asr.api.speechmatics.com/v2`, replaceable with `invokeUrl` (e.g. a `us1` host) | `POST {base}/jobs` multipart(`data_file`, `config` JSON) → `id` | `GET {base}/jobs/{id}` → `running` (in progress) / `rejected` · `deleted` · `expired` (terminal failure, resubmit) / `done`. On `done`, `GET {base}/jobs/{id}/transcript?format=json-v2` in the same poll | `diarization: "speaker"` (otherwise `"none"`). No speaker count hint | `ko`→`ko`, `en`→`en`, `ko-en`→`ko`, `auto`→`auto` (language identification; the detected language is read from the tokens' `alternatives[].language`. The job config has `language_identification_config.low_confidence_action: "allow"` — the default rejects when it is not confident), `zh-tw`→`cmn` + `output_locale: "cmn-Hant"` (the default output is Simplified) | `enhanced` (`operating_point`) |
| `rev` (Rev AI) | Header `Authorization: Bearer {key}` | `POST https://api.rev.ai/speechtotext/v1/jobs` multipart(`media`, `options` JSON) → `id` | `GET …/jobs/{id}` → `in_progress`/`failed`/`transcribed`. On `transcribed`, `GET …/jobs/{id}/transcript` (`Accept: application/vnd.rev.transcript.v1.0+json`) in the same poll. **Result wait limit of 8 hours** (non-English jobs take up to 6 hours to process, so the default 2 hours is not enough) | `skip_diarization` (= the inverse of `diarize`). No speaker count hint | `ko`→`ko`, `en`→`en`, `ko-en`→`ko`, `auto`→`ko` (leaving the field out means English by default, not detection, so it is always sent) | — (`transcriber: "machine"`) |
| `gladia` | Header `x-gladia-key`; value = key | `POST https://api.gladia.io/v2/upload` multipart(`audio`) → `audio_url`; then `POST https://api.gladia.io/v2/pre-recorded` JSON → `id` | `GET https://api.gladia.io/v2/pre-recorded/{id}` → `queued` · `processing`/`error`/`done` | `diarization`, `diarization_config.number_of_speakers` (when a single value is known) or `min_speakers`/`max_speakers` | `ko`→`["ko"]`, `en`→`["en"]`, `ko-en`→`["ko","en"]` + `code_switching: true`, `auto`→`language_config` omitted | — (`model` is sent only when specified) |

The adapters live in the `transcribe/` package of `core/commonMain` as `interface SttProvider { submit(ctx, file): Submitted;
poll(ctx, ref): PollResult }`, and `TranscribeRunner` knows only remux → submit → poll → normalize →
Drive write, regardless of the provider.

The default model names are updated to each provider's current GA model. Since the user can
change `model`, **the core does not validate the name.**

#### Length · size limits

A one-hour meeting is roughly 14 MB at 32 kbps AAC. Still, every provider has a cap, and going over it gets a 4xx rejection
(`UNSUPPORTED_AUDIO`, not retried).

**Only the limits a 32 kbps recording can realistically reach** are declared by the provider in `SttProvider.limits` (`SttLimits(maxBytes,
maxDurationSec)`). The runner checks those limits right after joining the parts and just before uploading, and if they are exceeded it fails with `UNSUPPORTED_AUDIO` before
sending any bytes (not retried). The detail states the actual value together with the limit, like `30 MB exceeds openai's 25 MB` /
`2h 15m exceeds clova's 2h` — the shell shows it as is under the `UNSUPPORTED_AUDIO` sentence. **Limits that vary by tier or plan are not declared** — we would then
reject first a recording that a higher plan would accept. Such limits, and the limits 32 kbps cannot reach, are still learned only from the provider's 4xx, as before.

| provider | Limit | Checked before upload |
|---|---|---|
| `openai` | File 25 MB (roughly 1 hour 44 minutes at 32 kbps). `gpt-4o-transcribe-diarize` is reported to accept only up to about 25 minutes, separately from that (unofficial) | 25 MB is checked. The diarize model's 25 minutes is unofficial, so it is not checked |
| `groq` | Free tier file 25 MB | No — it varies by tier |
| `together` | Audio 4 hours (file 80 MB) | Only the 4 hours is checked. 80 MB cannot be reached at 32 kbps |
| `gladia` | Audio 135 minutes (standard plan. Enterprise is 4 hours 15 minutes) | No — it varies by plan |
| `clova` | Synchronous call 2 hours | Yes |
| `azure` | Fast transcription audio 5 hours | Yes |
| `daglo` | Audio 4 hours | Yes |
| `rtzr` | File 4 hours (2 GB) — file STT documentation, 2026-09-25 | Yes |

### Audio preparation (remux)

`CoreDeps.audio.concat(parts: List<File>, out: File)` — the shell implements it. It joins the parts of the same track in part-number order
**losslessly** (segment boundaries are lossless, so the AAC frames are copied as is. No re-encoding).

| Platform | Implementation |
|---|---|
| Android | `MediaExtractor` + `MediaMuxer` (AAC track copy, pts continued across parts) |
| Apple | `AVAssetReader` → `AVAssetWriter` (`outputSettings: nil`, passthrough). `AVMutableComposition` + `AVAssetExportSession(presetPassthrough)` fails with `AVErrorOperationNotSupportedForAsset` because each part has a different format description |
| Windows / JVM desktop | Bundled ffmpeg (ADR-019) `-f concat -safe 0 -c copy`. ffmpeg exits 0 with truncated output even when a part is missing, so the existence of the parts is checked first in Kotlin |

Encoder padding (each part has frames ~0.18 s longer than its presented duration) is trimmed by keeping only the frames of which at least half falls within the presented duration
— otherwise the timestamps of the later parts shift further with every part. `gaps` (meta) is ignored — the timeline of the joined file is
"the time where there is audio", and the result timestamps are mapped back to the recording timeline with `parts[].startOffsetSec`
(`start/end` in `transcript.json` are relative to the recording). The output is a temporary file and is deleted when the step ends (unrelated to the part
retention rules).

### Polling · status

The result of `StepRunner.run` includes **`StepOutcome.Waiting(retryAfterSec, state)`** (§10).

- `Waiting` does **not consume** `attempts`. The Job goes to `WAITING(next_run_at = now + retryAfterSec)`, the step stays
  `PENDING`, and `state` (submission ref · token · submission time) is saved. So **while transcription is in progress there is no budget notion of "when"
  to retry** — there is only the next polling time.
- If the time since submission exceeds **the result wait limit the provider declared** (`SttProvider.resultTimeout`, default 2 hours; only `rev` has 8 hours —
  non-English jobs take up to 6 hours to process, so giving up at 2 hours would abandon a live job and pay for the same audio
  again) or the provider returns a **terminal failure state** (`failed`/`error`), the runner **clears the state** and then
  throws `FAILED(RESULT_TIMEOUT | PROVIDER_ERROR)` (retryable) — the Executor
  does not clear state on failure, so it has to be cleared for **the retry to submit anew**. Transient errors during polling (network · 5xx · 429) keep the state
  and keep polling the same ref.
- The shell's scheduler adapter wakes up again at `next_run_at` (Android WorkManager `OneTimeWorkRequest` delay, iOS
  `BGProcessingTaskRequest.earliestBeginDate`, desktop timer). iOS does not guarantee the exact time, so `runDueJobs` is also called when the app
  enters the foreground.
- A synchronous provider (`clova sync`) that receives cooperative cancellation while waiting for the HTTP response drops the request and, instead of `Waiting`,
  **resubmits** (there is no result ref). Synchronous calls are harmless on desktop but can exceed the execution time budget in the mobile background
  — the recommended default provider on mobile is `assemblyai`/`rtzr` (asynchronous).

### Errors

| reason | Retry | Condition |
|---|---|---|
| `MISSING_SECRET` | No | This device has no key with that name |
| `AUTH_REJECTED` | No | 401/403. The UI shows **"Check the key"** + a way into the settings |
| `QUOTA` | Yes (No when the balance or usage limit is exhausted) | 429 (`Retry-After` first), 402. When the provider says the balance or usage limit has run out (Rev `insufficient_balance` · `invoicing_limit_exceeded`, RTZR 429 `A0001`), a retry gets the same answer, so it stops — the notice says "check its limits and billing" (2026-09-25) |
| `PROVIDER_ERROR` | Yes | 5xx, network, timeout, Deepgram 422 (upload interrupted · delayed), provider `failed`/`error` status (message preserved) |
| `UNSUPPORTED_AUDIO` | No | File rejected with a 4xx, a limit exceeded before submission (declared by the provider, the limit in the detail), or a job the provider says failed because of the audio (Gladia `error_code` 4xx, Rev `invalid_media` · `empty_media` · `duration_*`, CLOVA `ERROR_AUDIO_*`) — sending the same file again gets the same answer, so it is not resubmitted (2026-09-25) |
| `NO_INPUT_TRACK` | No | No input track |
| `RESULT_TIMEOUT` | Yes | The provider's result wait limit exceeded after submission (default 2 hours, `rev` 8 hours) |

The reason is written to `step_run.last_error` as a **`CoreMessage` code** (§7 rule 5). Six of them are `NAME|what the provider said` —
the shell renders `NAME` as a sentence in its own language and shows the part after `|` untranslated, as is, under the sentence. Only `MISSING_SECRET` takes an argument
(`MISSING_SECRET:{secretRef}`). For `MISSING_SECRET` · `AUTH_REJECTED`, `StepReport.needsKey` turns on the "Check the key"
action. **The key is fixed in the recording processing settings, so it goes to that screen.**

### Result files

They are written to the recording folder `{folder}/{base}/` under the following names. A copy under the same names is also kept in the local recording directory (for the app's detail
screen; kept regardless of the part deletion rules).

| File | Contents |
|---|---|
| `{base}.transcript.json` | The schema below. The canonical copy for machines |
| `{base}.transcript.txt` | For people and agents. One line = `[HH:MM:SS] S1: text`. A line break when the speaker changes or a segment passes 60 seconds |
| `{base}.transcript.md` | **Local folder only** (§3 "Storage location"), for notes apps such as Obsidian that open only Markdown (2026-10-03). Front matter `title` (only when there is one; a JSON string, which YAML reads as a double-quoted scalar) · `recordingId` · `startedAt`, then the lines of `.txt`, each its own paragraph. A rename rewrites it together with `meta.json` |

Drive writes follow the same idempotency rule as `drive.upload` (same name + same md5 is skipped; otherwise it is **overwritten** — after a rerun the latest
result is canonical).

`transcript.json` schema: `spec/transcript.schema.json`.

```json
{
  "schema": 1,
  "recordingId": "01J9ABCDEF0123456789ABCDEF",
  "track": "mono",
  "language": "ko",
  "provider": { "name": "rtzr", "model": "sommers", "jobRef": "…" },
  "createdAt": "2026-08-29T03:10:00.000Z",
  "durationSec": 3612.4,
  "speakers": [ { "id": "S1", "name": null }, { "id": "S2", "name": null } ],
  "segments": [
    { "start": 0.0, "end": 3.2, "speaker": "S1", "text": "시작하겠습니다.",
      "words": [ { "start": 0.0, "end": 0.6, "text": "시작하겠습니다." } ] }
  ]
}
```

- Speaker ids are normalized from the provider labels to `S1, S2, …` in order of appearance. `name` is always `null` (user labeling comes later).
- `words` is included when the provider gives it and omitted otherwise. `start/end` are seconds (decimal), on the **recording timeline**.
- With `diarize: false`, `speakers` is the single `[{"id":"S1"}]`, and every segment is `S1`.

### Meta hint `context.participants`

§3. Integer, optional. Where it is filled in:

- Desktop · phone: the participant count is chosen in the title dialog after stopping (2·3·4·5·6+·Unknown). The default "Unknown" = omitted.
- Watch: none (the phone receives the transfer and enqueues it right away). The processing plan's `speakers` default applies.

### Webhooks

> **Retired (2026-09-24)**: there are no webhooks (§4). The shape of the result file entries (`track: "transcript"`) remains in the step output `files[]`.

The result files are added to the `files[]` of §4: `track: "transcript"` (two entries, json · txt). Only when the preceding `transcribe` step
succeeded.

### What is not included

Local speaker diarization, identifying "me" by transcribing mic/sys separately, editing and saving speaker names, entering the participant count
on the watch. There is no Gemini adapter either — its speaker diarization works only through the prompt and its timestamps cannot be trusted, so it cannot keep the
`start/end` contract of `transcript.json`. The adapter interface is the same, so adding a provider means adding one adapter.

---

## 9. Design system "Blueprint" (formerly docs/09)

The principle in one line: **intent, not decoration**.

### Trends → application

| # | Trend | Recly application |
|---|---|---|
| 1 | AI collaboration (a supporting layer, never forced) | Transcription is **chosen in settings: local, external API, or OFF**. New installs use local (OFF in builds without a local engine), and the external API runs with the user's key. The UI rule is "supporting layer": the original (parts · timer · status) is always the main content, the transcript is a separate layer beside or below it, and the result never covers or replaces the display of the original. Only recordings with a result get an entry point in their list row, and it opens in the transcript tab of the detail screen. There is no persuasion UI such as automatic suggestions, automatic runs or "Improve with AI" |
| 2 | **Purposeful motion** (status signals, deliberate micro-delays) | For **rare, high-stakes actions** such as starting/stopping a recording, uploading and signing in, a visible processing state of 0.3–0.8 seconds (the button changes to "Saving…" and shows "✓" when done). No decorative animation. All motion respects `reduce motion` |
| 3 | **Raw aesthetics** (monospace · grid · wireframe) | **The center of Recly's visual language.** Timers, part numbers, file names, sizes, sha and status codes are monospace. Screens are square nodes on a visible grid; the recording screen shows the device, transcription and status nodes, and the processing settings are offered as the existing sectioned table. No filler illustrations, no gradient overlays |
| 4 | Inclusive visuals (control) | **Reduce motion and high contrast follow the system settings as they are** — the app does not repeat the same toggles inside itself (duplicate controls are not control). WCAG AA (text 4.5:1, graphics 3:1). Swipe hints have a static alternative |
| 5 | **Fluid typography** | A continuous scale instead of breakpoints: Compose `sp` + interpolation based on window size, SwiftUI Dynamic Type + `ScaledMetric`, clamp interpolation by window width on Windows. The user's font size setting is respected |
| 6 | Authorship (Crafted, not prompted) | **No icon mascot.** Instead, **honest system indicators**: version · build · device ID · open-source notices in monospace at the bottom of settings. No "Handmade" marketing copy (product honesty) |
| 7 | Anti-Liquid Glass | Glass **only on chrome (tab bar · toolbar · menu bar popover)**, never over content. Blur + gradient lighting only, no refraction distortion. The system tab bar follows the system default, but its text contrast is checked and its background is filled when needed |

### Tokens

- **Palette** (neutral + a single accent):
  - Light: background `#F7F7F5` (paper), surface `#FFFFFF`, grid lines `#E6E6E2`, body `#111111`, secondary `#5E5E5A`,
    accent **`#0F62FE`** (blueprint blue), recording/danger `#DA1E28`, success `#198038`, warning `#B28600`.
  - Dark: background `#0E0F12`, surface `#16181D`, grid lines `#23262D`, body `#F2F2F0`, secondary `#9A9CA3`, accent `#4589FF`,
    recording `#FA4D56`, success `#42BE65`, warning `#F1C21B`.
  - **High-contrast mode** (only in shells where the system reports that setting — Apple `colorSchemeContrast`, Windows `win.highContrast.on`, Android API 34+ `UiModeManager.contrast` · API 36+ high-contrast text state):
    grid lines and secondary text are promoted to the body color, accent saturation is kept, borders are 2dp.
  - The **input field border** is `inputBorder` (the secondary text color), separate from the decorative grid. It is at least 3:1 in light and dark,
    and focus uses the accent color and a thicker border.
- **Typography**: UI body = the platform system sans-serif (Roboto / SF Pro / Segoe UI — no bundled local fonts; Korean glyphs
  are guaranteed). **Data = monospace** (Android `FontFamily.Monospace`/Roboto Mono, Apple SF Mono, Windows
  Cascadia/Consolas): timer `00:12:34`, part `p001`, file names, bytes, status code `NEEDS_AUTH`, device ID. Scale:
  12 / 14 / 16 (body) / 20 / 28 / 44 (timer) — fluid interpolation.
- **Shape**: corner radius 4 (nodes) / 8 (cards) / 0 (table rows) / **2 (badges)** — squares smaller than a node, such as status badges, checkboxes, radios and switch thumbs,
  use half the node radius (`Radius.badge`). The round record button is a **square node + thick border**
  (72×72; while recording, filled + mono timer). On phones (Android · iPhone), **text buttons are at least 88 wide** (dp/pt, 2026-09-29):
  a two-character button such as `Open` (`열기` in Korean) does not become the narrowest thing on the screen, and short buttons side by side line up at one width. `Close` · `Delete` ·
  `Cancel` and data buttons in the fixed-width font (utterance times) keep their own width (user decision). At 360dp · 375pt, in Korean and English,
  this was tuned so that no new line breaks appear — list rows were changed to the flow of screen principle 2 below, and the mobile data confirmation dialog (three buttons)
  always stacks vertically. Desktop is unchanged.
- **Spacing**: multiples of 4, base rhythm 8/16/24. Visible grid: 8dp dotted background (6% opacity, off in high contrast).
- **Lines**: connectors and dividers 1dp (2dp in high contrast), straight lines and right angles only.
- **Motion**: standard easing `ease-in-out 200ms`, status-transition badges `fade 150ms`, processing state shown for at least 400ms (kept even when the real work is faster,
  at most 800ms). **With `reduce motion`, "instant transition + text status only"** — what is turned off is the *transition*; the text
  status itself, such as "…" and "✓", stays.
- **Icons**: geometric thin lines (1.5dp), platform system icons first (Material Symbols Outlined / SF Symbols light).

### App icon

The master is the single **`docs/design/icon.svg`** ("A · Record node", 1024×1024 artboard): on a dot-grid background (64 pitch, r=5,
`#888884` 28%), a node square (560, radius 40, border 28) with a record square inside it (240, radius 24). The colors are the tokens as they are.

1. **One master** — nothing is redrawn per platform; it is **only exported**.
2. The **monochrome template** (22×22 grid: outer square x3 y3 w16 h16 radius 2 border 1.5, inner x8 y8 w6 h6 radius 1) is
   used by the menu bar, the tray and the Android `monochrome` layer. Rasters under 64px are also drawn in these proportions instead of from the master — the master's
   border drops below one pixel and disappears.
3. The **recording variant** exists only in the menu bar and tray. Only the inner square is red (Light `#DA1E28` / Dark `#FA4D56`); the outer one keeps the body
   color.

Platform outputs: Android phone and watch use vector adaptive icons (`res/mipmap-anydpi-v26/ic_launcher*.xml` +
`res/drawable/ic_launcher_{background,foreground,monochrome}.xml`, inside the 66dp safe zone of a 108dp canvas), Apple uses each
app's `Assets.xcassets/AppIcon.appiconset` (iOS light/dark/tinted; macOS 16~512 @1x/@2x with a 10% margin + squircle),
the macOS menu bar uses the `MenuBarIcon`/`MenuBarIconRecording` image sets, and Windows/jpackage uses
`windows/app/src/main/icons/recly.{ico,icns,png}`.

All rasters are committed, so the build does not run the scripts. To regenerate them (macOS only):

```
swift scripts/render-icons.swift
python3 scripts/make-ico.py --check windows/app/src/main/icons/recly.ico
```

### Screen principles

1. **Recording screen = instrument panel**: at the top, three nodes — device, workflow, status (connected by straight lines); in the middle, a mono timer; at the bottom, the square record
   node. While recording, only the elapsed time, as a mono timer (2026-09-03: part numbers, segment boundaries and track metrics were removed —
   they are data that is not shown to the user). These three form the screen's boundary.
   - The **line below the record node** (phone, 2026-09-29) is used only when there is something to say: in the idle state, only the message left by the start or stop just
     made (recording failed, finishing the save failed, too short so not uploaded, and so on). `Waiting` · `Opening` · `Recording` · `Saving` are not written there, because the status
     node and the timer already say them. The line keeps its height even when empty, so the node does not move, and it is cleared when the user goes to another tab and
     comes back — it is news of that moment, not a message to leave on the tab. This decision applies only to the two phones; the desktop menu's and the watch's
     lines are unchanged.
   - The **device node value** is the source code as is (`phone` / `desktop`) — it is not translated. It is treated like the track (`mono`), and
     the header meta in all four shells is also `<source> · <first 8 characters of the device ID>`.
   - **Display of the workflow in use** (ADR-016) — **retired (2026-09-24)**: there is no workflow to choose, so the picker and the
     `Choose a workflow` prompt are gone. What follows is a record. The workflow node in all four shells shows **only its name**. The picker lists only workflow
     names, and the selected one is a filled chip (✓) — "selected" is not written out. A single-selection control shows its selection
     state by itself; adding words only makes it longer. Choosing in the picker changes this device's pointer on the spot.
     In the workflow list, the row in use gets the badge `In use`, and the action on the other rows is
     `Use`. **When there is no selection, or the workflow the pointer pointed to is gone**, both the node and the picker show, in all four shells, the single line
     `Choose a workflow` — in the same place, it says that just starting runs nothing and
     how to fix that.
   - The **status node** is the recorder status code (`IDLE` · `STARTING` · `REC` · `STOPPING`). When a job is running in the idle state
     (when the ledger has a row in `UPLOADING`), it shows `UPLOADING` (accent) with a rotating 8pt square loader to its left (the loader used next to the text of work that runs without
     progress — `Downloading model…` uses the same one. No system spinner is used — only the list's pull-to-refresh is allowed as an exception (§3 "Recordings from other devices",
     2026-09-29). There are only two loaders: this one, and the **waveform loader**, used only in the waveform's place
     (the detail screen in screen principle 2). The waveform loader does not rise and fall or flow like playback and recording waveforms, and does not leave the places it has passed filled —
     flowing waves, bouncing bars, breathing and filling from the left are playback or recording indicators on the various platforms, so they are not used (2026-09-26 research)) — with `reduce
     motion`, the code only. While recording, `REC` takes precedence. The ledger is updated even mid-pass through the core's job row observation (`jobs.observe()`)
     (Apple · Android · Windows 2026-09-03; Windows has no reduce motion signal, so its loader always spins).
     Windows also uses the shell states `OPENING` (before the helper starts) · `NO_HELPER` · `NAMING` (a title is being entered).
     **Phones (Android · iPhone) borrow one more**: when idle and there is a row being received from the watch, it shows `RECEIVING` (accent, the same
     loader) (2026-09-04). If a job on this device is running, `UPLOADING` wins, and while recording, `REC` beats both.
     Desktop has nothing to receive, so it is unchanged.
2. **List = ledger**: row = `time (mono) · title · length · status code · progress`. In all four shells, regardless of language, the time column is
   **`MM-dd` over `HH:mm`** (month first; a fixed-width pattern, not locale formatting — only the spoken sentence uses locale formatting).
   The status is a text badge (both color and text), and the failure reason is a translated message key.
   **Three things happening elsewhere** (2026-09-04, §3 "Recordings from other devices") are read **before** the job state mapping — none of the three is a job of this
   device, so they cannot be read from the queue: `RECEIVING` (being received from the watch = `receiving`; its `status = recording`, so
   left alone it would show as `REC`) · `UPLOADING` (another device is uploading = `remoteUploading`; the **same
   word** as this device's upload — the badge says what, not where) · `TRANSCRIBING` (another device is transcribing = `remotePending` with
   `transcribe`). All three are accent. The first two have **no actions, like a row being recorded**
   (no delete, retry or Drive link — deleting would pull the folder out from under someone else's upload), and `TRANSCRIBING` is like an adopted `DONE`
   row (details · delete). The length column keeps the placeholder used when there is no `durationSec` (it does not make up `0:00`),
   and shells that measure the badge column width measure these codes too. In the header count, the first two count as `Waiting` and `TRANSCRIBING` is not counted. `NEEDS_AUTH` waiting for a Drive connection (badge `Upload waiting`) also counts as `Waiting` — it is work that resumes by itself once Drive is connected, not a failure (2026-09-29, both phones).
   The spoken states are `Receiving from the watch` · `Uploading on another device` · `Transcribing on another device` (§7).
   Row expansion has **actions — in one horizontal line, wrapping to the next line when they overflow (no vertical listing)**: `Open in Drive` (when there is a link) ·
   `Retry` (**in the failure states `FAILED` · `NEEDS_AUTH` · `NEEDS_SPACE`, and in `RETRY`, which waits for backoff after a failure** (all four shells, 2026-09-04; excluding `TRANSCRIBING`, where the provider is transcribing — that is someone else's clock) **only**) · `Check the key` (`AUTH_REJECTED`) ·
   `Details` (opens the detail screen — on desktop, the window of the same name) · Delete (except while recording or uploading). On phones these
   buttons are laid out in one flow from the row's title cell to the right edge, and `Delete` stands at the right end of the last line — if there is no room
   for it, at the right end of the next line (2026-09-29, Android `ActionFlow` · Apple `ActionFlowLayout`). Previously `Delete` stood apart in its own cell under the status badge,
   and the other buttons wrapped by its width. An expanded row stays expanded only while the user stays on that screen: on phones, tapping the list
   tab again or going to another tab and back shows the initial list with every row collapsed (2026-10-01, both phones). Tapping the list
   tab while the detail is open closes the detail first (Android; the iPhone detail is a sheet that covers the tab bar). In the detail, the original is the main content: the transcript is layered on top of **local part playback** (play/pause + an `elapsed / total length` mono clock; a playhead moves over the waveform (the parts decoded into peaks of 0.25-second windows; the played section is accent), and dragging or tapping the waveform seeks to that time — Apple · Android · Windows 2026-09-03; the track is `mix` if there is one, otherwise `mono`; if the file is not on this device, `No audio on this device`). All four shells (2026-09-03; Apple uses a RecKit shared view + AVQueuePlayer, Android uses media3 ExoPlayer, Windows has the bundled ffmpeg decode to PCM and plays it through `SourceDataLine` — the JVM cannot decode AAC). For an uploaded recording, parts that are not local are fetched from Drive with `core.audio()` (`Fetching from Drive…`/`Could not fetch from Drive`). **There is no text while fetching** (2026-09-26, four shells): the waveform's place holds the
**waveform loader** (§9 "Motion" — a 10-cell band in the secondary text color passes one cell at a time from left to right over short ghost ticks in the grid
color), and the play button's place holds **button-shaped progress** (an accent border the same size as the button fills with accent in proportion to the bytes received,
and when it is full, that spot becomes the play button). Progress rises part by part (the core's
`audio(recordingId, progress)` reports the bytes received against the total bytes of the parts to be sent). For a recording fetched from Drive, once the peaks
are ready, the bars rise once from the center line, starting from the left (0.75 seconds; immediately with reduce motion). With reduce motion (Android · Apple), the band
stops and `Fetching from Drive…` is written again — Windows does not know that signal, so its band always runs. Screen readers read the progress cell as
`Fetching from Drive…` and a percentage. Until the fetch decision is made, the play button is not shown; if only part of the audio was fetched, only the contiguous leading part plays.
**The waveform is decoded only once** (2026-09-27, four shells): the shell stores the peaks it decoded as the core's `WaveformPeaks` (`waveform.v1` — `RWF1`, the window count,
16 bits per window) next to the parts in the recording folder (`core.recordings.saveWaveform`), and opening the detail reads that first
(`core.recordings.waveform`; if the window count differs from the selection being decoded this time, it decodes again). When a recording finishes on this device (on a phone, when the watch recording
has been fully received), it is precomputed in the background. While decoding because there is none, the waveform's place holds the **waveform loader** instead of a flat line (which reads as silence)
(ghost ticks only with reduce motion; screen reader `Loading waveform…`), and the play button stays as it is. This file is not uploaded; it survives the 7-day local audio
cleanup (which deletes only the parts), so the waveform is drawn even before the audio is fetched from Drive again, and it is deleted together with the recording. While recording on this device, playback is not offered. There is no "Upload now" (2026-09-02:
   a recording without jobs does not need uploading, and waiting jobs run at their time). The menu bar and tray popovers and the desktop detail window use the same ledger
   — **they read 20 rows at a time and add the next 20 rows when the last row becomes visible** (infinite scroll; 2026-09-04, previously the popover and detail window showed the 5 most recent rows).
   **On Android and iPhone, the detail's playback area is fixed below the body** (2026-09-10). Only the transcript body scrolls; below the waveform,
   elapsed/total length is on the left and the play/pause button at the right edge. In both states the button's minimum width is 120pt on iPhone and
   120dp on Android, and on Android the button is at least 48dp tall. The playback area does not cover the body. The audio download failure notice is
   shown below the mobile playback controls, which keeps the button right-aligned.
   **macOS and Windows keep the existing top playback area and the desktop window and split layouts.**
   All four shells offer transcript text selection, copy all and seeking by utterance time. The transcript search button and input field are not shown.
   Copy all is a text-less copy icon on the right of the detail header, immediately left of `Rename`. Its accessibility name is
   `Copy all`, and after copying, a check mark and the accessibility name `Copied` signal completion. It copies the whole original text with the times.
   Seeking by time is disabled while recording and while audio is being checked or downloaded, and times outside the range of audio actually held are disabled too. Copy is offered whether or not there is audio.
   Utterance groups are cached when the result changes, and long utterances from the same speaker are also split, starting at the next segment, at a 60-second or 1,200-character
   threshold and shown in a lazy list (a single segment returned by the provider is itself never split). The whole
   string is not re-joined on every playback clock tick. Android and Windows waveforms have visible keyboard focus and 5-second left/right seeking.
   All four shells observe job, step and recording changes in an open detail and refresh the transcription result.
   When only the transcript or the title changes, the playback and reading positions are not reset. Recording state and audio parts are observed separately from the transcript,
   so when a recording ends or parts are added while the detail is open, the playback area updates without reopening the screen.
   iPhone and macOS stop existing playback first, in the recording start preparation step, and then start capture. Until capture has fully stopped,
   playback and seeking are blocked, and the block is also released when starting the recording fails. Android stops playback when a recording starts on this device,
   and blocks playback and seeking (waveform · utterance times) while recording. The start and end of other recordings are also reflected in an open detail.
   It distinguishes waiting for transcription / no transcription step / job failed / read failed / empty transcript / body. A read failure offers reading the result
   again. On an explicit retry, if the local transcript is corrupted, the remote copy is fetched through the existing Drive path, verified, and then
   swapped in. If a newer valid local result appears during the download, that result is kept. Normal lookups are local-first, and healthy
   files are not downloaded again. An empty transcript offers a notice to play the recording. While playback is preparing or buffering, no separate notice text
   is added, so the player height and the position of the surrounding content do not change. Real playback failures are shown on screen and retried on the next
   playback request. Windows also shows ffmpeg's abnormal exit and exit-wait timeout as failures, excluding exits caused by the user's
   stop or seek. The detail header has only the title, and internal IDs are not shown (2026-09-29 — they are not information for the user
   to read).
   The list's first read shows a loading notice, and a truly empty list shows a notice that this is where recordings collect (desktop windows also show a start recording button, §9 screen principle 8).
   Delete in the recordings list is fixed at the right of the action area, and its right border lines up with the right border of the `DONE` badge in the status column
   above. The remaining actions, such as open, retry and details, wrap in the space on the left. The same rule applies to the mobile list and to the expanded list
   in the desktop menu and tray.
   (2026-09-03: the parts table in rows was removed — part numbers, tracks, bytes and sha are not shown to the user)
3. **Workflow editor = node graph** — **retired (2026-09-24)**: the user workflow editor and list were removed. The sentences about the editor and
   list are a record; the general layout rules at the end (from "iPhone supports portrait and left/right landscape") and the unsaved key
   input rule remain valid. Record: trigger (device) → step nodes → end. Nodes are square, connectors straight, and the selected node has an
   accent border. Steps are added with `+` on a connector. If there is already a `drive.upload`, `Drive upload` in the add menu is disabled
   (2026-09-04: a second upload does nothing if it targets the same folder, and if it targets a different folder it becomes a copy that later steps do not know about — webhooks and transcription
   read only the last upload; this is an editor rule and the parser does not block it). Webhooks are one per endpoint, so several of them are natural.
   On mobile, Save and Cancel sit below the input area and account for the keyboard and the safe area. While typing, the graph collapses and the input area
   scrolls. The transfer consent notice can be checked even while typing. Errors are explained in the scroll area and take the user to the related input area.
   With large text, buttons wrap, and the Android recording screen at small heights and with large text can scroll.
   Next to `New workflow` at the top of the workflow list is a borderless info icon. Its accessibility name and desktop
   tooltip are `How to set up transcription`. On mobile a bottom sheet, and on desktop a popover attached to the
   icon, explains Drive connection → transcription step → provider key → workflow selection. Opening the guide does not
   rearrange the list, and even on narrow screens the help and create buttons stay in the same right-hand action area. It does not block the first run.
   Apple screens that place an X close button at the top right of the guide center the title and the button vertically in the same row.
   In all four shells, the workflow list enters editing through each item's `Edit` button. The name and description area is
   information with no click action, and `Use` and delete are independent actions. `Edit` and `Use` are shown as blue
   accent buttons. In the mobile list, `Edit` is right-aligned together with delete, immediately to its left, with a minimum width of 96pt on iPhone
   and 96dp on Android. `Use` is on the left, and when the actions wrap with large text, the edit · delete area stays right-aligned.
   The item in use has delete disabled, and the list does not add a notice telling the user to choose another workflow
   first. Edit follows the existing unsaved-changes protection procedure.
   iPhone supports portrait and left/right landscape, and at low heights and with accessibility text sizes the recording screen scrolls.
   On Android, narrow headers and large text separate the title from the action row, tab names show up to two lines, and the status nodes are laid out vertically.
   On short landscape screens, the tab icon row is omitted and the waveform and the playback clock are placed side by side. When the keyboard opens, the editing header that
   duplicates the tab bar collapses to make room for the save area. Long Apple button labels reflow to at most three lines within the screen width.
   Apple Watch scrolls its main screen and wraps status text, and Wear also allows multiple lines for long workflow names and transfer status.
   The discard confirmation for unsaved workflow input was retired together with the editor (record). The remaining unsaved input is the API key
   field of the recording processing settings, and all four shells discard it **without confirmation** (code checked 2026-09-29): `Cancel` during `Replace key` and choosing another provider (the field
   switches to that provider's) clear the entered value, and coming back to the same provider does not restore it. `Cancel` on the settings draft only reverts to the saved
   settings, so if the provider is unchanged the key field is unchanged too.
   On mobile, switching tabs preserves drafts, and Android's back applies only to the current tab.
4. **Settings = sectioned table**: Account / Language / Theme / Capture (per platform) / Uploads (phone) / Recording processing / Agent connection (desktop, §12 and §14
   "Agent connection") / Settings file (desktop) / Privacy (phone) / About (version · build · device ID · open-source notices, mono) — in this order, and a section the shell does not have is skipped (2026-09-29).
   The settings file — export and import of the recording processing settings — is a utility, so the desktops show it as a section of its own after the features,
   Agent connection included; the phones keep it at the end of Recording processing (2026-10-06). **Theme**
   is three chips common to all four shells, `System default` · `Light` · `Dark`, and like language it is a local setting of this
   device — when unset, it follows the system's `prefers-color-scheme` (Windows 2026-09-01, the other three 2026-09-04).
   Settings hold no technical values (segment length and the like) — values the user cannot change are not shown (2026-09-04).
   The secondary description under a row uses small sans-serif (`12sp`, scaling with the user's font size), the secondary color, and 16 left/right / 8 top/bottom spacing.
   Settings that pick one item from a growing list — `App language`, and recording processing's `Provider` · `Spoken language` — are a **dropdown** at the row end
   in all four shells (2026-09-29): a box shaped like a quiet button with the current value and `▾`; tapping it opens the list (on Android and Windows a square popup with the same
   border and radius, no motion; on Apple the platform menu), and the chosen item gets the same `✓` as chips. For accessibility it is one
   element — the name is the row title, the value is the current value, the role is button. A value with nothing to choose (`Speech recognition model … Qwen3-ASR 0.6B`) has no
   box, only secondary-color text, so it looks different from a choice. A choice among only three (theme, transcription method) stays as chips. On phones (Android · iPhone),
   this chip row **fills** its line (2026-09-29): so that the chips alone do not float on the left of a screen where every other control sits at the right edge.
   If all chips fit at the same width, they share the line evenly; if a long name (`System default`) keeps them from fitting, the remaining
   space is added equally to each chip's own width — no chip gets narrower than its own width. If they do not fit on one line, they wrap, and each line is filled (Android
   `FillRow`, Apple `FillLayout`). In desktop windows the three words would stretch to the panel edge, so they stay left-aligned.
   The Drive connection status also uses the common settings row. On the right is a single danger-colored “Disconnect” button, and one confirmation dialog completes it.
   The outside shortcuts that are always in place (`Microphone` → `Open System Settings`, `Privacy Policy` → `Open`) are quiet (gray)
   buttons — they are nothing to draw attention to. When permission is denied, the recording screen shows a separate accent-colored `Open Settings` (2026-09-29, phones).
   Their text uses the same `14sp` token as the surrounding rows, buttons and links, and they keep the minimum click target (Apple · Windows 44, Android 48) and the user's font scaling.
5. **Notifications · dialogs**: **title + one-line description + at most 2 buttons.** The processing state is inline (the button changes to "Saving…"), and on completion
   a badge. A two-way question does not get a third option.
6. **macOS menu bar**: a popover (glass allowed) with the 3 status nodes + the recent ledger (infinite scroll, 20 rows at a time) + actions. The Windows tray has the same structure (a Compose
   popup window). While recording, a live waveform of the track being recorded (peaks of 0.1-second windows, recording color) flows in the empty space of the action row — the iPhone
   recording screen has the same band under the timer (Apple 2026-09-03). The Windows tray popup has the same band under the timer — the helper
   sends peaks of 0.1-second windows as `level {peaks[]}` events (2026-09-03). The Android recording screen has the same band under the timer —
   it reads `MediaRecorder.getMaxAmplitude()` every 0.1 seconds (2026-09-03).
7. **Watch**: mono timer + square start/stop, **one-line status**, recordings still waiting to transfer — while the transfer pass is finding the phone and handing over files,
   the same recordings are spoken as `Sending` (2026-09-04; tiles and complications follow the same rule).
   Waiting and sending are two questions the user asks about the same recordings, and only the transfer pass knows which one applies.
   Apple Watch always says `Waiting to send` (complication `Waiting`) until the phone acks (2026-10-05): `WCSession` cannot tell a queued file from one that is
   moving — `isTransferring` is true until delivery and the system may throttle it — so "sending" would be a guess.
   The transfer state shares the status line rather than taking a second one, and is said **without a count** — what the user asks is whether any recordings are left,
   and the number does not change what they do (2026-10-05, both watches, tiles and complications included). The line says one thing, in this order: what a stop had to report
   (a failure, a deferred save), what the recorder is doing, transfers the phone refused (Wear), then recordings still on the watch. Idle with nothing
   left, it is blank and keeps its height, because the hollow button already says the watch is ready. A stop that went as asked is not announced.
8. **Button placement** (2026-09-25, common to mobile and desktop; directions are by start/end, so they flip in RTL):
   - A button group inside a form or settings block is **end-aligned (right)**. The confirming action is at the very end, and `Cancel` is right before it. In the recording processing
     settings, `Cancel` · `Save` appear **only when something has changed** — two disabled buttons do not take up room all the time, and their appearing
     is itself the signal that there is something to save.
   - Actions attached to one item go at the end of that item's row (Drive disconnect, deleting another provider's key). If the item spans several lines, they are end-aligned
     below it. Buttons right below an input field, value or notice (`Save key`, `Replace key` · `Delete`, `Download model`, the confirmation in the permission revocation notice) are also
     end-aligned below it. Groups of a different nature are separated by a heading (recording processing's `Settings file`: export · import).
   - A single recovery button below a centered notice (empty list, transcript load failure, microphone denied) is centered.
   - An empty list (2026-09-26) shows, in the vertical center of the space the list would take, one line in the body color (`No recordings yet`) and below it one dimmed line
     (`Recordings you make appear here.`), and a button is placed one step (24) away. Phones have `Record` in the tab bar right below, so they show no button;
     only desktop windows (Mac detail, Windows recording window) show `Start recording`. The short ledger in popovers and the tray shows only one line.
   - In the command row of the menu bar popover and the tray, the primary actions (start/stop · settings) are at the start, and secondary actions and quit at the end.
   - When dialog buttons do not fit on one line, they stack at full width and keep their order (primary action last).
   - Exception: the expanded actions of a ledger row pin only delete to the end, as in principle 2, and the rest wrap from the start.

### Accessibility

The system's `reduce motion` · `prefers-color-scheme` · contrast · font size are applied automatically — **the app settings have no accessibility section,
and the default motion and light/dark behavior is the only behavior.** The only exception is `Theme` in §9 principle 4: the light/dark scheme alone can be chosen differently from the system by
the user (it is a preference, not an accessibility toggle), and high contrast and motion are still decided only by the system. **Every state is color + text** — nothing says anything by color alone.
**Red means only two things (2026-09-26)**: in states, only failure (`FAILED` and its reason), and in actions, only irreversible deletion (`Delete` for a recording or an API key, the confirm button of a delete
confirmation dialog). Waiting states (`NEEDS_MODEL` · `NEEDS_CONSENT` · `NEEDS_AUTH` · `NEEDS_SPACE`), down to the banner text and the reason in the expanded row, use the same
warning tone as the badge or a dimmed color, and never red. **Korean does not break lines inside a word**: Android sets `WordBreak.Phrase` in the `LineBreak` of the app Typography
(the Compose default turns off Android 15+'s phrase-based line breaking for Korean), Windows (Compose Desktop)
has the string table insert U+2060 between Hangul syllables inside a word when the UI language is Korean (user text is left as is), and on Apple the default is already word-based.
**Size notation** in every shell uses decimal units: whole MB below 1,000 MB (`988 MB`), and GB with one decimal place from there up (`1.2 GB`). Tab order and
screen reader labels are resources (§7), and an element that opens a screen when pressed, like a row, states that role in its label (e.g. list row =
`row, button`).

---

## 10. Core (KMP) (formerly docs/10)

**Waiting for transfer permission on iPhone:** `NEEDS_CONSENT` in `job.status` and `step_run.status`, together with the `TRANSFER_CONSENT_REQUIRED` message, is the state that needs the external transfer permission of §15. An ordinary retry cannot get around it; once permission is given, the job resumes and keeps the steps it already completed.

**Waiting for a local model (2026-09-26):** When the engine is present but the model is not, `local.transcribe` throws `StepFailure(needsModel)`, and the job and the step wait in `NEEDS_MODEL` (`last_error` = `LOCAL_MODEL_REQUIRED`). It is not a failure, so it uses no attempts and does not report a failure to other devices. When `ReclyCore.prepareLocalEngine(language)` downloads the model, it returns the `NEEDS_MODEL` jobs for the same language to `PENDING`, keeping their completed steps and checkpoints. `Retry` (`JobService.retry`) is accepted too — it replans with the current settings, so it is the way out after switching to an external API. `LocalEngineInfo` reports the download size, the progress and whether a download is running through `modelBytes`·`progress`·`downloading`.

### Targets · dependencies

| Item | Value |
|---|---|
| Kotlin | 2.2.x, K2 |
| Targets | `androidTarget`, `jvm`, `iosArm64`, `iosSimulatorArm64`, `macosArm64`, `watchosArm64`, `watchosDeviceArm64`, `watchosSimulatorArm64` |
| HTTP | Ktor 3.x client — Android/JVM `OkHttp`, Apple `Darwin` |
| Serialization | kotlinx-serialization-json |
| DB | SQLDelight 2.x — Android/JVM `sqlite-driver`, Apple `native-driver` |
| Files | okio (`FileSystem`, `HashingSink` for sha256/md5) |
| Settings | multiplatform-settings (the shell provides secure storage as `SecureStore`) |
| Time | kotlinx-datetime |
| Logging | the core's own `Logger` interface |
| Swift exposure | SKIE + `assembleXCFramework` |

Simulator slices exist for **arm64 only** (Apple Silicon only). A static XCFramework does not export linker options, so
the Apple app targets need **`-lsqlite3` in Other Linker Flags**.

### Packages

```
recly.core
  model/        Workflow, Step, Trigger, RecordingMeta, Part, Job, StepRun, DeviceInfo   — 1:1 with spec
  ids/          Ulid
  workflow/     WorkflowParser · WorkflowValidator · Template · WorkflowSelector · WorkflowMutator
  recording/    RecordingRepository · MetaWriter · PartHasher (sha256 + md5)
  job/          JobService · Executor · StepRunner · Backoff · JobStore
  drive/        DriveApi · ResumableUploadPlanner · FolderResolver · AppData
  storage/      CloudFiles · CloudStorage (picks the storage by id) · ICloudFiles · UbiquityContainer · FolderFiles · LocalFolder (PathFolder) · StorageKind
  webhook/      Signer · PayloadBuilder · WebhookRunner   — retired (2026-09-24)
  transcribe/   SttProvider adapters · TranscribeRunner
  sync/         WorkflowSync (pull/push/merge)
  secrets/      SecretsRepository · SecretSync · SecretSyncStore
  transfer/     TransferReceiver (receiver-side verification · ack helpers)
  platform/     SecureStore · TokenProvider · Transport · Crypto · AudioTools · Clock · Logger · DeviceInfo
  ReclyCore     composition root
```

### DB schema (SQLDelight)

```sql
CREATE TABLE recording (
  id TEXT PRIMARY KEY, source TEXT NOT NULL, platform TEXT NOT NULL,
  workflow_id TEXT, title TEXT, started_at TEXT NOT NULL, ended_at TEXT, duration_sec REAL,
  timezone TEXT NOT NULL, dir TEXT NOT NULL, meta_json TEXT NOT NULL, status TEXT NOT NULL,
  drive_folder_id TEXT,                   -- this recording's `{base}/` folder (ADR-014)
  remote INTEGER NOT NULL DEFAULT 0,      -- a row adopted from Drive (§3 "Recordings from other devices")
  remote_pending TEXT,                    -- work that device still has to do (same section, the folder's `pending` marker)
  drive_synced INTEGER NOT NULL DEFAULT 0  -- Drive copy of this device's recording, verified and restored without a Job
);
CREATE TABLE part (
  recording_id TEXT NOT NULL, part INTEGER NOT NULL, track TEXT NOT NULL,
  file TEXT NOT NULL, bytes INTEGER NOT NULL, sha256 TEXT NOT NULL, md5 TEXT,
  deleted INTEGER NOT NULL DEFAULT 0,
  drive_file_id TEXT,                     -- the Drive file to fetch an adopted part from (§3)
  PRIMARY KEY (recording_id, part, track)
);
CREATE TABLE job (
  id TEXT PRIMARY KEY, recording_id TEXT NOT NULL, workflow_id TEXT NOT NULL,
  workflow_json TEXT NOT NULL,            -- snapshot of the definition at run time
  status TEXT NOT NULL,                   -- PENDING RUNNING WAITING DONE FAILED NEEDS_AUTH NEEDS_SPACE SKIPPED_SHORT
  created_at TEXT NOT NULL, updated_at TEXT NOT NULL, next_run_at TEXT,
  UNIQUE (recording_id, workflow_id)
);
CREATE TABLE step_run (
  id TEXT PRIMARY KEY, job_id TEXT NOT NULL, step_id TEXT NOT NULL, ordinal INTEGER NOT NULL,
  status TEXT NOT NULL,                   -- PENDING RUNNING SUCCEEDED FAILED SKIPPED NEEDS_AUTH NEEDS_SPACE
  attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at TEXT, last_error TEXT,
  state_json TEXT,                        -- per-step resume state
  output_json TEXT,                       -- output the next step reads (drive: folderId, files[])
  UNIQUE (job_id, step_id)
);
CREATE TABLE drive_folder_cache (path TEXT PRIMARY KEY, folder_id TEXT NOT NULL, checked_at TEXT NOT NULL);
CREATE TABLE sync_state (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE kv (key TEXT PRIMARY KEY, value TEXT NOT NULL);   -- non-secret settings such as deviceId
-- the secret_sync table was dropped together with the retirement of secret sync (migrations/2.sqm)
  name TEXT PRIMARY KEY, updated_at TEXT NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0, dirty INTEGER NOT NULL DEFAULT 0
);
```

`step_run.state_json` for `drive.upload`:

```json
{ "folderId": "…", "files": { "p001_mic": { "sessionUri": "…", "offset": 1310720, "fileId": null },
                              "p001_sys": { "fileId": "1AbC…" } } }
```

Because the session URI and the offset are saved, the upload continues chunk by chunk even if the process dies.

#### Schema migrations

`Rec.sq` is **always the full schema**, and what brings an installed DB up to date is
`core/src/commonMain/sqldelight/migrations/<n>.sqm` (`n` means "upgrade from this version", so `1.sqm` goes 1 → 2).
`Schema.version` comes from the migration files automatically (currently **7**; `3.sqm` adds `recording.drive_folder_id`·
`recording.remote`·`part.drive_file_id`, `4.sqm` adds `recording.remote_pending`, `5.sqm` adds
`recording.drive_synced`, and `6.sqm` adds `job.drive_account_id`·`job.disconnected_status`, §3).

- **Every time you change the schema**, edit `Rec.sq` and add the next-numbered `.sqm` **in the same commit**. Doing
  only one of the two makes the schemas of new installs and existing installs diverge.
- **Never edit a `.sqm` that has already shipped.** That file has already run on other people's devices — if something
  needs fixing, add the next number.
- Per driver: Android (`AndroidSqliteDriver`) and Apple (`NativeSqliteDriver`) take `RecDatabase.Schema` and handle
  create/migrate themselves through `user_version`. **JDBC (Windows, tests) does nothing**, so
  `JvmRuntime.openDriver` does it instead: if there are no tables, `create` and then stamp the version; if behind,
  `migrate`; if current, leave it as is. If `user_version` is 0 but tables exist, it is treated as **version 1** (a file
  from the time when the desktop did not stamp a version).
- Verification is `MigrationTest`: it upgrades a DB built by hand with the version 1 DDL and checks that the existing
  rows remain and that the new tables are used.
- **There is no way down.** Installing a v1 build over a DB upgraded to v2 kills the process with `Can't downgrade
  database` (this happens only when installing several branches on the same device; clearing the app data fixes it).

### Job state machine

```
enqueue ──► PENDING ──runDueJobs──► RUNNING ──all steps finished──► DONE
                                       │ step failed (retries left) → WAITING(next_run_at)
                                       │ step failed (abort)        → FAILED
                                       │ repeated 401               → NEEDS_AUTH  ──sign-in──► PENDING
                                       │ 403 storageQuotaExceeded → NEEDS_SPACE ──"Retry"──► PENDING
below minDurationSec ──► SKIPPED_SHORT (PENDING when run manually)
```

`NEEDS_AUTH` and `NEEDS_SPACE` are the two states that are **terminal until the user does something**. The scheduler
does not pick them even when it wakes again (`runDueJobs` targets only `PENDING`·`WAITING`), and they use no retry
budget.

- `runDueJobs(now)`: Jobs with `status IN (PENDING, WAITING) AND (next_run_at IS NULL OR next_run_at <= now)`, one at a
  time in `created_at` order. Only one runs at a time (mobile network, battery). On desktop the shell can raise
  `maxConcurrent` to 2.
- Steps run in `ordinal` order. Steps that are `SUCCEEDED`/`SKIPPED` are skipped; execution starts from the first
  `PENDING`/`FAILED` (retryable) step.
- When a step is settled as `FAILED` (attempts ≥ maxAttempts or a non-retryable error), `onError` applies: `abort` → Job
  FAILED; `continue` → next step.
- `runDueJobs` supports cooperative cancellation (WorkManager stop, app exit). State is saved chunk by chunk, so it
  resumes from the point where it was interrupted.
- The retention rule (§3, ADR-017 7-day window) is evaluated not when a job reaches DONE but by **`Retention.sweep(now)`
  at the end of every `runDueJobs` pass**: `JobStore.claimPurge` checks eligibility (all DONE, upload succeeded) and age
  (both the file mtime and the last DONE `updated_at` are 7 days old) inside **one transaction** and claims the parts
  with `part.deleted=1`, and only then does `RecordingRepository.purgeParts` delete the files (meta and DB are kept).
  `ReclyCore.audio(recordingId)` downloads the missing parts of the playback track (`mix`>`mono`) from Drive (`fileId`
  is from the upload output, sha256 verified), writes them under the same name and sets `deleted=0` again — that part
  lives another 7 days from its new mtime. `enqueue`, under the same transaction discipline, rejects a recording whose
  parts are all claimed (`PartsPurged`). When the conditions are not met, it logs `rec.retained`.
- A persisted `FAILED` step is terminal: the next pass does not call its runner again (it is either one that `continue`
  moved past, or it runs again only after `retry()` returns it to PENDING).
- `JobService.retry(jobId)`: NEEDS_AUTH/NEEDS_SPACE/FAILED/SKIPPED_SHORT → PENDING (failed steps reset).
  **WAITING → PENDING now** — clears `next_run_at` and the steps' `next_attempt_at` but keeps `attempts` (it means
  "now", not a budget reset; it remains a core rule, but since 2026-09-02 the UI has no "Upload now" button, and the
  shells' "Retry" makes this call only in failed states).
- The **paired transitions** of step_run and job (WAITING·NEEDS_AUTH·NEEDS_SPACE·FAILED) are written in one
  transaction. Defensively, if a PENDING step's `next_attempt_at` is in the future, it is not run, and the job is put in
  WAITING for that time.

### Unknown steps in a job snapshot

The **job snapshot** is the workflow at enqueue time (`job.workflow_json`). When an older build reads a job created by
an app that knows a newer schema, that snapshot may contain a step `type` the build does not know (when two builds take
turns on the same device, or when a backup is restored). If the decode exception travels through `JobStore.toJob` up to
`observeJobs`, the **whole list** dies, so the unit of isolation is a single job (the same kind of defense as the sync
freeze (§5)).

- `toJob` does not throw on a snapshot decode failure. It returns that job alone with `Job.workflow = null`, status
  **`FAILED`** whatever the row says, and the code for the shell to render in `Job.snapshotError`. Other jobs and
  recordings read normally.
- The code is `CoreMessage.UNSUPPORTED_STEP`, found by decoding `steps[]` one by one, with **the `type` of the first
  step that fails** as its argument. If no step can be singled out, it falls back to `STEP_FAILED`.
- `selectDue` never hands out such a job, and `claimPurge` does not take it as evidence that "the upload is all done" —
  an unreadable snapshot proves nothing, and the parts are the only copy of the original audio.
- **`workflow_json` is never rewritten.** The row stays exactly as the newer app wrote it, and `retry()` only returns
  the row to `PENDING`, so once the app is updated the same row decodes as is and runs as originally intended.
- **Retired (2026-09-24)** — With no user document, a job runs according to its snapshot (a fixed plan), and changing
  settings does not change jobs in progress (§5 "Fixed processing settings"). For the record: **the step definition in
  the document at run time wins** (2026-09-04). The snapshot is *what* the job is (which workflow, which steps); the
  document is what the user *means now*. A user who fixed a URL, key or plan after a failure expects the next attempt to
  use that fix (Z Fold7 device test: a waiting transcription kept calling only the snapshot's Free Clova domain for 30
  minutes). So when `Executor` runs a job, it looks up the workflow with the same `workflowId` in the current document
  and swaps in the document's definition for steps with **the same `id` and the same `type`**. If the workflow was
  deleted, the step is missing or its type changed, or the document cannot be read, that step stays as in the snapshot.
  The order and presence of steps are still the snapshot's (the `step_run` rows are exactly that).
- Deleting a recording does not read the snapshot, so it works as usual (§3 Delete recording).
- The shell uses `snapshotError` instead of the step's `last_error` as the error text for that job.

### Drive out of space — `NEEDS_SPACE`

When the user's Drive is full, uploading is **not solved by retrying.** Until a person frees up space or upgrades the
plan, every resend gets the same 403, and once the 8 backoff attempts are burned the Job becomes `FAILED`, burying "why
it failed" behind a retry-budget-exhausted message. So next to `NEEDS_AUTH` there is one more state of the same nature.

- **Detection**: Drive answers 403 with `errors[].reason == "storageQuotaExceeded"` (or a `message` that means the
  same). Other 403s (permission or scope problems) take the `DRIVE_REAUTH`/`STEP_FAILED` path. `DriveApi.send` does the
  detection **before `check`** — the resumable family skips `check` to read 308·404·5xx itself, so placing the
  detection after it would let a full Drive fall through as an ordinary non-retryable failure. That is why it is the
  same whether it comes from starting a resumable session, a chunk PUT, a multipart request or `meta.json`.
- **Behavior**: the step goes to `NEEDS_SPACE`, and so does the Job. `attempts` is **not incremented** and no
  `next_run_at` is set. The resumable session URI and offset in `state_json` are **cleared** — a session expires after 1
  week, and starting over once the user has freed up space is more reliable.
- **Retry**: only `JobService.retry(jobId)` returns the job to `PENDING`. There is no signal that clears it on its own,
  as sign-in does (the core does not poll Drive capacity), so the user has to press **"Retry"**.
- **One notification**: when a job enters `NEEDS_SPACE`, the shell posts 1 notification. If a job in the same state
  already exists, it posts no new notification and only updates the count on the existing one. `NEEDS_AUTH` uses the
  same rule.
- **The originals stay**: `NEEDS_SPACE` is not DONE, so the retention rule (ADR-017) does not delete the parts. Once
  there is space, the upload continues where it left off. Only the resume state is cleared; the `output_json.folderId`
  that "Also delete from Drive" uses survives the parking.
- Core message: `CoreMessage.DRIVE_STORAGE_FULL` (no arguments; detail holds what Drive said). The shell text is
  "Google Drive is out of space — free some up and try again" + a link to the Drive storage
  page (<https://drive.google.com/settings/storage>).
- **This detection also applies to the Drive writes of `transcribe`** (result files are written to Drive too).
- **iCloud** (§3 "Storage location"): if the system refused to upload any file because the account is out of space
  (4354), it is the same parking (`CoreMessage.ICLOUD_STORAGE_FULL`). The shell text is "iCloud is out of space — free
  some up and try again", with no link.

### Delete · disconnect API

The canonical rules are §3 Retention · deletion. The core exposes two calls.

```kotlin
suspend fun RecordingRepository.delete(recordingId: String, deleteDrive: Boolean): DeleteResult
sealed interface DeleteResult {
    data class Deleted(val driveDeleted: Boolean, val driveError: String? = null) : DeleteResult
    data object Busy : DeleteResult      // a Job for this recording is RUNNING — nothing was deleted
    data object NotFound : DeleteResult
}

suspend fun ReclyCore.disconnect(alsoDeleteRecordings: Boolean): DisconnectResult
data class DisconnectResult(val deletedRecordings: Int, val busyRecordings: List<String>)
```

Order of `disconnect`: delete recordings (optional, one by one with `delete`) → `tokenProvider.invalidate()` →
clear `tokens` → delete `job`·`step_run` (keeping those of
`busyRecordings`) → clear `sync_state` → clear `drive_folder_cache`. All of it runs inside `Executor.quiesced`.
Without the option, recording files and `recording`/`part` rows remain, and **Drive is never touched**. **The core
does not revoke the grant** — it is a platform SDK call and therefore the shell's job; the `DisconnectPhase`, revoke
debt and `DisconnectGate` the shell must honor are in §3.

### Failures the user can fix, and their notices

"Failures that heal if you wait" (5xx, network, 429) are retried quietly. The ones below are failures that **clear only
when a person does something**, and the app calls on the user only for this list. All text lives in resources, and the
core gives only `CoreMessage` keys (§7 rule 5).

| State · code | When | What the app says | What the user taps |
|---|---|---|---|
| Job `NEEDS_AUTH` (`NEEDS_AUTH`·`DRIVE_REAUTH`·`DRIVE_CONSENT_REQUIRED`) | a repeated 401, the Drive grant is gone, a consent screen is needed but there is no screen to show it on | The list banner is one gray line: “Uploads waiting: N” + a “Connect Drive” button. The system notification gives the reason a connection is needed and the number of waiting items | Sign in / allow Drive permission again → resumes automatically on success. On Android, just opening the app attempts to allow it again (§6 Android) |
| Job `NEEDS_SPACE` (`DRIVE_STORAGE_FULL`) | Drive 403 `storageQuotaExceeded` | "Google Drive is out of space — N recordings are waiting" | Open Drive storage / Retry |
| Step `FAILED` (`MISSING_SECRET:{name}`) | this device has no key with that name | "This device has no `{name}` key" | Enter the key (goes straight to the API key entry in settings) |
| **Retired (2026-09-24)** Step `FAILED` (`INVALID_SECRET:{name}`) — webhook signing keys only | the stored value cannot be used as a signing key | "The value of the `{name}` key is invalid" | Enter the key again |
| Step `FAILED` (`AUTH_REJECTED`) | the STT provider rejected the key (401/403) | "Check your key" + exactly what the provider said | Enter the key again |
| Step retrying / `RETRY_BUDGET_SPENT:QUOTA` (`QUOTA`) | provider quota or billing (429·402). A 429 waits quietly according to `Retry-After`, but once the budget is spent it is the user's problem | "The provider is out of quota — check its limits and billing" | Open the provider console / Retry |
| **Retired (2026-09-24)** Step `FAILED` (`WEBHOOK_HTTP:{status}`) | the webhook returned 4xx (except 408·425·429) | "The webhook rejected it with {status} — check the URL and the signing secret" | Open the workflow editor / Retry |

Rules:

1. **Notifications collapse to one per state.** Even if 5 jobs are blocked for the same reason, there is 1
   notification and only the count in its body goes up. A notification for the same reason is posted again only when
   the state cleared and then got blocked again.
2. **A tap goes to the screen that can fix it** — the sign-in screen, the API key entry in settings. It does not end at
   "Open app". Every reason has a place to go.
3. **When the state clears, the notification is removed.** When that reason disappears from the queue, the notification
   goes away too.
4. **Failures that retrying heals are not notified.** They show only as the status badge on the list row.
5. **Drive connection guidance is offered in one place, at the top of the list.** It does not duplicate the connection explanation or status badges, and it uses the existing secondary font and colors. When there is no waiting work, the list hides the connection guidance and the user connects in settings. Real failures such as running out of storage or key errors keep their existing reasons and fix actions.

| Platform | Surface |
|---|---|
| Android phone | notification channel `jobs` (separate from the recording FGS channel, **silent · default priority**). Tap → the matching screen. The banner at the top of the list shows the same reasons (Drive connection in the compact form above) |
| Galaxy Watch | none — the watch does not create Jobs (ADR-002). Only transfer failures, as one line on the watch screen |
| **iPhone** | `UNUserNotificationCenter` local notifications + a banner at the top of the list. Without permission, **banner only** |
| Apple Watch | none (same reason as iPhone) |
| **macOS** | menu bar icon error state + a banner at the top of the popover + `UNUserNotificationCenter` notifications (the same path as meeting detection) |
| Windows | tray icon error state + a `TrayIcon.displayMessage` balloon + a banner at the top of the tray popup |

### StepRunner

```kotlin
interface StepRunner {
    val type: String
    /** Output on success, StepFailure(retryable, reason) on failure. Save state often with ctx.saveState(). */
    suspend fun run(ctx: StepContext): StepOutput
}
class StepContext(val job: Job, val step: Step, val recording: RecordingMeta, val prior: Map<String, StepOutput>,
                  val state: JsonObject?, val saveState: suspend (JsonObject) -> Unit,
                  val secrets: SecureStore, val token: TokenProvider, val transport: Transport, val audio: AudioTools, …)
sealed interface StepOutcome { data class Done(val output: StepOutput); data class Waiting(val retryAfterSec: Int, val state: JsonObject) }
```

A failure throws `StepFailure`.

- `DriveUploadRunner`: resolve the folder (cache → `files.list` → create) → a resumable session per part (a single
  multipart request if ≤5 MB) → md5 verification → `meta.json` last → output `{folderId, webViewLink, files[]}`.
- `WebhookRunner` — **Retired (2026-09-24)**. For the record: find the most recent `drive.upload` output in `prior` and build the payload → sign → POST → response rules (§4).
- `TranscribeRunner`: remux the input tracks with `deps.audio.concat` → `SttProvider.submit` → repeat `Waiting(30)` →
  when `poll` completes, normalize to `transcript.json/.txt` → local copy + Drive write (the `drive.upload` folder from
  `prior`).
- **`StepOutcome.Waiting(retryAfterSec, state)`** — when `run` returns this, the Executor does not increment `attempts`,
  leaves the step `PENDING`, moves the Job to `WAITING(next_run_at = now + retryAfterSec)` and saves `state`.

Finding "the last X output of an earlier step" is done by `priorOutput` alone, and the runners use it.

### ResumableUploadPlanner (ADR-015)

A set of pure functions. It knows nothing about the network.

```kotlin
object ResumableUploadPlanner {
    fun startRequest(meta: DriveFileMeta, totalBytes: Long, mime: String): HttpPlan          // POST …?uploadType=resumable
    fun chunkRequest(sessionUri: String, offset: Long, chunk: ByteRange, total: Long): HttpPlan
    fun queryRequest(sessionUri: String, total: Long): HttpPlan                              // Content-Range: bytes */total
    fun onResponse(status: Int, headers: Headers, body: String?): Outcome                    // Continue(offset) | Done(file) | Restart | Retry(after) | Fail(reason)
}
```

`Transport` executes an `HttpPlan` and returns `(status, headers, body)`. The default implementation is Ktor. The Apple
shell provides an implementation that sends chunk PUTs as background `URLSession` upload tasks (it cuts each chunk into
a temporary file and uses `fromFile`).

Chunk size: 1 MiB on mobile (a multiple of 256 KiB), 8 MiB on desktop. 5xx or a network error → recheck the offset with
`queryRequest` and continue. 404 → restart the session (Drive discards the earlier partial upload). A session is valid
for 1 week — if the session in `state_json` is older than 7 days, restart.

### Concurrency · threading

- The core exposes only `suspend` APIs. The shell injects the dispatcher (`CoreDeps.io`).
- DB access is serialized with a per-store `Mutex` (so that read-modify-write sequences do not overlap even if the
  shell passes a multithreaded dispatcher; a guard for platforms where the SQLDelight driver is not thread-safe).
- `runDueJobs` is not reentrant — `Mutex.tryLock`; if it is already running, it returns immediately.
- **A pair of transitions that spans different stores happens inside one lock**, as with a value and its meta row
  (§5 `SecretSyncStore`).

### Tests (all JVM, no device)

| Area | Tests |
|---|---|
| Parser · validation — **Retired (2026-09-24)** | `spec/examples/workflows.json` passes; each invalid-document fixture (duplicate step id, unknown variable, http URL, 11 steps…) gives its specified error |
| Template | variable substitution, time zone conversion, path-safe substitution |
| Selection rules — **Retired (2026-09-24)** | driven by the 4-step rule table |
| Backoff | 8-attempt sequence cap, jitter range |
| ResumableUploadPlanner | 200/308/404/5xx/401 scenarios, chunk boundaries, offset recheck |
| DriveUploadRunner | full flow with Ktor `MockEngine`: create the folder → 5xx on the 2nd of 3 part uploads → resume → re-upload on md5 mismatch → meta last |
| WebhookRunner — **Retired (2026-09-24)** | signature verification with the official Standard Webhooks test vectors; 429 `Retry-After`; immediate failure on 4xx; the earlier step's output is reflected |
| Executor | resume from the interruption point after a simulated process restart (keep only the DB, new Executor); `abort`/`continue`; `NEEDS_AUTH` transition; quiesce order |
| Sync | pull/push/merge cases: **remote-only change**, local-only change, **change on both sides → LWW per id**, deletion heuristics, broken remote, push after an Outdated schema migration |
| SecretSync | real PBKDF2·AES-GCM operations, forged AAD, tombstones and the deletion watermark, extra files, fail-closed |
| **Schema migrations** (`MigrationTest`) | DB built with the version 1 DDL → after migrate, existing rows preserved + new tables used |
| Job snapshot (`JobSnapshotTest`) | an unknown step type isolates only that job as FAILED; no effect on the list, `selectDue` or `claimPurge` |
| TransferReceiver | sha256 mismatch → nack; orphan parts without meta deleted after 24h |
| Drive out of space (`DriveQuotaTest`) | 403 `storageQuotaExceeded` → `NEEDS_SPACE` (attempts unchanged, `next_run_at` null, `state_json` cleared), afterwards `runDueJobs` does not pick it again, completes after `retry()`; other 403s take the existing path |
| Delete (`RecordingDeleteTest`) | the four tables deleted, `RUNNING` → `Busy`, local data is deleted even if `deleteDrive` fails and `driveError` is set, delete vs `claimRunning` race, cancellation at commit time |
| Recordings from other devices (`RemoteRecordingsTest`) | list folders → adopt (parts `deleted=1`+`drive_file_id`, `meta.json` written), sorted by start time, the second fetch makes 1 request, folders without meta are held back, own rows are not overwritten, only adopted rows whose folder vanished are deleted, two folders with the same id · move to a rerun folder (both the old folder vanishing and the new folder completing), rejects id mismatch · non-ULID · forged part file names, no account · throttle · fetch failure, "Delete local only" does not come back (+ the record is cleaned up when the folder vanishes, re-adoption after `clearIgnored`, via `drive_folder_id` even after the queue is emptied, `adopt` rejects inside the transaction), `uploaded` true · `enqueue`=`PartsPurged`, playback fetches by file id, sweep after 7 days, Drive deletion uses `drive_folder_id`, titles: rename→description+meta push, adopted rows too, pending on failure then the next fetch, description→applied to the row (pending wins), empty description ignored |
| Disconnect (`ReclyCoreTest`) | deletion order, recordings and `recording` rows kept, `busyRecordings`, Drive `files.delete` not called |

So that the `spec/` schemas and the core's serialization models do not drift apart, the tests parse the example JSON →
serialize it → compare its structure with the original.

---

## 11. Android · Wear (formerly docs/11)

Targets: Wear OS 6/7 (API 36/37) on Galaxy Watch4 and later, Android phones on API 34+. `minSdk 34`, `targetSdk 36`. The package name and
signing key are the same on phone and watch (a Play requirement).

### Modules

```
android/
  recording/   Library. SegmentedRecorder(MediaRecorder), RecorderService(FGS microphone), SilenceMonitor,
               AndroidSecureStore · deviceId · SystemClock (shared by phone and watch)
  datalayer/   One copy of the phone↔watch paths and JSON contract
  app/         Phone app. Compose. Recording processing settings, recording, running jobs, auth, Data Layer receiving
  wear/        Watch app. Wear Compose. Recording, transfer, tile, Ongoing Activity
```

### `:android:recording`

- The phone and the Galaxy Watch record **the microphone only**. System audio capture exists only in the meeting mode of macOS and Windows.
- `SegmentedRecorder`
  - `MediaRecorder`: `AudioSource.MIC`, `OutputFormat.MPEG_4`, `AudioEncoder.AAC`, 16 kHz (if unsupported, falls back to 44.1 kHz and
    records that in the meta), mono, 32 kbps.
  - Segments: `setMaxFileSize(segmentSec × bitrate/8 × 1.07)` + `setNextOutputFile(nextFile)` set in advance;
    on `MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED` the previous part is finalized (sha256 computed on the IO thread) →
    `RecordingRepository.addPart`. `setMaxDuration` **stops** the recording at `MAX_DURATION_REACHED`, and
    `MAX_DURATION_APPROACHING` is not in the public SDK, so it is not used.
  - `setPrivacySensitive(true)` must be called **before** `setOutputFormat` (calling it afterwards throws
    `IllegalStateException` on API 36).
- `MicrophoneRouting`: requests `MediaRecorder.setPreferredDevice` in the order wired/USB input → Bluetooth HFP/BLE headset →
  OS default input. Within the same kind, it keeps the input actually in use.
  Device connect/disconnect and routing notifications are coalesced in 250 ms windows, and during recording the actual `routedDevice` is checked every second.
  If the request is refused or not applied within 5 seconds, it goes back to the OS default input, and a refused device is tried again when it reconnects.
  Vendor-specific inputs follow the OS choice. Switching inputs never re-creates the `MediaRecorder` or the segment file.
  `setCommunicationDevice`, SCO start/stop, call mode and playback capture APIs are not used.
  Missing route information alone does not end the recording; it leaves a diagnostic log. Real recorder errors go through the existing save and stop path.
- `SilenceMonitor`: `AudioManager.registerAudioRecordingCallback` — records `isClientSilenced` transitions as `silenced`
  ranges. Earlier callbacks that arrive after the recording ends are ignored.
- `RecorderService`: `foregroundServiceType="microphone"`, notification + `OngoingActivity` (watch), action: stop. A start must always come
  from a visible activity, tile or notification action (the while-in-use rule). On stop it calls `finalize` (title null) **immediately** → the phone UI then shows the title
  dialog (`updateTitle`) → `jobs.enqueue`; the notification's stop action enqueues directly. On the watch, `TransferQueue.add`. A duplicate
  stop is ignored, and finalize and enqueue run to completion in a scope independent of the service's lifetime.
- `RecordingRecovery.reconcile()`: at app start and before a new recording starts, finds rows with `status = recording` (except rows still being
  received from the watch, §3 "Watch → phone transfer contract"), registers and finalizes the unregistered parts on disk, and hands recordings that were finalized but not processed
  to `RecorderHost.onRecordingReady(recordingId, enqueue=true)` (recovery from process death or from termination during the dialog,
  §3).
- **The host decides the enqueue policy**: `RecorderService` and `RecordingRecovery` do not call `core.enqueue` directly; after
  finalize they call `RecorderHost.onRecordingReady(recordingId, enqueue)`. The phone host does
  `core.enqueue` + `onJobsDue` when `enqueue` is set; otherwise the title dialog enqueues later. The watch host calls
  `TransferQueue.add` regardless of the flag — the watch does not create Jobs (ADR-002).

### Phone `:android:app`

| # | Scope |
|---|---|
| A1 | Project skeleton, `:core` dependency, DI, DataStore, `ReclyCore` assembly |
| A2 | Auth: Credential Manager sign-in + `AuthorizationClient` (drive.file), `TokenProvider`, secure storage |
| A3 | Recording screen + `RecorderService` hookup, ~~workflow picker sheet~~ (retired 2026-09-24), title and participant count input after stop (optional) |
| A4 | Recording list: state (recording/waiting/uploading n%/done/failed/sign-in needed/out of space), manual run and delete |
| A5 | `WorkflowWorker`: `OneTimeWorkRequest` (unique `rec-jobs`, `NetworkType.CONNECTED`), calls `runDueJobs()`, `Result.retry` on failure (the periodic instance returns success after the cap — so the 6h safety net does not die); **after every pass, the follow-up run is recomputed**: the minimum of WAITING's `nextRunAt` and PENDING (immediate) arms a single `rec-jobs-next` (REPLACE, delay 0 if in the past); if `alreadyRunning`, the timer is left alone and a follow-up comes 60 seconds later; enqueue also arms `rec-jobs-next` at 0 seconds; the core `retry` (WAITING included) + `setExpedited` that the old "Upload now" used is used unchanged by "Retry"; changing the "Upload on Wi-Fi only" setting re-arms `rec-jobs-next` and `rec-jobs-periodic` with the new constraint (`rec-jobs` is KEEP — cancelling a running pass uses up retry budget; one pass already in the queue may run under the old constraint) |
| A6 | **Retired (2026-09-24)** — workflow editing UI: list ("Edit" · "Use" · delete on each row, a badge on the row in use), name/minimum length, step order editing (drag), `drive.upload` folder template, `webhook` URL/secret, `transcribe` form and provider notice, a warning for secrets missing on this device, key management (values are per device, §5). Write rules: every document change re-reads `current()` inside the mutex, then applies and saves (prevents races between whole-document saves); the editor remembers the `updatedAt` from when it was opened, and if a save from another window changed it, it refuses to save and offers "Reopen"; copying a secret sets the clipboard sensitive flag |
| A7 | **Retired (2026-09-24)** — workflow export/import in settings (§5): SAF `CREATE_DOCUMENT` (default name `recly-workflows.json`) · `OPEN_DOCUMENT`, import applies after a replace confirmation, a note that keys are not included in the file |
| A8 | Data Layer receiving: `WearableListenerService` (`ChannelClient.onChannelOpened` → `receiveFile`, paths `/rec/part/…` · `/rec/meta/…`), sha256 verification, `MessageClient` ack `/rec/ack` · `/rec/ack-meta`, on meta receipt register + enqueue + `onJobsDue`; ~~publish the workflow summary on `DataClient` `/rec/workflows` (urgent)~~ (retired 2026-09-24); declares the `rec_phone` capability |
| A9 | Entry points: Quick Settings tile, home widget (start/stop), app shortcuts. When starting from a tile or widget, the FGS background-start exception is either **used or given up** — it never fails silently |
| A10 | Settings: Google Drive connection state and disconnect action, language, Wi-Fi only, consent reminder, **log export** |
| A11 | Play listing: Wear OS form factor included, screenshots, Data safety form ("No data collected") |

### Watch `:android:wear`

| # | Scope |
|---|---|
| W1 | Skeleton: Wear Compose M3, `standalone=false` (phone app required), reuses `RecorderService` |
| W2 | Main screen: large start/stop button, ~~workflow picker (summaries received over `DataClient`; if none was picked on the watch, **"Phone's workflow"** — both watches say the same thing)~~ (retired 2026-09-24), elapsed time, recordings waiting to transfer (`Sending` while it is finding the phone and handing them over — only while a pass has the channel open, 2026-09-04; in the status line without a count, §9 screen principle 7). The record screen fills the first screen, and Help sits below it, reached by touch or bezel scroll (2026-10-05). There are only two screens and no navigation library — the only journey on the watch is "record" |
| W3 | `OngoingActivity` (Wear OS 6) / Live Updates (7): watch face chip, tapping opens the app |
| W4 | `TransferQueue`: finds the phone node with `CapabilityClient`, `ChannelClient.openChannel` → `sendFile` per part, waits for the ack (5-minute timeout), deletes on ack, keeps the queue on failure or no connection, retries on connection events. The acceptance criterion is **"record with the phone off → turn the phone on → automatic transfer completes"**, and until then the watch screen honestly says "the phone does not have it yet" |
| W5 | Entry points: tile (`launchAction`), complication (state), **a second launcher item "Recly Record"** (`QuickStartActivity` — Samsung's "Double press home key" setting can only pick an app and cannot pass extras, so this item attaches the auto-start extra, hands off to MainActivity and disappears), **a setup guide screen** ("Double home key → record"). The criteria are **"tap the tile → recording starts at once"** and **"double home key → recording starts at once"** |
| W6 | Haptics: CLICK on start, DOUBLE_CLICK on stop, tick on transfer complete |
| W7 | Battery: no Wi-Fi request while recording, no keep-screen-on; transfer prefers charging (optional) |

### Caveats

- The `dataSync` FGS is not used (6h/24h cap). Uploads go through WorkManager.
- The watch app has no auth, network or workflow execution code. From `:core` it uses only `model`, `recording` and `transfer` (the sending-side
  queue model), and the module has no HTTP client at all.
- If battery optimization is on for the Galaxy Wearable app, the BT proxy drops — this is included in the setup guide.
- **Samsung sleeping apps delay WorkManager.** The UI does not hide it and honestly shows "Waiting to send"
  (2026-09-02: the "Upload now" button is gone — opening the app runs a foreground pass, and "Retry" runs a failed job
  at once).
- **Round screens** (2026-09-29, Play Wear quality rejection for "watch shape"): a scrolling list shrinks an item only once it has almost entirely left the screen,
  so a whole paragraph in one item gets clipped by the curve above and below the center. The setup guide screen gives the title and each sentence its own item
  (the split follows the language's sentence boundaries, so the translations stay whole paragraphs) and centers them; it adds 9.4% of the screen width to the scaffold's 5.2% on each side
  to fit the width of the square inscribed in the circle (14.6% per side), and it opens at the first item. The recording screen takes sizes designed for a large round watch (227dp)
  and scales them down by the width ratio on smaller watches (§9 "Fluid typography") — on a 192dp watch the Help button caught on the bottom curve
  (it has been below the first screen since 2026-10-05).
  Verification uses round 454 px and 384 px emulators at font scale 1.0 and 1.24, with screenshots when the screen opens, mid-scroll and at the end.

---

## 12. macOS (formerly docs/12)

Target: macOS 14.4+ (the version where Core Audio process tap TCC is stable), Apple Silicon first. Menu bar app (`LSUIElement`), no
sandbox (direct distribution).

### Structure

```
apple/
  Rec.xcworkspace
  RecKit/                       Swift package (shared by iOS · watchOS · macOS + platform conditionals)
    Sources/RecKit/
      Recorder/                 SegmentedRecorder (AVAudioEngine tap → AVAudioFile AAC, 900 s rotation) — all Apple targets
      MacCapture/               #if os(macOS): ProcessTapCapture, MicCapture, TrackWriter(mic/sys/mix), DriftCompensator
      Detect/                   #if os(macOS): MicInUseMonitor, MeetingAppMonitor, MeetingDetector
      Transfer/                 #if os(iOS)||os(watchOS): WatchTransferQueue (WCSession)
      Auth/                     #if os(iOS)||os(macOS): GoogleAuth (AppAuth, Drive-only) → TokenProvider
      Transport/                #if os(iOS)||os(macOS): BackgroundTransport (URLSession background)
      Storage/                  #if os(iOS)||os(macOS): ICloudContainer (core UbiquityContainer — §3 "Storage location")
      Workflow/                 CoreWorkflowDocuments, WorkflowInspector (editor shared by both shells — retired 2026-09-24)
      CoreBridge/               ReclyCore(XCFramework) assembly, SecureStore(Keychain), FileSystem, Logger, Crypto
  RecMac/                       Menu bar app, SwiftUI
```

`ReclyCore.xcframework` is the output of `./gradlew :core:assembleXCFramework`, and `apple/scripts/build-core.sh` stages it
into `apple/RecKit/Frameworks/`. It is a static framework, so **the app target needs `-lsqlite3` in Other Linker Flags**
(without it: unresolved `_sqlite3_*` symbols).

**Retired (2026-09-24)** — **Workflow editing logic is not written twice in Swift.** `CoreWorkflowDocuments` implements the core's `WorkflowDocuments` in Swift
(`__current`/`__save`/`__writeFrozen`), and the edit block passes the
`suspend (WorkflowsDocument) -> WorkflowsDocument?` the core requires as `DocumentMutation: KotlinSuspendFunction1` — all three shells
use the same mutex and the same staleness rule.

### Capture pipeline

```
 Microphone (AVAudioEngine inputNode, converted to 16 kHz mono)  ─┐
                                                                  ├─► DriftCompensator ─► TrackWriter(mic) ─┐
 System (CATapDescription global tap, own process excluded       ─┘                       TrackWriter(sys) ─┼─► mix (summed with −6 dB headroom) ─► TrackWriter(mix)
        → private aggregate that contains no physical device → IOProc, verified input rate)
```

- The three `TrackWriter`s share the same start time and the same segment boundaries (900 s). Part numbers match across tracks.
- The microphone and system callbacks copy the buffer and the host timestamp of its first sample, then return immediately. Conversion and AAC writing
  happen on separate serial queues, and pending audio is capped at 2 seconds per queue. The microphone is processed 0.6 seconds late to absorb
  the system resampler's latency, and on stop the tails of both queues and the converters are all saved.
- `DriftCompensator`: streams with timestamps are aligned on the common host clock. It does not cut large system buffers down to the microphone
  callback size and throw the rest away. The origin is corrected with each callback's capture time so that small hardware clock differences do not
  accumulate. The existing path for inputs without timestamps uses the cumulative frame count vs wall clock to **estimate the rate difference every 60 seconds** and
  resamples. Target: offset between the two tracks < 20 ms after 1 hour.
- **Microphone selection**: always automatic. A manual UID saved in the past is not used. It selects the only active input of a confirmed meeting app → the macOS default input,
  in that order. A browser counts as a meeting input only when the title of a meeting window owned by that browser can be confirmed.
  An automatically selected input is not changed by a meeting-app mute alone. A normal device switch settles for 1 second and a disconnect gets a 2-second grace period before
  a replacement input is selected, and the selected device's state and full format are checked every second. Transient errors on the very first start are also retried briefly.
  Only Recly's AudioUnit is bound; the OS default input and output and the meeting app's device and mute settings are not changed.
  AirPods input is supported, and the built-in microphone is not forced automatically during a call. A recording the user started continues independently of the meeting app's
  mute. No microphone picker UI is exposed; during recording only the current input device's name is shown as status. The guidance for restoring microphone permission stays.
- **Echo**: both tracks are saved without AEC. If the output device is the built-in speaker, a one-line warning at start ("With headphones the other side
  does not bleed into your own track.").
- Recording rules: on stop or input restart, `AVAudioConverter` is drained with `.endOfStream` before the segment is closed (preserving the tail frames of 48 kHz
  input); if a closed segment cannot be read back, it is not registered and stays `.pending`, holding back
  finalize; recovery quarantines an unreadable tail as `.corrupt` and deletes a recording with no readable part (§3);
  `applicationShouldTerminate` answers `.terminateLater` while a session is alive and quits after stop · finalize · enqueue; the device ID lives in
  `{dataDir}/device.id`, not the Keychain (avoids the keychain ACL modal on ad-hoc-signed rebuilds).
- **Permissions**: `NSMicrophoneUsageDescription`, `NSAudioCaptureUsageDescription`. There is no API to check tap permission in advance, so the prompt comes at the first
  IOProc start, and if that fails, the app points to the System Settings "Screen & System Audio Recording" deep link.
- **Tap re-creation**: when the output device changes (`kAudioHardwarePropertyDefaultOutputDevice`) or the format changes, the tap is re-created and recorded in `gaps`.
  The capture format is read from **the aggregate input stream's virtual format**, not the format right after the tap is created.
  It has to match the samples actually delivered even when the rate changes in Bluetooth call mode. It subscribes to format changes of the input stream and
  also re-checks with 2-second polling. A format that cannot be read, or that is not mono Float32, is never interpreted by guesswork.
  The aggregate does not include the real output subdevice, and the sample rate setting applies only to this virtual device.
  It also subscribes to tap format changes. It verifies the delivery rate over two intervals of at least 0.25 seconds from the capture timestamps and frame counts, and
  if that differs from the reported rate by more than 3%, it stops using that generation's data and re-creates only the system tap. This is not a volume or silence
  check, and it does not treat the other side not speaking as an error. The microphone keeps going while the system tap is being recovered.
  Consecutive system buffer shortfalls also request recovery. Repeated recovery uses a retry interval of at most 10 seconds, and recovering/failed is shown
  in the status line. If the input itself drops, the app reports an error after a limited number of reconnect attempts.
  The `rec.tap.format` log records the tap and input stream rates. The status line shows **the name of the output device being captured**.
  `capture-diagnostics.json` in the recording folder stores the device name, rates, OS version and recovery/buffer-loss events, up to 512 of them.
  This diagnostics file stays local only and is not part of the Drive upload.

### Meeting detection · context

- `MicInUseMonitor`: a `kAudioDevicePropertyDeviceIsRunningSomewhere` listener on the default input device + a default-input change listener.
- `MeetingAppMonitor`: the list of running app bundle IDs (`us.zoom.xos`, `com.microsoft.teams2`,
  `com.tinyspeck.slackmacgap`, `com.hnc.Discord`, browsers) — when the microphone is in use && a meeting app is active, an "Are you in a meeting? Start
  recording" notification (one click). Cooldown 600 s, one notification per meeting. **Browser Meet goes only as far as the window title, without asking for AX permission.**
- **There is no calendar context.** EventKit reads and the meta field `context.calendar` (title · start · end · attendee emails) were removed from the
  whole product — calendar access was the only path that carried attendee emails all the way to the webhook, and to get that value
  only macOS carried one extra permission prompt. In all three shells the title is entered after stop.
- **End detection**: when the microphone has not been in use for 60 seconds straight, the app raises an "End the recording?" notification. **It is not an automatic stop** —
  the app does not stop the recording for the user.

### Menu bar app

- **Status icon**: idle / recording (red) / uploading / error. The error state is also spoken through the accessibility description.
- **Menu bar** popover: 3 state nodes + the recent ledger (infinite scroll, 20 rows at a time; state, and when expanded, the actions of §9 principle 2 — Open in Drive · Retry · Details · Delete —
  on one horizontal line) + actions, with a notice banner at the top.
- **Mac popover size**: the height is limited so the popover fits inside the current screen minus the menu bar and the Dock. Only settings and the ledger scroll, and
  Settings/Hide settings and Quit at the bottom are always shown. While settings are open, the button reads `Hide settings`.
  Hiding settings shrinks it to the ledger's size, and when the screen layout changes, the position and height are fitted again.
- **The popover holds these items**: start · stop, the recent ledger, settings, sign-in (the workflow editing window was retired 2026-09-24). The AWT menu on Windows is
  a reduced fallback (macOS uses a `.window` MenuBarExtra, so there is no NSMenu).
- **The popover stays open when this app's windows take focus** — opening a details or settings window or the delete dialog from the popover
  and clicking inside it does not close it. Only a click in another app, a click on the status icon, or Esc closes it (on the Mac, `MenuBarPanel`'s
  AppKit `NSPanel` + a global mouse monitor, 2026-09-03; the Windows tray popup tells apart the case where focus went to another window of this JVM,
  2026-09-04).
- **Runner**: the app process calls `runDueJobs()` (a) right after a Job is created (b) on a 5-minute timer (c) when the network returns (`NWPathMonitor`)
  (d) as the `nextRunAt` follow-up. Launch at login via `SMAppService`.
- **Workflow editing window** — **retired (2026-09-24)**. For the record: the same features as on the phone. A SwiftUI form + `WorkflowInspector` (shared in RecKit). The desktop is the main
  stage for editing.

### Agent connection

The Mac app bundles `recly-events` (`events/`, §15 §9) as `Recly.app/Contents/MacOS/recly-events` and runs it for the user while
**Settings → Agent connection → Tell ChatGPT about new recordings** is on. It is **off by default** (2026-10-05). The app talks to it only by running it:
`status --json` every 5 seconds while the switch is on; `init --tunnel-id … --tunnel-key-stdin --no-check` for the tunnel fields, with the key on standard input and never in the arguments; and
`serve --drive-token-stdin` as a child process writing to the program's own `logs/serve.log`, with a pipe on its standard input that only the app holds (2026-10-06). The app
writes its own Drive connection's short-lived access token (about an hour) to that pipe, one per line — right after the start, then whenever the token changes (checked every 5 seconds
while the server runs) — so this copy has no Google sign-in of its own and the refresh token never leaves the app's secure storage (§15 §9); and the server stops whenever the pipe
closes, the app quitting or crashing, as `--exit-with-stdin` did. A server that exits is restarted at most 3 times in 10 minutes, then the status line says so until the
switch is turned off and on. A server the app did not start — the CLI's `service install`, or a terminal — is left alone and shown as running outside Recly; it keeps
the CLI's own Google sign-in (`init --google`, `google-token.json`).
The program keeps its own home directory (`~/Library/Application Support/recly-events`) and tunnel key, shared with the CLI; the app stores only the switch (`agentEventsEnabled`).
`make mac` and `make mac-release` build the arm64 program first (Go, Recly's desktop client from `local.properties`, with its notices as
`Contents/Resources/THIRD-PARTY-recly-events.txt`), and the `Embed recly-events` build phase signs it like the app, with the hardened runtime. A build made without Go has
no program, and the switch says `Not in this build`.

**The section (2026-10-06)**, top to bottom: the switch, one status line (only while the switch is on), the `OpenAI tunnel` row, the footnote
(`Runs recly-events on this Mac. It reads only the names and links …`) and `Set-up guide`. The footnote and the guide stay after an agent subscribes:
the subscription recly-events remembers can outlive the agent or the ChatGPT app, and a new tunnel or key needs the guide again. `Set-up guide` opens
`https://recly.dev/agent` in the browser (`https://recly.dev/agent.ko` when the app is in Korean). The switch only decides whether
recly-events runs: the tunnel row, the footnote and the guide are there with it off too, so it can be set up first and turned on last, or a tunnel changed
without starting it; off, a saved tunnel starts nothing. There is no Google row: the copy the app runs reaches Drive through the app's own connection, the
`Drive connected` row in the same pane. (A `Google sign-in` row — recly-events' own sign-in, compared with the upload account by `permissionId` — was
retired the same day.)

- **Status line**: one sentence that says only what the rows do not. `Connect Google Drive above to start` while this device's Drive is not connected (the app's
  own state: the Mac's restored credential, Windows' signed-in state) — the server is not started then, and is stopped if Drive is disconnected while it runs.
  With the §9 square loader: `Starting` · `Connecting to the tunnel`. Running with a subscribed agent: `Your subscribed agent hears about each new transcript`;
  with no subscription yet: `Add the app in ChatGPT and ask your agent to subscribe`; after the subscription ended:
  `The subscription ended. Ask your agent to subscribe again.` A server started elsewhere: `Already running outside Recly`. In the danger color:
  `The tunnel is not connecting. Check the tunnel ID and key.` and `Stopped after repeated errors. Turn it off and on to try again.` Nothing while the tunnel
  row asks for its fields. `Running — ChatGPT can reach this Mac`, `Connect Google Drive and save a tunnel to start` and `Google sign-in ended. Connect again.`
  are gone — "can reach" said what was possible, not what was happening or what to do next — and `Finish signing in in your browser` went with the sign-in row.
- **`OpenAI tunnel`**: once a tunnel ID and key are saved, one row `✓ Saved on this device` in the success color — the shape of the API key row (§5 "Secrets") —
  with a quiet `Change tunnel`. The `Tunnel ID` · `Tunnel key` fields and `Save` show only while nothing is saved, or after `Change tunnel` (then with `Cancel`;
  the ID is prefilled, and a key left empty keeps the saved key — its placeholder says `Leave empty to keep the saved key`). `Save` needs an ID, and a key when
  none is saved.
- **Google Drive storage only**: recly-events can see nothing in iCloud or a local folder, so with either as the storage location (§3 "Storage location") the
  switch is disabled with the subtitle `Works only when recordings are stored in Google Drive` (in the place and shape of `Not in this build`), nothing below it
  is shown, and the app does not run recly-events. The switch keeps its own setting, which takes effect again when the storage goes back to Google Drive.

### Tasks

| # | Scope |
|---|---|
| M1 | Workspace, RecKit, XCFramework hookup, menu bar skeleton |
| M2 | `MicCapture` + `TrackWriter` → single mic track segment recording |
| M3 | `ProcessTapCapture` (global tap, self excluded) → sys track; permission flow and denial UX |
| M4 | `DriftCompensator` + mix track |
| M5 | Auth (AppAuth macOS) + `BackgroundTransport` + runner hookup |
| M6 | Meeting detection + notifications |
| M7 | **Retired (2026-09-24)** — **workflow editing window** + workflow export/import in settings (§5): `NSSavePanel` (default name `recly-workflows.json`) · `NSOpenPanel`, import applies after a replace confirmation, a note that keys are not included in the file |
| M8 | Consent reminder (once at the first recording, can be turned off in settings) + jurisdiction notices + speaker warning. The reminder asks before the *first* recording, and if "Do not ask again" is chosen, it can be turned back on in settings |
| M9 | Distribution: Developer ID signing, hardened runtime, notarytool, DMG (`apple/scripts/release-mac.sh`). There is no Sparkle auto-update |

### Consent reminder · jurisdiction notices

Local capture **leaves no sign at all** for the other side (unlike Zoom's own recording, there is no dialog and no badge on the participants' side).
So the duty to notify lies entirely with the user, and all the app can do is say so clearly once (ADR-011).

**What the app shows**

1. **Reminder** — "Did you tell the participants about the recording?" + [I told them] [Cancel]. Without confirmation, the recording does not start.
   It can be turned off in settings (turning it off is also the user's choice, and turning it off does not shift the responsibility). When it appears differs by device —
   Mac and Windows ask for every new recording (regardless of detection, meeting mode fixed), while phones (iPhone · Android) have no way to tell a meeting apart, so they
   ask **only once, before the first recording**, and the settings wording states that difference. **The two watches have none** (the screen is small, the phone is the source of truth, and
   the responsibility when recording with a watch is the same).
2. **Jurisdiction notice** — tapping "What's my jurisdiction?" opens a screen that summarizes the table below. It is a per-language resource; the list of jurisdictions and the links are shared.
   The wording is identical, letter for letter, across the four shells and is bound together by a comparison test.
3. **Always-on recording indicator** — no covert mode is built (App Store 2.5.14, Play stalkerware policy). The red menu bar
   icon · tray icon · Live Activity · Ongoing Activity · system microphone indicator.
4. The OAuth consent screen when connecting a Google account (§6) is **a different consent** — the user's own consent to Drive access, not the meeting
   participants' consent to being recorded. The notice does not mix the two.

**Jurisdiction summary** (**not legal advice** — everyone has to check for themselves)

| Jurisdiction | Gist | What the notice says |
|---|---|---|
| Korea | Under the Protection of Communications Secrets Act, **recording by a participant is legal** — a conversation you take part in may be recorded without the other side's consent and is not punishable, and there is no separate duty to notify. Conversely, **recording or listening in on a conversation between others that you are not part of is a crime**. Even lawfully recorded content can create separate liability if it is **disclosed or distributed** | "Record only conversations you take part in. Recording a conversation you are not present for is subject to criminal punishment." |
| United States (federal) | The federal Wiretap Act is one-party consent. However, **where state law is stricter, state law applies** | "It varies by state. If any of the states below is involved, get everyone's consent." |
| United States (all-party consent states) | Commonly cited: California, Delaware, Florida, Illinois, Maryland, Massachusetts, Montana, New Hampshire, Oregon, Pennsylvania, Washington. **The list differs by source** (some compilations include Connecticut · Hawaii and leave out Delaware), and the scope of "conversation" versus "telephone call" differs by state. In a remote meeting the participants are spread across several states, so **taking the strictest state as the baseline is the practical standard** | "If even one of these states is involved, get everyone's consent before you start, and keep a record of the consent." |
| EU / EEA (GDPR) | Recording a meeting is processing personal data. It needs a **lawful basis**, participants must be **told who keeps it, why and for how long**, and access and erasure requests must be answerable. The "purely personal or household activity" exemption does not apply to work or commercial recordings | "If it is a work meeting, give notice before you start and keep a record of the basis. If a participant asks for deletion, you have to delete the original and the result files in Drive." |
| Elsewhere | Not verified | "Check for yourself for countries not listed here." |

**What the app cannot guarantee** (stated in the notice with the same weight)

- It **does not notify participants automatically.** Posting a message into the meeting app's chat is impossible without meeting app integration, so
  there is only a **copy-text button** for wording the user can paste instead.
- It **does not determine the jurisdiction.** Guessing the country from the device locale to pick a notice is as far as it goes; where the participants are
  is something the app cannot know.
- It **does not record consent.** Having pressed "I told them" is only a local log entry in the app, not legal evidence.
- The table's content is **not legal advice**; it is a point-in-time summary (§Open decisions "legal review").

This section is not macOS-only — the reminders on Windows · Android · iPhone use the same wording resources.

### Open issues

- Unconfirmed whether the tap works in the App Store sandbox → direct distribution. If it is verified later, App Store in parallel.
- Echo when the built-in speaker is used. Make it an option depending on the results of the `setVoiceProcessingEnabled` experiment.

---

## 13. iOS · watchOS (formerly docs/13)

Targets: iOS 17+, watchOS 10+. The Apple Watch is iPhone-only (no Android pairing). These are the `RecPhone` and
`RecWatch` targets of the `apple/` workspace, and they share the RecKit modules (`Recorder`, `Transfer`, `Auth`, `Transport`, `Workflow`, `CoreBridge`) with
macOS.

### iPhone

- **Recording**: captures **the microphone only**. RecKit `SegmentedRecorder` (AVAudioEngine → AAC `AVAudioFile`, 900 s rotation).
  `AVAudioSession` is `.playAndRecord`/`.default`, option `.allowBluetoothHFP`.
  It selects automatically in the order wired/USB → Bluetooth HFP/BLE → built-in input, and keeps the current input within the same kind.
  If selecting an external input is refused, it uses the OS default input and tries again when the device reconnects or a new recording starts.
  `UIBackgroundModes: audio` keeps it going while locked. It checks the input UID, data source and actual sample rate, and on a device change
  continues the same recording with a new engine and converter. The hardware callback copies the buffer and timestamp, and file writing happens on a separate serial queue
  (pending audio capped at 2 seconds). Before stopping or reconnecting, the pending buffers and the converter's remaining samples are saved first.
- **Interruptions**: when a phone call or Siri takes over the input, it waits and, after the end notification, reconnects with the new input format.
  `silenced` is closed only after a real restart succeeds, and the reconnect interval is recorded in `gaps`. Late notifications for a recording that has ended are ignored.
  Loss or reset of the OS audio service saves the audio so far and ends the recording. Following Apple's guidelines, the next recording starts with a user action.
- **Playback**: before recording starts, playback is blocked and any existing player is stopped; playback is allowed only after the recording input has finished shutting down.
  Play switches the session to `.playback`/`.default` and activates it. The `.playAndRecord` category that remains after recording ends is not
  taken as a sign of whether a recording is in progress.
- **Display**: Live Activity (elapsed time, stop button) — Lock Screen · Dynamic Island · watch Smart Stack. It is **renewed at the 8-hour limit**.
- **Entry points**: App Intents `StartRecordingIntent`, `StopRecordingIntent` → Siri · Shortcuts · Action button.
  An iOS 18 Control starts by opening the app with `OpenIntent` (starting a long-running audio session from a widget extension is unreliable).
- **Auth**: AppAuth requests only `drive.file`, and `TokenProvider` handles token refresh, expiry and 401. RecKit `GoogleAuth` and `AppleTokenProvider` are shared with macOS, and the iPhone uses
  `signIn(presenting: UIViewController)`.
- **Storage location** (§3 "Storage location"): the top row of settings chooses Google Drive, iCloud or a local folder picked in Files. iCloud uploads are done by the system, so
  `BackgroundTransport` and the background URLSession are used only for Drive chunks and uploads for external transcription.
- **Runner** (the executor): in the foreground, RecKit `JobRunner` (right after a job is created · 5-minute timer · network return · `nextRunAt`
  follow-up + a fifth trigger only the phone has, app activation). When the app is not on screen, the work splits as in the table below.
- **UI**: recording, list, settings (including recording processing settings). SwiftUI. (Workflow editing was retired 2026-09-24)
- **App Review**: a review note giving the grounds for the 4.8 exception, 2.5.14 recording indicator (Live Activity + system microphone indicator).

#### What actually happens in the background

| Step | While the app is suspended | After the app is terminated |
|---|---|---|
| Drive chunk PUT | **Works** — `BackgroundTransport` cuts the chunk into a temporary file and sends it as an upload task on the background `URLSession` (`app.recly.upload`). The completion event enters the planner as a response and the next chunk is scheduled | The transfer finishes, but no coroutine is waiting for the result. iOS wakes the app with `handleEventsForBackgroundURLSession`, and the event only cleans up the temporary file. The offset is not lost, because `DriveApi` asks Drive again on resume |
| Starting the resumable session · `meta.json` · writing Job state | The core has to run for this, so it **does not work** | Does not work |

So **the only thing that happens on its own in the background is the upload bytes**; the rest is handled by `runDueJobs()` in the
execution time that a `BGProcessingTask` (`app.recly.jobs`) obtains. It is scheduled (a) right after a recording stops, (b) right after the app wakes for an upload event, and (c)
as one follow-up of its own by each task that was processed. `Info.plist` must have `UIBackgroundModes: processing` and
`BGTaskSchedulerPermittedIdentifiers: [app.recly.jobs, app.recly.uploadNow]` for registration to work (the chunk PUT itself
needs no background mode). On iOS 26, a pass the user started from a row (since 2026-09-02 this is **"Retry"**; the identifier name
still uses `uploadNow` from the old "Upload now") keeps running with progress UI as a `BGContinuedProcessingTask`
— registration uses the wildcard `app.recly.uploadNow.*`, submission uses `app.recly.uploadNow.{recordingId}`, and
`Info.plist` lists **only the prefix** (without `.*`). It is gated by `#available`, so below iOS 26 the foreground pass +
`BGProcessingTask` is all there is.

#### Receiving from the watch

In `WCSessionDelegate.session(_:didReceive:)`, **synchronously**: move the file → verify sha256 →
`transferUserInfo(["ack": …])` (guaranteed delivery) → on meta receipt, register and enqueue. On the receiving side of `transferFile`, a file
that is not moved inside the callback is deleted. Sending the workflow summary to the watch with `updateApplicationContext` was retired (2026-09-24).

| # | Scope |
|---|---|
| I1 | Targets · RecKit · XCFramework |
| I2 | Recording + background audio + Live Activity |
| I3 | Auth + **list** + foreground **runner** |
| I4 | `BackgroundTransport` + `BGProcessingTask` |
| I5 | **Retired (2026-09-24)** — **workflow editing** + workflow export/import in settings (§5): SwiftUI `fileExporter` (default name `recly-workflows.json`) · `fileImporter`, import applies after a replace confirmation, a note that keys are not included in the file |
| I6 | WC receiving · ack (the receiving side of §3 Watch → phone transfer contract) |
| I7 | App Intents · Action button · Control |
| I8 | TestFlight |

### Apple Watch

- **Recording**: the shared RecKit segment recorder. `UIBackgroundModes: audio` (not WatchKit's `WKBackgroundModes`).
  Records **the microphone only**. A session started in the foreground keeps going when the wrist is lowered. No length limit. 16 kHz AAC 32 kbps.
  `.record`/`.default`; on watchOS 11+, `.allowBluetoothHFP` allows headset input.
  watchOS does not support `setPreferredInput`, so the OS handles the actual input choice (watchOS 10 uses the default recording session).
  Handling of input changes, interruptions, buffer saving and audio service resets is shared with the iPhone.
- **Transfer**: `WCSession.transferFile(url, metadata: [recordingId, part, track, sha256, file])` per part + the meta last.
  The `didFinish` callback can be missed, so **the phone's ack (`didReceiveUserInfo`)** is the completion criterion. Acked parts are deleted.
  Retry: unacked parts are resent when the app becomes active (the phone ignores duplicates by sha256).
- **Entry points**: complication (state + tap to start), App Shortcut → Watch Ultra Action button, Double
  Tap (`handGestureShortcut(.primaryAction)`) for stop.
- No auth or network. (Receiving the workflow summary was retired 2026-09-24)

| # | Scope |
|---|---|
| WA1 | Target · RecKit (watchOS slice) |
| WA2 | Recording + background audio |
| WA3 | `transferFile` queue + ack |
| WA4 | Complication · App Shortcut · Double Tap |
| WA5 | Haptics (`WKInterfaceDevice.play(.start/.stop/.success)`) — the third haptic (transfer complete) means "it has now left my hands" |
| WA6 | Interruption (phone call · Siri) handling |

### Caveats

- watchOS app 75 MB limit — the linked `RecWatch.app` is 13 MB, so there is room to spare.
- Every Apple target **needs `-lsqlite3` linked** (a static XCFramework does not export linker options).
- Simulator builds go through **`apple/scripts/build-sim.sh`** (a wrapper that pins `ARCHS=arm64` on the command line):
  `ReclyCore` has no x86_64 simulator slice, and the SwiftPM package (RecKit) ignores project-level
  `ARCHS`/`EXCLUDED_ARCHS` conditionals as well as a `-destination` that names the arch — all three were tried and confirmed;
  only command-line build settings reach it. No x86_64 core target is added.

---

## 14. Windows (formerly docs/14)

Target: Windows 11 (per-process loopback needs build 20348+; below that, endpoint loopback fallback).

### Structure

```
windows/
  app/               Compose Desktop (JVM). Tray, recording processing settings, runner, auth (loopback PKCE), helper management
  capture-helper/    Rust binary. WASAPI microphone + loopback capture, resampling, drift correction, segment file writing,
                     microphone-in-use detection
```

JVM ↔ helper: the app spawns the helper, sends commands on stdin (JSON lines: `start {dir, base, segmentSec, tracks}`, `stop`,
`detect on/off`) and receives events on stdout (`part_done {…sha256}`, `level {peaks[]}`, `mic_in_use {app}`, `error`).
File names are built from the `base` the app gives — the helper does not name files.
**If the helper dies, the app finalizes up to the last part.** Stdout closing is the signal the app waits for. Stdout is the protocol and stderr is the log.

### Capture

- Microphone: WASAPI capture (shared mode, event-driven). `wasapi` crate.
- System: default render endpoint loopback (`AUDCLNT_STREAMFLAGS_LOOPBACK`).
  With no callbacks during silence, a timer inserts silent frames. Per-process loopback is not there yet — it is global.
- Encoding: piped to **the bundled ffmpeg** (ADR-019). The MF path remains as `--encoder mf`, and the CI `--self-test` actually tries both formats
  and logs the evidence.
- Track, segment and meta rules are the same as on macOS. Drift correction works the same way with the same target (1 hour < 20 ms).
  New desktop recordings on both macOS and Windows are fixed to **meeting mode (microphone + system audio)** (2026-09-24).
  The mode picker UI and the applying of previously chosen values are removed. `mono` support for phones, watches and existing recording meta stays.
  The Mac's system audio permission guidance and Windows' loopback handling follow the existing platform paths.
- **Permissions**: there is no prompt. If Settings → Privacy → Microphone → "Let desktop apps access your microphone" is off, the app detects it through the registry
  (`HKCU\…\ConsentStore\microphone`) and shows guidance.

### Detection

- Microphone use: active sessions from `IAudioSessionManager2::GetSessionEnumerator` (capture endpoint) + process
  names (`Zoom.exe`, `ms-teams.exe`, `slack.exe`, `Discord.exe`, browsers) + window titles (`EnumWindows`).
- The rules are pure Kotlin and **the same state machine** as macOS: microphone in use × meeting app × 600 s cooldown, one notification per meeting,
  **60 s idle → "End the recording?"** — not an automatic stop. There is no automatic recording either (ADR-011).
- The microphone signal comes from a different helper depending on the moment. When not recording, a detection-only helper reports with `detect on`; once a recording starts,
  the `mic_in_use` of **the recording helper** is used — a helper leaves its own process out of the session list, so only this way can a quiet meeting be told apart from the microphone Recly
  itself opened. The handover waits one direction at a time, and ownership is **a token per recording session**. `resume`, `mic_in_use` and notification actions whose token does not match
  are dropped. There is no interval in which both helpers are alive at once.
- Notifications are AWT tray balloons, so **they have no buttons** (toast libraries with action buttons are a Windows-only runtime dependency).
  Clicking the balloon is acceptance, and the reliable path is the tray menu item.
- **There is no calendar context** (removed from the whole product, as in §12). The title is entered after stop.

### App

- Tray: status icon, start/stop, recent (ledger, infinite scroll 20 rows at a time), settings, notice banner (the editing window was retired 2026-09-24). Launch at login is
  `HKCU\…\Run` (the counterpart of the Mac's `SMAppService`).
- Auth: the Windows part of §6 (Ktor CIO 127.0.0.1 server + system browser + PKCE). The refresh token is in Credential Manager (JNA).
- **Runner**: right after a Job is created + 5-minute timer + network return + `nextRunAt` follow-up.
- Packaging: `jpackage` MSI. The helper and ffmpeg (LGPL shared build) go into `app/resources/windows-x64/`, and the installed app
  receives them through `compose.application.resources.dir` and passes `--ffmpeg <path>` to the helper. The ffmpeg LGPL notice
  ships with the installation as `THIRD-PARTY-ffmpeg.md`. The MSI can only be built on Windows, so CI (`windows-release.yml`) builds it on release tags (`v*`) and attaches it to the GitHub release.

### Agent connection

The same as the Mac's (§12 "Agent connection"), off by default: `recly-events.exe` sits next to the capture helper in `app/resources/windows-x64/`, built by
`windows-release.yml` with Recly's desktop client, with its notices as `THIRD-PARTY-recly-events.txt`, and runs while **Settings → Agent connection** is on (the
registry value `agentEvents`). Its home directory is `%AppData%\recly-events`. On the development host, `RECLY_EVENTS` points the app at another program, such as
`events/bin/recly-events`. A local folder as the storage location disables the switch, as on the Mac (2026-10-06).

### Development host (macOS) stand-ins

The development machine is macOS, so the Windows-only parts sit behind interfaces and stubs are selected on macOS.

| Feature | Windows | macOS (development host) |
|---|---|---|
| Secret · token storage | Credential Manager (`WindowsCredentialStore`, JNA) | `{dataDir}/dev-secure-store.json` — **no encryption, development only** |
| Launch at login | `HKCU\…\Run` | no-op, shown as disabled in settings |
| Data directory | `%LOCALAPPDATA%\Recly` | `~/Library/Application Support/app.recly.windows` |
| Capture helper | `recly-capture-helper.exe` from the MSI resources | none → tray "No capture helper" (stood in for by a fake helper) |
| Running apps · window titles | process table + `EnumWindows` | always empty → stood in for by `RECLY_DETECT_PROCESSES` |
| Microphone "Let desktop apps access your microphone" | registry | always `UNKNOWN` (no guidance text appears) |
| Meeting notifications | tray balloon | same API — shows up in the macOS Notification Center |

### Tasks

| # | Scope |
|---|---|
| N1 | Compose Desktop skeleton, `:core` JVM dependency, tray |
| N2 | Helper: microphone capture → PCM segments → ffmpeg → m4a |
| N3 | Helper: loopback + drift correction + mix |
| N4 | Auth + runner |
| N5 | **Detection** + notifications |
| N6 | **Retired (2026-09-24)** — editing window + workflow export/import in settings (§5) |
| N7 | MSI + signing (no SmartScreen warning) |

---

## 15. Privacy · data flows (formerly docs/15)

The purpose of this section is to list **every path by which data leaves a device where Recly is installed**, without
leaving a single one out. The text shown to users is [`docs/policy/privacy-policy.md`](policy/privacy-policy.md); this
section is the basis for that text and the implementation contract. **A change that adds a new network call must amend
this section in the same change.**

### One-line summary

Recly **has no server.** Data goes to (1) the user's Google Drive (§1, including Google OAuth) — or the user's iCloud
(§1b) if iCloud was chosen on iPhone · Mac, (2) the STT provider (§3), **only when the user chose external API
transcription** in the recording processing settings, and (3) **the user's own other paired device** (watch ↔ phone,
§4) — the first two go with the user's account and the user's keys, and the third stays between two of the user's own
devices. A local folder the user picked on iPhone · Mac · Windows · the Android phone (§1c) is on the device itself — Recly sends nothing anywhere
by writing there, and whatever syncs that folder is the user's own choice. Beyond these, App Store builds use the StoreKit country lookup below, local transcription uses model downloads
the user requested (Apple system assets; on Android · Windows, public files on Hugging Face · GitHub), and policy
links open in the browser only when the user taps them (end of §3). Webhooks (§2) were retired on 2026-09-24 and are no longer a path. The optional
`recly-events` program (§9) runs only when the user runs it or turns it on in the Mac or Windows app, and its paths are listed there.

### Apple on-device speech model assets

- When the user requests a model download (Settings, a waiting recording · banner, the first-run card), the Apple Speech `AssetInventory` system service downloads the language model.
  It is a system asset download managed by Apple; the app does not configure a separate server address or pass an API key.
- `SpeechTranscriber` processing uses the device's audio file/PCM and finalized results. Choosing local transcription
  does not by itself send audio to an external STT, and the default flow's Drive upload of the original and results stays a separate step.
- No model · unsupported platform · unsupported language are states that need the user to act in settings. A wait for OS run time is shown as an ordinary wait.
  A thermal wait (Apple `serious` or higher, Android `SEVERE` or higher) shows the reason on the row as `Waiting for the device to cool down` and
  resumes automatically once the device cools (2026-09-27). Low power / battery saver mode does not stop it. This policy does not guarantee that the device produces zero heat.

### Android · Windows local transcription models

- The model is downloaded only when the user taps **Download model** — in Settings, on a waiting recording · banner, or on the first-run card (§5 "Fixed processing settings").
  Android downloads it as a WorkManager foreground job with a notification. On Windows, the app asks the capture helper's
  `--network-cost` whether the connection is metered; it only reads the OS's connection cost information and makes no network request. There are two download sources, with no authentication:
  `https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/68818b2313fe77bd06f6a7c5068ff3ef59d02b8a/…`
  (six model · tokenizer files; large files redirect to `*.cdn.hf.co`) and
  `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx` (VAD, redirects to
  `release-assets.githubusercontent.com`). Core `Qwen3Asr.files` is the source of truth for the file list, sizes and hashes.
- The requests are only GETs with a `Range` header. They send no recordings, transcripts, processing settings, keys or device identifiers. What the host sees is the IP address,
  the HTTP client's User-Agent and the requested file. Files are pinned by commit, size and SHA-256, so if a file changes on the host side, it is not installed.
- Transcription itself happens on the device, and audio does not leave through this path. The Drive upload of the original and results stays a separate step.
- On Windows the sherpa-onnx native library ships inside the app (jar); it is not downloaded.

### China mainland App Store

Response to App Review on 2026-09-14: on iPhone/iPad, OpenAI transcription is disabled based on `CHN` from StoreKit `Storefront.current.countryCode`. Device language, region settings, GPS and IP estimates are not used to decide. Transcription of Apple Watch recordings runs on the iPhone, so it follows the same policy. This App Store policy does not apply to Android, Windows or directly distributed macOS.

- The `openai` provider and transcription endpoints that directly specify an OpenAI/ChatGPT domain are blocked. An OpenAI address specified under another provider name such as `groq` is blocked too. Transcription offered by other companies is a separate service and is not removed wholesale by this policy.
- That provider is hidden from the provider list in settings and validated again on save and on import confirmation. The same policy applies to saved settings, restored databases and jobs already in the queue, right before execution and right before every transcription request. Saved settings and keys are not deleted automatically.
- An unconfirmed region or a failed lookup is not treated as allowed. That transcription waits 60 seconds with `STOREFRONT_UNAVAILABLE` and does not use up retries. If China mainland is confirmed, it fails with `PROVIDER_REGION_RESTRICTED`. Other steps and the `onError` contract stay as they are.
- In the restricted or region-unconfirmed state, transcription HTTP redirects are not followed automatically. The transcription provider's final endpoint must be used; this prevents audio from being delivered through a redirect to a destination that was never checked.
- StoreKit's current country information is read right before use, and the settings screen subscribes to `Storefront.updates`. A previous country is not stored on disk and reused as grounds for allowing.
- Additional system service path: the app asks Apple StoreKit for App Store country information. Recly passes no recordings, transcripts, processing settings or external provider API keys to this lookup. Apple's system service manages the account/storefront information, and the app does not call a separate IP geolocation server.

### iPhone providers

Response to App Review on 2026-09-29 (App Review 5.1.1(i) — the policy must confirm "same or equal protection" from
third parties): iPhone offers only providers that do not use recordings for model training. Policy §3(2) "Providers
offered on iPhone" confirms equal protection with this list.
Android · Windows · directly distributed macOS keep all fourteen, and the policy discloses the differences between providers (user decision).

| Provider | Grounds for exclusion from training |
|---|---|
| OpenAI · Groq · Azure AI Speech · RTZR | Default |
| Deepgram | Recly sends `mip_opt_out=true` on every request |
| AssemblyAI | Recly sends to the EU region (`api.eu.assemblyai.com`) on every platform — per the documentation, files sent to European servers are excluded from training (no plan condition) |
| CLOVA Speech | Per the terms, used for engine improvement only with customer consent |
| ElevenLabs | Only when "Improve the models for everyone" is turned off in the account. The app cannot check this, so the transfer permission dialog asks the user to confirm it |

- Core `TranscriptionPolicy.APP_STORE_PROVIDERS` is the source of truth. It is on only in the App Store shell (`TranscriptionPolicy(region)`).
  The rest are hidden from the provider list in settings, and save · import confirmation and the pre-run check (`requireAllowed`) block them with `PROVIDER_NOT_OFFERED`
  — jobs already in the queue also fail before the request. Saved settings and keys are not deleted automatically.
- ElevenLabs transfer permission (the settings save dialog and the allow button in Settings → Privacy) turns on only when the
  "I turned off model training in my ElevenLabs account." checkbox is checked. The dialog shows that ElevenLabs uses recordings
  for model improvement unless this is turned off, and the ElevenLabs help page
  (<https://elevenlabs.io/docs/help-center/legal/is-my-data-used-to-improve-eleven-labs-ai-models>, checked 2026-09-29).
  ElevenLabs's EU data residency and zero-retention mode are Enterprise-only, so they are not used.
- Excluded (iPhone): Together AI, Mistral AI, Daglo, Speechmatics, Rev AI, Gladia.

### §0 What stays on the device

| Data | Where | Does it leave? |
|---|---|---|
| Original recording (`.m4a` parts) | §3 Local storage path | The Drive upload step uploads it to the user's Drive (§1), or copies it into the local folder the user picked (§1c). If external API transcription was chosen, one file of the concatenated track goes to that provider (§3). **Recordings made on the watch first move to the paired phone** (§4) |
| `meta.json` | Same folder | The upload step uploads it alongside. The watch's moves to the phone together with the parts |
| Transcription result files | Same folder (local copy) | Written alongside into the Drive recording folder |
| Job · step state, retry budget, upload session offsets | Local SQLite (`rec.db`) | **Never leaves** (principle 2) |
| `deviceId` (a new UUID v4 per install) | Secure storage / on macOS `{dataDir}/device.id` | Carried in `deviceId` of the `meta.json` uploaded to Drive (§1). Does not leave any other way |
| Logs (`rec.*`, `shell.*`, `detect.*`) | Platform logs (Android `Log`, Apple `os.Logger`, JVM stdout) | Only when the user takes them out with "Export logs" |
| App settings such as language and Wi-Fi only | Platform settings store | Does not leave (not a sync target) |

### §1 Google Drive — the user's own Drive

| Item | Details |
|---|---|
| Scope | Only `drive.file` (ADR-009). Non-sensitive. **The full `drive` scope is not requested** — the app cannot read the user's other files that it did not create |
| What is uploaded | Inside the recording folder `{folder template}/{base}/` (default `recly/{yyyy}/{yyyy}-{MM}/`): the part `.m4a` files, `{base}.meta.json`, and, if transcription is on (local or external API), `{base}.transcript.json/.txt` |
| Metadata on the folder | The title in the folder `description`, `recordingId` · `workflowId` in `appProperties` |
| appDataFolder | **Not used.** Recording processing settings and secret values both exist only on the device (§5), and the only way to move them between devices is a settings export/import that the user does by hand |
| What is received | `md5Checksum` · file metadata for upload verification. The list of the user's other files is not requested |
| Who sees it | The user, and people the user has shared the folder with. **Recly has no server that can access these files** — the OAuth refresh token exists only in the device's secure storage; the short-lived access tokens it yields are used only for Google API calls on the device (including, while Agent connection is on, by the recly-events copy the desktop app runs, §9) and are never sent to Recly |
| Control | Disconnect at any time in Google account settings (<https://myaccount.google.com/permissions>). The in-app "Disconnect" is also in all four shells (§3) — one action does both the grant revoke (Android `AuthorizationClient.revokeAccess`, Apple · Windows `oauth2.googleapis.com/revoke`) and the local cleanup of `ReclyCore.disconnect` |

The only Google endpoints Recly calls are the Drive API (`www.googleapis.com`) and
OAuth (`accounts.google.com` · `oauth2.googleapis.com`). Apple's direct OAuth path is
`accounts.google.com/o/oauth2/v2/auth`, `oauth2.googleapis.com/token` and `oauth2.googleapis.com/revoke`,
and it calls no profile lookup API. To confirm the account on reconnect, it calls
`GET https://www.googleapis.com/drive/v3/about?fields=user(permissionId)`.
Only Drive's opaque identifier, not the name or email, is stored in the device DB, and the identifier of unfinished jobs stays after disconnecting so they can resume.
Account confirmation for earlier jobs uses only the `owners(permissionId)` field of the existing `files.get`. Deleting the job records also deletes that identifier.
While Settings → Agent connection is on, the Mac and Windows apps hand the copy of recly-events they run this connection's short-lived access token through the
standard-input pipe only they hold (§9, 2026-10-06); the refresh token stays in the app's secure storage.

**Account email handling** per shell is as follows:

| Shell | Where it comes from | Where it stays | When it is cleared |
|---|---|---|---|
| Android phone | The Google ID token from Credential Manager (`GoogleIdTokenCredential.id` is the email address) | Secure storage `account/email` | Sign out |
| iPhone · Mac | Drive-only OAuth, so email · profile are not requested | Not stored in new credentials. An existing profile, ID token and login hint are removed when the credentials are migrated | Migration or disconnect |
| Windows | Not received (no profile scope) | Not stored | — |

None of it is sent to Recly (there is no server to receive it). It is a local value for picking the account again.

### §1b iCloud — the user's own iCloud (iPhone · Mac, ADR-024)

| Item | Details |
|---|---|
| When | Only recordings on an iPhone · Mac where iCloud was chosen as the storage location in settings (§3 "Storage location"). Does not apply to Android · Windows · the watches |
| What is uploaded | Same as Drive — inside `{folder template}/{base}/` in the app's iCloud Drive folder ("Recly" in the Files app · Finder): the part `.m4a` files, `{base}.meta.json`, `{base}.transcript.json/.txt` if transcription is on, and the folder attributes file `{base}.folder.json` (title · recording id · progress markers) |
| Who sends it | **The app makes no network request.** The app writes files into the device's iCloud container, and Apple's system service uploads them. The user's iCloud account and Apple's terms and policies apply, and for accounts with Advanced Data Protection turned on, iCloud Drive is end-to-end encrypted (Apple) |
| Who sees it | The user, and the user's devices signed in with the same Apple ID. **Recly cannot access it** — it has neither a server nor any permission to this data |
| Control | Switch the app's storage location back to Drive (later recordings only), turn off Recly's iCloud use in system settings, delete the folder in the Files app · Finder, delete Recly's data in iCloud storage management |

### §1c Local folder — a folder the user picked on the device (iPhone · Mac · Windows · Android phone)

| Item | Details |
|---|---|
| When | Only recordings on an iPhone, Mac, Windows PC or Android phone where the local folder was chosen as the storage location in settings (§3 "Storage location"). Does not apply to the watches, which hand their recordings to the phone |
| What is written | Same layout as Drive — inside `{folder template}/{base}/` in the picked folder: the part `.m4a` files, `{base}.meta.json`, and `{base}.transcript.json/.txt/.md` if transcription is on |
| Who sends it | **Nobody — the app makes no network request.** It copies files into a folder on the device. If that folder is synced by another app or service (Obsidian Sync, Dropbox, Syncthing, a NAS, iCloud Drive or OneDrive folders…), that tool and its terms decide where the files go next; Recly neither knows nor controls it |
| Who sees it | Whoever can read that folder on the device, and whatever the user syncs it to. **Recly cannot access it from anywhere else** |
| Access | Mac · Windows: an ordinary path the user picked. iPhone: only the folder the user picked in the Files picker, which the user can take back in Settings › Privacy & Security › Files and Folders. Android: only the folder the user granted through the system folder picker (Storage Access Framework); Recly asks for no "all files" access |
| Control | Switch the storage back (later recordings only), pick another folder, or delete the files in the folder. Deleting the app leaves them in place |

### §2 Webhooks — an address the user entered

> **Retired (2026-09-24)**: webhooks were removed from the product (§4). Nothing leaves through this path — webhooks of
> jobs queued by earlier builds are not sent either. What follows is a record.

Only when there is a `webhook` step: **one POST** to the URL the user wrote in that step. The receiver is the user's n8n · Cloudflare
Worker · own script (there is no receiver run by Recly).

- **What goes in the body** (§4, `spec/webhook.payload.schema.json`): recording metadata (`recordingId`, `source`, `platform`,
  title, start · end time, duration, time zone, track list, `context`), the file list (name · bytes · sha256 and **Drive file
  id · `webViewLink`**), the folder path and Drive folder id, the workflow id · name, the device id · platform · device name.
- **`context` holds no one else's identifiers.** The payload builder carries `meta.context` as is, without touching it, but
  all it contains is `app` (the bundle id of the detected meeting app, desktop only) and `participants` (a head-count integer).
  **`context.calendar` was removed** — before that, the meeting title · time · **list of attendee email addresses** that macOS read with EventKit
  were carried here all the way to the webhook receiver. Now no shell reads the calendar, so that path does not exist at all.
- **What does not go in the body**: the audio bytes themselves, the transcript **text** (only the file entries are included), access tokens, secret values.
- `webViewLink` is just a link, not a public URL — only people with Drive permission can open it. Even so, **the webhook receiver learns
  that the file exists and where it is.**
- Signature: Standard Webhooks. The secret (`whsec_…`) is in the device's secure storage and is not carried in the body. If `secretRef` is left empty,
  it goes out unsigned (that choice is the user's too).
- Transport is HTTPS only. The exception is `127.0.0.1` · `localhost` (a local receiver). Redirects are not followed, the timeout is 30 seconds, and failures
  are retried.

### §3 STT providers — only when the user added that step

External STT transfer happens only when the user chooses **external API** as the transcription method in the recording
processing settings and enters their own API key (§5 "Fixed processing settings", §8). With local transcription or OFF, nothing in this section happens.

| What | Where to | When |
|---|---|---|
| **The whole audio** (one file of the `mono` or `mix` track with the parts concatenated) | The STT service the user chose as `provider` | When the `transcribe` step runs |
| Speaker count hint, language setting | The same request | Same |

- The call goes **directly from the device to the provider**. There is no intermediate server, relay or callback URL. Recly cannot see the contents of this request.
- Authentication is **the user's key**. Billing also goes to the user's account.
- How long the provider keeps the data after that, and whether it trains on it, is **that provider's policy**, which Recly does not
  control.
- **This notice is in the recording processing settings of three shells.** Three lines appear **right below the provider selection** for external transcription.
  The wording is letter-for-letter the same in the three shells (a cross-check test) and exists in both en and ko.

  `transcribe` (en; `{provider}` is the display name of the chosen provider, 2026-09-25):

  > External transcription sends the whole recording to {provider}.
  > How long they keep it, and whether they train on it, is that provider's own policy — Recly does not control it.
  > Read the provider's policy before you use it.

- **Links**: these three lines carry no link. iPhone's transfer permission dialog and Settings → Privacy link to the official policies
  in the table below (`PrivacyLinks`) (checked 2026-09-26).

#### Provider retention policies — checked against official documents on 2026-09-26

All fourteen receive the same thing — **one file of the concatenated audio track** and the **language · diarization options** carried in that request (the table above in §3).
What differs is what that provider does next. These are the defaults for the API products.

| provider | Training · retention defaults (summary) | Official link |
|---|---|---|
| AssemblyAI | Recly sends to the EU region — per the documentation, files sent to European servers are excluded from training (no plan condition. The US region default is training, which only paid plans can refuse; the terms §4.3 have no EU exception, so it is cited as "according to the documentation"). Uploaded audio is deleted within 48 hours, transcripts after 30 days (2026-09-29) | <https://www.assemblyai.com/docs/data-retention-and-model-training> |
| CLOVA Speech | Used for engine improvement only with customer consent (terms §4②); recognition results · execution logs kept 7 days for dispute handling (§7①). The retention period of the audio itself is not published (2026-09-29 terms <https://www.ncloud.com/policy/terms/clsph>) | <https://privacy.navercloudcorp.com/en/ncp/PrivacyPolicy/ncp-p> |
| RTZR | Not used for training. Batch audio deleted after recognition, transcripts kept up to 3 days | <https://developers.rtzr.ai/privacy> |
| OpenAI | No training without opt-in. The transcription endpoints have no abuse-monitoring retention either | <https://developers.openai.com/api/docs/guides/your-data> |
| Groq | No training, no retention by default (logs for outage · abuse investigations up to 30 days) | <https://console.groq.com/docs/your-data> |
| Together AI | Stored and used for product improvement by default (training is opt-in); storage can be turned off in the account | <https://www.together.ai/privacy> |
| Mistral AI | Kept 30 days for abuse monitoring. Free mode trains until refused; paid default not stated | <https://legal.mistral.ai/terms/privacy-policy/> |
| ElevenLabs | Unless on Enterprise, even paid plans train by default. Turning off "Improve the models for everyone" in the account's Data use excludes data from then on (no API). Per-request `enable_logging=false` is Enterprise-only. Request history kept until deleted (2026-09-29) | <https://elevenlabs.io/privacy-policy> |
| Deepgram | The default is retention for model improvement, but **Recly sends `mip_opt_out=true` on every request**, so data is kept only during processing and not used for training | <https://developers.deepgram.com/trust-security/your-data> |
| Azure AI Speech | No training, fast transcription audio not stored, processed only to provide the service | <https://learn.microsoft.com/en-us/azure/foundry/responsible-ai/speech-service/speech-to-text/data-privacy-security> |
| Daglo | Per the API terms may be used for quality · performance improvement, audio kept 3 months, opt-out method not stated | <https://developers.daglo.ai/privacy> |
| Speechmatics | Model improvement only on opt-in (the terms have a clause granting rights to use transcripts), batch data deleted after 7 days | <https://www.speechmatics.com/legal/privacy-policy> |
| Rev AI | May be used to train its own speech models (generative excluded), up to 30 days, no API opt-out method found | <https://www.rev.com/legal/privacy> |
| Gladia | Kept up to 12 months, Free · Starter may be used for training (documents disagree) | <https://www.gladia.io/privacy-notice> |

**Writing rule**: provider terms change. When moving them into the policy or app wording, keep the check date and the official link with them, and re-check
against the official documents before quoting. Do not invent unverified retention periods or training status. Do not write that all providers protect
at the same level — equal protection is confirmed only for the eight in "iPhone providers", and the grounds are that section's table. For the rest, the policy
discloses the differences between providers (2026-09-26 user decision: keep all 14 → after the repeat rejection on 2026-09-29, only eight on iPhone).

**iPhone transfer permission (2026-09-09).** The iOS core turns on `requireTransferConsent` and checks, right before the actual request, for explicit permission for the transcription provider / configured address / data · purpose version. Polling, retries and multiple requests are each checked, and a request that left before a withdrawal may finish. `NEEDS_CONSENT` is a waiting state, not a failure. It cannot be skipped with `onError: continue`, and the retry budget, transcription progress and completed uploads are preserved. After permission is given, the job resumes from the blocked step. The destination for a waiting job is computed from the processing plan fixed to that job.

When the recording processing settings are saved and the destination has not been allowed yet, the confirmation dialog “Send recordings to {provider}?” appears. It shows the recipient (provider name, “A third-party AI speech recognition service. Recly does not operate it.”, address), the data sent and its purpose, a link to the provider's policy and how to withdraw, and is answered with “Don't allow” · “Allow & save” (App Review 5.1.1(i) · 5.1.2(i)). ElevenLabs can be allowed only after checking the box confirming that training is off ("iPhone providers"). If permission is not given, nothing is saved. This settings save is the only place that asks; nothing asks during recording. An import goes into the draft as an editable preview, so it goes through the same confirmation when saved. For the same destination it is an ordinary save without further confirmation (restored 2026-09-26 — the confirmation had been dropped in the 2026-09-25 switch to the fixed processing plan). Saving an API key is not permission. Permission is stored in the device-local `kv` and is not included in settings export. It is tied to iPhone's `privacy/transfer-device` keychain identifier (`AfterFirstUnlockThisDeviceOnly`), so restoring the DB onto another device does not carry the permission over. This identifier is not an API credential and has no effect without the local permission record. Changing the API key, restarting, or editing mere notice wording is not a reason to ask again. A significant change of provider · configured address · data sent/purpose, a withdrawal, or a new device requires permission again. Other shells keep their existing policy and share only the state · message contract.

Existing jobs and jobs received from the Watch stay waiting for permission without a popup, and are resolved by going from the list to Settings → Privacy. Permission can be withdrawn on the same screen. Withdrawal does not delete data already sent or API keys. Google Drive uses its own separate Google OAuth. This permission is also separate from the participant recording consent reminder (§12 · §13).

**Policy pages the user opens.** Settings and the notices above open Recly's public privacy policy (English `https://recly.dev/policy/privacy-policy`, Korean `https://recly.dev/policy/privacy-policy.ko`) and the chosen provider's privacy policy in an external browser. `PrivacyLinks` manages the URL list (recly.dev, AssemblyAI, NAVER Cloud, RTZR, OpenAI, Groq, Together, Mistral, ElevenLabs, Deepgram, Microsoft, Daglo, Speechmatics, Rev, Gladia). Android opens only the same two addresses of the Recly policy, via Settings → `Privacy` → `Privacy Policy` → `Open` (the Korean one when the app is in Korean, `privacyPolicyUrl`; 2026-09-29) — Android has no transfer permission, so the `Allowed destinations` row exists only on iPhone. The Mac and Windows `Set-up guide` under Agent connection opens `https://recly.dev/agent` (`/agent.ko` in Korean) the same way. The website is visited only when the user taps a link. It is not an automatic request in the recording · job execution path.

### §4 Transfer between paired devices — watch ↔ phone

The watch uses neither Drive nor the network (ADR-002). Even so, **data leaves the watch** — to the paired phone. This path
happens independently of both Drive and providers.

| Direction | What | How |
|---|---|---|
| Galaxy Watch → Android phone | Recording part `.m4a` files, and `meta.json` once everything is acked | Wear Data Layer `ChannelClient.openChannel` → `sendFile`. File metadata rides in the channel path, not in a separate payload (`/rec/part/{recordingId}/{part}/{track}/{sha256}/{file}` · `/rec/meta/{recordingId}`). The phone receives with `WearableListenerService.onChannelOpened` → `receiveFile` and acks with `MessageClient` |
| Apple Watch → iPhone | Same | WatchConnectivity `WCSession.transferFile(_:metadata:)`. recordingId · part · track · sha256 · file name ride in the `metadata` dictionary. The phone receives with `WCSessionDelegate.session(_:didReceive:)` and acks with `transferUserInfo` |
| Android phone → Galaxy Watch | **Retired (2026-09-24)** — ~~workflow summary: only two fields per workflow, `id` · `name`~~. The watch has no workflow list | ~~`DataClient.putDataItem` path `/rec/workflows`~~ |
| iPhone → Apple Watch | Only the app language (the workflow summary `id` · `name` was retired 2026-09-24), no step contents | `WCSession.updateApplicationContext` |

- **Both devices are the user's own.** The transfer rides the OS's pairing channel, and **Recly's server is not involved** —
  there is no server to receive it. The watch module does not even have an HTTP client.
- Recording file transfer goes **only to a nearby node** — Android filters out nodes that are not `isNearby`, which excludes relay through the Google cloud,
  and Apple uses only `WCSession`. (The `/rec/workflows` DataClient item, which had no filter, was retired 2026-09-24 and
  is no longer published.)
- For recordings the direction is **one-way**. There is only watch → phone; there is no path by which transcript text or workflow steps · secrets · Job results
  go phone → watch.
- The watch deletes its own copy once it receives the ack — no recording history stays on the watch.
- Keys and tokens do not go through this path. The only thing in the watch's secure storage is the device UUID.

### §5 BYO keys and tokens — only in the device's secure storage

Key values **are not part of the recording processing settings.** The settings hold only a name (`secretRef`), and the value is entered on each device separately
(ADR-008). So there is no key in the settings export file, in Drive, or in logs. The storage mechanism is the same as the Secrets table in §5.

- What is stored here: Google access/refresh tokens (`tokens` namespace), STT API
  keys (`secrets` namespace), `deviceId`.
- **The only path by which a key leaves is provider authentication.** When the user chooses external API transcription, the STT key is carried as is
  to the provider in a request header. So the correct sentence is not "it does not leave the device" but **"it never goes to Recly, only to the provider the
  user chose"**.
- **There is no sync between devices.** A key exists only on the device it was entered on, and on a device without the key that step fails immediately with `MISSING_SECRET`.
  The exported settings file holds no values either — all the file contains is the `secretRef` name (§5).
- Development host exception: `DevFileSecureStore`, used when running the Windows app on macOS, is plaintext base64 JSON. It is **development
  only** and is not selected in Windows builds.

### §6 What is not collected

- No analytics, usage statistics or event collection.
- No crash reporting (no Firebase/Crashlytics/Sentry/AppCenter-family dependency in any of the four shells).
- No remote config, A/B testing or advertising identifiers.
- There is **no account** on Recly's side. No sign-up, no email collection, no user identifier. Signing in is done against the user's Google account,
  and the resulting token exists only on the device.
- No "our server" calls such as update checks or license checks either.

### §7 Data deletion

The canonical rules are in §3 Retention · deletion. Summary:

| What the user wants | How | What remains |
|---|---|---|
| Get rid of this recording | "Delete" in the list → `Delete local only` (default) or `Also delete the Drive folder`. All four shells have it | With the default, the files in Drive remain (they are the user's files). The `job` · `step_run` rows are deleted with it |
| Automatic deletion | When every Job is DONE and the whole audio is in Drive, the local audio becomes eligible for cleanup 7 days after the later of the last Job update and the newest cache file time (ADR-017). Audio downloaded again starts a new period. `meta.json` · DB rows · transcript copy · waveform peaks (`waveform.v1`) remain | Unfinished · failed · permission-waiting Jobs keep the original. If Drive is full and a Job is parked at `NEEDS_SPACE`, that Job is not DONE, so **the local original is not deleted and stays on the device as is** |
| Cut off Recly's Drive access | The app's "Disconnect" or Google account settings | The recording files in Drive remain — disconnect **never calls** `files.delete`. This device's Google token · finished Jobs · Drive folder cache are deleted, and unfinished Jobs are kept and paused until the same account reconnects. Recording processing settings · API keys · transfer permission records remain. **The recording files and the `recording`/`part` rows remain. Recordings are deleted separately from the list** |
| Delete the recordings in iCloud | "Delete" in the list → `Also delete the iCloud folder` (the default keeps it), or the "Recly" folder in the Files app · Finder, or iCloud storage management | Deleting the app leaves the data in iCloud (Apple). There is no iCloud disconnect in the app — turn off Recly's iCloud use in system settings |
| Delete the recordings in the local folder | "Delete" in the list → `Also delete from the local folder` (the default keeps it), or delete them in the folder yourself | Deleting the app leaves the folder and its files where they are |
| Delete everything | Delete each key in the secrets list + disconnect + delete the app + delete the `recly/` folder in Drive | **Deleting the app erases everything only on Android/Wear.** On macOS `~/Library/Application Support/app.recly.mac/` and keychain items remain, on Windows `%LOCALAPPDATA%\Recly\` and Credential Manager items remain, and on iOS · watchOS keychain items may remain (Apple does not guarantee their deletion). Per-platform cleanup is in `docs/policy/privacy-policy.md` §7 |
| Copies held by a provider | Recly cannot delete them on the user's behalf | The user does it directly, following that provider's console · policy |

**What deleting a recording deletes.** `RecordingRepository.delete` deletes `step_run` → `job` → `part` →
`recording` in one transaction and deletes the directory within the same lock section. So the recording's full processing plan
snapshot (`job.workflow_json`), the raw failure message (`step_run.last_error`), the resume state (`state_json` — Drive resumable session
URI · offset · `fileId`, STT provider job identifiers) and the step outputs (`output_json`) **never stay behind in the DB without
being visible in the UI.**

**What disconnecting leaves behind.** When the revoke fails, **the grant still stands on Google's side** — the local cleanup has already finished, so
the Google token and Jobs are gone from this device, but Recly stays in the Google account's app list. The app records that as a revoke debt
and says so, with a link to the permissions page. If the local cleanup fails, `DisconnectPhase` stays at `REVOKED_CLEANUP_OWED` and is paid off
on the next run, and sign-in is blocked in the meantime. A recording that could not be deleted because of a `RUNNING` Job remains together with that Job's rows.

### §8 Changes that must amend this section

If there is even one of a new network call, a new step type, a new scope, a new storage location, or the introduction of telemetry (= amending ADR-022), amend this section,
`docs/policy/privacy-policy.md`, and the Play "Data safety" form · App Store privacy labels together.

### §9 recly-events — an optional server the user runs (2026-10-05)

`events/` builds `recly-events`, a program that lets the user's ChatGPT agent (a dot or a Work chat) hear about a new
transcript. The user runs it themselves, or turns it on in the Mac or Windows app (**Settings → Agent connection, off by
default**, §12 and §14 "Agent connection"), which runs the copy the app bundles. The phone and watch apps do not have it, and
the desktop apps talk to it only by running it, so none of §0–§7 changes, and nothing below happens unless the user runs it or
turns it on. Only the Mac DMG and the Windows MSI carry it; the App Store and Play forms are unaffected. It listens on no
network port (`status` and `test` reach it through a Unix socket in its own directory) and makes three kinds of outbound
connections:

| Path | To | What is sent | What comes back |
|---|---|---|---|
| Google sign-in, Drive metadata | `accounts.google.com` (consent in the user's browser, CLI only), `oauth2.googleapis.com` (CLI only), `www.googleapis.com/drive/v3` (`changes`, `files`, `about`) | Run from the CLI: Recly's own desktop OAuth client (the Windows app's, compiled into the `events-v…` release archives) and its token, scope `drive.file`, so Drive shows it only the files Recly's apps created; built from source without that client, it takes the user's own client and `drive.metadata.readonly` instead. The copy a desktop app runs: that app's own Drive connection's access token as the Bearer token, the same `drive.file` reach (2026-10-06) | names, IDs, folder descriptions (Recly titles), the folder's `recordingId` and links, and, for the CLI's `init` check, the account's opaque `permissionId` — **it never downloads file contents** |
| OpenAI Secure MCP Tunnel | `api.openai.com` (`/v1/tunnel…`, long polling by the embedded `tunnel-client`) | the tunnel ID, a runtime key restricted to Tunnels Read + Use, and the answers to ChatGPT's MCP requests: the event inbox (recording name, title, start time, device, Drive IDs and links) | ChatGPT's MCP requests: discovery, tool calls, `events/subscribe` |
| Event delivery | the callback URL from `events/subscribe`, only when its host is in `callbackHosts` (default `connectors.api.openai.com`), port 443, public addresses only, no redirects | a Standard Webhooks-signed `recording.transcribed` event with the fields above | 2xx, 410 (ends the subscription) or a retry |

- No transcript text, audio, STT key or long-lived Recly credential passes through it. The copy a desktop app runs receives that app's short-lived Drive access token on standard input (`serve --drive-token-stdin`, §12 "Agent connection"), keeps only the latest in memory, sends it as the Bearer token on every Drive request (the first one waits up to 15 s for the first line), and never writes it to disk or logs it; the app's refresh token stays in its secure storage. `status --json` then reports `driveFromApp: true` in its `server` object, `recly-events status` prints `Google: the Recly app's own Drive connection`, and `serve.start` logs `driveFromApp` (2026-10-06). The agent reads the transcript itself, through its own Google Drive connector.
- "Disconnect" in any Recly app ends its Drive access on either path. The CLI's sign-in is a grant of Recly's Cloud project, so that revoke (§6) ends its token too and it asks to be signed in again; the copy a desktop app runs simply gets no more tokens from a disconnected app, and that app stops it. recly-events itself never revokes, which would disconnect every Recly device.
- It reads no email, name or profile at all: the CLI's `init` check confirms Drive with `about?fields=user(permissionId)` and prints only that it is connected — Google's consent screen has just shown the account (2026-10-06).
- Its home directory (`~/Library/Application Support/recly-events`, `$XDG_CONFIG_HOME/recly-events`, `%AppData%\recly-events`) is owner-only and holds the config, the CLI's Google client and token, the tunnel key, `state.json` (Drive cursor, subscriptions with their signing secrets, the event inbox including titles, the delivery queue) and logs (event IDs and outcomes, no titles).
- Removing it: turn off Settings → Agent connection or run `recly-events service uninstall`, delete the home directory (its Google token goes with it), delete the tunnel and key in OpenAI Platform, delete the app in ChatGPT. Removing Recly at https://myaccount.google.com/permissions would disconnect every Recly app as well; do that only for a client of the user's own.

---

## 16. Personas · pricing (formerly docs/16) — proposal, undecided

**This section is a proposal, not a rule.** Pricing · billing are not decided yet (§Open decisions), and once decided they move up into the rules table in §0.

### Where this product stands

> **2026-09-24**: webhooks · user workflows were removed. "Signed webhook" · "same workflow" in the table below are a record of the
> proposal at the time; today the only input for automation is the Drive recording folder.

**No product, commercial or open source, does "capture without a bot → keep the original in my Drive → webhook/my automation".**
Granola · ChatGPT Record · Notion **delete** the original audio, and even the open-source tools that keep recordings locally lock webhooks · automation in a
$10–15/month paid tier. So the common denominator of the personas is not "people who need AI summaries" but **"people who want to hold the
originals and the automation themselves"**.

| # | Who | JTBD | What Recly sells | Reason to pay |
|---|---|---|---|---|
| 1 | Individual power users / developers who run n8n. They record solo voice memos, calls and lectures more than meetings | "The moment a recording ends, make it the input of the pipeline I have already built" | One signed webhook and one Drive folder. With a single "new item in folder" trigger, segments · tracks · metadata arrive together. The selling point is that the app does not offer to build the pipeline for you | **Almost none.** This persona is not a revenue source but **the basis of trust** (a reputation as an honest recorder, bug reports, workflow examples) |
| 2 | Korean knowledge workers with many meetings. They need Korean meeting minutes, and keeping meeting audio resident in an outside SaaS is a burden | "Transcribe Korean meetings properly, but keep the audio and results in **my Drive**" | The same workflow wherever they record, a transcript as a file next to the original with `transcribe(clova\|rtzr)`, minutes through the agent they already use + `skills/recly-notes/`. No bot joins the meeting | **Yes.** But key issuance · billing setup is a barrier to entry (creating an app in the NAVER Cloud console and copying the `invokeUrl` back) |
| 3 | Consultants · recruiters · researchers who spend most of the day on Zoom · Teams · Meet calls on macOS · Windows | "Without letting a bot in, split my voice from the other side's, and pile the originals up in my storage without deleting them" | mic/sys/mix three tracks, detect → confirm → record, the original kept as is in `recly/memo/2026-08/` | **Yes.** But this is the most competitive spot, and these people compare products on "summary quality". Recly's differentiator is not quality but **ownership** |

### Competitor pricing

| Product | Price | Model |
|---|---|---|
| Plaud (dedicated hardware + app) | Device $159–189 + $99.99/year | Hardware + subscription. Originals locked in its own cloud |
| Otter / Fireflies | $10–25 per user per month | Bot/cloud SaaS subscription |
| CLOVA Note | Free for individuals, 300 minutes/month | Dominates the individual market with a free tier |
| Meetily | Free / BYO key, Pro $15/month | Open source + paid convenience tier |
| **Recly (proposal)** | **App free · keys are the user's** | No server |

How to read it: in this table, the cells Recly cannot win are "summary quality" and "ease of setup", and the cells it can win are "owning the originals" and "zero
fixed monthly cost". As long as CLOVA Note's free 300 minutes exists, **a plan that charges Korean individual users a monthly subscription does not hold up.**

### Proposal

**A. The app is free, the keys are the user's** (recommended) — transcription runs on the user's own key and summaries on the user's own agent subscription, so
usage costs never reach the developer. With no server, operating costs are effectively zero, and charging money easily turns into a move that erodes that structural advantage.

**B. Later, an optional paid convenience tier** (left open, but none for now) — the candidate is managed STT credits. **But this touches a product
principle**: managed credits mean sending the user's audio to the developer's account, and that means **a server comes into
existence.** At that moment the privacy policy's "Recly has no servers" becomes false, and ADR-021 · ADR-022 must be amended together.
Alternatives that build no server (lowest burden first): ① stronger key issuance guides (the console steps in the app with screenshots — zero cost,
zero damage to principles), ② a one-time in-app purchase for a specific feature (the store does receipt verification), ③ donations · sponsorship.

**C. Revenue we decided not to take** — subscriptions based on user count · recording time, ads, data use (ADR-022 already blocks it).

### Costs this decision has to cover

| Item | Amount | Frequency |
|---|---|---|
| Apple Developer Program | $99 | Yearly |
| Google Play developer registration | $25 | Once |
| Windows code signing | Unconfirmed (billed yearly) | Yearly — needed to get rid of the SmartScreen warning |
| GitHub Actions (private repository, `windows-latest` runner) | Usage-based billing | Monthly |
| Domain · privacy policy hosting | Small amount | Yearly — publishing OAuth to Production needs a public URL |

The total is $124 in the first year and $99 + signing + CI every year after, so **it is not a structure that "cannot be sustained without charging money."** This is
the basis of proposal A.

---

## 20. Verification status (formerly docs/20)

### Instrumentation

All clients use the same log event names: `rec.start`, `rec.part`, `rec.stop`, `rec.finalize`, `xfer.part`,
`xfer.ack`, `job.step.start/ok/fail`, `sync.pull/push/merge`, `secrets.*`, `detect.*`. **These names are a stable
contract** (§21). The only way to see what happened on a real device is **"Export logs"** in settings (a file ring buffer),
and nothing leaves beyond that (ADR-022).

### Local webhook receiver

> **Retired (2026-09-24)**: with webhooks removed, the receiver (`scripts/webhook-receiver.mjs` · `.test.mjs`) was deleted too. What follows is a record.

Every acceptance check involving Drive · webhooks is judged with this receiver. It verifies the §4 Standard Webhooks signature by recomputing it the same way as the core `Signer`,
and checks the body against `spec/webhook.payload.schema.json`.

```
cd spec && npm ci          # once, the first time (this receiver uses ajv as is)
node scripts/webhook-receiver.mjs --port 8787 --secret whsec_… [--log ./hooklog] [--fail-first 1]
```

The workflow's `webhook` step `url` is `http://127.0.0.1:8787/hook`, and `secretRef` is the name of the secret holding that value. The app and
the receiver only need **the same `whsec_` string** (pass the app's "Generate a webhook secret" value to `--secret`, or conversely put a value you chose into the app's
secret form). Android devices · emulators use `adb reverse tcp:8787 tcp:8787`; the iOS simulator and desktop apps use
the Mac's `127.0.0.1` directly.

On success it prints one line per delivery.

```
ok id=01J9STEPR0N0123456789ABCDE recordingId=01J9ABCDEF0123456789ABCDEF event=recording.completed drive.fileId=1AbC… attempt=1
```

If `drive.fileId` is `-`, there was no successful `drive.upload` before it (§4). A signature mismatch · timestamp out of range prints 401, a schema
violation prints 400. `--fail-first 1` answers 500 to the first delivery of a given `webhook-id` to create the retry path.
`node --test scripts/webhook-receiver.test.mjs` verifies the receiver itself.

### Acceptance scenarios

| Target | Scenario |
|---|---|
| **Core** | `./gradlew :core:jvmTest` passes; example JSON round trip (parse → serialize) is structurally identical |
| **Android phone** | 1 **Retired (2026-09-24)** ~~New install → two default workflows are created locally, and the one in use on this phone is Memo~~ → New install → the recording processing settings are ready with their defaults (§5 "Fixed processing settings"). 2 One-hour recording (screen off) → stop → enter title → within 30 seconds, 4 parts + meta in Drive `recly/memo/2026-08/{base}/`~~, one webhook received (signature verification passes)~~ (webhook part retired 2026-09-24). 3 Record in airplane mode → turn it off → automatic upload. 4 Force-quit the app during upload → WorkManager finishes it (no duplicate files in Drive). 5 **Retired (2026-09-24)** ~~webhook 500 → retry → success~~. 6 `MISSING_SECRET` on a device without the secret~~, and with `continue` the next step proceeds~~ (user `onError` retired 2026-09-24). 7 Under 30 seconds → `SKIPPED_SHORT`, and the row has no retry or upload button. 8 Rotate during the consent screen (activity re-creation) → authorization completes |
| **Galaxy Watch** | 1 Tap the tile → recording starts immediately, the watch face chip shows, and it continues after the app is closed. 2 Two 20-minute recordings without the phone → connect the phone → both transferred · acked · deleted on the watch · uploaded to Drive from the phone. 3 BT drops during transfer → on reconnect, resumes from the un-acked parts, no duplicates on the phone. 4 **Retired (2026-09-24)** ~~Rename a workflow on the phone → reflected in the watch's choices~~ |
| **macOS** | 1 **"One notice on joining Zoom"** → click → three-track recording → stop → mic/sys/mix + meta in Drive~~, webhook~~ (retired 2026-09-24). 2 Clap offset < 20 ms after a one-hour meeting. 3 **Retired (2026-09-24)** ~~A workflow made on the phone appears in the Mac editor and merges with two-way edits without conflicts~~. 4 Install the DMG on a new Mac → passes Gatekeeper → two permission prompts (microphone → system audio) |
| **iPhone + Apple Watch** | 1 Action button → record → locked for 3 hours → stop → go to the home screen → upload completes while locked (background URLSession). 2 Watch 20-minute recordings × 2 → iPhone receives automatically · acks · runs. 3 Stop with Double Tap |
| **Windows** | **1 "Join Teams → notice"** → record → Drive~~ + webhook~~ (retired 2026-09-24). 2 No SmartScreen warning when installing the MSI. |
| **Transcription** | 1 On the phone, with ~~the "Minutes" workflow~~ the external API transcription settings, record 3 minutes or more (2 parts or more) → choose the head count → parts · meta · `*.transcript.json/.txt` appear in the Drive folder and the transcript by speaker shows on the detail screen. The transcript's `start/end` places the second part's span after 900 seconds (remux + offset verification). 2 Process killed right after submission → the next `runDueJobs` does not submit again but polls with the same `jobRef` until completion (`attempts` does not go up). 3 No key → `MISSING_SECRET` (immediately), wrong key → `AUTH_REJECTED` + "Check the key". 4 Desktop three tracks → `mix` is chosen as the input and ~~a `transcript` entry in the webhook `files[]`~~ (retired 2026-09-24) `*.transcript.json/.txt` in the Drive folder |

### What has actually been verified so far

**Automated verification (no device)** — all of the suites below being green is the baseline for every change.

| Suite | Cases |
|---|---|
| `:core:jvmTest` | 391 |
| `:android:app:testDebugUnitTest` | 262 |
| `:android:wear:testDebugUnitTest` | 53 |
| `:android:recording:testDebugUnitTest` | 49 |
| `:android:datalayer:testDebugUnitTest` | 23 |
| `:windows:app:test` | 260 |
| `cargo test` (capture-helper) | 32 |
| RecKitTests | 354 |
| ReclyTests (iOS) | 28 |
| ReclyUITests | 7 |

**Real-device acceptance passed**

| Item | What was checked |
|---|---|
| Desktop (JVM app, macOS host) | Real Google account PKCE sign-in → three-track recording (fake helper) → 3 parts + meta in Drive `recly/{yyyy}/{yyyy}-{MM}/{base}/` → one webhook that passed signature · schema verification on the local receiver |
| Android phone (emulator, real account) | Credential Manager ladder sign-in + Drive permission granted → three jobs parked at `NEEDS_AUTH` resumed automatically and finished uploading |
| Schema v2 migration | Installing the new build over a real-device DB with `user_version = 1` upgrades it 1 → 2 without a crash |
| Android `concat` runtime | An instrumented test makes two AAC parts, concatenates them, then decodes — zero duration error, every frame decodes (§8 lossless copy · pts concatenation) |
| Cross-shell wording unification (§7 rules 10 · 11, §9 Screen principles 1) | Actually checked after aligning the four shells' dictionaries into one: the recording screens of the Android emulator and the iPhone simulator show the same node values (`phone` · only the name of the workflow in use · `IDLE`) and the same header meta (`phone · <id8>`), the phone's picker shows only workflow names (`Meeting` selected · `Memo`), and after stopping, the title prompt shows `Recording title` + `Leave it empty to keep the timestamp name` + `People in the room` (Unknown · 2 · 3 · 4 · 5 · 6+). `CrossShellDictionaryTest` locks en · ko, including `People in the room` · `Unknown` · `6+`, which were missing from the RecMac catalog (spots where English leaked into the Korean UI) |
| Android · Windows local transcription engine (2026-09-26) | Ran the opt-in smoke test (`LocalSpeechSmokeTest`, one per shell) on the Windows shell JVM on a macOS host and on an Android emulator (arm64, 8 GB memory): range download and hash check of the roughly 1 GB model (JVM 108 seconds, emulator 164 seconds) → a 17.9-second Korean TTS clip transcribed into 3 speech segments (JVM 3.7 seconds, emulator 5.1 seconds, including model load) → resume after the first segment → cancel at the first checkpoint. On the emulator's settings screen, one file deleted and then Download model → only that file is downloaded again and the model is ready |
| Workflow export/import — **Retired (2026-09-24)** | Importing `recly-workflows.json` exported from settings on another device → the list is replaced, and this device's defaults · secret values stay as they were (not in the file). Importing an old-schema file → migrated and saved |

**Pending — all of it waits on hardware · accounts, not on a code problem**

| Item | Why |
|---|---|
| Galaxy Watch 3-hour background recording, measured `sendFile` of 30 MB | No real Galaxy Watch. Checked only with the emulator and fakes |
| Apple Watch background recording of 2–3 hours, `transferFile` 50 MB · ack reliability | No real Apple Watch |
| The real macOS tap permission prompt, capture from the three apps Zoom/Meet/Teams, clap offset | Needs a human's clicks (automation is blocked). The design · path and the drift *estimator* were verified with a synthetic harness |
| Real capture (loopback, `IAudioSessionManager2` detection), MSI · SmartScreen, clearing Credential Manager, a real `/revoke` call, system high-contrast detection | **No Windows PC.** CI (`windows-latest`) stands in for compile · unit tests, the release-tag CI (`windows-release.yml`) for MSI creation, and running the package on a macOS host for the UI |
| Reproducing `NEEDS_SPACE` on a device | Could not create a truly full Google Drive. The core is checked with a fake Transport (`DriveQuotaTest`), the shells only for the banner · badge with unit tests and a dev flag |
| Actually running disconnect | Tapping it removes that account's grant **on every device** (revoke is per Cloud project), which blocks all remaining real-device sign-in checks. The dialog, the warning wording and "confirm disabled while recording" were seen in the UI; the revoke → `core.disconnect` path only with core · shell unit tests |
| Real-device heat · battery · long recordings (30–120 minutes) · accuracy of Android · Windows local transcription, the real-device flow from recording to publishing | No test Android phone or Windows PC. Emulator and macOS host speed is not real-device performance, and the smoke clip is synthetic speech |
| M7 real-key acceptance (transcription scenarios 1 · 2 · 4) | Needs real STT keys. Checked with a fake key up to `AUTH_REJECTED` → "Check the key" → entering the editor |
| iPhone · macOS real-device sign-in | This document has no record of it being checked. Store listing · signing are resolved (released on the App Store · Google Play on 2026-10-01, TestFlight skipped) |
| Local folder storage on real devices (§3 "Storage location") | No Windows PC or test Android phone. Checked with core · shell unit tests, the `rec36` emulator's real external-storage provider, a macOS build and the iOS simulator. A real Windows path · `JFileChooser` · Controlled Folder Access · removed drive, a system-revoked Android grant, the Mac picker over the menu-bar popover, and on a real iPhone writing while locked · a folder in iCloud Drive or another app's provider · Obsidian reading it remain |
| Real-device sync of iCloud storage (§3 "Storage location") | No iCloud container registration · App ID capability · Mac Developer ID profile yet (human work). Only upload waiting · completion · out of space · listing · titles were checked, with core JVM tests and RecKit unit tests. Uploading on an iPhone · Mac with the same Apple ID → listing on the other device → renaming → deleting must be checked on real devices |

---

## 21. Conventions (formerly docs/21)

These are things that audits · cleanups **looked at and decided to keep**. Only the reasons are written down, briefly, so that the next audit does not
bring up and argue the same thing again. To reverse a decision, add a reason here or delete the entry.

### 1. Log event names are a stable contract — never rename them

Event names such as `rec.start` · `rec.part` · `rec.stop` · `rec.finalize` · `xfer.*` · `job.step.start/ok/fail` · `sync.*`
are a contract under which the four shells use **the same names** (§20), and measurement records and acceptance scenarios hang on these strings. Being
visible only in code is no reason to polish them to match the product name or current class names — they are not shown to users, so leaving them
as they are is the contract. `CoreMessage` codes are the same: the stored `last_error` and the shells' rendering use the code strings as is.

### 2. `WorkflowSync.reconcile` is dense but is not to be split up

The rules only read when local · remote · deletion watermark · `dirty` are **seen together on one screen**. Splitting it into helpers would make each piece
shorter, but following the whole sync rule would mean jumping between files; the body of this function is the rule itself.

### 3. The remaining length of `ShellModel` and `MenuModel` stays

Windows `ui/ShellModel.kt` and Mac `MenuModel.swift` are long, but **the logic worth extracting has already been moved out** —
`DisconnectGuard`/`Phase`/`Gate`, `StatusLine`, `runTracked`/`persistFlushed`, `DisconnectFlow`,
`WorkflowInspector`, `RecordingDialogs`, `RecorderStatusLine`. What remains is state fields and thin delegation to the shared implementations, so
a split that cuts more lines would only create churn without moving any contract.

### 4. Test doubles live once, in the shared test-support location

Drift starts the moment the same fake is written a second time inside a test file (copies that differ only in failure variants, different device
identifiers). When a second user appears, move it up to that module's shared test-support location, and build special behavior as **a delegate
wrapping the shared double**, not as a copy. Current locations: core `core/src/jvmTest/.../testing/` (`TestSupport`, `FakeDrive`),
Windows `windows/app/src/test/.../Fakes.kt`, and on Android a shared test file per module.

### 5. Three step→label maps are intentional

> **2026-09-24**: once the workflow editor (`WorkflowEditorScreen` · `StepEdit`) is removed, the editor-side maps among the subjects of this decision
> go away. The same principle (different wording, different maps) applies to the maps that remain.

Android `WorkflowsViewModel` has separate `Step`→label and `StepEdit`→label maps, and `WorkflowEditorScreen` has one more, `StepKind`→label.
**This is because the wording differs** — the list uses short names (`Drive`), and the editor and the add-step dialog use names that describe the action
(`Drive upload`). Merging them into one would break the wording on two of the three screens. What should be merged is not the maps, only cases that
write the same wording twice.

### 6. The codebase language is English

Identifiers, comments, doc comments, commit messages, log event names and messages, test names and module READMEs are written in English. **User-facing
strings are localized through resources** (§7) and not hardcoded in code. The documents in `docs/`, including this one, are
English.

---

## Open decisions

These are what remain for people, not code, to decide.

| Item | Status |
|---|---|
| **Trademark** | At the US USPTO, the identical word mark **RECLY** is registered and live in Class 9 (mobile apps) (Reg. 7739986, 2025-03-25), and there is no identical mark in Korea. Launching · filing in the US is risky. Options: launch in Korea first (excluding the US) / a US trademark attorney's opinion / rename (display name only). Keep the name if the release scope is Korea; renaming is recommended if it is global |
| **License · publication** | **Decided (2026-09-04)**: `AGPL-3.0-or-later` (`LICENSE` verbatim) + two §7 additional permissions (app store distribution, 7(e) trademark not granted — `LICENSE-EXCEPTIONS.md`) + `TRADEMARK.md`. **No CLA** — contributions are accepted under the same terms (including the additional permissions) (`CONTRIBUTING.md`). The name **stays Recly** even for a global release (the US RECLY trademark risk is accepted). The repository was made public on 2026-09-05, and the store launch was 2026-10-01 — App Store (iPhone · Apple Watch) and Google Play (Android phone · Wear OS). macOS · Windows are distributed directly through GitHub Releases |
| **Pricing · billing** | The §16 proposal (free app + user keys, paid convenience tier later) is **awaiting a decision**. Once decided, it moves up into the §0 rules. To decide along with it: keep a paid convenience tier open as a possibility, or nail down "there will never be a server" as a product promise / confirm the cost of Windows code signing and take it on or not |
| **Legal review of jurisdiction consent wording** | The jurisdiction table in §12 is a web summary, not legal advice. Before store submission, check at least the Korea · US (whether the state list is current) · EU entries, or keep the notice only at the level of "check your jurisdiction yourself" |
| **Privacy policy publication** | All implementation prerequisites are met. What remains: ① finalizing the per-provider retention policy URLs in the §15 §3 table (until they are final, the app notice carries no link either), ② a public contact email, ③ the legal review above. All three are human work |
| **Watch slice size criterion** | The original criterion was "watchOS slice < 20 MB", but after applying SKIE the pre-strip static slice is 20.1–21.8 MB, over that line. The actual app after linking · stripping is 13 MB, so there is room within the 75 MB budget. A decision is needed: rewrite the criterion as "linked watch app size", or shrink the watch source set to bring the slice back |
| **iCloud real-device acceptance** | From 0.1.2 (build 32, 2026-10-02) the builds ship with iCloud on (§3 "Storage location"; the container · App ID · Mac Developer ID profile · `Local.xcconfig` are in place). What remains is checking upload · completion · other-device listing · playback · deletion on a real iPhone · Mac with the same Apple ID, and fixing the statement in the App Store submission documents (`app-review.md` · `app-store-metadata.en.txt` · `app-store-copy.md`) that only Drive is used |
| **macOS echo (AEC)** | With the built-in speakers, the other side's voice mixes into the mic track. For now there is only a one-line warning at start; whether to make it an option is decided by the results of a `setVoiceProcessingEnabled` experiment |
