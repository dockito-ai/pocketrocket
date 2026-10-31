GROUP="Utilities"; SOURCE="termux pkg"; PKGS="tmux"; BINS="tmux"
install() { apt_add tmux; }
probe() {
  # Deliberately no TMUX_TMPDIR: tests what a session gets by default (cause B if it wants /tmp).
  local s="sweep$$"
  trap "tmux kill-session -t $s 2>/dev/null" RETURN
  tmux new-session -d -s "$s" 'echo hello-tmux; sleep 30'
  sleep 1
  tmux capture-pane -p -t "$s" | need 'hello-tmux'
}
