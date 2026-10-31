GROUP="Utilities"; SOURCE="termux pkg"; PKGS="fzf"; BINS="fzf"
install() { apt_add fzf; }
probe() { printf 'apple\nbanana\ncherry\n' | fzf -f nan | need '^banana$'; }
