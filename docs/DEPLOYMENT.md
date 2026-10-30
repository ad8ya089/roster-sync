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
         ports: 8080:8080
         volume: appdata:/data              ← uploaded files + spill files
         healthcheck: curl --fail --silent localhost:8080/actuator/health/readiness
```

**Profiles:** local Compose defaults to `demo`. Ordinary production uses `prod` alone and excludes demo fixtures/dev endpoints. The interview environment deliberately runs `prod,demo`; dev endpoints are restricted to OPERATOR once implemented.

**Dockerfile (multi-stage):**
- Stage 1 `maven:3.9-eclipse-temurin-21` — copy `pom.xml` first and run `mvn -B -q dependency:go-offline` (layer cache), then copy `src` and `mvn -B -DskipTests package`.
- Stage 2 `eclipse-temurin:21-jre` (multi-arch: works on x86 laptops and ARM/Graviton) — non-root user, `JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"`, `EXPOSE 8080`.
- Nobody needs Java or Maven installed.

The runtime image installs curl for its readiness check and runs as UID/GID 10001, which owns `/data`.

**First run on a fresh clone:**
```bash
cp .env.example .env        # defaults work for local
docker compose up --build   # Flyway creates schema; demo seeder creates 2 tenants, offices, users, a few employees, bookings, seats
curl localhost:8080/actuator/health/readiness
```
Tests need Docker (Testcontainers) — say so in the README. Before recording the video, verify on a clean machine or a clean clone with `docker compose down -v` first.

Optional: `docker compose --profile monitoring up` adds Prometheus (scrapes `/actuator/prometheus`) and Grafana with a provisioned dashboard.

---

## 2. AWS — every realistic option, compared

Assumptions: one Spring Boot container (needs ~0.5–1 GB RAM), one Postgres, a background scheduler (outbox poller every 500 ms), a background worker that streams a 25–100 MB CSV to local disk and runs for tens of seconds, and an interview window of a few weeks. Region `ap-south-1` (Mumbai) for India latency; prices below are us-east-1 list prices from public sources (Oct 2026) — Mumbai is similar; check the AWS Pricing Calculator before you launch.

| Option | What you run | Fits this app? | Rough monthly cost (always on) | Effort | Interview defensibility |
|---|---|---|---|---|---|
| **A. EC2 + the same docker compose** (+ Caddy for HTTPS) | 1 × t4g.small (2 vCPU ARM, 2 GiB) | **Yes, unchanged** — identical to the local run | t4g.small on-demand ≈ $12/mo + public IPv4 ≈ $3.6/mo + 20 GB gp3 ≈ $2 | Low (≈ 1 h) | **High** — "the artifact I demo locally is exactly what runs in the cloud" |
| B. EC2 (app via compose) + **RDS PostgreSQL** | t4g.small + db.t4g.micro | Yes; change only `SPRING_DATASOURCE_URL` | A + roughly $12–15 for db.t4g.micro (db.t4g.small ≈ $23) | Medium | High — "managed backups/PITR, Multi-AZ upgrade path" |
| C. **ECS Express Mode** (Fargate + ALB + HTTPS URL) + RDS | 1 Fargate task 0.5 vCPU / 1 GB, ALB, RDS | Yes, with care: ephemeral task disk (fine for a run, lost on restart — use S3/EFS for uploaded files), schedulers duplicate if > 1 task (need ShedLock) | Fargate ARM ≈ $14 + ALB ≈ $16 + IPv4 ≈ $3.6 + RDS ≈ $12–15 + logs → **≈ $45–55/mo** | Medium–high (ECR, IAM roles, secrets) | High as "production shape", but more surface to be grilled on |
| D. Elastic Beanstalk (Docker platform) | EC2 + ELB managed by EB | Yes | ≈ A (+ ALB if load-balanced env ≈ $16) | Medium | Medium — it's an EC2 wrapper; adds little over A for this scope |
| E. **Lightsail** instance + same compose | 2 GB Lightsail bundle | Yes, unchanged | $12/mo flat (IPv4 included); new Lightsail accounts get 3 months free on the $5/$7/$12 bundles | Lowest | Medium-high — "fixed-price VPS, same compose"; fewer AWS concepts to show |
| F. AWS App Runner | — | — | — | — | **Not an option**: App Runner is closed to new customers (AWS points to ECS Express Mode instead) |
| G. **Lambda** (+ API Gateway + RDS Proxy) | functions | **Poor fit** (see below) | low at idle, but RDS + Proxy dominate | High: re-architecture | Defensible only as "why I did NOT use it" — which is a great answer to have |
| H. EKS | Kubernetes | Overkill | control plane alone costs more than A–E combined | Very high | Low for this scope |

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
| Cost | ~$0 extra | + roughly $12–25/mo |
| Interview line | "Fine for a demo; in prod I'd move to RDS for PITR and Multi-AZ" | "Separated state from compute" |

