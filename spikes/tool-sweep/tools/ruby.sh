GROUP="Languages"; SOURCE="termux pkg"; PKGS="ruby"; BINS="ruby gem"
install() { apt_add ruby; }
probe() {
  printf 'require "json"\nputs JSON.generate({sum: (1..10).sum})\n' >a.rb
  ruby a.rb | need '"sum":55'
  gem list >/dev/null
}
