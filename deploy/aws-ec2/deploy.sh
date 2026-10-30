#!/usr/bin/env bash
set -euo pipefail
cd /opt/roster-sync
git pull --ff-only origin main
# Build first: a failed build leaves the current runtime untouched.
docker compose --env-file .env -f deploy/aws-ec2/compose.prod.yml build
docker compose --env-file .env -f deploy/aws-ec2/compose.prod.yml up -d --no-build --wait
