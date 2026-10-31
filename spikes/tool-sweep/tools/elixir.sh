GROUP="Languages"; SOURCE="termux pkg"; PKGS="elixir"; BINS="elixir"
install() { apt_add elixir; }
probe() {
  printf 'IO.puts("sum #{Enum.sum(1..10)}")\n' >a.exs
  elixir a.exs | need 'sum 55'
}
