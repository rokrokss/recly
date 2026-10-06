#!/bin/sh
# Usage: push.sh <seed dir> — replaces the app's data on the running emulator with a seed.py output.
set -e
A=${ADB:-${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}/platform-tools/adb}
SEED=$1
$A shell am force-stop app.recly
$A shell rm -rf /data/local/tmp/seed
$A push "$SEED" /data/local/tmp/seed >/dev/null
$A shell chmod -R a+rX /data/local/tmp/seed
$A shell "run-as app.recly sh -c 'rm -rf files/rec/recordings databases/rec.db-journal && mkdir -p files/rec/recordings && cp /data/local/tmp/seed/rec.db databases/rec.db && cp -r /data/local/tmp/seed/recordings/. files/rec/recordings/'"
$A shell run-as app.recly ls files/rec/recordings
