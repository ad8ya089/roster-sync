# HR Roster Sync — idempotent, out-of-order, self-healing

MoveInSync Backend Case Study 5 (Advanced Track). Java 21 · Spring Boot 4.1 · PostgreSQL 17 · one `docker compose up`.

**[Live API](https://rostersync-aditya.duckdns.org)** · **Credentials provided privately** · **[Demo video — recording pending](https://example.com/REPLACE_WITH_DEMO_VIDEO)**

Deployed in **Sydney (`ap-southeast-2`)**; approximately 150 ms of added latency from India is accepted. HTTPS, the light demo, reboot recovery and encrypted S3 backups are verified. The video link is a placeholder.

### How to verify in 2 minutes

Run these five lines in Bash with curl and jq. Enter the password supplied privately; the token stays in the shell variable.

```bash
BASE=https://rostersync-aditya.duckdns.org; curl --fail-with-body "$BASE/"
read -rsp 'Private demo password: ' DEMO_PASSWORD; echo
TOKEN=$(jq -n --arg password "$DEMO_PASSWORD" '{username:"acme.admin",password:$password}' | curl -fsS "$BASE/api/v1/auth/token" -H 'Content-Type: application/json' --data-binary @- | jq -er .accessToken)
curl --fail-with-body "$BASE/api/v1/offices" -H "Authorization: Bearer $TOKEN" | jq
curl -sS -o /dev/null -w 'Unauthenticated offices: %{http_code} (expected 401)\n' "$BASE/api/v1/offices"; unset DEMO_PASSWORD TOKEN
```

## Target behavior

- Ingests employee **create / update / deactivate** events from HR in near real time, ignoring **stale** and **duplicate** ones, and rejecting invalid records **per record** without failing the batch.
- Protects **transport-owned fields** (pickup point, special assistance) from HR overwrites.
- Reconciles a **nightly 200,000-row export** with a bounded-memory bucketed diff and a **safety brake** that refuses to deactivate anyone when the file looks truncated.
- Publishes a change event for every applied change through a **transactional outbox**; deactivations cancel future cab bookings and free seats.

## Run it

```mermaid
flowchart LR
    HR[HR events] --> API[Authenticated API]
    CSV[Nightly CSV] --> RECON[Bucketed diff and safety brake]
    API --> DB[(PostgreSQL: employees and receipts)]
    RECON --> DB
    DB --> OUTBOX[Transactional outbox]
    OUTBOX --> SINK[Logs and booking side effects]
    OPS[Operator] --> RECON
```

```bash
cp .env.example .env
docker compose up --build
curl 127.0.0.1:8081/actuator/health/readiness
```
Requirements for the container run: Docker Desktop/Engine with Compose. No host Java or Maven is required. Run `./scripts/demo.sh` for the guided walkthrough, or `DEMO_AUTO=1 ./scripts/demo.sh` for assertions without pauses. Git Bash on Windows and Linux require curl + jq. The [Postman collection](postman/roster-sync.postman_collection.json) and [environment template](postman/roster-sync.postman_environment.json) contain the same requests; fill demoPassword locally.

On Windows PowerShell, use `Copy-Item .env.example .env` for the first step and `curl.exe` for the readiness request. The app starts after PostgreSQL is healthy; Flyway creates the schema. The API uses 8080; public Actuator endpoints use the separate management port, bound to `127.0.0.1:8081` on the host. PostgreSQL stays on the Compose network. The runtime is non-root and uploads live on the `/data` volume.

Local builds require JDK 21 or newer; JDK 22 is supported with `maven.compiler.release=21`. The Maven wrapper downloads Maven automatically:

```bash
./mvnw -B test               # unit tests, no Docker
./mvnw -B verify             # unit + PostgreSQL integration tests, Docker required
./mvnw -B verify -Pbenchmark # normal tests + separate 256 MiB benchmark forks
```

In PowerShell, replace `./mvnw` with `.\mvnw.cmd`. Docker and CI use Temurin 21. A network proxy, if required by your machine, belongs in your local Maven settings rather than the repository.

All Actuator endpoints are public on 8081; readiness includes database connectivity. No Actuator routes exist on 8080. POST `/api/v1/auth/token` is public; other API handlers require a signed bearer token and declare allowed roles. Both demo and production logging use ECS JSON; correlation IDs appear in `X-Correlation-Id` and error bodies, including 401/403 responses.

Optional monitoring: set a strong `GRAFANA_ADMIN_PASSWORD` in `.env`, then run `docker compose --profile monitoring up -d`. Prometheus is at `http://127.0.0.1:9090`; Grafana at `http://127.0.0.1:3000` (user `admin`), with the provisioned **Roster Sync Operations** dashboard. Default Compose starts only app and db. Metrics use finite labels without tenant/employee IDs or PII. `GET /api/v1/ops/summary` (OPERATOR) separates tenant-scoped durable SQL state from instance-wide counters/percentiles since restart; see [observability](docs/OBSERVABILITY.md). Production binds 8081 only to instance loopback for SSM forwarding; Caddy proxies only 8080, and Prometheus/Grafana stay local.

The `demo` profile seeds tenants `acme` and `globex`, offices `BLR-1`, `BLR-2`, inactive `BLR-3`, and `HYD-1`. Seeded usernames are `acme.hr`, `acme.admin`, `acme.operator`, `E1001`, `globex.hr`, `globex.admin` and `globex.operator`; their password comes from `DEMO_PASSWORD`. Acme has employees `E1001`–`E1005`, two future bookings and one completed booking for `E1002`, and route `R-BLR-07` with four seats. Restarting preserves existing data.

Compose defaults to `demo`. Ordinary production uses `prod` alone, without demo fixtures or dev endpoints. The interview deployment intentionally enables `prod,demo`; dev endpoints require OPERATOR authentication. Change all example credentials before exposing an interview deployment publicly.

Log in using the `DEMO_PASSWORD` chosen in `.env`, then pass the returned `accessToken`:

```bash
curl --fail-with-body localhost:8080/api/v1/auth/token \
  -H 'Content-Type: application/json' \
  -d '{"username":"acme.admin","password":"<your DEMO_PASSWORD>"}'
curl --fail-with-body localhost:8080/api/v1/offices \
  -H 'Authorization: Bearer <accessToken>'
```

Office reads allow TRANSPORT_ADMIN and OPERATOR; writes require TRANSPORT_ADMIN. Globex admins see their own offices, and accessing an Acme office returns 404. JWTs use HS256 with a secret of at least 32 UTF-8 bytes, expire after one hour by default, and allow 30 seconds of clock skew. Disabling a user prevents new tokens; existing tokens remain valid until expiry because validation is stateless. Details and response contracts are in [`docs/API.md`](docs/API.md) and [`docs/SECURITY.md`](docs/SECURITY.md).

## The rules, stated precisely

**Idempotency key:** `(tenantId, employeeId, version)`. **Version comparison** (per employee, under a row lock): newer version → `APPLIED` when content changes, `NO_CHANGE` when identical · older → `STALE` · equal → `DUPLICATE` if the payload hash matches, else `CONFLICT`. CREATE/UPDATE carry the **full HR-owned state** at that version, so their final state never depends on arrival order.

Higher-version identical snapshots, including DEACTIVATE on INACTIVE employees or tombstones, produce `VersionAdvanced` with outcome `NO_CHANGE`: advance the HR version/timestamp and write no outbox event. `Apply` requires non-empty changed fields and exactly one Employee outbox event. Equal versions with the same content remain `DUPLICATE`. The full rules are in `docs/API.md`.

**Field ownership conflict rule:** every field has exactly one owner (HR: name, email, phone, manager, office, shift, status; transport admin: pickup point, special assistance). HR payloads ignore non-owned fields with warning `FIELD_NOT_OWNED_BY_SOURCE`; transport PATCH rejects HR-owned keys with 400 and that code. Ownership is configuration (`roster.ownership.transport-owned`).

**Safety brake:** a nightly run that would deactivate ≥ 30 % of active employees (configurable, per tenant), exceeds the optional missing-count guard, or mismatches trailer/expected row counts stops in `AWAITING_APPROVAL` before any apply. Ratio comparisons use exact arithmetic; zero active employees skip ratio/count guards while row-count checks still apply. An operator may approve all, skip deactivations or reject.

## Documentation

The Phase 9 console is at its **design checkpoint**: login, the opt-in styleguide and a working reconciliation Run detail review. Other screens await design approval. The [console specification](docs/FRONTEND.md) defines the dispatch-board visual rules. Tokens remain in memory; hash routing keeps server API paths independent. Docker builds the UI without requiring host Node, and ordinary Maven verification remains Node-independent.

For local design review with ports 8080/8081 free, run `VITE_STYLEGUIDE=true docker compose -p roster-sync-checkpoint up --build -d`. This uses separate local volumes. The styleguide and username hints default off in the AWS image; the existing live deployment is unchanged until Phase 9 is complete.

| Doc | What's in it |
|---|---|
| [`docs/DESIGN.md`](docs/DESIGN.md) | design note: architecture diagram, hardest-flow sequence diagram, failure-mode table |
| [`docs/RECONCILIATION.md`](docs/RECONCILIATION.md) | bucketed diff, brake formula, complexity, where it stops scaling |
| [`docs/DECISIONS.md`](docs/DECISIONS.md) | trade-offs (consistency vs availability vs latency), what was left out |
| [`docs/DATA_MODEL.md`](docs/DATA_MODEL.md) · [`docs/API.md`](docs/API.md) | schema and API contract |
| [`docs/SECURITY.md`](docs/SECURITY.md) · [`docs/CACHING.md`](docs/CACHING.md) · [`docs/OBSERVABILITY.md`](docs/OBSERVABILITY.md) | auth/tenancy/PII/errors, caching staleness bounds, metrics |
| [`docs/TESTING.md`](docs/TESTING.md) | test catalogue, concurrency & failure-injection tests, benchmark method |
| [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) | compose, AWS options compared, EC2 runbook |
| [`docs/REQUIREMENTS_TRACEABILITY.md`](docs/REQUIREMENTS_TRACEABILITY.md) | every PDF requirement → code → test |

## Assumptions
1. Each employee's `version` is strictly increasing at the HR source (epoch millis if HR only has timestamps).
2. CREATE/UPDATE events carry the full HR-owned state; DEACTIVATE needs only id + version.
3. The nightly export carries each row's current version and the export's `asOf` time.
4. Employee IDs are unique per tenant, not globally.
5. Absence from the nightly file is weaker evidence than a versioned event, so recon deactivations never bump the HR version.
6. Operators are tenant-scoped users.

## Measured local performance

Machine: Intel Core i5-1240P, 12 physical / 16 logical cores, 15.69 GiB OS-reported RAM, Windows 11 Home Single Language build 26200. Docker Desktop Engine 29.9.2: 16 logical CPUs and 7.60 GiB VM memory. Host benchmark JDK 22.0.1 compiles release 21; the running app uses Temurin 21. These are local measurements, not AWS sizing guarantees.

The benchmark uses three runs in one dedicated 256 MiB JVM, a 200,000-employee source, deterministic seed 20261030, 1% mutation / 0.5% drop / 0.1% new. Each file has 199,190 valid rows and plans exactly 1,922 updates, 1,010 deactivations and 200 creates. Peak heap is sampled every 50 ms; coverage remains enabled for normal tests and is excluded from benchmark timing.

| Run | Dry-run wall time | Diff phase | Apply 3,132 actions | Sampled peak heap |
|---|---|---|---|---|
| 1 | 23.455 s | 23.109 s | 12.713 s | 99.69 MiB |
| 2 | 17.067 s | 16.954 s | 10.563 s | 103.62 MiB |
| 3 | 15.548 s | 15.369 s | 10.272 s | 109.40 MiB |
| **Median** | **17.067 s** | **16.954 s** | **10.563 s** | **103.62 MiB** |

Keep the **40-action default**. Median mixed-file apply throughput in this repeat is **296.5 actions/s**, about 1.2% below the 300 target; earlier repeats reached 303–304, so that throughput floor is not consistent across repeats. No further application tuning was performed. Across **237 nonempty committed chunks**, including cold chunks and transaction commit, nearest-rank **p50 / p99 / max = 127.6 / 310.0 / 1,253.6 ms**. The approved criterion is **p99 <1 second AND max <2.5 seconds** (half the 5-second lock timeout): **PASS; the chunk acceptance item is closed**. Raw durations are generated under target.

The matched update-only profiling comparison used 1,000 warm-up actions, 5,000 measured actions, chunk 64, the same 256 MiB heap and no coverage agent: **89.6 → 380.3 actions/s (4.25×)**. JDBC execution/batch/commit calls fell from **6.08 → 0.17 per action**. These counts describe JDBC calls, not packet captures. The implementation batches action status updates and outbox inserts, defers flushing, and reads/locks employee sets together.

Cold JIT, class loading and host scheduling vary. Earlier mixed-file repeats measured 303–304 actions/s with maxima 779–1,265 ms; instrumented 40-action probes measured 251–258 actions/s and cold maxima 833–1,605 ms. A stressed 64-action probe reached 6,140 ms. These are different workloads/instrumentation and are retained rather than hidden. The old saved summaries contained only maxima, so their p50/p99 cannot be recovered; this repeat adds complete distribution capture. The new criterion replaces the old maximum-below-one-second target. It is a local acceptance measurement, not a guarantee for every host or cold start. See [trade-offs](docs/DECISIONS.md).

The latest separate 40-action JDBC-counting probe measured 252.2 actions/s, 330 ms maximum after warm-up and 925 ms cold maximum. Its first measured chunk used 0.25 JDBC calls/action. This instrumented update-only workload is separate from both the mixed-file result and the archived matched 64-action comparison.

### HTTP ingestion scaling (k6)

Each case is configured for 60 seconds, with per-VU IDs and a 90% fresh / 5% replay / 5% stale cycle. All six cases had **zero HTTP and outcome failures**. Login/setup is excluded from request latency. Batch size 1 measures actual single-event response latency; the per-event columns for batch size 50 divide each request duration by 50 and are **amortized cost, not individual response latency**. All baseline rows use `synchronous_commit=on`.

| Events/request | VUs | Events/s | Request p50 / p99 (ms) | Per-event p50 / p99 (ms) | Max pool pending | PG commits/s |
|---|---|---|---|---|---|---|
| 1 | 5 | 59.0 | 71.2 / 235.4 | 71.18 / 235.36 | 0 | 61.2 |
| 50 | 1 | 75.9 | 650.6 / 933.1 | 13.01 / 18.66 | 0 | 96.2 |
| 50 | 5 | 245.1 | 985.6 / 1,380.6 | 19.71 / 27.61 | 0 | 243.4 |
| 50 | 10 | 322.1 | 1,446.4 / 2,502.7 | 28.93 / 50.05 | 0 | 301.2 |
| 50 | 20 | 405.0 | 2,325.4 / 3,832.8 | 46.51 / 76.66 | 1 | 372.9 |
| 50 (sync off, diagnostic) | 20 | 492.0 | 1,873.7 / 3,780.7 | 37.47 / 75.61 | 2 | 436.0 |

The pool size stayed at 20. Hikari gauges were sampled every 200 ms (observed maxima; no gauge errors). PG commits/s is the `xact_commit` delta divided by its measured enclosing wall window: 65.6–69.8 seconds, including container launch/login/teardown, publisher and observation transactions. It is not ingestion-only commits during exactly 60 seconds. Each case started with an empty outbox queue. Raw JSON/logs stay in ignored loadtest/results; the matrix is resumable because draining between cases takes substantially longer than the timed traffic.

1. Scaling bends above 5–10 VUs: 10→20 adds 26% events/s while request p99 rises 53%, so more concurrency increasingly buys waiting time.
2. Normal-run spot samples showed WALWrite/WalSync waits; `synchronous_commit=off` raised throughput 21.5% and removed those observed waits. Durable commit I/O contributes, but per-record SQL/commit coordination and CPU/runnable work remain; this single diagnostic does not isolate every remaining cost.
3. Baseline max pending connections was 0/0/0/1 across batch-50 cases; raising the pool is not justified by that and cannot remove commit costs. The 20-VU case ended with 22,260 pending outbox rows: 405 events/s is an **ingress burst rate**, not sustainable end-to-end delivery capacity.

The diagnostic temporarily relaxed durability only on the local database and restored `synchronous_commit=on` in finally, verified before the test suite. It is not a production recommendation: acknowledged commits may be lost after a crash with asynchronous commit. The earlier isolated 20-VU run measured 423 events/s (request p50/p99 2,210/3,912 ms); retain that as variation, rather than treating either run as a hardware-independent guarantee.

Run `BATCH_SIZE=1 LOAD_VUS=5 ./scripts/loadtest.sh` or the default batch50 case. `python loadtest/run-matrix.py` runs the local-only matrix, including the temporary durability diagnostic. Docker Desktop uses `BASE_URL=http://host.docker.internal:8080`; Linux may use `DOCKER_NETWORK=host BASE_URL=http://127.0.0.1:8080`. See [test methodology](docs/TESTING.md).

## Complexity & limits _(summary; details in RECONCILIATION.md and DECISIONS.md)_
Event apply: O(log M) index lookup, O(1) memory. Reconciliation: O(N + M) time, O(N/B) heap with B = 256 buckets, O(N) temp disk. First scaling limits: single Postgres writer, single outbox poller, table growth, per-row apply for mass changes.

## Tests

| Type | Test cases |
|---|---|
| Unit and web-slice | 139 |
| jqwik properties | 2, each with 1,000 fixed-seed trials |
| Architecture and endpoint guards | 4 |
| PostgreSQL integration | 147 |
| **Backend normal verify** | **292** |
| Frontend unit | 17 |
| Design-checkpoint browser cases | 5, including 12 axe scans |
| Benchmark/profile methods | 2, last run in Phase 8; the 200k method executes three samples |

The Phase 9 checkpoint `./mvnw -B verify` passes 292 backend tests with Node absent from PATH and zero failures/errors/skips. The earlier Phase 8 unfiltered `verify -Pbenchmark` passed 292 tests at that revision; the benchmark profile has not been rerun for this frontend checkpoint. Normal push/PR CI runs `verify`; the benchmark runs only on manual dispatch and the weekly Monday 03:00 UTC schedule. The approved chunk p99/max criterion passes as stated above. Domain line coverage is **99.45% (359/361)**; JaCoCo renders its report at target/site/jacoco/index.html without a gate. Coverage includes ordering, idempotency, version-only updates, ownership, tenant/role isolation, rollback, real-context restart recovery, publisher concurrency, rehire, streaming/200k safety brake, management isolation, rollback-safe caches and PII boundaries. [Testing details](docs/TESTING.md).

## Demo video
[Demo video — recording pending](https://example.com/REPLACE_WITH_DEMO_VIDEO)

[Live API](https://rostersync-aditya.duckdns.org)

The video link is a placeholder. The API is live in **Sydney (`ap-southeast-2`)** at `rostersync-aditya.duckdns.org`. The repeatable local demo and sample requests are available now. [Deployment plan and private management access](docs/DEPLOYMENT.md).

## Local verification status

Verified on 2026-10-30 with Docker Desktop's Linux/WSL2 engine: clean-volume `docker compose up --build -d` succeeds. Readiness became UP in 33.36 seconds, and in 22.63 seconds after restarting the app. V1 defines 13 core tables; Phase 5 V2 adds processed_event alongside Flyway history; demo fixture counts are correct and unchanged after restart. All 30 captured application log lines were ECS JSON without fixture PII or sample secrets. Correlation headers and liveness pass. The container runs Temurin 21.0.13 as UID 10001 with writable `/data`.

Host Maven and Maven inside the build image reach Maven Central directly. The plain Docker build succeeds without HTTP_PROXY/HTTPS_PROXY build arguments, and Docker client automatic proxy defaults were unset. No proxy URLs, credentials, or network-specific defaults are committed. The temporary Maven settings used during initial setup remain outside the repository. First builds download images and dependencies; subsequent builds reuse the Maven build layers.

Domain line coverage is 99.45% (359/361 lines, no gate).

## Event ingestion and replay

```bash
curl --fail-with-body localhost:8080/api/v1/hr/events \
  -H 'Authorization: Bearer <HR_SYSTEM accessToken>' \
  -H 'Content-Type: application/json' --data-binary @samples/feed.json
```

The 50-event fixture creates/updates/deactivates/reactivates ten employees; its first send reports 44 APPLIED, 3 STALE and 3 in-batch DUPLICATE. Replaying it returns 50 DUPLICATE results, preserving employee row contents, receipts and outbox counts. Each duplicate includes originalOutcome. `samples/bad-batch.json` returns three APPLIED and two REJECTED results; rejected keys remain available for correction. Conflicts are audited with VERSION_CONFLICT and never keep a new receipt.

GET `/api/v1/employees`, `/employees/{id}`, `/employees/{id}/events` and `/me` enforce role, tenant and employee self-access. Lists return `{items,page,size,totalElements}` with size 1–200. PATCH `/employees/{id}/transport-fields` distinguishes null (clear) from absent (retain), supports inactive employees, and leaves versions/outbox untouched on a no-op. Only transport-owned fields are accepted.

HR batches contain 1–1000 records and at most 2 MiB (2,097,152 bytes). Both Content-Length and chunked bodies are bounded. Accepted event content is hashed with its type; eventId/occurredAt are excluded. Higher identical content advances the HR version/timestamp with NO_CHANGE and no Employee event. The Phase 5 publisher delivers committed outbox rows asynchronously, one transaction per row, with bounded sink execution, retries and consumer deduplication.


## Outbox crash and side effects
Run `./scripts/demo-crash.sh` from Linux or Git Bash on Windows (curl, jq and Docker CLI required). It arms a demo-only one-shot HALT, sends a unique employee event, shows the exit/restart and prints that eventId's published log line after readiness returns. Demo credentials come from the ignored .env or environment. Tokens and passwords are not printed.

Each publisher tick handles up to OUTBOX_BATCH_SIZE rows (default 100), each in its own transaction with FOR UPDATE SKIP LOCKED. Sink calls use four threads, queue 100 and OUTBOX_SINK_TIMEOUT (default 2s). A timeout does not cancel the sink: late completion and redelivery are possible. Consumers deduplicate `(sink,event_id)` and check current state. Retry delays start at 1s and double to 300s; OUTBOX_MAX_ATTEMPTS (default 10) ends in DEAD with one alert. OPERATOR can inspect tenant-scoped outbox/alerts and retry DEAD rows. Acknowledgement is idempotent. Delayed rows do not block newer events.

Deactivation cancels only future SCHEDULED bookings and frees seats, in an independent consumer transaction. The employee FOR UPDATE lock serializes the status check with rehire; booking creation uses FOR SHARE and rejects inactive employees. Retention runs in 5,000-row transactions at RETENTION_CRON. A single scheduler instance is assumed; multi-instance retention would need ShedLock. Graceful shutdown stops claims and allows 20 seconds per phase.

## Nightly reconciliation
POST multipart `/api/v1/reconciliation/runs` with file and asOf returns 202 after synchronous header validation. Parsing spills into 256 binary buckets; the pure differ compares one bucket at a time. The safety brake stops all changes when a truncated export appears to omit too many active employees. OPERATOR may approve ALL, approve SKIP_DEACTIVATIONS or reject; dry runs are terminal PLANNED runs. Whole-file ordering rejects asOf at/before the last completed export.

Explicit ADVANCE actions preserve higher identical versions without business events. Apply rechecks planned versions under employee locks, has no receipts or nested event retries, and commits configured chunks (default 40 after measured tuning). Each chunk batches employee flushing, outbox inserts and action-status bookkeeping. Failed chunks roll back before per-action fallback. Startup resumes APPLYING runs, fails interrupted planning and preserves awaiting approvals; a heartbeat watchdog avoids failing live work. Multi-instance deployment would need leases and shared upload storage.

Demo OPERATOR endpoints seed S000001… fixtures with Java CRC32/COPY and stream deterministic CSV exports with seed/mutation/drop/new/truncation options. Seed fixtures intentionally bypass outbox/receipts. Export uses a read-only PostgreSQL cursor with fetchSize=1000. See the run JSON contracts in [API.md](docs/API.md) and the algorithm in [RECONCILIATION.md](docs/RECONCILIATION.md).
