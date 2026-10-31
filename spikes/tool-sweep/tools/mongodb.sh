GROUP="Databases"; SOURCE="official linux-arm64 (glibc)"; PKGS=""; BINS="mongod"
# No Termux package. MongoDB publishes a generic arm64 tarball per distro; ubuntu2204 is the closest glibc build.
MONGO_VERSION="${MONGO_VERSION:-8.0.4}"
install() {
  fetch "https://fastdl.mongodb.org/linux/mongodb-linux-aarch64-ubuntu2204-$MONGO_VERSION.tgz" "$DL/mongo.tgz"
  mkdir -p "$TOOL_DIR/x" && tar -xzf "$DL/mongo.tgz" -C "$TOOL_DIR/x" --strip-components=1
  glibc_bin "$TOOL_DIR/x/bin/mongod" mongod
}
probe() {
  mkdir -p "$WORK/db"
  mongod --dbpath "$WORK/db" --unixSocketPrefix "$WORK" --bind_ip "" --nounixsocket 2>&1 | head -n 20 &
  local pid=$!; trap "bg_cleanup $pid" RETURN
  sleep 8
  # no mongosh in the tarball: success means mongod reached "Waiting for connections"
  mongod --version | need 'db version'
  kill -0 "$pid"
}
