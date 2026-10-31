GROUP="Utilities"; SOURCE="termux pkg"; PKGS="make"; BINS="make"
install() { apt_add make; }
probe() {
  printf 'out.txt:\n\techo built > out.txt\n' >Makefile
  make >/dev/null && need '^built$' <out.txt
}
