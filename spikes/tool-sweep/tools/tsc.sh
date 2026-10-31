GROUP="JS tooling"; SOURCE="npm"; PKGS=""; BINS="tsc"
install() { npm_add typescript; }
probe() {
  printf 'const n: number = 41 + 1;\nconsole.log("n=" + n);\n' >a.ts
  npm_run tsc --outDir out --target es2020 a.ts
  node out/a.js | need 'n=42'
}
