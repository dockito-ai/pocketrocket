GROUP="Cloud / deploy"; SOURCE="termux pkg"; PKGS="flyctl"; BINS="flyctl fly"
install() { apt_add flyctl; }
probe() {
  export FLY_CONFIG_DIR="$WORK/fly" HOME="$WORK"
  printf 'app = "sweep-test"\nprimary_region = "iad"\n[http_service]\n  internal_port = 8080\n' >fly.toml
  flyctl config validate --local-only 2>&1 | need -i 'valid|configuration'
}
