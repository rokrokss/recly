#!/usr/bin/env bash
# Prints THIRD-PARTY-NOTICES.txt for the recly-events binary: the licence and NOTICE files of the Go
# standard library and of every module linked into it on macOS, Linux or Windows, as `go list` sees
# them in the module cache. release.sh puts the output next to the binary in every archive.
set -euo pipefail
cd "$(dirname "$0")/.."

modules() {
  for os in darwin linux windows; do
    GOOS="$os" go list -deps \
      -f '{{with .Module}}{{if not .Main}}{{.Path}}@{{.Version}} {{.Dir}}{{end}}{{end}}' ./cmd/recly-events
  done | sort -u
}

# legal FILE... — each file under its own heading.
legal() {
  for f in "$@"; do
    printf -- '--- %s\n\n' "$(basename "$f")"
    cat "$f"
    printf '\n'
  done
}

files() {
  find "$1" -maxdepth 1 -type f \( -iname 'licen[cs]e*' -o -iname 'copying*' -o -iname 'notice*' \) | sort
}

cat <<'EOF'
Third-party notices for recly-events

recly-events is part of Recly and licensed under AGPL-3.0-or-later (LICENSE, LICENSE-EXCEPTIONS.md).
The binary contains the Go standard library and the Go modules below, each under the licence that
follows its name.

EOF
printf '=== Go standard library %s\n\n' "$(go env GOVERSION)"
legal "$(go env GOROOT)/LICENSE"
modules | while read -r module dir; do
  printf '=== %s\n\n' "$module"
  found="$(files "$dir")"
  if [[ -z "$found" ]]; then
    echo "notices: no licence file in $module" >&2
    exit 1
  fi
  while IFS= read -r f; do legal "$f"; done <<< "$found"
done
