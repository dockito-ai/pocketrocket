#!/data/data/app.pocket/files/usr/bin/bash
# flutter-dual.sh <flutter args...>
# Runs the Flutter tool the way bin/flutter's last line does, but keeps an exec helper in the Flutter tool's own
# process (glibc) and passes one to its children (bionic), so Flutter can start android/gradlew
# (#!/usr/bin/env bash). Needs POCKETROCKET_PRELOAD=<make-preload.sh output>.
# The Flutter folder is FLUTTER_ROOT, else the version in the nearest .fvmrc under ~/fvm/versions.
# Run the normal `flutter` launcher once first (it builds the tool and prepares downloaded glibc tools).
set -euo pipefail
: "${POCKETROCKET_PRELOAD:?run make-preload.sh and set POCKETROCKET_PRELOAD}"
if [ -z "${FLUTTER_ROOT:-}" ]; then
  d=$PWD
  while [ "$d" != / ] && [ ! -f "$d/.fvmrc" ]; do d=$(dirname "$d"); done
  [ -f "$d/.fvmrc" ] || { echo "no .fvmrc above $PWD; set FLUTTER_ROOT" >&2; exit 1; }
  v=$(sed -n 's/.*"flutter"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$d/.fvmrc")
  FLUTTER_ROOT=$HOME/fvm/versions/$v
fi
export FLUTTER_ROOT
dart=$FLUTTER_ROOT/bin/cache/dart-sdk/bin
export LD_PRELOAD=$POCKETROCKET_PRELOAD
exec "$dart/dartvm.glibc" --resolved_executable_name="$dart/dart" --executable_name=dart \
  --packages="$FLUTTER_ROOT/packages/flutter_tools/.dart_tool/package_config.json" \
  "$FLUTTER_ROOT/bin/cache/flutter_tools.snapshot" "$@"
