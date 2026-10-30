# Deployment

Two goals, in this order:
1. **One-command local run** (required by the brief): `docker compose up`.
2. **A live URL** (a plus point): the same containers on AWS, deployed in a way that is cheap, reliable for an interview window, and easy to defend.

---

## 1. Local (required)

```
docker-compose.yml
├── db   postgres:17-alpine
│        healthcheck: pg_isready -U $POSTGRES_USER -d rostersync   (interval 5s, retries 20)
│        volume: pgdata
└── app  build: .  (multi-stage Dockerfile)
         depends_on: db: condition: service_healthy
         restart: unless-stopped            ← makes the kill -9 demo self-heal
         env_file: .env
         ports: 8080:8080, 127.0.0.1:8081:8081
         volume: appdata:/data              ← uploaded files + spill files
         healthcheck: curl --fail --silent 127.0.0.1:8081/actuator/health/readiness
```

**Profiles:** local Compose defaults to `demo`. Ordinary production uses `prod` alone and excludes demo fixtures/dev endpoints. The interview environment deliberately runs `prod,demo`; dev endpoints are restricted to OPERATOR once implemented.

**Dockerfile (multi-stage):**
- Stage 1 `maven:3.9-eclipse-temurin-21` — copy `pom.xml` first and run `mvn -B -q dependency:go-offline` (layer cache), then copy `src` and `mvn -B -DskipTests package`.
- Stage 2 `eclipse-temurin:21-jre` (multi-arch: works on x86 laptops and ARM/Graviton) — non-root user, `JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"`, `EXPOSE 8080 8081`.
- Nobody needs Java or Maven installed.

The runtime image installs curl for its readiness check and runs as UID/GID 10001, which owns `/data`.

**First run on a fresh clone:**
```bash
cp .env.example .env        # defaults work for local
docker compose up --build   # Flyway creates schema; demo seeder creates 2 tenants, offices, users, a few employees, bookings, seats
curl 127.0.0.1:8081/actuator/health/readiness
```
Tests need Docker (Testcontainers) — say so in the README. Before recording the video, verify on a clean machine or a clean clone with `docker compose down -v` first.

Optional: set `GRAFANA_ADMIN_PASSWORD` in `.env`, then `docker compose --profile monitoring up -d`. Prometheus scrapes `app:8081/actuator/prometheus`; Grafana has the provisioned Roster Sync Operations dashboard. Their host ports 9090/3000 bind only to loopback. Default Compose starts only app and db. All actuator endpoints are permitAll on management 8081; none exist on API 8080.

---

## 2. AWS — every realistic option, compared

Assumptions: one Spring Boot container (needs ~0.5–1 GB RAM), one Postgres, a background scheduler (outbox poller every 500 ms), a background worker that streams a 25–100 MB CSV to local disk and runs for tens of seconds, and an interview window of a few weeks. Chosen region: **Sydney (`ap-southeast-2`)**, final. About **150ms added latency from India is accepted** for this interview deployment; this is an accepted estimate, not a measured latency guarantee. All regional resources and deployment commands use Sydney. Prices for the chosen EC2 design are checked against Sydney rates below; alternative architectures are not part of this approval request.

| Option | Fit for this service | Decision |
|---|---|---|
| EC2 + app/db/Caddy | Same container artifact, local files and single worker | Proposed below, priced in Sydney |
| EC2 + RDS | Managed backups and separated database availability | Later production option; requires its own Sydney estimate |
| ECS/Fargate + RDS | Requires shared uploads, scheduler coordination and different ingress | Later production option; outside this approval |
| Lightsail / Beanstalk | Alternate VM packaging | Outside this approval |
| Lambda | Requires an ingestion/background-work redesign | Poor match to the current service |
| EKS | Adds a cluster to a single-service exercise | Deliberately omitted |

### Why not Lambda (have this answer ready)
1. **Background work doesn't fit the request/response model.** The outbox poller runs every 500 ms forever; reconciliation runs for tens of seconds after the upload returns 202. On Lambda you'd move the poller to an EventBridge schedule (minimum 1-minute rate → event lag goes from 0.5 s to ~60 s) and the reconciliation into Step Functions / an S3-triggered function.
2. **Payload and time limits.** Synchronous Lambda invocations cap the request payload at about 6 MB — a 200k-row CSV (~25 MB) can't be uploaded through the API; you'd need presigned S3 uploads. Functions max out at 15 minutes.
3. **Database connections.** Hundreds of concurrent Lambda instances each opening Postgres connections exhaust the DB; you'd add RDS Proxy (more cost and config).
4. **Cold starts** for Spring Boot are seconds; SnapStart reduces this a lot, but it's another moving part to explain.
5. **The brief values a one-command run.** Lambda splits one app into several deployables, so the cloud no longer matches the local `docker compose up`.
So: Lambda is excellent for spiky, stateless, short handlers; this service is a long-running, stateful-ish worker with a DB-backed queue. Containers on a VM or Fargate fit better.

