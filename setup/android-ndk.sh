#!/data/data/app.pocket/files/usr/bin/bash
# The Android NDK add-on's shim (decisions 50 and 96, docs/android-build.md). pocketrocket-setup.sh sources this file
# for --only android-ndk; it also runs alone, which is how it is tested into a scratch folder:
#
#   android-ndk.sh fetch-ndk-build <out>             Google's ndk-build and build/ from AOSP, pinned and checked
#   android-ndk.sh fetch-multilib <work>             Termux's ndk-multilib .deb through apt, unpacked; prints its usr folder
#   android-ndk.sh shim <out> <ndk-build> <multilib-usr>   the whole shim, from the two above
#   android-ndk.sh stub <lib.so> <clang target> <out.so> [clang]   one link-only stub of a phone library
#   android-ndk.sh hook <file> <shim> <placeholder>  the Gradle init script
#
# Google publishes no arm64 NDK, so the shim is Termux's clang behind folders laid out as the NDK, with:
#   - Google's ndk-build script and build/core makefiles (text, Apache 2.0), fetched from AOSP platform/ndk at the
#     release below and checked against its git tree hash. Never Google's x86_64 binaries.
#   - four ABIs (arm64-v8a, armeabi-v7a, x86, x86_64): crt objects, libunwind, libatomic, libc++ shared and static
#     and compiler-rt builtins from Termux's ndk-multilib, which apt downloads and checks; the package is not installed.
#   - link-only stubs of the system libraries, generated for every ABI from this phone's own /system/lib64, so what
#     links is what the phone has (one policy for all ABIs).
#   - clang wrappers that add the shim's sysroot and resource dir and drop Termux's rpath.
# docs/android-ndk-gaps-spike.md has the measurements and the why.

# The pinned AOSP release: tag ndk-r28c (NDK 28.2.13676358), its commit and the git tree of build/ in it.
NDK_BUILD_URL=https://android.googlesource.com/platform/ndk
NDK_BUILD_COMMIT=805836ef53231ef86dd5fcc5652a1c28d74f4bc4
NDK_BUILD_TREE=dd78e5f2c69d1dded3bece4debc182624c2effe3
NDK_REVISION=28.2.13676358
NDK_MULTILIB_PACKAGE=ndk-multilib
NDK_MIN_API=21
NDK_MAX_API=37

# abi, GNU triple (folder names), LLVM triple (clang --target), CMake processor, arch, bitness
NDK_ABIS="arm64-v8a aarch64-linux-android aarch64-none-linux-android aarch64 arm64 64
armeabi-v7a arm-linux-androideabi armv7-none-linux-androideabi armv7-a arm 32
x86 i686-linux-android i686-none-linux-android i686 x86 32
x86_64 x86_64-linux-android x86_64-none-linux-android x86_64 x86_64 64"
# The NDK's system libraries. Each gets a stub per ABI made from the phone's own copy; one the phone lacks is left out.
NDK_SYSTEM_LIBS="c m dl log android z EGL GLESv1_CM GLESv2 GLESv3 OpenSLES OpenMAXAL vulkan jnigraphics mediandk nativewindow aaudio amidi camera2ndk binder_ndk sync"
NDK_SYSTEM_LIB_DIR=${NDK_SYSTEM_LIB_DIR:-/system/lib64}
# clang's resource dir by a name that survives `pkg upgrade` (the clang package keeps `latest` pointing at its version).
NDK_CLANG_RESOURCE="$PREFIX/lib/clang/latest"

ndk_say() { printf '%s\n' "$*" >&2; }

