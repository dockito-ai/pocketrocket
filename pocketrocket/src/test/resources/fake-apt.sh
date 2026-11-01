# A pretend dpkg and apt for the setup script's uninstall tests, sourced after it. FAKE_APT is a folder holding
# `installed` (one package per line) and `deps` ("<package> <what it depends on>" per line). Purging a package takes
# every installed package that depends on it, as apt does; real purges are appended to `purged`.

dpkg() { [ "$1" = -s ] && grep -qxF "$2" "$FAKE_APT/installed"; }

# dpkg-query -W -f=<format> <pattern>: "ii  <name>" for each installed package matching the pattern.
dpkg-query() {
  local pat="${*: -1}" p
  case "$pat" in *'$'*) pat='*' ;; esac
  while read -r p; do
    # shellcheck disable=SC2254
    case "$p" in $pat) printf 'ii  %s\n' "$p" ;; esac
  done <"$FAKE_APT/installed"
}

fake_apt_closure() {
  local set=" $* " changed=1 a b p
  while [ "$changed" = 1 ]; do
    changed=0
    while read -r a b; do
      grep -qxF "$a" "$FAKE_APT/installed" || continue
      case "$set" in *" $b "*) case "$set" in *" $a "*) ;; *) set="$set$a "; changed=1 ;; esac ;; esac
    done <"$FAKE_APT/deps"
  done
  for p in $set; do grep -qxF "$p" "$FAKE_APT/installed" && printf '%s\n' "$p"; done | sort -u
}

apt-get() {
  local sim=0 a pk=()
  shift
  for a in "$@"; do
    case "$a" in -s) sim=1 ;; -y | -o | Dpkg::*) ;; *) pk+=("$a") ;; esac
  done
  if [ "$sim" = 1 ]; then
    fake_apt_closure "${pk[@]}" | sed 's/^/Purg /'
  else
    fake_apt_closure "${pk[@]}" >"$FAKE_APT/gone"
    cat "$FAKE_APT/gone" >>"$FAKE_APT/purged"
    grep -vxF -f "$FAKE_APT/gone" "$FAKE_APT/installed" >"$FAKE_APT/left" || true
    mv "$FAKE_APT/left" "$FAKE_APT/installed"
  fi
}
