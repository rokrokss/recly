#!/usr/bin/env bash
# The iPhone app (with the watch app embedded) as an App Store Connect archive, and optionally the
# upload to TestFlight. Signing is Xcode's automatic signing under the developer's own team, so the
# tracked projects stay unsigned (docs/development.md "Release signing"): the team comes from
# `Config/Local.xcconfig` (`RECLY_DEVELOPMENT_TEAM`) or `RECLY_TEAM_ID`, never from the tree.
#
# `-allowProvisioningUpdates` lets xcodebuild register the bundle ids, the app group and the
# certificates itself. That needs either an Apple ID signed in to Xcode (Settings → Accounts) or an
# App Store Connect API key passed through RECLY_ASC_KEY_PATH / RECLY_ASC_KEY_ID / RECLY_ASC_ISSUER.
#
# Uploading is a separate, explicit step (`UPLOAD=1`) for the same reason release-mac.sh does not
# notarize on a plain run: it sends the build to Apple under the user's account.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
derived="$repo_root/apple/build/release-ios"
out="$repo_root/apple/build/dist"
archive="$out/Recly.xcarchive"

team="${RECLY_TEAM_ID:-}"
if [[ -z "$team" && -f "$repo_root/apple/Config/Local.xcconfig" ]]; then
  team="$(sed -n 's/^RECLY_DEVELOPMENT_TEAM *= *\([A-Z0-9]*\).*/\1/p' "$repo_root/apple/Config/Local.xcconfig" | head -1)"
fi
if [[ -z "$team" ]]; then
  echo "release-ios: no team. Put RECLY_DEVELOPMENT_TEAM = <team id> in apple/Config/Local.xcconfig or set RECLY_TEAM_ID." >&2
  exit 1
fi

auth=()
if [[ -n "${RECLY_ASC_KEY_PATH:-}" ]]; then
  auth=(-authenticationKeyPath "$RECLY_ASC_KEY_PATH"
        -authenticationKeyID "${RECLY_ASC_KEY_ID:?RECLY_ASC_KEY_PATH needs RECLY_ASC_KEY_ID}"
        -authenticationKeyIssuerID "${RECLY_ASC_ISSUER:?RECLY_ASC_KEY_PATH needs RECLY_ASC_ISSUER}")
fi

SKIP_IF_PRESENT=0 "$repo_root/apple/scripts/build-core.sh"

rm -rf "$derived" "$archive"
mkdir -p "$out"
echo "release-ios: archiving as team $team"
xcodebuild \
  -collect-test-diagnostics never \
  -workspace "$repo_root/apple/Rec.xcworkspace" \
  -scheme Recly \
  -configuration Release \
  -destination 'generic/platform=iOS' \
  -derivedDataPath "$derived" \
  -archivePath "$archive" \
  -allowProvisioningUpdates \
  ${auth[@]+"${auth[@]}"} \
  DEVELOPMENT_TEAM="$team" \
  CODE_SIGN_STYLE=Automatic \
  CODE_SIGN_IDENTITY="Apple Development" \
  archive

python3 "$repo_root/apple/scripts/validate-ios-oauth.py" "$archive"
python3 "$repo_root/apple/scripts/validate-apple-package.py" \
  "$archive/Products/Applications/Recly.app" --dsym-directory "$archive/dSYMs"

plist="$(mktemp -t recly-export).plist"
trap 'rm -f "$plist"' EXIT
cat > "$plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>method</key><string>app-store-connect</string>
  <key>destination</key><string>$([[ "${UPLOAD:-0}" == "1" ]] && echo upload || echo export)</string>
  <key>teamID</key><string>$team</string>
  <key>signingStyle</key><string>automatic</string>
  <key>uploadSymbols</key><true/>
  <key>manageAppVersionAndBuildNumber</key><false/>
</dict></plist>
PLIST

xcodebuild \
  -collect-test-diagnostics never \
  -exportArchive \
  -archivePath "$archive" \
  -exportOptionsPlist "$plist" \
  -exportPath "$out/ios" \
  -allowProvisioningUpdates \
  ${auth[@]+"${auth[@]}"}

if [[ "${UPLOAD:-0}" == "1" ]]; then
  echo "release-ios: uploaded to App Store Connect — it appears under TestFlight once processed."
else
  echo "release-ios: exported $out/ios (no upload). Upload with: UPLOAD=1 apple/scripts/release-ios.sh"
fi
