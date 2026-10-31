#!/usr/bin/env bash
# Checks a built release APK before it's published. Used by scripts/release.sh and .github/workflows/release.yml.
#
#   scripts/check-release-apk.sh APK X.Y.Z CERT_SHA256
#
# The APK must say app.pocket, versionName X.Y.Z and versionCode 1000 + major*10000 + minor*100 + patch (aapt2), and
# every signer line apksigner prints (v2, v3, ...) must name CERT_SHA256 and nothing else. For a real release
# CERT_SHA256 is the pin in scripts/release-cert.sha256. Needs ANDROID_HOME with build-tools.
set -euo pipefail

[ $# = 3 ] || { echo "usage: scripts/check-release-apk.sh APK X.Y.Z CERT_SHA256" >&2; exit 2; }
APK="$1" VERSION="$2" CERT="$(echo "$3" | tr 'A-F' 'a-f')"
[ -f "$APK" ] || { echo "error: no APK at $APK" >&2; exit 1; }
[[ "$VERSION" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || { echo "error: version must be X.Y.Z, got $VERSION" >&2; exit 2; }
CODE=$((1000 + BASH_REMATCH[1] * 10000 + BASH_REMATCH[2] * 100 + BASH_REMATCH[3]))
[[ "$CERT" =~ ^[0-9a-f]{64}$ ]] || { echo "error: certificate SHA-256 must be 64 hex characters" >&2; exit 2; }

TOOLS="$(ls -d "${ANDROID_HOME:?ANDROID_HOME is not set}"/build-tools/*/ | sort -V | tail -1)"
BADGE="$("${TOOLS}aapt2" dump badging "$APK" 2>/dev/null | sed -n 1p)"
echo "  $BADGE"
[[ "$BADGE" == *"name='app.pocket'"*"versionCode='$CODE'"*"versionName='$VERSION'"* ]] ||
  { echo "error: the APK doesn't say app.pocket $VERSION ($CODE)" >&2; exit 1; }

SIGNER="$("${TOOLS}apksigner" verify --print-certs "$APK" | sed -n 's/^.*[Ss]igner.* certificate SHA-256 digest: //p' | sort -u)"
[ -n "$SIGNER" ] && [ "$SIGNER" = "$CERT" ] ||
  { echo "error: the APK isn't signed by $CERT alone (signers: ${SIGNER:-none})" >&2; exit 1; }
echo "  signed by $SIGNER only"
