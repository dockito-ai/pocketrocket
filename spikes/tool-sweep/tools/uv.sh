GROUP="Languages"; SOURCE="termux pkg"; PKGS="uv"; BINS="uv"
install() { apt_add uv; }
probe() {
  export UV_CACHE_DIR="$DL/uv-cache"
  uv venv v && uv pip install --python v/bin/python six
  ./v/bin/python -c 'import six; print(six.__version__)'
}
