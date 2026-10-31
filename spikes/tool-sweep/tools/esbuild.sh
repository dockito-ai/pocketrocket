GROUP="JS tooling"; SOURCE="termux pkg"; PKGS="esbuild"; BINS="esbuild"
install() { apt_add esbuild; }
probe() {
  printf 'const x: number = 6 * 7;\nconsole.log("answer", x);\n' >a.ts
  esbuild a.ts --bundle --outfile=out.js --log-level=warning
  node out.js | need 'answer 42'
}
