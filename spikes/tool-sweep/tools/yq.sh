GROUP="Utilities"; SOURCE="termux pkg"; PKGS="yq"; BINS="yq"
install() { apt_add yq; }
probe() {
  printf 'a:\n  b: [1, 2, 3]\n' >a.yml
  yq '.a.b[1]' a.yml | need '^2$'
  yq -o=json '.a' a.yml | need '"b"'
}
