#!/data/data/app.pocket/files/usr/bin/bash
# wrap-gen-snapshot.sh <flutter-root> <x86_64-glibc-lib-dir>
# Flutter publishes Android gen_snapshot (the AOT compiler for release and profile builds) only for x86_64 Linux
# hosts (android-arm64-release/linux-x64.zip), and on an arm64 Linux host looks for it in .../linux-arm64/.
# This puts a wrapper there that runs the official x86_64 binary under qemu-user (Termux's qemu-user-x86-64),
# with Termux's x86_64 glibc (fetch-termux-deb.sh x86_64 glibc from termux-glibc, then dpkg-deb -x).
# The x86_64 binaries must already be downloaded: run `flutter precache --android` (or any android build) first.
set -euo pipefail
root=$1 lib=$2
qemu=$(command -v qemu-x86_64) || { echo "pkg install qemu-user-x86-64" >&2; exit 1; }
[ -f "$lib/ld-linux-x86-64.so.2" ] || { echo "no ld-linux-x86-64.so.2 in $lib" >&2; exit 1; }
for mode in android-arm64-release android-arm64-profile android-arm-release android-arm-profile; do
  dir=$root/bin/cache/artifacts/engine/$mode
  [ -f "$dir/linux-x64/gen_snapshot" ] || { echo "skip $mode: no linux-x64/gen_snapshot"; continue; }
  mkdir -p "$dir/linux-arm64"
  printf '#!%s\n# Written by a PocketRocket spike: the official x86_64 gen_snapshot under qemu-user.\nunset LD_PRELOAD\nexec %q %q --library-path %q %q "$@"\n' \
    "$PREFIX/bin/bash" "$qemu" "$lib/ld-linux-x86-64.so.2" "$lib" "$dir/linux-x64/gen_snapshot" >"$dir/linux-arm64/gen_snapshot"
  chmod +x "$dir/linux-arm64/gen_snapshot"
  echo "$dir/linux-arm64/gen_snapshot"
done
