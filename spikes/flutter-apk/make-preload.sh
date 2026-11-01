#!/data/data/app.pocket/files/usr/bin/bash
# make-preload.sh <out-dir>
# Builds one LD_PRELOAD value that works in bionic AND glibc programs, so a glibc program (Dart, the Flutter
# tool) and every bionic child it starts (bash running gradlew, java) each get their own exec helper.
#
# glibc's loader expands $PLATFORM in LD_PRELOAD (to "aarch64"); bionic's linker takes it literally. So
#   LD_PRELOAD='<out>/$PLATFORM/1.so:<out>/$PLATFORM/2.so'
# loads, in bionic programs, <out>/$PLATFORM/{1,2}.so: Termux's termux-exec and an empty library;
# in glibc programs, <out>/aarch64/{1,2}.so: Termux's termux-exec-glibc and libpocketrocket-exec.so
# (glibc-execvp.c: execvp/execv on top of the exported execve, which glibc's own execvp bypasses).
# Prints the LD_PRELOAD value.
set -euo pipefail
out=$1 here=$(cd "$(dirname "$0")" && pwd)
bionic_exec=$PREFIX/lib/libtermux-exec-ld-preload.so
glibc_exec=$PREFIX/glibc/lib/libtermux-exec.so
[ -f "$glibc_exec" ] || { echo "needs termux-exec-glibc (pkg install termux-exec-glibc)" >&2; exit 1; }
mkdir -p "$out/aarch64" "$out/\$PLATFORM"
clang --target=aarch64-linux-gnu -O2 -fPIC -shared -nostdlib -fno-stack-protector \
  -o "$out/libpocketrocket-exec.so" "$here/glibc-execvp.c"
printf 'void pocketrocket_empty(void) {}\n' >"$out/empty.c"
clang -O2 -fPIC -shared -nostdlib -o "$out/libempty-bionic.so" "$out/empty.c"
ln -sfn "$glibc_exec" "$out/aarch64/1.so"
ln -sfn "$out/libpocketrocket-exec.so" "$out/aarch64/2.so"
ln -sfn "$bionic_exec" "$out/\$PLATFORM/1.so"
ln -sfn "$out/libempty-bionic.so" "$out/\$PLATFORM/2.so"
printf '%s\n' "$out/\$PLATFORM/1.so:$out/\$PLATFORM/2.so"