# ndk_fetch_build <out>: a shallow, blob-less git fetch of the pinned commit. Git checks every object it receives,
# and build/'s tree must be the pinned one, so a changed file fails. (The gitiles tarball is not byte-stable, so it
# can't be pinned by its SHA-256.)
ndk_fetch_build() {
  local out="$1" work
  work="$(mktemp -d "${TMPDIR:-/tmp}/pocketrocket-ndk-build.XXXXXX")" || return 1
  if git -C "$work" init -q &&
    git -C "$work" fetch -q --depth 1 --filter=blob:none "$NDK_BUILD_URL" "$NDK_BUILD_COMMIT" &&
    [ "$(git -C "$work" rev-parse FETCH_HEAD)" = "$NDK_BUILD_COMMIT" ]; then
    if [ "$(git -C "$work" rev-parse 'FETCH_HEAD:build')" != "$NDK_BUILD_TREE" ]; then
      ndk_say "ndk-build from AOSP is not the pinned release (build/ tree differs)"; rm -rf "$work"; return 1
    fi
    rm -rf "$out"; mkdir -p "$out"
    if git -C "$work" archive FETCH_HEAD build | tar -x -C "$out" --strip-components=1; then rm -rf "$work"; return 0; fi
  fi
  ndk_say "could not fetch ndk-build from $NDK_BUILD_URL"; rm -rf "$work" "$out"; return 1
}

# ndk_multilib_info: "<file name> <size>" of Termux's ndk-multilib .deb, from apt's index.
ndk_multilib_info() {
  apt-cache show "$NDK_MULTILIB_PACKAGE" 2>/dev/null | awk '/^Filename:/ { n = split($2, p, "/"); f = p[n] } /^Size:/ { s = $2 } /^$/ && f != "" { print f, s; exit }'
}

# ndk_multilib_download <work dir>: Termux's ndk-multilib .deb into <work>, through apt, which checks it against the
# Packages index from the signed InRelease. The package is architecture `all`, so plain apt-get download works.
ndk_multilib_download() {
  mkdir -p "$1"
  rm -f "$1"/"$NDK_MULTILIB_PACKAGE"_*.deb
  (cd "$1" && apt-get download -q "$NDK_MULTILIB_PACKAGE") >&2 || { ndk_say "could not download $NDK_MULTILIB_PACKAGE"; return 1; }
}

# ndk_multilib_unpack <work dir>: unpacks the downloaded .deb into <work>/x (it is never installed: 433 MB as a
# package, of which the shim uses about 140) and prints the usr folder inside.
ndk_multilib_unpack() {
  local work="$1" deb usr
  deb="$(ls "$work"/"$NDK_MULTILIB_PACKAGE"_*.deb 2>/dev/null | head -1)"
  [ -n "$deb" ] || { ndk_say "$NDK_MULTILIB_PACKAGE: no .deb downloaded"; return 1; }
  rm -rf "$work/x"; mkdir -p "$work/x"
  dpkg-deb -x "$deb" "$work/x" || { ndk_say "could not unpack $deb"; return 1; }
  rm -f "$deb"
  usr="$(find "$work/x" -maxdepth 6 -type d -path '*/files/usr' | head -1)"
  [ -n "$usr" ] && [ -d "$usr/aarch64-linux-android/lib" ] || { ndk_say "$NDK_MULTILIB_PACKAGE has an unexpected layout"; return 1; }
  printf '%s\n' "$usr"
}

