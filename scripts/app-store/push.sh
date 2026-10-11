#!/bin/sh
# Usage: push.sh <simulator udid> <seed dir> — replaces the iPhone app's data on that simulator with a seed.py output.
# Always pass the throwaway simulator's UDID, never `booted`: another booted simulator may hold someone's data.
set -e
UDID=$1 SEED=$2
xcrun simctl terminate "$UDID" app.recly 2>/dev/null || true
DATA="$(xcrun simctl get_app_container "$UDID" app.recly data)/Library/Application Support/app.recly"
rm -rf "$DATA/recordings" "$DATA/rec.db" "$DATA/rec.db-wal" "$DATA/rec.db-shm"
cp "$SEED/rec.db" "$DATA/rec.db"
cp -R "$SEED/recordings" "$DATA/recordings"
ls "$DATA/recordings"
