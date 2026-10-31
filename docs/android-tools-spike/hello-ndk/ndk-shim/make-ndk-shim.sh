#!/data/data/app.pocket/files/usr/bin/bash
# Spike only (docs/android-ndk-spike.md): lays out Termux's clang, ndk-sysroot and the phone's own system libraries as
# the NDK folder the Android Gradle Plugin expects. Needs: pkg install clang cmake ninja ndk-sysroot
set -eu
NDK="${1:-$PREFIX/opt/android-ndk-shim}"
HERE="$(cd "$(dirname "$0")" && pwd)"
# Must equal the project's android.ndkVersion, or AGP 9.5's default (28.2.13676358) when the project sets none.
REV="${NDK_SHIM_REVISION:-28.2.13676358}"

rm -rf "$NDK"
mkdir -p "$NDK/meta" "$NDK/build/cmake"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
SR="$TC/sysroot"
mkdir -p "$TC/bin" "$SR/usr/lib"
# The plugin picks the host folder from the OS name, so an arm64 Linux-looking host still asks for linux-x86_64.
ln -s linux-x86_64 "$NDK/toolchains/llvm/prebuilt/linux-arm64"

printf 'Pkg.Desc = PocketRocket spike: Termux clang behind the NDK layout\nPkg.Revision = %s\n' "$REV" >"$NDK/source.properties"
printf '{"min":21,"max":36,"aliases":{}}\n' >"$NDK/meta/platforms.json"
printf '{"arm64-v8a":{"bitness":64,"default":true,"deprecated":false,"proc":"aarch64","arch":"arm64","triple":"aarch64-linux-android","llvm_triple":"aarch64-none-linux-android"}}\n' >"$NDK/meta/abis.json"
printf '{}\n' >"$NDK/meta/system_libs.json"

# clang looks for its resource dir beside the path it was called by; the sample's -no-canonical-prefixes keeps that path.
mkdir -p "$TC/lib"
ln -s "$PREFIX/lib/clang" "$TC/lib/clang"
for b in clang clang++ ld.lld llvm-strip llvm-objcopy llvm-ar llvm-ranlib llvm-nm llvm-readelf; do
  ln -s "$PREFIX/bin/$b" "$TC/bin/$b"
done

# Linker wrapper: Termux's clang always adds -rpath=<sysroot>/usr/lib, a path that means nothing outside this app.
cat >"$TC/bin/ld-no-rpath" <<EOF
#!$PREFIX/bin/bash
args=()
for a in "\$@"; do case "\$a" in -rpath=*) ;; *) args+=("\$a") ;; esac; done
exec "$PREFIX/bin/ld.lld" "\${args[@]}"
EOF
chmod 700 "$TC/bin/ld-no-rpath"

# Sysroot: headers from Termux. Libraries are only the crt objects and libc++ from Termux, and the phone's own system
# libraries, so no Termux library in \$PREFIX/lib can be linked by accident.
ln -s "$PREFIX/include" "$SR/usr/include"
for f in crtbegin_so.o crtend_so.o crtbegin_dynamic.o crtend_android.o crt_pad_segment.o libunwind.a libatomic.a libcompiler_rt-extras.a libc++_shared.so; do
  ln -s "$PREFIX/lib/$f" "$SR/usr/lib/$f"
done
for l in c m dl log z android EGL GLESv1_CM GLESv2 GLESv3 OpenSLES jnigraphics mediandk nativewindow vulkan aaudio binder_ndk; do
  [ -e "/system/lib64/lib$l.so" ] && ln -s "/system/lib64/lib$l.so" "$SR/usr/lib/lib$l.so"
done
ln -s . "$SR/usr/lib/aarch64-linux-android"

cp "$HERE/android.toolchain.cmake" "$NDK/build/cmake/android.toolchain.cmake"
echo "NDK shim ready: $NDK ($(du -sk "$NDK" | cut -f1) KB)"
