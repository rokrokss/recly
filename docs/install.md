# Installing Recly

Every platform's build goes up on [GitHub Releases](https://github.com/rokrokss/recly/releases),
tagged `v<version>-build.<n>`. All of them are marked pre-release, so GitHub's "latest release"
link does not reach them — open the Releases list and take the newest. Each release carries
`SHA256SUMS`: with the files in one folder, `shasum -a 256 -c SHA256SUMS` checks them.

Android gets two APKs, `Recly-Android-<version>-<build>.apk` for the phone and
`Recly-WearOS-<version>-<build>.apk` for the watch, both signed with the project's upload key.
They need Android 14 / Wear OS 5 or later.

The watch cannot upload on its own — it hands recordings to the phone app, so **install both**.

## Android phone

Open the release in the phone's browser, download `Recly-Android-<version>-<build>.apk`, open it,
and allow installs from that source. If Play Protect warns about an app from outside Play, you can
still install it. Google sign-in works: the upload key's SHA-1 is registered on the Android OAuth
client.

## Galaxy Watch

Wear OS has no browser and no APK installer, so the watch APK can only arrive over ADB. On the
watch, Settings → About watch → Software → tap the version seven times, then Developer options →
turn on **ADB debugging** and **Wireless debugging**, and pair from a machine on the same Wi-Fi:

```bash
adb pair <pairing IP:port> <code>       # Wireless debugging → "Pair new device"
adb connect <IP:port>                   # the address on the Wireless debugging screen
adb -s <IP:port> install -r Recly-WearOS-*.apk
```

Without a computer: download the watch APK on the phone and push it with an ADB-based installer
app from Play (for instance "Wear Installer 2"); the watch settings above are the same. Take
Galaxy Wearable off the phone's background-battery limits or the Bluetooth link drops.

*Double press home key → record*: on the watch, Settings → Advanced features → Customise keys →
Double press home key → Open app, and choose **"Recly Record"** (the second launcher entry;
"Recly" only opens the app).

## Windows

The MSI is on the [GitHub release](https://github.com/rokrokss/recly/releases). It is not yet
code-signed, so SmartScreen shows "Windows protected your PC" — choose **More info** → **Run
anyway**.

## macOS

Download `Recly-macOS-<version>-<build>.dmg` from the newest
[release](https://github.com/rokrokss/recly/releases), open it and drag Recly to Applications. The
app is signed with a Developer ID certificate and notarized by Apple, so it opens without any
Gatekeeper workaround. macOS 14.4 or later, Apple Silicon.

## iPhone · Apple Watch

The App Store release is in review. Until it is out, the way in is building from source — see
[development.md](development.md). The `.ipa` on each release is the App Store upload package; it
cannot be installed on a device directly.
