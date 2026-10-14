#!/usr/bin/env bash
# Builds the KMP XCFramework and stages it inside apple/RecKit, where Package.swift consumes it as
# a binaryTarget. Staging (rather than pointing the package up at core/build/) is what lets
# `swift build` resolve the package on its own, without a relative path out of the package root.
# `SKIP_IF_PRESENT=1` leaves an existing Gradle build alone (Xcode "Run Script" use) but still
# refreshes the copy; without it Gradle decides, which is cheap when nothing in :core changed.
#
# A full build compiles and links six Kotlin/Native targets with release optimization: 10m51s when
# they ran one after another (2026-10-15, after a :core change, M4 Pro 14 cores). Gradle runs one
# project's tasks in parallel only with the configuration cache, so every Gradle call here asks for
# it and the six links run side by side.
#
# Two shorter loops link only what a check reads, release like the full build — a debug framework
# keeps SQLiter's load-extension calls, which the system SQLite does not export, and a test bundle or
# app linked against it fails to load. Each swaps its slices into the staged copy and leaves a marker
# naming them, so `build-sim.sh` refuses to build against slices older than the change:
# - `CORE_SLICES=macos` (`make core-mac`): the macOS slice, all `make mac` / `make mac-test` read.
# - `CORE_SLICES=sim` (`make core-sim`): the macOS and the two simulator slices, all the Mac and the
#   simulator builds read. The device slices are for releases, whose scripts always run the full build.
# A full run (the default) removes the marker.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
built="$repo_root/core/build/XCFrameworks/release/ReclyCore.xcframework"
staged="$repo_root/apple/RecKit/Frameworks/ReclyCore.xcframework"
partial="$repo_root/apple/RecKit/Frameworks/ReclyCore.partial"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"

# Kotlin target · staged slice, for the partial loops.
slices_macos="macosArm64:macos-arm64"
slices_sim="$slices_macos iosSimulatorArm64:ios-arm64-simulator watchosSimulatorArm64:watchos-arm64-simulator"

case "${CORE_SLICES:-all}" in
  macos|sim)
    [[ "$CORE_SLICES" == "macos" ]] && slices="$slices_macos" || slices="$slices_sim"
    if [[ ! -d "$staged/macos-arm64" ]]; then
      echo "build-core: no staged XCFramework to update yet — run a full \`make core\` once" >&2
      exit 1
    fi
    tasks=()
    for pair in $slices; do tasks+=(":core:linkReleaseFramework${pair%%:*}"); done
    "$repo_root/gradlew" -p "$repo_root" --configuration-cache "${tasks[@]}" "$@"
    names=()
    for pair in $slices; do
      target="${pair%%:*}" slice="${pair##*:}"
      rsync -a --delete "$repo_root/core/build/bin/$target/releaseFramework/ReclyCore.framework/" \
        "$staged/$slice/ReclyCore.framework/"
      names+=("$slice")
    done
    echo "${names[*]} $(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$partial"
    ;;
  *)
  if [[ "${SKIP_IF_PRESENT:-0}" == "1" && -d "$built" ]]; then
    echo "build-core: $built already there, skipping the Gradle build"
  else
    "$repo_root/gradlew" -p "$repo_root" --configuration-cache :core:assembleXCFramework "$@"
  fi

  # --delete: a stale slice left in the copy would still be linked.
  mkdir -p "$(dirname "$staged")"
  rsync -a --delete "$built/" "$staged/"
  rm -f "$partial"
  ;;
esac

# Xcode's explicit-module cache keeps a precompiled ReclyCore.pcm keyed to the header it was built
# from. A refreshed XCFramework changes that header, and instead of rebuilding the module the next
# `xcodebuild` fails with "ReclyCore.h has been modified since the module file was built". The
# cache is per DerivedData tree — one per checkout or worktree — so every one of ours is cleared;
# it is a cache, and the next build simply rebuilds it.
derived="${DERIVED_DATA:-$HOME/Library/Developer/Xcode/DerivedData}"
for cache in "$derived"/Rec-*/Build/Intermediates.noindex/SwiftExplicitPrecompiledModules; do
  [[ -d "$cache" ]] || continue
  rm -rf "$cache"
  echo "build-core: cleared $cache"
done

# docs/13 M5-L1 deliverable 5: every Apple target links one of these, and the two *simulator*
# slices are the ones RecPhone and RecWatch are built against long before either meets hardware. A
# slice dropped from `:core`'s target list still leaves a green Gradle build here and surfaces much
# later as "no such module ReclyCore" inside an app target, with nothing pointing back at this
# script — so the staged copy is checked for all five by name.
for slice in ios-arm64 ios-arm64-simulator macos-arm64 watchos-arm64_arm64_32 watchos-arm64-simulator; do
  if [[ ! -d "$staged/$slice" ]]; then
    echo "build-core: $staged has no $slice slice — check :core's apple targets" >&2
    exit 1
  fi
done

echo "build-core: $staged"

# The other build input the package needs that is not checked in: the diarization models the
# RecKitSpeakers target copies into the iPhone and Mac apps.
"$repo_root/apple/scripts/fetch-speaker-models.sh"
