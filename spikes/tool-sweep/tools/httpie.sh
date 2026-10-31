GROUP="Utilities"; SOURCE="termux pkg"; PKGS="httpie"; BINS="http https"
install() { apt_add httpie; }
probe() {
  export HTTPIE_CONFIG_DIR="$WORK/httpie"
  http --check-status --print=h GET https://example.com/ | need 'HTTP/.* 200'
}