### Database: container on EC2 vs RDS
| | Postgres container on the EC2 box | RDS PostgreSQL |
|---|---|---|
| Parity with local | identical | different endpoint/params only |
| Backups | you do it: nightly `pg_dump` → S3 (cron) | automated backups + point-in-time restore |
| Failure of the instance | app and DB down together; EBS volume survives | DB survives app instance loss; Multi-AZ optional |
| Cost | Included in the proposed EC2 sizing | Requires a separate Sydney RDS quote |
| Interview line | "Fine for a demo; in prod I'd move to RDS for PITR and Multi-AZ" | "Separated state from compute" |

### Recommendation
- **Ship Option A** (EC2 t4g.small + docker compose + Caddy, Postgres in a container on gp3 EBS, nightly `pg_dump` to S3). It's the cheapest and the easiest to defend: same images, same compose file, same one command. Applicable account credits may offset eligible usage; the estimate below does not assume them.
- **Talk about Option C** (ECS Express Mode + RDS) as "what I'd do for production": stateless tasks behind an ALB, state in RDS, files in S3, ShedLock for schedulers, secrets in Secrets Manager, logs/metrics in CloudWatch. Knowing *what changes* when going from A to C is the interview gold:
  - uploaded files → S3 (tasks are disposable), spill files stay on task-local ephemeral storage;
  - `@Scheduled` jobs → ShedLock (DB table lock) so only one task polls/recovers; outbox is already multi-instance-safe via `SKIP LOCKED`;
  - Caffeine → short TTL or Redis (ElastiCache);
  - health checks → ALB target group on `/actuator/health/readiness`;
  - autoscaling on CPU / request count; min 1 task.
- If AWS billing or setup worries you, **Option E (Lightsail)** gives the same compose-on-a-VM story at a flat price.

---

## 3. Option A step by step (what Prompt 8 automates in `deploy/aws-ec2/`)

1. **Account hygiene:** create an AWS Budget alert ($15/month) before launching anything.
2. **Proposed launch, subject to the account-specific plan and approval:** Ubuntu 24.04 LTS arm64 on `t4g.small`, or x86_64 `t3.small` fallback, 20 GB encrypted gp3, an SSM instance role and restricted backup-bucket access. Bootstrap adds a 2 GB swap file. No resource is created before the concrete resource/name/cost plan is approved. The proposal below selects Sydney and the verified eligible t4g.small.
3. **Security group:** inbound 80 and 443 from anywhere; **no** 22 (use SSM Session Manager); no SSH exception. Postgres, 8080 and 8081 are never exposed.
4. **Elastic IP** (stable address for DNS). Note: every public IPv4 costs ~$0.005/hour whether attached or not — release it when you're done.
5. **DNS:** the human points `rostersync-aditya.duckdns.org` at the Elastic IP on the DuckDNS website. No DuckDNS token is needed by the agent.
6. **User data / bootstrap** (`deploy/aws-ec2/bootstrap.sh`): install Docker Engine + compose plugin, clone the repo, write `.env` from SSM Parameter Store SecureStrings (`/rostersync/JWT_SECRET`, `/rostersync/POSTGRES_PASSWORD`, `/rostersync/DEMO_PASSWORD`), `docker compose --env-file .env -f deploy/aws-ec2/compose.prod.yml up -d --build`.
7. Production Compose contains **app + db + caddy only**, without Prometheus/Grafana. Do not publish API 8080; bind management as `127.0.0.1:8081:8081` on the instance. Caddy publishes 80/443 and proxies only app:8080, so public actuator paths return 404. The security group does not open 8081. Use `prod,demo` for the intentional interview exception (OPERATOR dev endpoints); ordinary production uses `prod` alone. Set JVM `-Xmx768m` and Postgres `shared_buffers=256MB`.
8. **Backups:** cron `pg_dump | gzip | aws s3 cp - s3://<bucket>/rostersync/$(date +%F).sql.gz` nightly.
9. **Deploy updates:** `deploy/aws-ec2/deploy.sh` runs via SSM: `git pull && docker compose … up -d --build`. Optional GitHub Action that triggers it on tag.
10. **Smoke test:** over SSM, `docker compose exec app curl -fsS localhost:8081/actuator/health/readiness`. Public `/actuator/health/readiness` must return 404. Run the API demo through `BASE_URL=https://<domain>`; the internal readiness check remains private.
11. **Cost control after interviews:** `docker compose down`, stop or terminate the instance, release the Elastic IP, delete snapshots.

