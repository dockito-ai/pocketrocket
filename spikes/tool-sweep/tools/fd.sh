GROUP="Utilities"; SOURCE="termux pkg"; PKGS="fd"; BINS="fd"
install() { apt_add fd; }
probe() {
  mkdir -p d/e && touch d/e/needle.txt d/hay.md
  fd needle d | need 'needle.txt'
}
