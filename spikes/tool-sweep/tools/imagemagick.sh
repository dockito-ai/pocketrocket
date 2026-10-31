GROUP="Utilities"; SOURCE="termux pkg"; PKGS="imagemagick"; BINS="magick convert"
install() { apt_add imagemagick; }
probe() {
  magick -size 20x10 xc:red a.png
  magick identify -format '%wx%h' a.png | need '^20x10$'
}
