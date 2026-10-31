#!/usr/bin/env bash
# Builds vdisplay.dex with any Android SDK: build.sh <platform android.jar> <d8> [out dir]
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
jar="$1"; d8="$2"; out="${3:-$here/out}"
mkdir -p "$out"
javac -nowarn -source 11 -target 11 -cp "$jar" -d "$out" "$here/VDisplay.java"
"$d8" --lib "$jar" --output "$out" --min-api 30 "$out"/VDisplay*.class
mv "$out/classes.dex" "$out/vdisplay.dex"
ls -l "$out/vdisplay.dex"
