GROUP="Databases"; SOURCE="termux pkg"; PKGS="sqlite"; BINS="sqlite3"
install() { apt_add sqlite; }
probe() {
  sqlite3 t.db "create table t(a int, b text); insert into t values (1,'x'),(2,'y');"
  sqlite3 t.db 'select sum(a) from t;' | need '^3$'
}
