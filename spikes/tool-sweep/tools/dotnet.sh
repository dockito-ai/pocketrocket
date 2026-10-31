GROUP="Languages"; SOURCE="termux pkg"; PKGS="dotnet-sdk-9.0"; BINS="dotnet"
install() { apt_add dotnet-sdk-9.0; }
probe() {
  export DOTNET_CLI_HOME="$WORK/dn" DOTNET_NOLOGO=1 DOTNET_CLI_TELEMETRY_OPTOUT=1
  dotnet new console -o app --no-restore >/dev/null
  dotnet run --project app | need 'Hello, World'
}
