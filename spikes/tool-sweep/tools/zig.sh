GROUP="Languages"; SOURCE="termux pkg"; PKGS="zig"; BINS="zig"
install() { apt_add zig; }
probe() {
  export ZIG_GLOBAL_CACHE_DIR="$WORK/zc" ZIG_LOCAL_CACHE_DIR="$WORK/zl"
  printf 'const std = @import("std");\npub fn main() void { std.debug.print("hello zig\\n", .{}); }\n' >a.zig
  zig build-exe a.zig && ./a 2>&1 | need 'hello zig'
}
