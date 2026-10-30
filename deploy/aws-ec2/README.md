# Sydney deployment proposal — not launched

Region is final: `ap-southeast-2` (Sydney), using profile `moveinsync-dev` and retaining the Free plan. The accepted additional latency from India is approximately 150 ms, a planning estimate rather than a measured SLA. See [the approval plan](../../docs/DEPLOYMENT.md#sydney-resource-approval-plan).

These local files make the runtime and IAM proposal reviewable. They create no AWS resources. Bootstrap, deployment, backup scheduling and secret provisioning will be implemented/executed only within the approved resource plan. Do not launch this proposal before approval.

The production Compose file is standalone; do not merge docker-compose.yml into it. It contains only app, db and Caddy. API 8080 and PostgreSQL have no host publication; management 8081 binds only 127.0.0.1. Caddy exposes TCP 80/443 and blocks actuator paths. No monitoring services run on the instance. The interview profile is intentionally prod,demo, with OPERATOR-only dev endpoints.

After approval, bootstrap will install Docker Engine/Compose from Docker's Ubuntu repository, ensure SSM Agent is running, create/persist a 2 GB swap file, clone the public repository under /opt/roster-sync, and fetch the three SSM SecureStrings into a root-owned mode 0600 .env without printing values. JVM heap is 768 MiB; Postgres shared_buffers 256 MB. Budget notifications use the separately supplied private recipient; no email address or credential is stored here.

The instance role attaches AmazonSSMManagedInstanceCore and the rendered instance-policy.template.json. The managed policy already grants broad parameter reads, so the explicit deny prevents reading parameters outside the project's Sydney hierarchy. This role does not support general patch-management workflows that rely on other Parameter Store hierarchies. S3 rights are limited to writing/aborting uploads below this backup bucket's backups/ prefix; no read, list or delete rights are granted. Use Standard SecureString with the AWS-managed aws/ssm key; no customer KMS key is proposed.

After secure bootstrap, the service start command will be:

```bash
cd /opt/roster-sync
docker compose --env-file .env -f deploy/aws-ec2/compose.prod.yml up -d --build --wait
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
