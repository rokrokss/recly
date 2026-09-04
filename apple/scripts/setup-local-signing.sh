#!/usr/bin/env bash
# Create the persistent local identity used by the Recly Mac development build.
#
# An ad-hoc signature's designated requirement is its code hash. That hash changes on every
# rebuild, so macOS Keychain ACLs and privacy grants no longer recognise the next build. A local
# certificate keeps the signing identity stable without requiring an Apple Developer account.
set -euo pipefail

identity="Recly Local Development"
keychain="$HOME/Library/Keychains/login.keychain-db"

if security find-identity -v -p codesigning "$keychain" 2>/dev/null \
    | grep -Fq "\"$identity\""; then
  echo "setup-local-signing: identity already exists: $identity"
  exit 0
fi

temporary="$(mktemp -d)"
trap 'rm -rf "$temporary"' EXIT
passphrase="$(openssl rand -hex 24)"

openssl req \
  -newkey rsa:2048 \
  -x509 \
  -sha256 \
  -days 3650 \
  -nodes \
  -subj "/CN=$identity/O=Recly Local Development" \
  -addext "keyUsage=critical,digitalSignature" \
  -addext "extendedKeyUsage=codeSigning" \
  -keyout "$temporary/key.pem" \
  -out "$temporary/cert.pem" \
  >/dev/null 2>&1

openssl pkcs12 \
  -export \
  -name "$identity" \
  -inkey "$temporary/key.pem" \
  -in "$temporary/cert.pem" \
  -passout "pass:$passphrase" \
  -out "$temporary/identity.p12"

# Keep the private key non-exportable and allow only Apple's signing tools to use it. The temporary
# PKCS#12 passphrase exists only for this import and disappears with the temporary directory.
security import "$temporary/identity.p12" \
  -k "$keychain" \
  -f pkcs12 \
  -P "$passphrase" \
  -x \
  -T /usr/bin/codesign \
  -T /usr/bin/xcodebuild
security add-trusted-cert -r trustRoot -p codeSign -k "$keychain" "$temporary/cert.pem"

if ! security find-identity -v -p codesigning "$keychain" 2>/dev/null \
    | grep -Fq "\"$identity\""; then
  echo "setup-local-signing: imported certificate is not a valid code-signing identity" >&2
  exit 1
fi

echo "setup-local-signing: created identity: $identity"
