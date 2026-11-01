#!/data/data/app.pocket/files/usr/bin/bash
# make-shim.sh <out> <aosp-ndk-build-dir> <ndk-multilib-usr> [revision]
# Prototype of a fuller NDK shim: the installed add-on's layout plus
#   - Google's ndk-build and build/core makefiles (AOSP platform/ndk, pinned), patched for an arm64 host,
#   - four ABIs (arm64-v8a, armeabi-v7a, x86, x86_64) from Termux's ndk-multilib: crt objects, libunwind,
#     libc++ (shared and static), compiler-rt builtins, API 24 stub system libraries,
#   - clang wrappers that pick the shim's resource dir (cross builtins) and drop Termux's rpath.
# <ndk-multilib-usr> is the usr folder of ndk-multilib (extracted .deb, or $PREFIX once the package is installed).
set -euo pipefail
out=$1 ndk_src=$2 ml=$3 rev=${4:-28.2.13676358}
major=${rev%%.*} minor=$(echo "$rev" | cut -d. -f2)
here=$(cd "$(dirname "$0")" && pwd)
clang_ver=$(basename "$("$PREFIX/bin/clang" -print-resource-dir)")

rm -rf "$out"; mkdir -p "$out"
tc="$out/toolchains/llvm/prebuilt/linux-x86_64"; sr="$tc/sysroot"
mkdir -p "$out/meta" "$tc/bin" "$sr/usr/lib"
ln -s linux-x86_64 "$out/toolchains/llvm/prebuilt/linux-arm64"
printf 'Pkg.Desc = PocketRocket spike: Termux clang behind the NDK layout, not Google NDK %s\nPkg.Revision = %s\n' "$rev" "$rev" >"$out/source.properties"

# abi triple llvm-triple proc arch bitness
ABIS="arm64-v8a aarch64-linux-android aarch64-none-linux-android aarch64 arm64 64
armeabi-v7a arm-linux-androideabi armv7-none-linux-androideabi armv7-a arm 32
x86 i686-linux-android i686-none-linux-android i686 x86 32
x86_64 x86_64-linux-android x86_64-none-linux-android x86_64 x86_64 64"
STUB_LIBS="c m dl log android EGL GLESv1_CM GLESv2 GLESv3 OpenSLES OpenMAXAL vulkan"
# Not in ndk-multilib: stubs are generated from the phone's arm64 library's symbol list.
GEN_LIBS="z jnigraphics mediandk nativewindow aaudio camera2ndk binder_ndk sync"

# --- meta, read by the Gradle plugin and CMake
{
  printf '{'; first=1
  while read -r abi tr ll proc arch bits; do
    [ $first = 1 ] || printf ','; first=0
    printf '"%s":{"bitness":%s,"default":true,"deprecated":false,"proc":"%s","arch":"%s","triple":"%s","llvm_triple":"%s"}' \
      "$abi" "$bits" "$proc" "$arch" "$tr" "$ll"
  done <<<"$ABIS"
  printf '}\n'
} >"$out/meta/abis.json"
printf '{"min":21,"max":36,"aliases":{"20":21,"25":24,"J":16,"K":19,"L":21,"M":23,"N":24,"O":26,"P":28,"Q":29,"R":30,"S":31,"T":33,"U":34,"V":35}}\n' >"$out/meta/platforms.json"
{ printf '{'; first=1; for l in $STUB_LIBS $GEN_LIBS; do [ $first = 1 ] || printf ','; first=0; printf '"lib%s.so":"all"' "$l"; done; printf '}\n'; } >"$out/meta/system_libs.json"

# --- build/: Google's ndk-build, plus the files the NDK's own build generates from meta/
cp -r "$ndk_src" "$out/build"
sed -i "1s|^#!.*|#!$PREFIX/bin/bash|" "$out/build/ndk-build"
# uname -m is aarch64 on Android; Google's script knows only arm64 (macOS).
sed -i 's/^  arm64) HOST_ARCH=arm64;;/  arm64|aarch64) HOST_ARCH=arm64;;/' "$out/build/tools/ndk_bin_common.sh"
grep -q 'arm64|aarch64' "$out/build/tools/ndk_bin_common.sh"
core="$out/build/core"
printf 'NDK_MAJOR := %s\nNDK_MINOR := %s\nNDK_BETA := 0\nNDK_CANARY := false\n' "$major" "$minor" >"$core/version.mk"
printf 'NDK_DEFAULT_ABIS := armeabi-v7a arm64-v8a x86 x86_64\nNDK_DEPRECATED_ABIS :=\nNDK_KNOWN_DEVICE_ABI32S := armeabi-v7a x86\nNDK_KNOWN_DEVICE_ABI64S := arm64-v8a x86_64\n' >"$core/abis.mk"
{ printf 'NDK_MIN_PLATFORM_LEVEL := 21\nNDK_MAX_PLATFORM_LEVEL := 36\n'
  for p in 20:21 25:24 J:16 K:19 L:21 M:23 N:24 O:26 P:28 Q:29 R:30 S:31 T:33 U:34 V:35; do
    printf 'NDK_PLATFORM_ALIAS_%s := android-%s\n' "${p%%:*}" "${p##*:}"; done; } >"$core/platforms.mk"
{ printf 'NDK_SYSTEM_LIBS :='; for l in $STUB_LIBS $GEN_LIBS; do printf ' lib%s.so' "$l"; done; printf '\n'; } >"$core/system_libs.mk"
cm="$out/build/cmake"
{ printf 'set(NDK_DEFAULT_ABIS "armeabi-v7a;arm64-v8a;x86;x86_64")\nset(NDK_DEPRECATED_ABIS "")\n'
  printf 'set(NDK_KNOWN_DEVICE_ABI32S "armeabi-v7a;x86")\nset(NDK_KNOWN_DEVICE_ABI64S "arm64-v8a;x86_64")\n'
  printf 'set(NDK_KNOWN_DEVICE_ABIS "arm64-v8a;x86_64;armeabi-v7a;x86")\n'; } >"$cm/abis.cmake"
