GROUP="Known gaps"; SOURCE="termux pkg"; PKGS="rust rust-std-wasm32-unknown-unknown"; BINS="rustc"
install() { apt_add rust rust-std-wasm32-unknown-unknown; }
probe() {
  printf '#[no_mangle]\npub extern "C" fn add(a: i32, b: i32) -> i32 { a + b }\n' >lib.rs
  rustc --target wasm32-unknown-unknown --crate-type cdylib -O lib.rs -o out.wasm
  [ -s out.wasm ] && head -c4 out.wasm | od -An -c | need '\\0\s+a\s+s\s+m'
}