# ndk_stub_source <dir> <lib.so> [<more.so>]: writes <dir>/stub.c and <dir>/vers.map for a link-only stub of the
# library (same exported names, default symbol versions and soname, empty bodies) and prints its soname. Google makes
# its stubs the same way, from symbol lists. Old versions (name@VER) and PRIVATE or PLATFORM versions are left out, as
# in Google's. Names from <more.so> are added where the first has none: the phone is 64-bit only, so a 32-bit ABI's
# own names (armeabi-v7a's dl_unwind_find_exidx) come from ndk-multilib's stub for that ABI.
ndk_stub_source() {
  local dir="$1" lib="$2" soname f; shift
  soname="$(llvm-readelf -d "$lib" 2>/dev/null | sed -n 's/.*(SONAME).*\[\(.*\)\]/\1/p')"
  [ -n "$soname" ] || soname="$(basename "$lib")"
  for f in "$@"; do if [ -e "$f" ]; then llvm-nm -D --defined-only --format=posix "$f" 2>/dev/null; fi; done | awk -v src="$dir/stub.c" -v map="$dir/vers.map" '
    function hex(s,   i, n, c) { n = 0; s = tolower(s); for (i = 1; i <= length(s); i++) { c = index("0123456789abcdef", substr(s, i, 1)); if (!c) return 0; n = n * 16 + c - 1 } return n }
    $1 ~ /[^@]@[^@]/ { next }
    {
      name = $1; sym = name; ver = ""
      if ((at = index(name, "@@")) > 0) { sym = substr(name, 1, at - 1); ver = substr(name, at + 2) }
      if (sym == ver || ver ~ /(PRIVATE|PLATFORM)$/ || (sym in seen)) next
      seen[sym] = 1
      if ($2 ~ /^[TtWi]$/) printf "void f%d(void) __asm__(\"%s\"); void f%d(void) {}\n", n, sym, n > src
      else if ($2 ~ /^[DdBbRrVv]$/) { size = hex($4); printf "char d%d[%d] __asm__(\"%s\");\n", n, (size > 0 ? size : 1), sym > src }
      else next
      key = (ver == "" ? "_NOVER" : ver); names[key] = names[key] "    " sym ";\n"; n++
    }
    END {
      if (n == 0) exit 1
      versioned = 0; for (k in names) if (k != "_NOVER") versioned = 1
      # A versioned library: its few unversioned names (LLVM internals) are left out, as lld cannot mix the two.
      if (!versioned) printf "{\n  global:\n%s  local: *;\n};\n", names["_NOVER"] > map
      else for (k in names) if (k != "_NOVER") printf "%s {\n  global:\n%s};\n", k, names[k] > map
    }' || { ndk_say "no symbols in $lib"; return 1; }
  printf '%s\n' "$soname"
}

# ndk_stub_build <dir> <soname> <clang target> <out.so> [clang]: compiles a stub written by ndk_stub_source.
ndk_stub_build() {
  "${5:-clang}" --target="$3" -fPIC -w -fno-builtin -nostdlib -shared -Wl,-soname,"$2" \
    -Wl,--version-script,"$1/vers.map" -Wl,--undefined-version -o "$4" "$1/stub.c"
}

# ndk_stub <lib.so> <clang target> <out.so> [clang]: one stub, for one ABI.
ndk_stub() {
  local work soname rc=0
  work="$(mktemp -d "${TMPDIR:-/tmp}/pocketrocket-stub.XXXXXX")" || return 1
  soname="$(ndk_stub_source "$work" "$1")" && ndk_stub_build "$work" "$soname" "$2" "$3" "${4:-clang}" || rc=1
  rm -rf "$work"
  return $rc
}

# ndk_write_meta <shim>: what the Gradle plugin and CMake read: meta/*.json.
ndk_write_meta() {
  local shim="$1" abi tr ll proc arch bits first=1 l
  mkdir -p "$shim/meta"
  {
    printf '{'
    while read -r abi tr ll proc arch bits; do
      [ $first = 1 ] || printf ','; first=0
      printf '"%s":{"bitness":%s,"default":true,"deprecated":false,"proc":"%s","arch":"%s","triple":"%s","llvm_triple":"%s"}' \
        "$abi" "$bits" "$proc" "$arch" "$tr" "$ll"
    done <<<"$NDK_ABIS"
    printf '}\n'
  } >"$shim/meta/abis.json"
  printf '{"min":%s,"max":%s,"aliases":{"20":21,"25":24,"J":16,"K":19,"L":21,"M":23,"N":24,"O":26,"P":28,"Q":29,"R":30,"S":31,"T":33,"U":34,"V":35}}\n' \
    "$NDK_MIN_API" "$NDK_MAX_API" >"$shim/meta/platforms.json"
  { first=1; printf '{'; for l in $NDK_SYSTEM_LIBS; do [ $first = 1 ] || printf ','; first=0; printf '"lib%s.so":"all"' "$l"; done; printf '}\n'; } \
    >"$shim/meta/system_libs.json"
}

