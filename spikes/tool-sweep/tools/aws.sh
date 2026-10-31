GROUP="Cloud / deploy"; SOURCE="termux pkg"; PKGS="awscli"; BINS="aws"
install() { apt_add awscli; }
probe() {
  export AWS_CONFIG_FILE="$WORK/awsconf" AWS_SHARED_CREDENTIALS_FILE="$WORK/awscred"
  aws s3 ls s3://noaa-ghcn-pds/ --no-sign-request --region us-east-1 | need 'PRE|csv'
}
