#!/usr/bin/env bash
# Interactive front end to the Release workflow (decision 67, .github/workflows/release.yml): pulls main, asks for
# the version, drafts the notes from the commits since the last tag and opens them in $EDITOR, then runs the
# workflow on GitHub Actions (a dry run first if you like) and follows it until it's done.
#
#   scripts/ship.sh            # build, sign and publish on GitHub Actions
#   scripts/ship.sh --local    # fall back to scripts/release.sh on this Mac (needs the keystore and Keychain)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
LOCAL=0
[ "${1:-}" = --local ] && LOCAL=1
REPO="dockito-ai/pocketrocket"
GH_TOKEN="$(gh auth token -u ShankarKakumani)" || { echo "error: gh has no login for ShankarKakumani" >&2; exit 1; }
export GH_TOKEN
say() { printf '\033[1m%s\033[0m\n' "$*"; }
ask() { local a; read -r -p "$1 " a; echo "${a:-$2}"; }
yes() { case "$(ask "$1 [Y/n]" y)" in [Yy]*) return 0 ;; *) return 1 ;; esac; }

[ "$(git rev-parse --abbrev-ref HEAD)" = main ] || { echo "error: not on main" >&2; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "error: the working tree has changes; commit or stash them first" >&2; exit 1; }
say "Pulling main"
git pull -q --rebase origin main
git push -q origin main

CURRENT="$(sed -n 's/^ *def pocketRocketVersion = "\([^"]*\)".*/\1/p' app/build.gradle)"
IFS=. read -r MA MI PA <<< "$CURRENT"
NEXT="$MA.$MI.$((PA + 1))"
LAST_TAG="$(git describe --tags --abbrev=0 --match 'v*' 2>/dev/null || true)"
say "Current version $CURRENT (last tag ${LAST_TAG:-none})"
VERSION="$(ask "Version to release [$NEXT]:" "$NEXT")"
VERSION="${VERSION#v}"

NOTES_DIR="$(mktemp -d)"
NOTES="$NOTES_DIR/notes-v$VERSION.md"
{
  echo "# Release notes for $VERSION. Lines starting with # are dropped. Save and close to continue;"
  echo "# leave it empty to stop. The SHA-256 and code link are added by the release."
  echo "Update from Settings › PocketRocket, or install over the current app."
  echo
  echo "**New**"
  git log --no-merges --format='- %s' ${LAST_TAG:+"$LAST_TAG..HEAD"} | grep -v -e '^- Release v' || true
} > "$NOTES"
${EDITOR:-nano} "$NOTES"
sed -i '' '/^#/d' "$NOTES"
[ -n "$(tr -d '[:space:]' < "$NOTES")" ] || { echo "notes are empty; stopped" >&2; exit 1; }

say "Notes"
sed 's/^/  /' "$NOTES"

if [ "$LOCAL" = 1 ]; then
  yes "Do a dry run first?" && scripts/release.sh "$VERSION" --notes "$NOTES" --dry-run
  yes "Build and publish $VERSION from this Mac now?" || { echo "stopped; nothing published"; exit 0; }
  scripts/release.sh "$VERSION" --notes "$NOTES"
  exit 0
fi

# Starts the workflow on main and follows the run it created until it ends.
run_release() {
  local since id
  since="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  gh workflow run release.yml -R "$REPO" --ref main -f version="$VERSION" -f notes="$(cat "$NOTES")" -f dry_run="$1"
  for _ in 1 2 3 4 5 6; do
    sleep 5
    id="$(gh run list -R "$REPO" --workflow release.yml --event workflow_dispatch --created ">=$since" -L 1 --json databaseId -q '.[0].databaseId')"
    [ -n "$id" ] && break
  done
  [ -n "$id" ] || { echo "couldn't find the run; see https://github.com/$REPO/actions/workflows/release.yml" >&2; return 1; }
  echo "https://github.com/$REPO/actions/runs/$id"
  gh run watch "$id" -R "$REPO" --exit-status --interval 30
}

if yes "Do a dry run on GitHub Actions first?"; then
  run_release true || { echo "dry run failed; nothing published" >&2; exit 1; }
fi
yes "Build and publish $VERSION on GitHub Actions now?" || { echo "stopped; nothing published"; exit 0; }
run_release false
git pull -q --rebase origin main
say "Done: https://github.com/$REPO/releases/tag/v$VERSION"
