GROUP="Languages"; SOURCE="termux pkg"; PKGS="python python-pip"; BINS="python pip"
install() { apt_add python python-pip; }
probe() {
  printf 'import sys, json\nprint(json.dumps({"sum": sum(range(10)), "v": sys.version_info[0]}))\n' >a.py
  python a.py | need '"sum": 45'
  python -m venv v && ./v/bin/pip --version >/dev/null
  ./v/bin/python -c 'import sys; print(sys.prefix)' | need '/v$'
}
