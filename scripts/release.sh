#!/usr/bin/env bash
# Local fallback: builds and publishes a PocketRocket release from the Mac. The normal way is the Release
# workflow on GitHub Actions (decision 67, .github/workflows/release.yml), started by scripts/ship.sh or
# `gh workflow run release.yml -f version=X.Y.Z -f notes=...`. Use this only when Actions can't run; it needs
# the keystore and the Keychain password on this Mac. See docs/app-updates.md.
#
#   scripts/release.sh X.Y.Z [--notes FILE] [--dry-run]
#
# Checks main is clean and pushed, sets the version in app/build.gradle, builds the arm64 release APK signed
# with the release key (~/.pocketrocket-keys/pocketrocket-release.jks, password from the login Keychain, never
# printed), checks the signer with apksigner, names it pocketrocket-vX.Y.Z-arm64.apk and computes its SHA-256.
# Then commits the bump, tags the code repo vX.Y.Z, pushes both, and creates the prerelease in the public
# ShankarKakumani/pocketrocket-releases repo (where the app looks) with the SHA-256 line in its notes, plus the
# same release on the private code repo's tag so both Releases pages list it.
# --dry-run does the checks and the signed build, prints what it would publish, and puts app/build.gradle
# back; it never commits, tags, pushes or touches GitHub.
set -euo pipefail

usage() { echo "usage: scripts/release.sh X.Y.Z [--notes FILE] [--dry-run]" >&2; exit 2; }

VERSION="" NOTES_FILE="" DRY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY=1 ;;
    --notes) shift; NOTES_FILE="${1:-}"; [ -f "$NOTES_FILE" ] || { echo "notes file not found: $NOTES_FILE" >&2; exit 2; } ;;
    -h|--help) usage ;;
    *) [ -z "$VERSION" ] || usage; VERSION="$1" ;;
  esac
  shift
done
[ -n "$VERSION" ] || usage
VERSION="${VERSION#v}"
TAG="v$VERSION"
CODE_REPO="ShankarKakumani/pocketrocket"
RELEASES_REPO="ShankarKakumani/pocketrocket-releases"
KEYSTORE="${POCKETROCKET_KEYSTORE:-$HOME/.pocketrocket-keys/pocketrocket-release.jks}"
KEY_ALIAS="pocketrocket"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE="$ROOT/app/build.gradle"
cd "$ROOT"

say() { printf '\033[1m%s\033[0m\n' "$*"; }
problem() {
  if [ "$DRY" = 1 ]; then echo "  [dry run] would stop here: $*"; else echo "error: $*" >&2; exit 1; fi
}

# Version: X.Y.Z with minor and patch below 100 (versionCode = 1000 + major*10000 + minor*100 + patch).
if ! [[ "$VERSION" =~ ^(0|[1-9][0-9]*)\.([0-9]|[1-9][0-9])\.([0-9]|[1-9][0-9])$ ]]; then
  echo "error: version must be X.Y.Z with minor and patch below 100, got $VERSION" >&2; exit 2
