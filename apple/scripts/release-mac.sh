#!/usr/bin/env bash
# docs/12 M9: a distributable Recly.app in a DMG — Developer ID + hardened runtime where there is
# an identity. Release builds refuse ad-hoc signing because its changing designated requirement
# makes Keychain and privacy grants ask again after every rebuild.
#
# The signature is Xcode's, not a `codesign --deep` afterwards: the app embeds ReclyCore.framework
# and GoogleSignIn's bundles, and the only thing that reliably signs nested code inside out is the
# build itself (`--deep` is documented as unsuitable for exactly this).
#
# Notarization is deliberately *not* part of a plain run. It uploads the build to Apple under the
# user's own account, which is not a thing a script should do because it was executed; the steps are
# here and are run by `NOTARIZE=1 NOTARY_PROFILE=<keychain profile>`. Sparkle is v1.1 (ADR/docs/12).
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
derived="$repo_root/apple/build/release"
out="$repo_root/apple/build/dist"

# The XCFramework is a Gradle output that is not checked in; without it the package does not resolve.
SKIP_IF_PRESENT=1 "$repo_root/apple/scripts/build-core.sh"

# Prefer a distributable Developer ID identity. A caller may select another stable identity for a
# local-only DMG, and the development certificate created by setup-local-signing.sh is the fallback.
identity="${RECLY_SIGNING_IDENTITY:-}"
if [[ -z "$identity" ]]; then
  identity="$(security find-identity -v -p codesigning \
    | sed -n 's/.*"\(Developer ID Application:.*\)"$/\1/p' | head -1)"
fi
if [[ -z "$identity" ]] && security find-identity -v -p codesigning \
    | grep -Fq '"Recly Local Development"'; then
  identity="Recly Local Development"
fi
if [[ -z "$identity" ]]; then
  cat >&2 <<'MSG'
release-mac: no stable code-signing identity was found.
  For a local build, run: apple/scripts/setup-local-signing.sh
  For distribution, install a Developer ID Application certificate.
MSG
  exit 1
fi

echo "release-mac: signing as $identity"
sign_flags=(CODE_SIGN_IDENTITY="$identity")
if [[ "$identity" == Developer\ ID\ Application:* ]]; then
  sign_flags+=(OTHER_CODE_SIGN_FLAGS="--timestamp")
fi

rm -rf "$derived" "$out"
# `ARCHS=arm64` on the command line and not only in the project: the RecKit package does not inherit
# the target's setting, and a Release build has no `ONLY_ACTIVE_ARCH` to fall back on — so it reaches
# for x86_64, which the XCFramework has no slice of (docs/12: Apple Silicon 우선).
xcodebuild \
  -workspace "$repo_root/apple/Rec.xcworkspace" \
  -scheme "Recly Mac" \
  -configuration Release \
  -destination 'platform=macOS' \
  -derivedDataPath "$derived" \
  ARCHS=arm64 \
  CODE_SIGN_STYLE=Manual \
  ENABLE_HARDENED_RUNTIME=YES \
  "${sign_flags[@]}" \
  build

app="$derived/Build/Products/Release/Recly.app"
version="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$app/Contents/Info.plist")"
dmg="$out/Recly-$version.dmg"

# What was actually signed, and how — the hardened runtime is a flag on the signature, not a build
# setting anyone can read back off the bundle.
codesign --verify --strict --verbose=2 "$app"
codesign -dv --verbose=2 "$app"
# A local certificate fails this, and that is expected: only a Developer ID build is distributable.
spctl --assess --type exec --verbose=4 "$app" || true

# The hardened runtime denies a signed app the microphone unless the signature carries this, and
# the failure mode is a build that records silence — on the user's Mac, not here. So it is checked
# against the signature rather than against the source.
entitlements="$(codesign -d --entitlements :- "$app" 2>/dev/null)"
printf '%s\n' "$entitlements"
for key in com.apple.security.device.audio-input; do
  if ! printf '%s' "$entitlements" | grep -q "$key"; then
    echo "release-mac: signature is missing $key — see RecMac/RecMac.entitlements" >&2
    exit 1
  fi
done

# `hdiutil` rather than `create-dmg`: one dependency fewer, and the layout of the window is not
# worth a Homebrew formula in the release path.
staging="$(mktemp -d)"
trap 'rm -rf "$staging"' EXIT
cp -R "$app" "$staging/"
ln -s /Applications "$staging/Applications"
mkdir -p "$out"
hdiutil create -volname "Recly $version" -srcfolder "$staging" -ov -format UDZO "$dmg"

if [[ "$identity" == Developer\ ID\ Application:* ]]; then
  codesign --force --timestamp --sign "$identity" "$dmg"
fi

echo "release-mac: $dmg"

if [[ "${NOTARIZE:-0}" != "1" ]]; then
  cat <<'MSG'
release-mac: notarization skipped.
  Set it up once:  xcrun notarytool store-credentials <profile> --apple-id <id> --team-id <team>
  Then:            NOTARIZE=1 NOTARY_PROFILE=<profile> apple/scripts/release-mac.sh
  It needs a Developer ID signature with a secure timestamp; an ad-hoc build is rejected.
MSG
  exit 0
fi

: "${NOTARY_PROFILE:?NOTARIZE=1 needs NOTARY_PROFILE=<notarytool keychain profile>}"
if [[ "$identity" != Developer\ ID\ Application:* ]]; then
  echo "release-mac: notarization requires a Developer ID Application identity" >&2
  exit 1
fi
xcrun notarytool submit "$dmg" --keychain-profile "$NOTARY_PROFILE" --wait
# Stapled to the DMG, so a download that is opened offline is still recognised.
xcrun stapler staple "$dmg"
xcrun stapler validate "$dmg"
spctl --assess --type open --context context:primary-signature --verbose=4 "$dmg"
echo "release-mac: notarized $dmg"
