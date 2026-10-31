GROUP="Cloud / deploy"; SOURCE="official linux-arm64 (glibc)"; PKGS="python"; BINS="gcloud"
# Official google-cloud-cli arm tarball; it needs a system python (CLOUDSDK_PYTHON).
install() {
  fetch "https://dl.google.com/dl/cloudsdk/channels/rapid/downloads/google-cloud-cli-linux-arm.tar.gz" "$DL/gcloud.tgz"
  mkdir -p "$TOOL_DIR/x" && tar -xzf "$DL/gcloud.tgz" -C "$TOOL_DIR/x" --strip-components=1
  ln -sf "$TOOL_DIR/x/bin/gcloud" "$TOOL_DIR/bin/gcloud"
}
probe() {
  export CLOUDSDK_PYTHON="$(command -v python)" CLOUDSDK_CONFIG="$WORK/gc"
  gcloud config set project sweep-test --quiet >/dev/null 2>&1
  gcloud config list 2>&1 | need 'sweep-test'
}
