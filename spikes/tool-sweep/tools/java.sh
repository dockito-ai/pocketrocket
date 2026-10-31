GROUP="Languages"; SOURCE="termux pkg"; PKGS="openjdk-21"; BINS="java javac"
install() { apt_add openjdk-21; }
probe() {
  printf 'public class Hello { public static void main(String[] a) { System.out.println("hello java"); } }\n' >Hello.java
  javac Hello.java && java -cp . Hello | need 'hello java'
}
