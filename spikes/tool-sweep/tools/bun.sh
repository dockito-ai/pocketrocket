GROUP="Languages"; SOURCE="termux pkg"; PKGS="bun"; BINS="bun"
install() { apt_add bun; }
probe() {
  printf 'const x: number = 6 * 7;\nconsole.log("answer", x);\n' >a.ts
  bun run a.ts | need 'answer 42'
  bun build a.ts --outfile out.js >/dev/null && bun out.js | need 'answer 42'
}
