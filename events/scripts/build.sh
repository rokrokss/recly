#!/usr/bin/env bash
# Builds recly-events — the one place its build flags live (docs/development.md "recly-events
# releases"). Recly's desktop OAuth client is compiled in from local.properties
# (google.desktopClientId/Secret) or REC_GOOGLE_DESKTOP_CLIENT_ID/SECRET, the values the Windows app
# is built with. It is never committed; without it the program builds, and `init --google` says so.
#
#   events/scripts/build.sh OUT [VERSION]                 GOOS and GOARCH come from the environment
#   REQUIRE_CLIENT=1 events/scripts/build.sh OUT VERSION  stop when there is no client (releases)
#
# Used by `make events` and release.sh.
set -euo pipefail
cd "$(dirname "$0")/../.."

out="${1:?usage: build.sh OUT [VERSION]}"
version="${2:-dev}"
case "$out" in /*) ;; *) out="$PWD/$out" ;; esac

# A key's first value in local.properties, or nothing: CI has no such file, and under `set -e` a sed
# that cannot open it would end the script without a word.
prop() { [[ -f local.properties ]] && sed -n "s/^$1=//p" local.properties | head -1 || true; }
id="$(prop google.desktopClientId)"
id="${id:-${REC_GOOGLE_DESKTOP_CLIENT_ID:-}}"
secret="$(prop google.desktopClientSecret)"
secret="${secret:-${REC_GOOGLE_DESKTOP_CLIENT_SECRET:-}}"
if [[ -z "$id" || -z "$secret" || "$id$secret" == *REPLACE_ME* ]]; then
  if [[ "${REQUIRE_CLIENT:-0}" == 1 ]]; then
    echo "build: no Recly desktop OAuth client (google.desktopClientId/Secret in local.properties)" >&2
    exit 1
  fi
  id=
  secret=
fi

mkdir -p "$(dirname "$out")"
cd events
CGO_ENABLED=0 go build -trimpath \
  -ldflags "-s -w -X main.version=$version -X main.googleClientID=$id -X main.googleClientSecret=$secret" \
  -o "$out" ./cmd/recly-events
