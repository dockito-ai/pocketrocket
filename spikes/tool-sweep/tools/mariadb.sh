GROUP="Databases"; SOURCE="termux pkg"; PKGS="mariadb"; BINS="mariadbd mariadb"
install() { apt_add mariadb; }
probe() {
  mariadb-install-db --no-defaults --datadir="$WORK/my" --auth-root-authentication-method=normal >"$WORK/init.log" 2>&1
  mariadbd --no-defaults --datadir="$WORK/my" --socket="$WORK/my.sock" --skip-networking --tmpdir="$WORK" --pid-file="$WORK/my.pid" >"$WORK/my.log" 2>&1 &
  local pid=$!; trap "bg_cleanup $pid" RETURN
  for _ in $(seq 40); do [ -S "$WORK/my.sock" ] && break; sleep 0.5; done
  mariadb --no-defaults -uroot -S "$WORK/my.sock" -N -e 'create database d; create table d.t(a int); insert into d.t values (1),(2); select sum(a) from d.t;' | need '^3$'
}
