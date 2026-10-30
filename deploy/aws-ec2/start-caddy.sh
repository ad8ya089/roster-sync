#!/usr/bin/env bash
set -euo pipefail
cd /opt/roster-sync
domain=rostersync-aditya.duckdns.org
expected=${1:?Pass the allocated Elastic IP}
# DNS is an explicit human hand-off; never start ACME against an unconfirmed address.
nslookup "$domain"
getent ahostsv4 "$domain" | awk '{print $1}' | sort -u | grep -Fx "$expected" >/dev/null || {
  echo "DNS does not resolve $domain to $expected; Caddy remains stopped" >&2; exit 1;
}
docker compose --env-file .env -f deploy/aws-ec2/compose.prod.yml up -d --no-build --wait
