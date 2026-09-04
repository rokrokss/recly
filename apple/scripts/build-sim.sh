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
scheme="$1" platform="$2" device="$3"
shift 3
exec xcodebuild -workspace "$here/../Rec.xcworkspace" -scheme "$scheme" \
  -destination "platform=$platform,name=$device" ARCHS=arm64 -collect-test-diagnostics never "$@"
