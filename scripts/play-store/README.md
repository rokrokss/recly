# Google Play store graphics

Templates and scripts for the Google Play listing graphics. The app's screens in them are real captures from
emulators holding demo recordings, not mock-ups. Captures and renders live in `build/play-store-assets/`
(gitignored, like the App Store set in `build/app-store-assets/`).

`clients.html` uses the same captures for the top-level README's Clients image, which `render.mjs` writes to
`docs/design/screenshots/<lang>/clients.png` (committed). Its Apple Watch screen is
`docs/design/screenshots/<lang>/apple-watch.png`.

## Upload map (Play Console → Grow users → Store presence → Main store listing)

| Field | File in `build/play-store-assets/out/` | Spec it meets |
|---|---|---|
| App icon | `icon-512.png` | 512×512, 32-bit PNG, fully opaque, full bleed from `docs/design/icon.svg` (Play adds the rounded mask and shadow) |
| Feature graphic | `<lang>/feature-graphic-b.png` | 1024×500, 24-bit PNG, no alpha |
| Phone screenshots | `<lang>/phone/01…05-*.png` | 1440×2560 (9:16), 24-bit PNG, ≥1080 px, at least 4 (promotion eligibility) |
| Wear OS screenshots (Wear OS form factor) | `<lang>/wear/01…03-*.png` | 454×454 (1:1, ≥384), the app's screens only: no frame, text or background added |

`<lang>` is `en` for English (United States) and `ko` for Korean. A language without its own graphics shows the
default language's.

Feature graphic: `b` is the default. Play's preview-asset advice says to avoid device images and black or dark
grey grounds. `a`, the App Store header with a round watch, has both, and is the second arm for a store
listing experiment.

Copy rules the templates keep: nothing Play's metadata policy forbids in graphics (rankings, awards, prices,
"Best of Play"), no "Free", "Best", "#1" or "Download now", and only what the Android apps do. That means no
iPhone, Apple Watch or iCloud, and on-device transcription only on phones with 8 GB of RAM.

## Making them

1. **Phone emulator.** A fresh AVD, so nothing on it is anyone's data: Pixel 8 profile, `hw.ramSize=8192M`
   (on-device transcription is offered from 8 GB). Install the APK from `make apk`. In the app, set Transcription to
   On device, record one short throwaway recording, force-stop, and pull the database the app wrote:
   `adb exec-out run-as app.recly cat databases/rec.db > build/play-store-assets/template.db`.
   The template's workflow and settings JSON are the app's own; `seed.py` reads them from there.
2. **Demo data.** `python3 scripts/play-store/seed.py <en|ko> build/play-store-assets/template.db <dir>`
   builds the nine recordings in `demo_data.py`: TTS audio (`say`, encoded like the app's recorder),
   `meta.json`, a `transcript.json` whose segment times are the real offsets in that audio, and job rows marked
   uploaded and transcribed. The transcripts are sample text, not transcription output.
3. **Phone captures.** `push.sh <dir>` replaces the app's data with the seed; `demo-bar.sh` sets the status bar.
   Language: `adb shell cmd locale set-app-locales app.recly --locales ko-KR` (or `en-US`). Capture with
   `adb exec-out screencap -p` into `build/play-store-assets/captures/<lang>/`: `list` (List tab), `expand` (first
   row tapped), `detail` (Details, then the second timestamp tapped), `record` (Record tab, idle).
4. **Watch captures.** A fresh large round Wear OS AVD (`hw.lcd.circular=true`) with the APK from `make wear-apk` and the
   microphone permission granted. `wear-idle` and `wear-rec` (12 s into a recording) from the app; `wear-tile`
   after adding the tile with
   `adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation add-tile --ecn component app.recly/app.recly.wear.entry.RecTileService --ei index 0`
   and swiping left from the watch face. Clear the app's data first if a recording is waiting to send. The watch
   draws its own clock, so the status bar demo mode does not reach it. Outside the round display the captures
   are the system's black; for none at all, use Android Studio's screenshot with the "Play Store Compatible"
   frame.
5. **Render.** `node scripts/play-store/render.mjs` frames the phone captures in `screenshot.html`, draws
   `feature.html`, renders the icon, flattens everything but the icon to 24-bit, and stops if any copy spills
   past the edges or into the device.