while read -r abi tr ll proc arch bits; do
  printf 'NDK_ABI_%s_MIN_OS_VERSION := 21\n' "$abi" >>"$core/abis.mk"
  printf 'set(NDK_ABI_%s_PROC "%s")\nset(NDK_ABI_%s_ARCH "%s")\nset(NDK_ABI_%s_TRIPLE "%s")\nset(NDK_ABI_%s_LLVM_TRIPLE "%s")\nset(NDK_ABI_%s_MIN_OS_VERSION "21")\n' \
    "$abi" "$proc" "$abi" "$arch" "$abi" "$tr" "$abi" "$ll" "$abi" >>"$cm/abis.cmake"
done <<<"$ABIS"
sed -e 's/^\([A-Z_0-9]*\) := \(.*\)$/set(\1 "\2")/' "$core/platforms.mk" >"$cm/platforms.cmake"
rm -rf "$core/toolchains/riscv64-linux-android-clang" # no riscv64 libraries in ndk-multilib
printf '#!%s/bin/sh\nexec "$(dirname "$0")/build/ndk-build" "$@"\n' "$PREFIX" >"$out/ndk-build"; chmod 700 "$out/ndk-build"
# Our CMake toolchain, four ABIs (the installed add-on's file, widened). Google's own stays beside it for comparison.
mv "$out/build/cmake/android.toolchain.cmake" "$out/build/cmake/google-android.toolchain.cmake"
cp "$here/android.toolchain.cmake" "$out/build/cmake/android.toolchain.cmake"

# --- toolchain binaries
for b in ld.lld llvm-strip llvm-objcopy llvm-ar llvm-ranlib llvm-nm llvm-readelf llvm-objdump clang-tidy; do
  [ -e "$PREFIX/bin/$b" ] && ln -s "$PREFIX/bin/$b" "$tc/bin/$b"
done
cat >"$tc/bin/ld-no-rpath" <<EOF
#!$PREFIX/bin/bash
# Termux's clang always adds -rpath=<sysroot>/...; that path means nothing in an APK.
args=()
for a in "\$@"; do case "\$a" in -rpath=*) ;; *) args+=("\$a") ;; esac; done
exec "$PREFIX/bin/ld.lld" "\${args[@]}"
EOF
for c in clang clang++; do
  cat >"$tc/bin/$c" <<EOF
#!$PREFIX/bin/bash
# The shim's sysroot unless the caller names one (ndk-build links without --sysroot, and Termux clang would use
# $PREFIX); the shim's resource dir, which holds compiler-rt for every ABI (Termux's has aarch64 only).
tc="\$(cd "\$(dirname "\$0")/.." && pwd)"
exec "$PREFIX/bin/$c" --sysroot="\$tc/sysroot" -resource-dir="\$tc/lib/clang/$clang_ver" --ld-path="\$tc/bin/ld-no-rpath" -Qunused-arguments "\$@"
EOF
done
chmod 700 "$tc/bin/ld-no-rpath" "$tc/bin/clang" "$tc/bin/clang++"

# --- resource dir: Termux's headers, Termux's aarch64 runtimes and ndk-multilib's cross runtimes
rd="$tc/lib/clang/$clang_ver"; mkdir -p "$rd/lib/linux"
for d in include share; do [ -e "$PREFIX/lib/clang/$clang_ver/$d" ] && ln -s "$PREFIX/lib/clang/$clang_ver/$d" "$rd/$d"; done
for f in "$PREFIX/lib/clang/$clang_ver/lib/linux/"* "$ml/opt/ndk-multilib/cross-compiler-rt/"*; do
  [ -e "$rd/lib/linux/$(basename "$f")" ] || ln -s "$f" "$rd/lib/linux/$(basename "$f")"
done

# --- sysroot: headers (ndk-sysroot already holds every ABI's asm headers) and one lib folder per ABI
ln -s "$PREFIX/include" "$sr/usr/include"
while read -r abi tr ll proc arch bits; do
  d="$sr/usr/lib/$tr"; mkdir -p "$d"
  for f in "$ml/$tr/lib/"*; do ln -s "$f" "$d/"; done
  for l in $STUB_LIBS; do ln -s "$ml/opt/ndk-multilib/$tr/lib/lib$l.so" "$d/lib$l.so"; done
  for l in $GEN_LIBS; do
    [ -e "/system/lib64/lib$l.so" ] || continue
    "$here/gen-stub.sh" "/system/lib64/lib$l.so" "${ll}24" "$d/lib$l.so" "$tc/bin/clang" 2>/dev/null || echo "stub lib$l.so for $abi failed" >&2
  done
done <<<"$ABIS"
# The installed add-on put arm64 files straight in usr/lib; keep that working for the old toolchain file.
for f in "$sr/usr/lib/aarch64-linux-android/"*; do ln -s "aarch64-linux-android/$(basename "$f")" "$sr/usr/lib/"; done
echo "shim ready: $out ($(du -sh "$out" | cut -f1), symlinks to $ml)"
