#!/data/data/app.pocket/files/usr/bin/bash
# gen-stub.sh <real-lib.so> <clang-target> <out.so> [clang]
# Builds a link-only stub of a system library for any ABI from the phone's arm64 copy: the same exported names,
# soname and default symbol versions, empty bodies. The NDK's own stubs are made the same way (from symbol lists).
set -euo pipefail
lib=$1 target=$2 out=$3 cc=${4:-clang}
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
soname=$(llvm-readelf -d "$lib" | sed -n 's/.*(SONAME).*\[\(.*\)\]/\1/p')
# posix format: name type value size. Only default versions (@@) and unversioned names; old @VER aliases are skipped.
llvm-nm -D --defined-only --format=posix "$lib" | awk '$1 !~ /[^@]@[^@]/ {print}' >"$work/syms"
i=0
while read -r name type value size; do
  sym=${name%%@@*}; ver=; [ "$sym" != "$name" ] && ver=${name#*@@}
  [ "$sym" = "$ver" ] && continue # the version definition symbol itself
  case $ver in *PRIVATE|*PLATFORM) continue ;; esac # not public API; Google's stubs leave these out too
  case $type in
    T|t|W|i) printf 'void f%d(void) __asm__("%s"); void f%d(void) {}\n' $i "$sym" $i ;;
    D|d|B|b|R|r|V|v) printf 'char d%d[%d] __asm__("%s");\n' $i $((16#${size:-1} > 0 ? 16#${size:-1} : 1)) "$sym" ;;
    *) continue ;;
  esac >>"$work/stub.c"
  echo "${ver:-_NOVER} $sym" >>"$work/vers"
  i=$((i+1))
done <"$work/syms"
[ -s "$work/stub.c" ] || { echo "no symbols in $lib" >&2; exit 1; }
# Version script: one node per version.
awk '{v[$1]=v[$1] "    " $2 ";\n"} END {
  if ("_NOVER" in v && length(v) == 1) { printf "{\n  global:\n%s  local: *;\n};\n", v["_NOVER"]; exit }
  # A versioned library: unversioned names (LLVM internals) are left out, as lld cannot mix the two.
  for (k in v) if (k != "_NOVER") printf "%s {\n  global:\n%s};\n", k, v[k] }' "$work/vers" >"$work/vers.map"
"$cc" --target="$target" -fPIC -w -fno-builtin -nostdlib -shared -Wl,-soname,"$soname" \
  -Wl,--version-script,"$work/vers.map" -Wl,--undefined-version -o "$out" "$work/stub.c"
