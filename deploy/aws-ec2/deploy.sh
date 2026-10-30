#!/usr/bin/env bash
set -euo pipefail
cd /opt/roster-sync
git pull --ff-only origin main
if [[ -n ${EXPECTED_COMMIT:-} && $(git rev-parse HEAD) != "$EXPECTED_COMMIT" ]]; then
  echo 'Repository head changed; refuse to deploy an unreviewed revision.' >&2; exit 1
fi
swapon --show=NAME --noheadings | grep -Fxq /swapfile || {
  echo 'Enable the existing 2 GB swap file before building.' >&2; exit 1;
}
# Build first: a failed build leaves the current runtime untouched.
docker compose --env-file .env -f deploy/aws-ec2/compose.prod.yml build
docker compose --env-file .env -f deploy/aws-ec2/compose.prod.yml up -d --no-build --wait db app
# Git may replace the Caddyfile inode. Recreate the single-file bind mount so
# Caddy reads the new routing rules; named certificate volumes remain intact.
docker compose --env-file .env -f deploy/aws-ec2/compose.prod.yml up -d --no-build --no-deps --force-recreate caddy
docker compose --env-file .env -f deploy/aws-ec2/compose.prod.yml exec -T caddy caddy validate --config /etc/caddy/Caddyfile
