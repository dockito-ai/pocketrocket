GROUP="JS tooling"; SOURCE="npm"; PKGS=""; BINS="prisma"
install() { npm_add prisma; }
probe() {
  export PRISMA_HIDE_UPDATE_MESSAGE=1 CHECKPOINT_DISABLE=1
  printf 'datasource db {\n  provider = "sqlite"\n  url = "file:./dev.db"\n}\nmodel User {\n  id Int @id @default(autoincrement())\n  name String\n}\n' >schema.prisma
  npm_run prisma validate --schema schema.prisma | need 'valid'
  npm_run prisma format --schema schema.prisma
}
