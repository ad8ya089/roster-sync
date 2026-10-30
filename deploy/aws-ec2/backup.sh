#!/usr/bin/env bash
set -euo pipefail
cd /opt/roster-sync
export AWS_REGION=ap-southeast-2 AWS_DEFAULT_REGION=ap-southeast-2
BUCKET=rostersync-backups-687971794741-ap-southeast-2
key="backups/rostersync-$(date -u +%Y%m%dT%H%M%SZ).sql.gz"
# pipefail prevents reporting success when pg_dump or gzip fails. No credentials are printed.
docker compose --env-file .env -f deploy/aws-ec2/compose.prod.yml exec -T db \
  pg_dump --username=roster --dbname=rostersync --no-owner --no-acl |
  gzip | aws s3 cp - "s3://$BUCKET/$key" --region "$AWS_REGION" --only-show-errors
printf 'backup.uploaded s3://%s/%s\n' "$BUCKET" "$key"
