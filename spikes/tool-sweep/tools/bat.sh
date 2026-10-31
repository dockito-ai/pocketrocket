GROUP="Utilities"; SOURCE="termux pkg"; PKGS="bat"; BINS="bat"
install() { apt_add bat; }
probe() {
  printf 'fn main() {}\n' >a.rs
  bat --color=always --style=plain a.rs | need 'main'
}