# ndk_install_build <shim> <ndk-build dir>: Google's build/ with what the NDK's own build would generate from meta/
# (the .mk and .cmake files), the one-line host fix, our CMake toolchain file and the root ndk-build script.
ndk_install_build() {
  local shim="$1" src="$2" here core cm abi tr ll proc arch bits p l
  here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  rm -rf "$shim/build"; cp -r "$src" "$shim/build" || return 1
  sed -i "1s|^#!.*|#!$PREFIX/bin/bash|" "$shim/build/ndk-build"
  # uname -m says aarch64 on Android; Google's script knows only arm64 (macOS).
  sed -i 's/^  arm64) HOST_ARCH=arm64;;/  arm64|aarch64) HOST_ARCH=arm64;;/' "$shim/build/tools/ndk_bin_common.sh"
  grep -q 'arm64|aarch64' "$shim/build/tools/ndk_bin_common.sh" || { ndk_say "ndk_bin_common.sh changed; cannot patch the host"; return 1; }
  core="$shim/build/core" cm="$shim/build/cmake"
  printf 'NDK_MAJOR := %s\nNDK_MINOR := %s\nNDK_BETA := 0\nNDK_CANARY := false\n' "${NDK_REVISION%%.*}" "$(echo "$NDK_REVISION" | cut -d. -f2)" >"$core/version.mk"
  printf 'NDK_DEFAULT_ABIS := armeabi-v7a arm64-v8a x86 x86_64\nNDK_DEPRECATED_ABIS :=\nNDK_KNOWN_DEVICE_ABI32S := armeabi-v7a x86\nNDK_KNOWN_DEVICE_ABI64S := arm64-v8a x86_64\n' >"$core/abis.mk"
  {
    printf 'NDK_MIN_PLATFORM_LEVEL := %s\nNDK_MAX_PLATFORM_LEVEL := %s\n' "$NDK_MIN_API" "$NDK_MAX_API"
    for p in 20:21 25:24 J:16 K:19 L:21 M:23 N:24 O:26 P:28 Q:29 R:30 S:31 T:33 U:34 V:35; do
      printf 'NDK_PLATFORM_ALIAS_%s := android-%s\n' "${p%%:*}" "${p##*:}"
    done
  } >"$core/platforms.mk"
  { printf 'NDK_SYSTEM_LIBS :='; for l in $NDK_SYSTEM_LIBS; do printf ' lib%s.so' "$l"; done; printf '\n'; } >"$core/system_libs.mk"
  {
    printf 'set(NDK_DEFAULT_ABIS "armeabi-v7a;arm64-v8a;x86;x86_64")\nset(NDK_DEPRECATED_ABIS "")\n'
    printf 'set(NDK_KNOWN_DEVICE_ABI32S "armeabi-v7a;x86")\nset(NDK_KNOWN_DEVICE_ABI64S "arm64-v8a;x86_64")\n'
    printf 'set(NDK_KNOWN_DEVICE_ABIS "arm64-v8a;x86_64;armeabi-v7a;x86")\n'
  } >"$cm/abis.cmake"
  while read -r abi tr ll proc arch bits; do
    # Without these, make stops inside gmsl: "invalid second argument to 'wordlist' function".
    printf 'NDK_ABI_%s_MIN_OS_VERSION := %s\n' "$abi" "$NDK_MIN_API" >>"$core/abis.mk"
    printf 'set(NDK_ABI_%s_PROC "%s")\nset(NDK_ABI_%s_ARCH "%s")\nset(NDK_ABI_%s_TRIPLE "%s")\nset(NDK_ABI_%s_LLVM_TRIPLE "%s")\nset(NDK_ABI_%s_MIN_OS_VERSION "%s")\n' \
      "$abi" "$proc" "$abi" "$arch" "$abi" "$tr" "$abi" "$ll" "$abi" "$NDK_MIN_API" >>"$cm/abis.cmake"
  done <<<"$NDK_ABIS"
  sed -e 's/^\([A-Z_0-9]*\) := \(.*\)$/set(\1 "\2")/' "$core/platforms.mk" >"$cm/platforms.cmake"
  rm -rf "$core/toolchains/riscv64-linux-android-clang" # ndk-multilib has no riscv64 libraries
  # Google's toolchain file needs Google's NDK (it checks Pkg.Desc, and CMake on Termux reports the host as Android).
  mv "$cm/android.toolchain.cmake" "$cm/google-android.toolchain.cmake"
  cp "$here/android-ndk.toolchain.cmake" "$cm/android.toolchain.cmake"
  printf '#!%s/bin/sh\nexec "$(dirname "$0")/build/ndk-build" "$@"\n' "$PREFIX" >"$shim/ndk-build"
  chmod 700 "$shim/ndk-build"
}

