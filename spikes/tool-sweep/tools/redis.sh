GROUP="Databases"; SOURCE="termux pkg"; PKGS="redis"; BINS="redis-server redis-cli"
install() { apt_add redis; }
probe() {
  redis-server --port 0 --unixsocket "$WORK/r.sock" --dir "$WORK" --save "" --daemonize no >"$WORK/r.log" 2>&1 &
  local pid=$!; trap "bg_cleanup $pid" RETURN
  for _ in $(seq 20); do [ -S "$WORK/r.sock" ] && break; sleep 0.5; done
  redis-cli -s "$WORK/r.sock" set k v >/dev/null
  redis-cli -s "$WORK/r.sock" get k | need '^v$'
}
