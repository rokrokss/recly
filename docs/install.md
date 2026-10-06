# Installing Recly

[한국어](install.ko.md)

The phone and watch apps are on the stores:

- **Android phone and Galaxy Watch**: [Google Play](https://play.google.com/store/apps/details?id=app.recly).
  Android 14 / Wear OS 5 or later.
- **iPhone and Apple Watch**: [App Store](https://apps.apple.com/app/recly-record-for-your-ai/id6809930443).
  iOS 17 / watchOS 10 or later.

The watch cannot upload on its own — it hands recordings to the phone app, so **install both**.

The Mac and Windows apps, and APKs of the Android apps for sideloading, are on
[GitHub Releases](https://github.com/rokrokss/recly/releases), tagged `v<version>-build.<n>`. The
newest app release is marked Latest, and [this link](https://github.com/rokrokss/recly/releases/latest)
opens it; the `events-v…` releases next to it are recly-events, not the apps. The file each device
needs:

| Device | File |
|---|---|
| Android phone (sideloading) | `Recly-Android-<version>-<build>.apk` |
| Galaxy Watch (sideloading) | `Recly-WearOS-<version>-<build>.apk` |
| Mac | `Recly-macOS-<version>-<build>.dmg` |
| Windows | `Recly-<version>.msi` |

The build number in a file name differs per platform: v0.2.0-build.34, for example, carries
`Recly-macOS-0.2.0-34.dmg`, `Recly-Android-0.2.0-39.apk` and `Recly-WearOS-0.2.0-1000039.apk`.
The `.aab` files are Google Play upload bundles and the `.ipa` is the App Store upload package;
neither is for installing.

Each release also carries `SHA256SUMS`. Put it in the folder with your downloads and run
`shasum -a 256 --ignore-missing -c SHA256SUMS` (macOS, Linux): it checks only the files you
downloaded. In Windows PowerShell, run `Get-FileHash .\Recly-0.2.0.msi -Algorithm SHA256` and
compare the hash, which it prints in capitals, with that file's line in `SHA256SUMS`.

Once Recly is installed, [set it up](setup.md): connect your storage and choose how to transcribe.

## Android phone

Install Recly from [Google Play](https://play.google.com/store/apps/details?id=app.recly).

**Sideloading instead**: open the release in the phone's browser, download
`Recly-Android-<version>-<build>.apk`, open it, and allow installs from that source. If Play
Protect warns about an app from outside Play, you can still install it. The APKs are signed with
the project's upload key, not the key Google Play signs with, so a Play install and an APK install
cannot update each other: to switch, uninstall first, and only after every recording shows as
uploaded — uninstalling deletes the ones still on the phone.

To check an APK's signer on a computer, run `apksigner verify --print-certs Recly-Android-*.apk`
(apksigner comes with the Android SDK Build-Tools). For the phone and the watch APK alike, the
output starts with:

```text
Signer #1 certificate DN: CN=Recly, O=Recly
Signer #1 certificate SHA-256 digest: edf7b9502a55a9f845292e6c3ff6278dd3018be8f092bfc531531e4e43e43e9f
```

An install from Google Play is signed by Google Play's app signing key and shows a different
certificate.

## Galaxy Watch

With the phone app installed, open Play Store on the watch and install Recly, or install it on the
watch from Google Play on the phone.

*Double press home key → record*: on the watch, Settings → Advanced features → Customise keys →
Double press home key → Open app, and choose **"Recly Record"** (the second launcher entry;
"Recly" only opens the app). Take Galaxy Wearable off the phone's background-battery limits or the
Bluetooth link drops.

**Sideloading instead**: install the phone APK as well — the watch and phone apps must come from
the same place. Wear OS has no browser and no APK installer, so `Recly-WearOS-<version>-<build>.apk`
can only arrive over ADB. ADB is Android Debug Bridge, the command-line tool in
[Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools) that
installs apps on a phone or watch over USB or Wi-Fi. On the watch, Settings → About watch →
Software → tap the version seven times, then Developer options → turn on **ADB debugging** and
**Wireless debugging**, and pair from a machine on the same Wi-Fi:

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
[release](https://github.com/rokrokss/recly/releases/latest), open it and drag Recly to
Applications. The app is signed with a Developer ID certificate and notarized by Apple, so it opens
without any Gatekeeper workaround. macOS 14.4 or later, Apple Silicon.

## Windows

**Beta.** The Windows app is built and tested in CI but has not yet been checked on a real
Windows PC. Please report what you find in [Issues](https://github.com/rokrokss/recly/issues).

Download `Recly-<version>.msi` from the newest
[release](https://github.com/rokrokss/recly/releases/latest) and open it. Windows 11 on x64. The
MSI is not code-signed, so SmartScreen shows "Windows protected your PC": choose **More info** →
**Run anyway**.

## Updating

The phone and watch apps update through Google Play or the App Store. A sideloaded APK updates when
you install the newer APK the same way you installed the first.

The Mac and Windows apps do not update themselves. Quit Recly (**Quit** in the Mac menu-bar popover
or the Windows tray popup), then install the newer DMG or MSI from the
[latest release](https://github.com/rokrokss/recly/releases/latest) over the old one; the MSI
upgrades the existing install.

To hear about new releases, choose **Watch** → **Custom** → **Releases** on
[rokrokss/recly](https://github.com/rokrokss/recly) on GitHub.

## Uninstalling

- **Phone and watch apps**: uninstall Recly like any other app. First make sure every recording
  shows as uploaded: uninstalling deletes the ones still on the device.
- **Mac**: choose **Quit** in the menu-bar popover, then move Recly from Applications to the Trash.
- **Windows**: Settings → Apps → Installed apps, then **…** next to Recly → **Uninstall**.

Recordings already uploaded stay in your storage. What else stays on the device, and how to remove
it, is in the
[privacy policy](https://recly.dev/policy/privacy-policy#what-survives-uninstalling-the-app).
