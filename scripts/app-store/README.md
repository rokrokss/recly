# iPhone promo images

The four captioned iPhone images at the top of the README and on recly.dev
(`docs/design/screenshots/<lang>/0N-*.png` and `.webp`, 540×1173 with transparent rounded corners, committed). The
screens are real captures of the iPhone and Apple Watch apps on throwaway simulators holding demo recordings,
not mock-ups. Captures and the seed live in `build/app-store-assets/` (gitignored). The Android counterpart, and
the demo recordings both sets use, are in [`scripts/play-store/`](../play-store/README.md).

| Image | Screen |
|---|---|
| `01-record-for-your-ai` | Record tab, idle, with the Apple Watch's record screen in front |
| `02-transcribe` | the first recording's Details, after its second timestamp was tapped |
| `03-notes` | the same recording's Details, its Notes chip selected |
| `04-list` | List tab |

## Making them

Use simulators made for this and delete them afterwards, so nothing on them is anyone's data, and pass their
UDID to every `simctl` call: `booted` may resolve to another simulator that is running.

1. **Simulators.** `xcrun simctl create shots-iphone com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro <iOS runtime>`,
   the same for `shots-watch` with `Apple-Watch-Series-11-46mm` and a watchOS runtime, `xcrun simctl pair <watch> <phone>`,
   boot both. On the phone: `simctl ui <udid> appearance light` and
   `simctl status_bar <udid> override --time 9:41 --dataNetwork wifi --wifiMode active --wifiBars 3 --cellularMode active --cellularBars 4 --operatorName "" --batteryState discharging --batteryLevel 100`.
2. **App.** After `make core`, build a signed simulator app (an unsigned one cannot reach the keychain):
   `apple/scripts/build-sim.sh Recly "iOS Simulator" shots-iphone -derivedDataPath <dir> CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- build`.
   Install `Recly.app` on the phone and `Recly.app/Watch/Recly.app` on the watch, and launch the phone app once so
   it writes its data directory, then terminate it. Do not sign in to anything.
3. **Demo data.** Copy the phone app's data directory
   (`$(xcrun simctl get_app_container <udid> app.recly data)/Library/Application Support/app.recly`) somewhere,
   then `python3 scripts/app-store/seed.py <en|ko> <that copy> build/app-store-assets/seed/<lang>` and
   `scripts/app-store/push.sh <udid> build/app-store-assets/seed/<lang>`. The recordings are `demo_data.py`'s, with
   rows marked uploaded and transcribed.
4. **Captures.** Launch with the language, in the demo recordings' time zone (not the host's):
   `SIMCTL_CHILD_TZ=Asia/Dubai xcrun simctl launch <udid> app.recly -AppleLanguages "(ko)" -AppleLocale ko_KR -appLanguage ko` (or `en`). Capture
   with `xcrun simctl io <udid> screenshot --mask=black build/app-store-assets/captures/<lang>/<name>.png`: `record`,
   `list`, `detail`, `notes` (the table above), and on the watch, launched the same way as `app.recly.watchkitapp`,
   `watch-idle` (no mask). watchOS draws its own clock, which the status bar override does not reach, so
   `promo.html` draws the phone's 9:41 over it; capture the watch in a minute ending in 1, or the real clock's last
   digit shows past that cover.
5. **Render.** `node scripts/app-store/render.mjs` frames the captures in `promo.html`, writes the PNG and WebP
   for both languages, and stops if any copy spills past the edges or into the phone. Captions and the README's
   alt texts say the same thing; change both together.

## App Store screenshots

The iPhone 6.9-inch set for App Store Connect, in `build/app-store-assets/store/<lang>/` (not committed):
1320×2868, opaque, square corners, the same frame and the same 04. `01-record` is the Apple Watch alone,
recording, as the Play set's first image is the Galaxy Watch: the watch is what brings people in. Its screen is
drawn from `RecordingView` at the places the idle capture measures, since a simulator watch would record from the
Mac's microphone. The set shows no notes and names no AI service, because the China mainland storefront shows the
English listing and does not get notes (docs/app-store-copy.md): `02-transcribe` is a recording without notes, so
no Notes chip, and `03-storage` is Settings → Storage.

1. **Captures.** Seed as above, then delete the seed's `*.notes.v1.json` before `push.sh`. Capture `transcript`
   (the first recording's Details, its second timestamp tapped) and `storage` (Settings, Local folder with a
   folder chosen: create one in the picker's On My iPhone) next to the others.
2. **Render.** `STORE=1 node scripts/app-store/render.mjs`.
