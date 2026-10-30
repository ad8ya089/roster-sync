#!/usr/bin/env bash
set -euo pipefail
umask 077
export DEBIAN_FRONTEND=noninteractive AWS_REGION=ap-southeast-2 AWS_DEFAULT_REGION=ap-southeast-2
# Swap must exist before package installation or the memory-heavy first image build.
if [[ ! -f /swapfile ]]; then
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
fi
swapon --show=NAME --noheadings | grep -Fx /swapfile >/dev/null || swapon /swapfile
grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
apt-get update
apt-get install -y ca-certificates curl git jq unzip dnsutils cron
install -m 0755 -d /etc/apt/keyrings
curl -fsSL --retry 5 https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
. /etc/os-release
cat > /etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: $VERSION_CODENAME
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
install -d /etc/docker
printf '%s\n' '{"log-driver":"json-file","log-opts":{"max-size":"10m","max-file":"3"}}' > /etc/docker/daemon.json
systemctl enable docker
systemctl restart docker
systemctl enable --now cron
if ! command -v aws >/dev/null; then
  arch=$(uname -m)
  work=$(mktemp -d)
  curl -fsSL --retry 5 "https://awscli.amazonaws.com/awscli-exe-linux-$arch.zip" -o "$work/aws.zip"
  unzip -q "$work/aws.zip" -d "$work"
  "$work/aws/install"
fi
if [[ ! -d /opt/roster-sync/.git ]]; then
  git clone https://github.com/ad8ya089/roster-sync.git /opt/roster-sync
fi
cd /opt/roster-sync
# Redirect SDK/CLI secret responses into a root-only file; never echo/source credentials.
envfile=$(mktemp /run/rostersync-env.XXXXXX)
trap 'rm -f "$envfile"' EXIT
for name in JWT_SECRET POSTGRES_PASSWORD DEMO_PASSWORD; do
  printf '%s=' "$name" >> "$envfile"
  aws ssm get-parameter --region "$AWS_REGION" --name "/rostersync/$name" \
    --with-decryption --query Parameter.Value --output text >> "$envfile"
done
cat >> "$envfile" <<'EOF'
POSTGRES_USER=roster
POSTGRES_DB=rostersync
DOMAIN=rostersync-aditya.duckdns.org
AWS_REGION=ap-southeast-2
ROSTER_DATA_DIR=/data
COMPOSE_FILE=/opt/roster-sync/deploy/aws-ec2/compose.prod.yml
COMPOSE_PROJECT_NAME=rostersync
EOF
chmod 600 "$envfile"
install -m 0600 "$envfile" /opt/roster-sync/.env
cat > /etc/cron.d/rostersync-backup <<'EOF'
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
15 17 * * * root /opt/roster-sync/deploy/aws-ec2/backup.sh >> /var/log/rostersync-backup.log 2>&1
EOF
cat > /etc/logrotate.d/rostersync-backup <<'EOF'
/var/log/rostersync-backup.log {
    weekly
    rotate 4
    compress
    missingok
    notifempty
}
EOF
echo 'bootstrap.build_started (Standard CPU credits: first build can be slow)'
docker compose --env-file .env -f deploy/aws-ec2/compose.prod.yml build
# Start only the private services. The DNS hand-off script starts Caddy later.
docker compose --env-file .env -f deploy/aws-ec2/compose.prod.yml up -d --no-build --wait db app
touch /var/lib/rostersync-bootstrap-complete
echo 'bootstrap.complete Caddy remains stopped pending DuckDNS verification'
