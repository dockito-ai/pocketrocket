GROUP="Languages"; SOURCE="termux pkg"; PKGS="kotlin"; BINS="kotlinc"
install() { apt_add kotlin; }
probe() {
  printf 'fun main() { println("hello kotlin") }\n' >a.kt
  kotlinc a.kt -include-runtime -d a.jar && java -jar a.jar | need 'hello kotlin'
}
