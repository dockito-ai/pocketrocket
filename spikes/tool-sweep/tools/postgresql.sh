GROUP="Databases"; SOURCE="termux pkg"; PKGS="postgresql"; BINS="postgres psql"
install() { apt_add postgresql; }
probe() {
  initdb -D "$WORK/pg" >/dev/null
  pg_ctl -D "$WORK/pg" -o "-k $WORK -c listen_addresses=''" -l "$WORK/pg.log" -w start
  trap 'pg_ctl -D "$WORK/pg" -m immediate stop >/dev/null 2>&1' RETURN
  psql -h "$WORK" -d postgres -Atc 'create table t(a int); insert into t values (1),(2); select sum(a) from t;' | need '^3$'
}
