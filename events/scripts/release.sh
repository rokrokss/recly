#!/usr/bin/env bash
# Builds the recly-events release archives on a Mac (docs/development.md "recly-events releases"):
# macOS as one universal binary, signed with Developer ID and notarized; Linux amd64 and arm64;
# Windows amd64, not code-signed; and SHA256SUMS. Recly's desktop OAuth client is compiled in,
# read from local.properties or the environment like `make events`. It is never committed.
#
#   TAG=events-v0.1.0 NOTARY_PROFILE=recly events/scripts/release.sh            # → events/dist/0.1.0/
#   TAG=events-v0.1.0 NOTARY_PROFILE=recly UPLOAD=1 events/scripts/release.sh   # and a draft release
#
# A draft is visible only to the repository's maintainers. Publish it from the Releases page or
# with `gh release edit <tag> --draft=false`; GitHub creates the tag then.
set -euo pipefail
cd "$(dirname "$0")/../.."

: "${TAG:?TAG=events-vX.Y.Z}"
if [[ ! "$TAG" =~ ^events-v([0-9]+\.[0-9]+\.[0-9]+)$ ]]; then
  echo "release: TAG must look like events-v1.2.3, not $TAG" >&2
  exit 1
fi
version="${BASH_REMATCH[1]}"
: "${NOTARY_PROFILE:?NOTARY_PROFILE=<notarytool keychain profile>}"

# The archives must be what one commit says, so that the tag can point at it.
if [[ -n "$(git status --porcelain -- events)" ]]; then
  echo "release: events/ has uncommitted changes" >&2
  exit 1
fi
commit="$(git rev-parse HEAD)"

prop() { sed -n "s/^$1=//p" local.properties 2>/dev/null | head -1; }
client_id="$(prop google.desktopClientId)"
client_id="${client_id:-${REC_GOOGLE_DESKTOP_CLIENT_ID:-}}"
client_secret="$(prop google.desktopClientSecret)"
client_secret="${client_secret:-${REC_GOOGLE_DESKTOP_CLIENT_SECRET:-}}"
if [[ -z "$client_id" || -z "$client_secret" || "$client_id$client_secret" == *REPLACE_ME* ]]; then
  echo "release: no Recly desktop OAuth client (google.desktopClientId/Secret in local.properties)" >&2
  exit 1
fi

identity="$(security find-identity -v -p codesigning \
  | sed -n 's/.*"\(Developer ID Application:.*\)"$/\1/p' | head -1)"
if [[ -z "$identity" ]]; then
  echo "release: no Developer ID Application identity in the keychain" >&2
  exit 1
fi

out="events/dist/$version"
work="$out/work"
rm -rf "$out"
mkdir -p "$work"
ldflags="-s -w -X main.version=$version -X main.googleClientID=$client_id -X main.googleClientSecret=$client_secret"

# build GOOS GOARCH OUTPUT
build() {
  mkdir -p "$(dirname "$3")"
  (cd events && CGO_ENABLED=0 GOOS="$1" GOARCH="$2" go build -trimpath -ldflags "$ldflags" \
    -o "../$3" ./cmd/recly-events)
}

# stage NAME BINARY — a directory with the binary and the licence it is under.
stage() {
  mkdir -p "$work/$1"
  cp "$2" "$work/$1/"
  cp LICENSE LICENSE-EXCEPTIONS.md "$work/$1/"
}

echo "release: building recly-events $version from ${commit:0:7}"

# macOS: Apple silicon and Intel in one binary, hardened runtime, notarized. A bare binary cannot
# be stapled; Gatekeeper finds the notarization online when a downloaded copy is first run.
build darwin arm64 "$work/darwin-arm64"
build darwin amd64 "$work/darwin-amd64"
mkdir -p "$work/darwin"
lipo -create -output "$work/darwin/recly-events" "$work/darwin-arm64" "$work/darwin-amd64"
codesign --force --options runtime --timestamp --identifier dev.recly.events \
  --sign "$identity" "$work/darwin/recly-events"
codesign --verify --strict --verbose=2 "$work/darwin/recly-events"
mac="recly-events_${version}_darwin_universal"
stage "$mac" "$work/darwin/recly-events"
ditto -c -k --norsrc --keepParent "$work/$mac" "$out/$mac.zip"
result="$(xcrun notarytool submit "$out/$mac.zip" --keychain-profile "$NOTARY_PROFILE" --wait 2>&1)" || true
echo "$result"
if ! grep -q "status: Accepted" <<<"$result"; then
  echo "release: notarization was not accepted (xcrun notarytool log <id> --keychain-profile $NOTARY_PROFILE)" >&2
  exit 1
fi

# Linux
for arch in amd64 arm64; do
  name="recly-events_${version}_linux_${arch}"
  build linux "$arch" "$work/linux-$arch/recly-events"
  stage "$name" "$work/linux-$arch/recly-events"
  tar -C "$work" -czf "$out/$name.tar.gz" "$name"
done

# Windows: not code-signed (the MSI's signing runs in CI), and not yet tried on a Windows PC.
name="recly-events_${version}_windows_amd64"
build windows amd64 "$work/windows/recly-events.exe"
stage "$name" "$work/windows/recly-events.exe"
(cd "$work" && zip -qr "../$name.zip" "$name")

(cd "$out" && shasum -a 256 ./*.zip ./*.tar.gz | sed 's# \./# #' > SHA256SUMS)
rm -rf "$work"
echo "release: archives in $out"
cat "$out/SHA256SUMS"

if [[ "${UPLOAD:-0}" != 1 ]]; then
  exit 0
fi
git fetch -q origin
if ! git merge-base --is-ancestor "$commit" origin/main; then
  echo "release: push ${commit:0:7} to origin/main first" >&2
  exit 1
fi
if gh release view "$TAG" > /dev/null 2>&1; then
  echo "release: $TAG already exists" >&2
  exit 1
fi
notes="recly-events $version tells your ChatGPT agent (a dot or a Work chat) when Recly finishes a transcript.
Set it up with [events/README.md](https://github.com/rokrokss/recly/blob/$TAG/events/README.md).

- macOS: one binary for Apple silicon and Intel, signed with Developer ID and notarized.
- Linux: amd64 and arm64.
- Windows: amd64, not code-signed and not yet tried on a Windows PC.

Check a download: \`shasum -a 256 -c SHA256SUMS --ignore-missing\`"
gh release create "$TAG" --draft --prerelease --latest=false --target "$commit" \
  --title "recly-events $version" --notes "$notes" "$out"/*.zip "$out"/*.tar.gz "$out/SHA256SUMS"
echo "release: draft $TAG created; publish it with: gh release edit $TAG --draft=false"
