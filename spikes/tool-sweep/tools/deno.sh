GROUP="Languages"; SOURCE="termux pkg"; PKGS="deno"; BINS="deno"
install() { apt_add deno; }
probe() {
  printf 'const x: number = 6 * 7;\nconsole.log("answer", x);\n' >a.ts
  deno run a.ts | need 'answer 42'
}
