# Installing Recly

The phone and watch apps are on the stores:

- **Android phone and Galaxy Watch**: [Google Play](https://play.google.com/store/apps/details?id=app.recly).
  Android 14 / Wear OS 5 or later.
- **iPhone and Apple Watch**: [App Store](https://apps.apple.com/app/recly-record-for-your-ai/id6809930443).
  iOS 17 / watchOS 10 or later.

The watch cannot upload on its own — it hands recordings to the phone app, so **install both**.

The Mac and Windows apps, and APKs of the Android apps for sideloading, are on
[GitHub Releases](https://github.com/rokrokss/recly/releases), tagged `v<version>-build.<n>`. All
of them are marked pre-release, so GitHub's "latest release" link does not reach them — open the
Releases list and take the newest. Each release carries `SHA256SUMS`: with the files in one
folder, `shasum -a 256 -c SHA256SUMS` checks them.

## Android phone

Install Recly from [Google Play](https://play.google.com/store/apps/details?id=app.recly).

**Sideloading instead**: open the release in the phone's browser, download
`Recly-Android-<version>-<build>.apk`, open it, and allow installs from that source. If Play
Protect warns about an app from outside Play, you can still install it. The APKs are signed with
the project's upload key, not the key Google Play signs with, so a Play install and an APK install
cannot update each other: to switch, uninstall first, and only after every recording shows as
uploaded — uninstalling deletes the ones still on the phone.

## Galaxy Watch

With the phone app installed, open Play Store on the watch and install Recly, or install it on the
watch from Google Play on the phone.

*Double press home key → record*: on the watch, Settings → Advanced features → Customise keys →
Double press home key → Open app, and choose **"Recly Record"** (the second launcher entry;
"Recly" only opens the app). Take Galaxy Wearable off the phone's background-battery limits or the
Bluetooth link drops.

**Sideloading instead**: install the phone APK as well — the watch and phone apps must come from
the same place. Wear OS has no browser and no APK installer, so `Recly-WearOS-<version>-<build>.apk`
can only arrive over ADB. On the watch, Settings → About watch → Software → tap the version seven
times, then Developer options → turn on **ADB debugging** and **Wireless debugging**, and pair from
a machine on the same Wi-Fi:

```bash
adb pair <pairing IP:port> <code>       # Wireless debugging → "Pair new device"
adb connect <IP:port>                   # the address on the Wireless debugging screen
adb -s <IP:port> install -r Recly-WearOS-*.apk
```

Without a computer: download the watch APK on the phone and push it with an ADB-based installer
app from Play (for instance "Wear Installer 2"); the watch settings above are the same.

## iPhone · Apple Watch

Install Recly from the [App Store](https://apps.apple.com/app/recly-record-for-your-ai/id6809930443).
The Apple Watch app comes with it: if it does not appear on the watch, open the Watch app on the
iPhone and install Recly from there.

The `.ipa` on each GitHub release is the App Store upload package; it cannot be installed on a
device directly. Building from source is in [development.md](development.md).

## macOS

Download `Recly-macOS-<version>-<build>.dmg` from the newest
[release](https://github.com/rokrokss/recly/releases), open it and drag Recly to Applications. The
app is signed with a Developer ID certificate and notarized by Apple, so it opens without any
Gatekeeper workaround. macOS 14.4 or later, Apple Silicon.

## Windows

The MSI is on the [GitHub release](https://github.com/rokrokss/recly/releases). It is not yet
code-signed, so SmartScreen shows "Windows protected your PC" — choose **More info** → **Run
anyway**.
