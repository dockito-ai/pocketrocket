GROUP="Cloud / deploy"; SOURCE="official linux-arm64 (glibc)"; PKGS=""; BINS="supabase"
# The npm package downloads this same release in a postinstall (cause D territory), so take it directly.
install() {
  fetch "https://github.com/supabase/cli/releases/latest/download/supabase_linux_arm64.tar.gz" "$DL/supabase.tgz"
  mkdir -p "$TOOL_DIR/x" && tar -xzf "$DL/supabase.tgz" -C "$TOOL_DIR/x" supabase
  glibc_bin "$TOOL_DIR/x/supabase" supabase
}
probe() {
  export SUPABASE_HOME="$WORK/sh" HOME="$WORK/home"; mkdir -p "$HOME"
  supabase init --workdir "$WORK/proj" >/dev/null 2>&1 || { mkdir -p proj && supabase init --workdir proj; }
  [ -f "$WORK/proj/supabase/config.toml" ]
}
