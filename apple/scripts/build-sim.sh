#!/bin/bash
# Every simulator build must pass ARCHS=arm64 on the command line: the core's XCFramework ships
# arm64-only simulator slices (no x86_64, no arm64_32), and neither project-level ARCHS or
# EXCLUDED_ARCHS conditionals nor an arch-qualified -destination reaches SwiftPM package targets
# (RecKit) — all three were tried; only a command-line build setting does. This wrapper is that
# setting, so nobody has to remember it.
#
# Usage: apple/scripts/build-sim.sh <scheme> <platform> <device-name> [xcodebuild args...]
#   apple/scripts/build-sim.sh Recly "iOS Simulator" "iPhone 17" build
#   apple/scripts/build-sim.sh "Recly Watch" "watchOS Simulator" "Apple Watch Series 11 (46mm)" build
#   apple/scripts/build-sim.sh Recly "iOS Simulator" "iPhone 17" test -only-testing:ReclyUITests
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
# A partial core (`make core-mac`, `make core-sim`) names its fresh slices; the iPhone app embeds the watch
# app, so both simulator slices have to be among them — `make core-mac` leaves them older than the change.
partial="$here/../RecKit/Frameworks/ReclyCore.partial"
if [[ -f "$partial" ]] && ! { grep -q 'ios-arm64-simulator' "$partial" && grep -q 'watchos-arm64-simulator' "$partial"; }; then
  echo "build-sim: the staged core has only a fresh macOS slice ($(cat "$partial")) — run \`make core-sim\` or \`make core\` first" >&2
  exit 1
fi
scheme="$1" platform="$2" device="$3"
shift 3
exec xcodebuild -workspace "$here/../Rec.xcworkspace" -scheme "$scheme" \
  -destination "platform=$platform,name=$device" ARCHS=arm64 -collect-test-diagnostics never "$@"