Optional (you already know CDK): `deploy/aws-cdk/` — a ~80-line CDK stack (default VPC, SG, role, EC2 with the bootstrap as user data, Elastic IP, S3 backup bucket, SSM params). Nice to show, not required.

Access the loopback management port through SSM port forwarding (AWS CLI + Session Manager plugin and an authenticated profile):
```bash
aws ssm start-session --profile moveinsync-dev --region ap-southeast-2 --target <INSTANCE_ID> \
  --document-name AWS-StartPortForwardingSession \
  --parameters '{"portNumber":["8081"],"localPortNumber":["18081"]}'
curl http://127.0.0.1:18081/actuator/health/readiness
```
Secrets will be generated directly into SSM Parameter Store SecureString after resource approval, without printing them. AWS authentication has been verified for moveinsync-dev; resource-plan approval and the budget alert recipient remain human-owned prerequisites; local benchmarks, load tests and demo work do not depend on them.

---

## 4. CI (GitHub Actions)

`.github/workflows/ci.yml`: on push/PR → `actions/setup-java` (Temurin 21, Maven cache) → `./mvnw -B verify -Pbenchmark` (Testcontainers works on `ubuntu-latest`, Docker is available) → upload Surefire/Failsafe reports → build the Docker image (no push) to prove the Dockerfile works. A green badge in the README is cheap credibility.

---

## 5. Non-AWS fallback (if you just need a URL fast)

Render can run the Dockerfile as a free web service with a free Postgres, but: free services sleep after 15 minutes idle (~1 min wake-up), have 512 MB RAM / 0.1 CPU (slow for a JVM), and the free database expires after 30 days. Acceptable as a backup link, not for running the 200k benchmark live.

## Local acceptance evidence (2026-10-30)

The default two-service stack was verified from clean named volumes. `docker compose up --build -d` succeeds without proxy build arguments; readiness is UP in 33.36 seconds after app start. Restart readiness takes 22.63 seconds and demo rows are not duplicated. The runtime is Temurin 21.0.13, UID 10001, with writable `/data`; all captured application log lines are JSON. Full Maven verification also passes all 106 tests. These are local acceptance measurements, not the Phase 8 performance benchmark.

Phase 5 shutdown and scheduler operation: server.shutdown=graceful and spring.lifecycle.timeout-per-shutdown-phase=20s. The poller stops claiming on ContextClosedEvent; the in-flight row commits or rolls back. The bounded sink executor drains for up to 20 seconds. Retention runs at RETENTION_CRON (default 03:00), in separately committed batches of 5,000. One deployed scheduler instance is assumed; multi-instance retention/recon scheduling requires ShedLock or equivalent coordination. Queue claims themselves are safe across multiple publishers through SKIP LOCKED.

Phase 6 JDBC URLs recommend reWriteBatchedInserts=true. The default host URL, Compose URL, Hikari driver properties and Testcontainers URL all enable it, including explicit datasource URL overrides through the driver property. Demo seeding streams COPY to temporary staging and inserts conflict-safely; export uses fetchSize=1000 inside a read-only transaction. Reconciliation uses one worker and a bounded queue, local uploaded files and per-bucket/per-chunk heartbeats. Multi-instance recovery requires leases plus shared file storage. Optional RECON_INBOX_DIR enables the configured nightly inbox cron; originals are preserved.

## Sydney resource approval plan

Status: **proposed, not created**. Public-repository preflight on 2026-10-30 passed: remote tree exposes only .env.example among the requested restricted-name matches; anonymous GitHub metadata is PUBLIC; Gitleaks scanned all 72 commits and 1.01 MB with no findings. Live STS identifies AccountFullAccessRole, not root. Sydney's free-tier-eligible filter includes t4g.small and t3.small; both are offered in all three Sydney zones. Free-tier eligibility does not guarantee zero spend or unrestricted create permissions.

