#!/data/data/app.pocket/files/usr/bin/bash
# fetch-termux-deb.sh <arch> <package> <out-dir>
# (Copied from spike/ndk-gaps; TERMUX_REPO, TERMUX_DIST, TERMUX_COMP pick the repo, e.g. termux-glibc, glibc, stable.)
# Downloads one .deb for any Termux architecture (aarch64, arm, i686, x86_64) without dpkg --add-architecture,
# checking the same chain apt checks: InRelease signed by a key in apt's trusted.gpg.d, the Packages file's SHA256
# from the signed InRelease, the .deb's SHA256 from that Packages file. Prints "<sha256>  <file>" on success.
set -euo pipefail
arch=$1 pkg=$2 out=$3
repo=${TERMUX_REPO:-https://packages-cf.termux.dev/apt/termux-main}
dist=${TERMUX_DIST:-stable} comp=${TERMUX_COMP:-main}
keys=${TERMUX_KEYRING_DIR:-$PREFIX/etc/apt/trusted.gpg.d}
mkdir -p "$out"
work=$(mktemp -d "$out/.index.XXXXXX"); trap 'rm -rf "$work"' EXIT

curl -fsSL "$repo/dists/$dist/InRelease" -o "$work/InRelease"
keyring_args=(); for k in "$keys"/*.gpg; do keyring_args+=(--keyring "$k"); done
# Only the signed text is used from here on; anything outside the signature is ignored.
gpgv "${keyring_args[@]}" --output - "$work/InRelease" >"$work/Release" 2>"$work/gpgv.log" || { cat "$work/gpgv.log" >&2; exit 1; }
grep -m1 "Good signature" "$work/gpgv.log" >&2 || true

idx="$comp/binary-$arch/Packages"
want=$(awk -v f="$idx" '/^SHA256:/{s=1;next} /^[A-Za-z]/{s=0} s && $3==f {print $1}' "$work/Release")
[ -n "$want" ] || { echo "no SHA256 for $idx in signed Release" >&2; exit 1; }
curl -fsSL "$repo/dists/$dist/$idx" -o "$work/Packages"
got=$(sha256sum "$work/Packages" | cut -d' ' -f1)
[ "$got" = "$want" ] || { echo "Packages hash mismatch: $got != $want" >&2; exit 1; }

read -r file sha size < <(awk -v p="$pkg" '
  /^Package: /{cur=$2} cur==p && /^Filename: /{f=$2} cur==p && /^SHA256: /{s=$2} cur==p && /^Size: /{z=$2}
  /^$/{ if (cur==p && f!="") { print f, s, z; exit } cur="" }' "$work/Packages")
[ -n "${file:-}" ] || { echo "$pkg not in $idx" >&2; exit 1; }
deb="$out/$(basename "$file")"
curl -fsSL "$repo/$file" -o "$deb"
got=$(sha256sum "$deb" | cut -d' ' -f1)
[ "$got" = "$sha" ] || { rm -f "$deb"; echo "$deb hash mismatch: $got != $sha" >&2; exit 1; }
echo "$sha  $deb  ($size bytes)"
