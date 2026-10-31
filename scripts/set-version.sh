#!/usr/bin/env bash
# Sets PocketRocket's version, the one `def pocketRocketVersion = "X.Y.Z"` line in app/build.gradle (decision 26).
# Used by scripts/release.sh and .github/workflows/release.yml; never bump the line by hand.
#
#   scripts/set-version.sh X.Y.Z
set -euo pipefail

[ $# = 1 ] && [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "usage: scripts/set-version.sh X.Y.Z" >&2; exit 2; }
GRADLE="$(cd "$(dirname "$0")/.." && pwd)/app/build.gradle"
perl -pi -e 's/^( *def pocketRocketVersion = )"[^"]*"/$1"'"$1"'"/' "$GRADLE"
grep -q "^ *def pocketRocketVersion = \"$1\"" "$GRADLE" || { echo "error: couldn't set $1 in app/build.gradle" >&2; exit 1; }
