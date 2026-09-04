# Installing Recly

Debug APKs go up as pre-releases (`v0.0.1-dev`,
[releases](https://github.com/rokrokss/recly/releases)) — one for the phone, one for the watch.
Both need Android 14 / Wear OS 5 or later.

The watch cannot upload on its own — it hands recordings to the phone app, so **install both**.

## Android phone

Open the release in the phone's browser, download the phone APK, open it, and allow installs
from that source. Play Protect warns about the debug signature — choose "Install anyway".
Google sign-in works: the debug keystore's SHA-1 is the one registered on the OAuth client.

## Galaxy Watch

Wear OS has no browser and no APK installer, so the watch APK can only arrive over ADB. On the
watch, Settings → About watch → Software → tap the version seven times, then Developer options →
turn on **ADB debugging** and **Wireless debugging**, and pair from a machine on the same Wi-Fi:

```bash
adb pair <pairing IP:port> <code>       # Wireless debugging → "Pair new device"
adb connect <IP:port>                   # the address on the Wireless debugging screen
adb -s <IP:port> install -r Recly-Watch-*.apk
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

## macOS · iPhone · Apple Watch

App Store, TestFlight and DMG releases are coming soon. Today the way in is building from
source — see [development.md](development.md).