# ndk_install_toolchain <shim> <multilib-usr>: bin/ (wrappers and links) and the resource dir with every ABI's builtins.
ndk_install_toolchain() {
  local shim="$1" ml="$2" tc bin rd b c f
  tc="$shim/toolchains/llvm/prebuilt/linux-x86_64"; bin="$tc/bin"; rd="$tc/lib/clang/latest"
  mkdir -p "$bin" "$rd/lib/linux"
  # The plugin and ndk-build pick the host folder from the OS, so an arm64 host asks for linux-x86_64 or linux-arm64.
  ln -sfn linux-x86_64 "$shim/toolchains/llvm/prebuilt/linux-arm64"
  for b in ld.lld llvm-strip llvm-objcopy llvm-ar llvm-ranlib llvm-nm llvm-readelf llvm-objdump llvm-addr2line llvm-symbolizer clang-tidy clang-format; do
    [ -e "$PREFIX/bin/$b" ] && ln -s "$PREFIX/bin/$b" "$bin/$b"
  done
  cat >"$bin/ld-no-rpath" <<EOF
#!$PREFIX/bin/bash
# Termux's clang always adds -rpath=<its sysroot>, a path that means nothing in an APK. No flag removes it.
args=()
for a in "\$@"; do case "\$a" in -rpath=*) ;; *) args+=("\$a") ;; esac; done
exec "$PREFIX/bin/ld.lld" "\${args[@]}"
EOF
  for c in clang clang++; do
    cat >"$bin/$c" <<EOF
