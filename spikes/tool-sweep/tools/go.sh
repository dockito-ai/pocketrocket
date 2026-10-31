GROUP="Languages"; SOURCE="termux pkg"; PKGS="golang"; BINS="go"
install() { apt_add golang; }
probe() {
  export GOCACHE="$WORK/gocache" GOPATH="$WORK/gopath" GOTMPDIR="$WORK/gotmp"; mkdir -p "$GOTMPDIR"
  printf 'package main\nimport "fmt"\nfunc main() { fmt.Println("hello go") }\n' >main.go
  go build -o hello main.go && ./hello | need 'hello go'
}
