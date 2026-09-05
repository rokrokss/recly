# `windows/` — the Recly Windows client

Two parts. The details of each are in that part's README.

| | |
|---|---|
| [`app/`](app/README.md) | Compose Desktop shell — tray, auth, runner, editor window, **detection & notification** (M6-L3) |
| [`capture-helper/`](capture-helper/README.md) | Rust capture helper — WASAPI mic + loopback, segments, `mic_in_use` |

This document covers only what binds the two into one — **MSI packaging · signing · SmartScreen**
(docs/14 N7).

## Building the MSI

`jpackage` (Compose `nativeDistributions`) can build an MSI **only on Windows**, and it needs WiX 3.
The development machine is macOS (M6-L3 "environment constraints"), so the release MSI is built by
`.github/workflows/windows-release.yml` — on a `v*` tag, or by hand from the Actions tab — and
attached to the GitHub release; `windows.yml`, on the same triggers, only compiles and tests. On a
local Windows PC:

```powershell
# 1. The helper
cd windows/capture-helper; cargo build --release

# 2. Put what goes into the MSI in the resources directory (the directory is empty in git)
copy target\release\recly-capture-helper.exe ..\app\resources\windows-x64\
#    ffmpeg: the LGPL shared build (ADR-019). A GPL build cannot be distributed.
#    https://github.com/BtbN/FFmpeg-Builds → ffmpeg-master-latest-win64-lgpl-shared.zip
copy <ffmpeg>\bin\ffmpeg.exe ..\app\resources\windows-x64\
copy <ffmpeg>\bin\*.dll      ..\app\resources\windows-x64\

# 3. The MSI
cd ..\..; .\gradlew :windows:app:packageMsi
#    → windows/app/build/compose/binaries/main/msi/Recly-<version>.msi
```

The layout of `app/resources/` is a jpackage rule: `windows-x64/` goes into Windows builds only,
`common/` goes into every platform. The installed app receives that path as
`compose.application.resources.dir`, and `CaptureHelper.command()` finds
`recly-capture-helper.exe` and `ffmpeg.exe` there and hands the helper `--ffmpeg <path>` (which is
why ffmpeg need not be on PATH).

The ffmpeg LGPL notice ships with the install as `app/resources/common/THIRD-PARTY-ffmpeg.md`.

Install form: **per-user** (`perUserInstall`) — it needs no administrator rights, and it is the same
user scope the data (`%LOCALAPPDATA%\Recly`) and the launch-at-login key (`HKCU\…\Run`) live in.
Launch at login is an app setting, not an MSI option (after installing, tray → Settings → "Launch
Recly at login"). An upgrade replaces the previous install through `upgradeUuid`.

## Signing

`scripts/sign-msi.ps1`. With no credentials it **skips signing and exits 0** — an unsigned MSI is
still a build, and a build must not fail on a fork.

| Method | Environment variables needed |
|---|---|
| Microsoft Trusted Signing (recommended) | `AZURE_TENANT_ID`, `AZURE_CLIENT_ID`, `AZURE_CLIENT_SECRET`, `TRUSTED_SIGNING_ENDPOINT`, `TRUSTED_SIGNING_ACCOUNT`, `TRUSTED_SIGNING_PROFILE` |
| EV certificate (PFX) | `WINDOWS_CERT_PFX_BASE64`, `WINDOWS_CERT_PASSWORD` |

In CI, putting in repository secrets of the same names turns the signing step on by itself.

```powershell
pwsh windows/scripts/sign-msi.ps1 -Msi windows\app\build\compose\binaries\main\msi\Recly-0.1.0.msi
```

## SmartScreen check procedure (docs/20 S8 · N7 — **on hold: no Windows PC**)

Once there is a Windows PC, check in this order. Nobody has been able to do it so far, and
docs/20 "Windows 보류 항목" says as much.

1. Check on a **new PC** (or a new user profile). A machine that has run it once earns reputation
   and the warning disappears, so checking on the machine that built it means nothing.
2. Download the MSI with a browser. It has to be a **download**, not a File Explorer copy — what
   wakes SmartScreen is the Mark of the Web (the `Zone.Identifier` alternate data stream).
   Check: `Get-Item .\Recly-0.1.0.msi -Stream Zone.Identifier`
3. Signature check: `Get-AuthenticodeSignature .\Recly-0.1.0.msi | Format-List` → `Status: Valid`,
   the subject of `SignerCertificate` is ours, a timestamp present.
4. Run it. Expected: **the blue "Windows protected your PC" window does not appear.**
   - If it does appear and says "Unknown publisher", it is not signed (back to 3).
   - If the signature is valid but the warning appears, there is no reputation yet. An EV
     certificate gets reputation immediately, but plain OV or Trusted Signing needs downloads to
     accumulate — in that case leave a screenshot of the warning screen and record it in docs/20 as
     "signed · awaiting reputation". It is an item time resolves, not a code problem.
5. After installing, check on real hardware (the items on hold along with it): the tray icon, the
   capture helper version row in Settings and "Run the self-test", the `HKCU\…\Run` launch-at-login
   toggle, Credential Manager sign-in, the detection notification when joining Teams.