#!$PREFIX/bin/bash
# PocketRocket's Android NDK: Termux's clang with the shim's sysroot (unless the caller names one; ndk-build links
# without --sysroot, and Termux's clang would then use its own) and resource dir (compiler-rt for every ABI).
tc="\$(cd "\$(dirname "\$0")/.." && pwd)"
exec "$PREFIX/bin/$c" --sysroot="\$tc/sysroot" -resource-dir="\$tc/lib/clang/latest" --ld-path="\$tc/bin/ld-no-rpath" -Qunused-arguments "\$@"
EOF
  done
  chmod 700 "$bin/ld-no-rpath" "$bin/clang" "$bin/clang++"
  # Headers and aarch64 runtimes from Termux (through `latest`, so a clang upgrade keeps them), cross builtins copied.
  for b in include share; do ln -s "$NDK_CLANG_RESOURCE/$b" "$rd/$b"; done
  for f in "$NDK_CLANG_RESOURCE"/lib/linux/*; do ln -s "$NDK_CLANG_RESOURCE/lib/linux/$(basename "$f")" "$rd/lib/linux/"; done
  for f in "$ml"/opt/ndk-multilib/cross-compiler-rt/libclang_rt.builtins-*-android.a; do
    [ -e "$rd/lib/linux/$(basename "$f")" ] || cp "$f" "$rd/lib/linux/"
  done
}

# ndk_install_sysroot <shim> <multilib-usr>: Termux's headers and, per ABI, ndk-multilib's runtime files and stubs of
# the phone's system libraries (plus the ABI's own names from ndk-multilib's API 24 stub, see ndk_stub_source).
ndk_install_sysroot() {
  local shim="$1" ml="$2" sr cc work abi tr ll proc arch bits l soname missing=""
  sr="$shim/toolchains/llvm/prebuilt/linux-x86_64/sysroot"; cc="$shim/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
  mkdir -p "$sr/usr/lib"
  ln -s "$PREFIX/include" "$sr/usr/include"
  while read -r abi tr ll proc arch bits; do
    mkdir -p "$sr/usr/lib/$tr"
    cp -a "$ml/$tr/lib/." "$sr/usr/lib/$tr/" || { ndk_say "ndk-multilib has no $tr libraries"; return 1; }
  done <<<"$NDK_ABIS"
  work="$(mktemp -d "${TMPDIR:-/tmp}/pocketrocket-stubs.XXXXXX")" || return 1
  for l in $NDK_SYSTEM_LIBS; do
    if [ ! -e "$NDK_SYSTEM_LIB_DIR/lib$l.so" ]; then missing="$missing lib$l.so"; continue; fi
    while read -r abi tr ll proc arch bits; do
      rm -f "$work"/*
      soname="$(ndk_stub_source "$work" "$NDK_SYSTEM_LIB_DIR/lib$l.so" "$ml/opt/ndk-multilib/$tr/lib/lib$l.so")" &&
        ndk_stub_build "$work" "$soname" "${ll}${NDK_MIN_API}" "$sr/usr/lib/$tr/lib$l.so" "$cc" ||
        { ndk_say "could not make the lib$l.so stub for $abi"; rm -rf "$work"; return 1; }
    done <<<"$NDK_ABIS"
  done
  rm -rf "$work"
  [ -z "$missing" ] || ndk_say "not on this phone, so not linkable:$missing"
}

# ndk_make_shim <out> <ndk-build dir> <multilib-usr>: the whole shim, built beside <out> and moved in at the end, so a
# failure leaves the old one (or none) and never half of one. The plugin reads the revision from source.properties.
ndk_make_shim() {
  local out="$1" src="$2" ml="$3" new="$1.new"
  rm -rf "$new"; mkdir -p "$new"
  printf 'Pkg.Desc = PocketRocket: Termux clang behind the NDK layout, not Google NDK %s\nPkg.Revision = %s\n' "$NDK_REVISION" "$NDK_REVISION" >"$new/source.properties"
  ndk_write_meta "$new" &&
    ndk_install_toolchain "$new" "$ml" &&
    ndk_install_build "$new" "$src" &&
    ndk_install_sysroot "$new" "$ml" || { rm -rf "$new"; return 1; }
  rm -rf "$out"; mv "$new" "$out"
}

# ndk_write_hook <file> <shim> <placeholder>: the Gradle init script, from the template beside this file.
ndk_write_hook() {
  local here tmp
  here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  mkdir -p "$(dirname "$1")"; tmp="$1.tmp"
  sed -e "s|@POCKETROCKET_NDK_SHIM@|$2|g" -e "s|@POCKETROCKET_NDK_PLACEHOLDER@|$3|g" -e "s|@POCKETROCKET_NDK_REVISION@|$NDK_REVISION|g" \
    "$here/pocketrocket-android-ndk.gradle" >"$tmp" && mv -f "$tmp" "$1"
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  set -euo pipefail
  case "${1:-}" in
    fetch-ndk-build) ndk_fetch_build "$2" ;;
    fetch-multilib) ndk_multilib_download "$2" && ndk_multilib_unpack "$2" ;;
    shim) ndk_make_shim "$2" "$3" "$4" ;;
    stub) ndk_stub "$2" "$3" "$4" "${5:-clang}" ;;
    hook) ndk_write_hook "$2" "$3" "$4" ;;
    *) ndk_say "usage: $0 fetch-ndk-build <out> | fetch-multilib <work> | shim <out> <ndk-build> <multilib-usr> | stub <lib> <target> <out> [clang] | hook <file> <shim> <placeholder>"; exit 2 ;;
  esac
fi
