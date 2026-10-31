GROUP="Cloud / deploy"; SOURCE="official linux-arm64 (glibc)"; PKGS="unzip"; BINS="terraform"
# HashiCorp's own release; no Termux package. Version from their checkpoint API, checked against SHA256SUMS.
install() {
  local v base f
  v="$(curl -fsS https://checkpoint-api.hashicorp.com/v1/check/terraform | jq -r .current_version)"
  case "$v" in [0-9]*.[0-9]*.[0-9]*) ;; *) return 1 ;; esac
  base="https://releases.hashicorp.com/terraform/$v"; f="terraform_${v}_linux_arm64.zip"
  fetch "$base/$f" "$DL/tf.zip"; fetch "$base/terraform_${v}_SHA256SUMS" "$DL/sums"
  sha256_check "$DL/tf.zip" "$(awk -v f="$f" '$2==f{print $1}' "$DL/sums")"
  mkdir -p "$TOOL_DIR/x" && unzip -q -o "$DL/tf.zip" terraform -d "$TOOL_DIR/x"
  glibc_bin "$TOOL_DIR/x/terraform" terraform
}
probe() {
  export TF_DATA_DIR="$WORK/tfdata" TF_IN_AUTOMATION=1 CHECKPOINT_DISABLE=1
  printf 'resource "terraform_data" "x" { input = "hi" }\noutput "o" { value = terraform_data.x.output }\n' >main.tf
  terraform init -input=false >/dev/null
  terraform apply -auto-approve -input=false | need 'o = "hi"'
}