### Recommendation
- **Ship Option A** (EC2 t4g.small + docker compose + Caddy, Postgres in a container on gp3 EBS, nightly `pg_dump` to S3). It's the cheapest and the easiest to defend: same images, same compose file, same one command. Your existing AWS credits cover months of it.
- **Talk about Option C** (ECS Express Mode + RDS) as "what I'd do for production": stateless tasks behind an ALB, state in RDS, files in S3, ShedLock for schedulers, secrets in Secrets Manager, logs/metrics in CloudWatch. Knowing *what changes* when going from A to C is the interview gold:
  - uploaded files → S3 (tasks are disposable), spill files stay on task-local ephemeral storage;
  - `@Scheduled` jobs → ShedLock (DB table lock) so only one task polls/recovers; outbox is already multi-instance-safe via `SKIP LOCKED`;
  - Caffeine → short TTL or Redis (ElastiCache);
  - health checks → ALB target group on `/actuator/health/readiness`;
  - autoscaling on CPU / request count; min 1 task.
- If AWS billing or setup worries you, **Option E (Lightsail)** gives the same compose-on-a-VM story at a flat price.

---

## 3. Option A step by step (what Prompt 8 automates in `deploy/aws-ec2/`)

1. **Account hygiene:** create an AWS Budget alert (e.g. $10/month) before launching anything.
2. **Launch** EC2 in `ap-south-1`: Ubuntu 24.04 LTS **arm64**, `t4g.small` (use `t4g.medium` if you want to run the 200k benchmark on the box), 20 GB gp3, an IAM role with `AmazonSSMManagedInstanceCore` (so you can use Session Manager instead of opening SSH) and `s3:PutObject` on a backup bucket.
3. **Security group:** inbound 80 and 443 from anywhere; **no** 22 (use SSM Session Manager) — or 22 only from your IP. Postgres and 8080 are never exposed.
4. **Elastic IP** (stable address for DNS). Note: every public IPv4 costs ~$0.005/hour whether attached or not — release it when you're done.
5. **DNS:** point a free DuckDNS subdomain (or a domain you own) at the Elastic IP.
6. **User data / bootstrap** (`deploy/aws-ec2/bootstrap.sh`): install Docker Engine + compose plugin, clone the repo, write `.env` from SSM Parameter Store SecureStrings (`/rostersync/JWT_SECRET`, `/rostersync/POSTGRES_PASSWORD`, `/rostersync/DEMO_PASSWORD`), `docker compose -f docker-compose.yml -f deploy/aws-ec2/compose.prod.yml up -d --build`.
7. `compose.prod.yml` overrides: removes the 8080 host port, adds `caddy:2` (ports 80/443, `Caddyfile`: `{$DOMAIN} { reverse_proxy app:8080 }` → automatic Let's Encrypt HTTPS), sets `SPRING_PROFILES_ACTIVE=prod,demo` for the intentional interview-only exception (demo endpoints require OPERATOR auth so you can show the 200k flow live; ordinary production uses `prod` alone and has no dev endpoints), sets JVM to `-Xmx768m`, Postgres `shared_buffers=256MB`.
8. **Backups:** cron `pg_dump | gzip | aws s3 cp - s3://<bucket>/rostersync/$(date +%F).sql.gz` nightly.
9. **Deploy updates:** `deploy/aws-ec2/deploy.sh` runs via SSM: `git pull && docker compose … up -d --build`. Optional GitHub Action that triggers it on tag.
10. **Smoke test:** `curl https://<domain>/actuator/health/readiness` and run `scripts/demo.sh BASE_URL=https://<domain>`.
11. **Cost control after interviews:** `docker compose down`, stop or terminate the instance, release the Elastic IP, delete snapshots.

Optional (you already know CDK): `deploy/aws-cdk/` — a ~80-line CDK stack (default VPC, SG, role, EC2 with the bootstrap as user data, Elastic IP, S3 backup bucket, SSM params). Nice to show, not required.

---

## 4. CI (GitHub Actions)

`.github/workflows/ci.yml`: on push/PR → `actions/setup-java` (Temurin 21, Maven cache) → `./mvnw -B verify` (Testcontainers works on `ubuntu-latest`, Docker is available) → upload Surefire/Failsafe reports → build the Docker image (no push) to prove the Dockerfile works. A green badge in the README is cheap credibility.

---

## 5. Non-AWS fallback (if you just need a URL fast)

Render can run the Dockerfile as a free web service with a free Postgres, but: free services sleep after 15 minutes idle (~1 min wake-up), have 512 MB RAM / 0.1 CPU (slow for a JVM), and the free database expires after 30 days. Acceptable as a backup link, not for running the 200k benchmark live.

## Local acceptance evidence (2026-10-30)

The default two-service stack was verified from clean named volumes. `docker compose up --build -d` succeeds without proxy build arguments; readiness is UP in 33.36 seconds after app start. Restart readiness takes 22.63 seconds and demo rows are not duplicated. The runtime is Temurin 21.0.13, UID 10001, with writable `/data`; all captured application log lines are JSON. Full Maven verification also passes all 106 tests. These are local acceptance measurements, not the Phase 8 performance benchmark.

Phase 5 shutdown and scheduler operation: server.shutdown=graceful and spring.lifecycle.timeout-per-shutdown-phase=20s. The poller stops claiming on ContextClosedEvent; the in-flight row commits or rolls back. The bounded sink executor drains for up to 20 seconds. Retention runs at RETENTION_CRON (default 03:00), in separately committed batches of 5,000. One deployed scheduler instance is assumed; multi-instance retention/recon scheduling requires ShedLock or equivalent coordination. Queue claims themselves are safe across multiple publishers through SKIP LOCKED.
