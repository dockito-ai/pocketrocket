#!/data/data/app.pocket/files/usr/bin/bash
# fetch-ndk-build.sh <out-dir>
# Google's ndk-build and build/ makefiles from AOSP platform/ndk at tag ndk-r28c (NDK 28.2.13676358), Apache 2.0,
# plain text. A shallow, sparse git fetch of one pinned commit: git checks every object it receives, and the tree
# of build/ is compared with the pinned hash, so a changed file cannot slip through.
# (The gitiles +archive tarball is not byte-stable, two downloads gave two SHA256s, so it cannot be pinned by hash.)
set -euo pipefail
out=$1
url=https://android.googlesource.com/platform/ndk
commit=805836ef53231ef86dd5fcc5652a1c28d74f4bc4 # refs/tags/ndk-r28c, peeled
tree=dd78e5f2c69d1dded3bece4debc182624c2effe3   # <commit>:build
[ ! -e "$out" ] || { echo "$out exists" >&2; exit 1; }
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
git -C "$work" init -q
git -C "$work" fetch -q --depth 1 --filter=blob:none "$url" "$commit"
[ "$(git -C "$work" rev-parse FETCH_HEAD)" = "$commit" ]
[ "$(git -C "$work" rev-parse "FETCH_HEAD:build")" = "$tree" ] || { echo "build/ tree differs from the pinned one" >&2; exit 1; }
mkdir -p "$out"
git -C "$work" archive FETCH_HEAD build | tar -x -C "$out" --strip-components=1
echo "ndk-build r28c ($commit, build/ tree $tree) in $out: $(du -sh "$out" | cut -f1)"
