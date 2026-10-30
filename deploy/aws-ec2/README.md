# Sydney deployment — live and verified

Region is final: `ap-southeast-2` (Sydney), using profile `moveinsync-dev` and retaining the Free plan. The accepted additional latency from India is approximately 150 ms, a planning estimate rather than a measured SLA. See [the approval plan](../../docs/DEPLOYMENT.md#sydney-resource-approval-plan).

The approved deployment is live. provision.py uses the authenticated AWS CLI and private temporary JSON requests; secret values never enter command arguments or logs. target/aws-deployment-state.json records resource IDs, never secret values. Do not use the provisioner to create additional deployments outside this approved plan.

The production Compose file is standalone; do not merge docker-compose.yml into it. It contains only app, db and Caddy. API 8080 and PostgreSQL have no host publication; management 8081 binds only 127.0.0.1. Caddy exposes TCP 80/443 and blocks actuator paths. No monitoring services run on the instance. The interview profile is intentionally prod,demo, with OPERATOR-only dev endpoints.

Bootstrap installs Docker Engine/Compose from Docker's Ubuntu repository, ensure SSM Agent is running, create/persist a 2 GB swap file, clone the public repository under /opt/roster-sync, and fetches the three SSM SecureStrings into a root-owned mode 0600 .env without printing values. JVM heap is 768 MiB; Postgres shared_buffers 256 MB. Budget notifications use the separately supplied private recipient; no email address or credential is stored here.

The instance role attaches AmazonSSMManagedInstanceCore and the rendered instance-policy.template.json. The managed policy already grants broad parameter reads, so the explicit deny prevents reading parameters outside the project's Sydney hierarchy. This role does not support general patch-management workflows that rely on other Parameter Store hierarchies. S3 rights are limited to writing/aborting uploads below this backup bucket's backups/ prefix; no read, list or delete rights are granted. Use Standard SecureString with the AWS-managed aws/ssm key; no customer KMS key is proposed.

Deployment build/start order (bootstrap starts db/app only until DNS is confirmed):

```bash
cd /opt/roster-sync
docker compose --env-file .env -f deploy/aws-ec2/compose.prod.yml build
docker compose --env-file .env -f deploy/aws-ec2/compose.prod.yml up -d --no-build --wait
```

The human updates rostersync-aditya.duckdns.org to the approved Elastic IP. Wait for DNS before verifying Caddy's automatically issued HTTPS certificate. No DuckDNS API token is required.

Private management access (local AWS CLI and Session Manager plugin required):

```bash
aws ssm start-session --profile moveinsync-dev --region ap-southeast-2 \
  --target <INSTANCE_ID> --document-name AWS-StartPortForwardingSession \
  --parameters '{"portNumber":["8081"],"localPortNumber":["18081"]}'
curl http://127.0.0.1:18081/actuator/health/readiness
```

Teardown will require explicit authorization: take/verify a backup, terminate the named instance, release its Elastic IP, remove the project's IAM/SSM/budget resources, and decide whether to retain or delete the S3 backups. No force-push, broad resource deletion or account-plan upgrade is part of this proposal.

Approved deployment changes: swap is enabled before building, Docker is enabled at boot, daemon and per-service logs rotate at 10m/3 files, and the host builds before Compose up. t4g.small Standard has no launch credits and a 20% baseline per vCPU; the first build can be slow. Bootstrap starts db/app only; start-caddy.sh requires matching DNS. The light demo skips only the 200k seed/recon; run on the instance over SSM to retain the real crash step without exposing a Docker socket. Public root responds with service identification and valid HTTPS. Acceptance also checks closed ports22/5432/8080/8081 from the user's machine, reboot recovery and a manually verified S3 backup.

Retrieve the demo password yourself in a private terminal (do not paste its output into chat or recordings):
```bash
aws ssm get-parameter --profile moveinsync-dev --region ap-southeast-2 \
  --name /rostersync/DEMO_PASSWORD --with-decryption --query Parameter.Value --output text
```
[Pause/teardown commands](TEARDOWN.md) are documented for the named deployment.

Verified 2026-10-30: HTTPS root 200, trusted Let's Encrypt issuerYE2, HTTP 308redirect; from the user's machine22/5432/8080/8081unreachable and443reachable. The actual DEMO_AUTO=1 DEMO_LIGHT=1 public-HTTPS run overSSM passed, including real exit137/crash/redelivery.200ksteps were skipped. A password scan of its log returned no match. Reboot throughSSM changed the kernel bootID and restored all three services/readiness without manualup; readiness UPat 87 seconds uptime.

Backups run daily at 17:15UTC (22:45IST). The scheduled5188-byte backup and manually triggered13084-byte backup both appeared in the private bucket; S3 reports AES256encryption. Logrotation is configured both in Docker daemon.json and per service. Swap 2 GBandDocker enabled-at-boot survive reboot.

Live IDs: instance i-0fd988840111e4f37; Elastic IP 54.253.159.60; allocation eipalloc-0dde410f8f5b98b56; SG sg-069a1da5e3620f86b; encrypted20GiBroot vol-0a7a654823faa9fa7. The live instance uses StandardCPUcredits and onlyapp/db/Caddy.