| Resource | Proposed name / configuration |
|---|---|
| Region/profile | ap-southeast-2 / moveinsync-dev; keep Free plan |
| Existing network | Default VPC vpc-0a9a664ef2dbb6dd4; public subnet subnet-04bf0920fa3b8d4fd in ap-southeast-2a; existing internet gateway, no NAT/LB/VPC endpoints |
| EC2 | rostersync-demo; t4g.small,2vCPU/2GiB, ARM64; IMDSv2 required with hop limit1; standard CPU credits to avoid Unlimited surplus charges |
| Fallback | t3.small only if the preferred type cannot be launched; select matching x86_64 Ubuntu AMI and report the fallback |
| OS | Canonical Ubuntu24.04LTS ARM64 via its public SSM AMI selector; current resolved AMI ami-0b58dc56768a169ca (Canonical owner099720109477, available, free-tier eligible); recheck owner/state before launch |
| Root EBS | rostersync-root-gp3;20GiB encrypted gp3, baseline3000IOPS/125MiB/s, delete with instance; backups remain in S3 |
| Public IPv4 | rostersync-eip, one VPC Elastic IP; no additional long-lived public address |
| Security group | rostersync-web-sg; inbound TCP 80/443 from0.0.0.0/0 only; no 22/5432/8080/8081; outbound permitted for packages, SSM, S3 and ACME |
| IAM role/profile | rostersync-ec2-role / rostersync-ec2-profile; trust ec2.amazonaws.com |
| IAM policies | AmazonSSMManagedInstanceCore plus rostersync-runtime-policy rendered from the reviewable template; project-only parameter reads and backup-prefix writes |
| Backups | rostersync-backups-687971794741-ap-southeast-2; private Sydney S3 bucket, block public access, bucket-owner enforced, SSE-S3,30-day expiry and1-day incomplete multipart cleanup |
| SecureStrings | /rostersync/JWT_SECRET, /rostersync/POSTGRES_PASSWORD, /rostersync/DEMO_PASSWORD; Standard tier, AWS-managed aws/ssm key; securely generated and stored only after approval, never printed |
| Budget | rostersync-monthly-15-usd; account monthly cost USD 15; actual80%/100% and forecast100% email alerts to the privately supplied recipient; exclude credits/refunds so credits do not hide gross usage |
| Runtime | app+db+caddy only; prod,demo for interview; Xmx768m, Postgres shared_buffers 256 MB; persistent 2 GB swap; container log rotation |
| Management | Host127.0.0.1:8081 only through SSM port forwarding; Caddy only proxies app:8080 and blocks actuator; no monitoring services on the server |
| DNS/TLS | rostersync-aditya.duckdns.org; human updates the A record after EIP allocation; Caddy HTTP challenge/automatic HTTPS |

The instance policy explicitly denies reading other Parameter Store hierarchies: AmazonSSMManagedInstanceCore already grants broad GetParameter/GetParameters access, so merely adding a scoped Allow would not restrict it. Other SSM agent/session permissions remain as required. The role has no S3 read/list/delete rights and no permission to create/update parameters. Credentials use the instance role, not access keys in the application. [Managed-policy source](https://docs.aws.amazon.com/aws-managed-policy/latest/reference/AmazonSSMManagedInstanceCore.html).

### Sydney estimate (USD,730 hours/month, before credits/tax)

Rates are from the official regional EC2/EBS and S3 price lists, effective 2026-10-01 and checked 2026-10-30. Baseline estimate assumes 20 GiB gp3, one IPv4, 1 GB retained compressed backups and low request traffic. The request allowance is illustrative, not measured backup storage.

| Item | Rate / assumption | Monthly |
|---|---|---|
| t4g.small Linux/shared on-demand |0.0212/hour ×730|15.476|
| gp3 root |0.096/GB-month ×20|1.920|
| Public IPv4 |0.005/hour ×730|3.650|
| S3 Standard backups |0.025/GB-month ×1|0.025|
| Low request allowance |Additional allowance|0.075|
| **Preferred total** |Rounded|**21.15**|
| **t3.small fallback total** |Compute0.0264/hour, same other assumptions|**24.94**|

[EC2/EBS Sydney price list](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonEC2/current/ap-southeast-2/index.csv), [S3 Sydney price list](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonS3/current/ap-southeast-2/index.csv), [public IPv4 pricing](https://aws.amazon.com/vpc/pricing/), [Budgets pricing](https://aws.amazon.com/aws-cost-management/aws-budgets/faqs/). The pricing distribution hostname is global; it does not change the Sydney deployment region. IAM and Budgets are global control planes, not extra regional deployments.

The USD 15 budget is an alert, **not a hard cap**; the conservative preferred gross estimate exceeds it. Applicable account credits or temporary trials may reduce the actual bill, but are not assumed in the baseline. No Paid-plan upgrade is authorized. Standard CPU credits avoid surplus CPU-credit charges, but may throttle sustained workloads. Additional backup storage, egress beyond free allowances, taxes and other account usage can change costs. No customer-managed KMS key, NAT gateway, load balancer, RDS or monitoring server is proposed.

After approval: create the scoped budget/IAM/S3/SSM resources, generate secrets through the SDK without printing them, allocate/associate the EIP, bootstrap the reviewed runtime, wait for the human's DuckDNS update, then verify HTTPS, tenant/auth/demo flows, backup upload and private 8081 forwarding. No AWS write API has been executed for this proposal. Bootstrap/deploy/backup automation remains part of the subsequent approved implementation, not a claim of a live deployment.
