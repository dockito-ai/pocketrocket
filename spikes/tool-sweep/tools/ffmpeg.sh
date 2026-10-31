GROUP="Utilities"; SOURCE="termux pkg"; PKGS="ffmpeg"; BINS="ffmpeg ffprobe"
install() { apt_add ffmpeg; }
probe() {
  ffmpeg -loglevel error -f lavfi -i testsrc=duration=1:size=64x64:rate=10 -c:v mpeg4 out.mp4
  ffprobe -v error -show_entries stream=width -of csv=p=0 out.mp4 | need '^64$'
}