fi
CODE=$((1000 + BASH_REMATCH[1] * 10000 + BASH_REMATCH[2] * 100 + BASH_REMATCH[3]))
CURRENT="$(sed -n 's/^ *def pocketRocketVersion = "\([^"]*\)".*/\1/p' "$GRADLE")"
[ -n "$CURRENT" ] || { echo "error: no pocketRocketVersion line in app/build.gradle" >&2; exit 1; }
newer() { [ "$1" != "$2" ] && [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -1)" = "$1" ]; }

say "Release $TAG (versionCode $CODE), current $CURRENT$([ "$DRY" = 1 ] && echo ', dry run')"

say "Checking the repo"
newer "$VERSION" "$CURRENT" || problem "$VERSION is not newer than the current $CURRENT"
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
[ "$BRANCH" = main ] || problem "on branch $BRANCH, not main"
[ -z "$(git status --porcelain)" ] || problem "the working tree has changes"
git fetch -q origin main || problem "couldn't fetch origin"
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main 2>/dev/null)" ] || problem "main isn't the same as origin/main (push or pull first)"
! git rev-parse -q --verify "refs/tags/$TAG" >/dev/null || problem "tag $TAG already exists"
EMAIL="$(git config user.email || true)"
[ "$EMAIL" = "ShankarKakumani@users.noreply.github.com" ] || problem "git user.email is '$EMAIL', not ShankarKakumani@users.noreply.github.com"
[ -f "$KEYSTORE" ] || { echo "error: release keystore not found at $KEYSTORE (restore it from iCloud Drive › PocketRocket Keys, see docs/app-updates.md)" >&2; exit 1; }
POCKETROCKET_KEY_PASSWORD="$(security find-generic-password -s pocketrocket-release-key -a pocketrocket -w 2>/dev/null)" ||
  { echo "error: no 'pocketrocket-release-key' password in the login Keychain" >&2; exit 1; }
export POCKETROCKET_KEYSTORE="$KEYSTORE" POCKETROCKET_KEY_PASSWORD POCKETROCKET_KEY_ALIAS="$KEY_ALIAS"
# The release key's certificate, to check the APK's signer against; it must be the pinned one.
PIN="$(tr -d '[:space:]' < "$ROOT/scripts/release-cert.sha256")"
KEY_CERT_SHA="$(keytool -exportcert -keystore "$KEYSTORE" -storetype pkcs12 -alias "$KEY_ALIAS" -storepass:env POCKETROCKET_KEY_PASSWORD 2>/dev/null | shasum -a 256 | cut -d' ' -f1)"
[ "$KEY_CERT_SHA" != "$(printf '' | shasum -a 256 | cut -d' ' -f1)" ] || { echo "error: couldn't read alias $KEY_ALIAS from $KEYSTORE with the Keychain password" >&2; exit 1; }
[ "$KEY_CERT_SHA" = "$PIN" ] || { echo "error: $KEYSTORE's certificate isn't the pinned one in scripts/release-cert.sha256" >&2; exit 1; }
if [ "$DRY" = 0 ]; then
  GH_TOKEN="$(gh auth token -u ShankarKakumani)" || { echo "error: gh has no login for ShankarKakumani" >&2; exit 1; }
  export GH_TOKEN
fi

say "Setting $VERSION in app/build.gradle"
cp "$GRADLE" "$GRADLE.release-backup"
restore() { [ -f "$GRADLE.release-backup" ] && mv "$GRADLE.release-backup" "$GRADLE"; }
if [ "$DRY" = 1 ]; then trap restore EXIT; else trap 'restore; echo "stopped; app/build.gradle put back" >&2' ERR; fi
scripts/set-version.sh "$VERSION"

say "Building the signed arm64 release APK"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
if ! java -version >/dev/null 2>&1; then export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"; fi
TERMUX_SPLIT_APKS_FOR_RELEASE_BUILDS=1 ./gradlew -q :app:assembleRelease
BUILT="$ROOT/app/build/outputs/apk/release/termux-app_apt-android-7-release_arm64-v8a.apk"
OUT_DIR="$ROOT/app/build/release"
APK="$OUT_DIR/pocketrocket-$TAG-arm64.apk"
mkdir -p "$OUT_DIR"
cp "$BUILT" "$APK"
scripts/check-release-apk.sh "$APK" "$VERSION" "$PIN"
SHA="$(shasum -a 256 "$APK" | cut -d' ' -f1)"
echo "  $APK"
echo "  SHA-256: $SHA"

NOTES="$OUT_DIR/notes-$TAG.md"
{
  if [ -n "$NOTES_FILE" ]; then cat "$NOTES_FILE"; else echo "PocketRocket $VERSION, arm64. Update from Settings › PocketRocket, or install over the current app; don't uninstall first."; fi
  echo
  echo "Code: https://github.com/$CODE_REPO/tree/$TAG"
  echo
  echo "SHA-256: \`$SHA\`"
} > "$NOTES"

if [ "$DRY" = 1 ]; then
  say "Dry run: would now run"
  echo "  git commit -m 'Release $TAG' app/build.gradle"
  echo "  git tag -a $TAG -m 'PocketRocket $VERSION'"
  echo "  git push origin main $TAG"
  echo "  gh release create $TAG $APK --repo $RELEASES_REPO --prerelease --title 'PocketRocket $VERSION' --notes-file $NOTES"
  echo "  gh release create $TAG $APK --repo $CODE_REPO --verify-tag --prerelease --title 'PocketRocket $VERSION' --notes-file $NOTES"
  echo "  notes:"; sed 's/^/    /' "$NOTES"
  exit 0
fi

rm -f "$GRADLE.release-backup"
trap - ERR
say "Committing, tagging and pushing"
git commit -q -m "Release $TAG" app/build.gradle
git tag -a "$TAG" -m "PocketRocket $VERSION"
git push -q origin main "$TAG"

say "Creating the GitHub prerelease"
gh release create "$TAG" "$APK" --repo "$RELEASES_REPO" --prerelease --title "PocketRocket $VERSION" --notes-file "$NOTES"
gh release create "$TAG" "$APK" --repo "$CODE_REPO" --verify-tag --prerelease --title "PocketRocket $VERSION" --notes-file "$NOTES"
say "Done: https://github.com/$RELEASES_REPO/releases/tag/$TAG (code: $CODE_REPO@$TAG)"
