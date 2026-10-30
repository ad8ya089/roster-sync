# HR Roster Sync — idempotent, out-of-order, self-healing

MoveInSync Backend Case Study 5 (Advanced Track). Java 21 · Spring Boot 4.1 · PostgreSQL 17 · one `docker compose up`.

> Status: **Phases 1–7 implemented**. Event ingestion, publishing/booking effects, streaming reconciliation, operational metrics and caches are available. Phase 8 adds the final benchmark, load test and deployment proof.

## Target behavior

- Ingests employee **create / update / deactivate** events from HR in near real time, ignoring **stale** and **duplicate** ones, and rejecting invalid records **per record** without failing the batch.
- Protects **transport-owned fields** (pickup point, special assistance) from HR overwrites.
- Reconciles a **nightly 200,000-row export** with a bounded-memory bucketed diff and a **safety brake** that refuses to deactivate anyone when the file looks truncated.
- Publishes a change event for every applied change through a **transactional outbox**; deactivations cancel future cab bookings and free seats.

## Run it

```bash
cp .env.example .env
docker compose up --build
curl 127.0.0.1:8081/actuator/health/readiness
```
Requirements for the container run: Docker Desktop/Engine with Compose. No host Java or Maven is required. The guided `scripts/demo.sh` walkthrough will be added in Phase 8.

On Windows PowerShell, use `Copy-Item .env.example .env` for the first step and `curl.exe` for the readiness request. The app starts after PostgreSQL is healthy; Flyway creates the schema. The API uses 8080; public Actuator endpoints use the separate management port, bound to `127.0.0.1:8081` on the host. PostgreSQL stays on the Compose network. The runtime is non-root and uploads live on the `/data` volume.

Local builds require JDK 21 or newer; JDK 22 is supported with `maven.compiler.release=21`. The Maven wrapper downloads Maven automatically:

```bash
./mvnw -B test               # unit tests, no Docker
./mvnw -B verify             # unit + PostgreSQL integration tests, Docker required
./mvnw -B verify -Pbenchmark # includes the benchmark once added in Phase 8
```

In PowerShell, replace `./mvnw` with `.\mvnw.cmd`. Docker and CI use Temurin 21. A network proxy, if required by your machine, belongs in your local Maven settings rather than the repository.

All Actuator endpoints are public on 8081; readiness includes database connectivity. No Actuator routes exist on 8080. POST `/api/v1/auth/token` is public; other API handlers require a signed bearer token and declare allowed roles. Both demo and production logging use ECS JSON; correlation IDs appear in `X-Correlation-Id` and error bodies, including 401/403 responses.

Optional monitoring: set a strong `GRAFANA_ADMIN_PASSWORD` in `.env`, then run `docker compose --profile monitoring up -d`. Prometheus is at `http://127.0.0.1:9090`; Grafana at `http://127.0.0.1:3000` (user `admin`), with the provisioned **Roster Sync Operations** dashboard. Default Compose starts only app and db. Metrics use finite labels without tenant/employee IDs or PII. `GET /api/v1/ops/summary` (OPERATOR) separates tenant-scoped durable SQL state from instance-wide counters/percentiles since restart; see [observability](docs/OBSERVABILITY.md). Production publishes no 8081 port; Caddy proxies only 8080.

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

**Field ownership conflict rule:** every field has exactly one owner (HR: name, email, phone, manager, office, shift, status; transport admin: pickup point, special assistance). A source may change only the fields it owns; non-owned fields in a payload are ignored and reported as the warning `FIELD_NOT_OWNED_BY_SOURCE`. Ownership is configuration (`roster.ownership.transport-owned`).

**Safety brake:** a nightly run that would deactivate ≥ 30 % of active employees (configurable, per tenant) — or whose trailer row count doesn't match — stops in `AWAITING_APPROVAL`; nobody is deactivated until an operator approves.

## Documentation

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

## Benchmarks _(filled in Phase 8)_

| Machine | JDK | Rows | Diff time | Apply time (1 % changed) | Peak heap (`-Xmx256m`) |
|---|---|---|---|---|---|
| | | 200,000 | | | |

| k6 ingest load | VUs | req/s | events/s | p50 | p99 | errors |
|---|---|---|---|---|---|---|
| | 20 | | | | | |

## Complexity & limits _(summary; details in RECONCILIATION.md and DECISIONS.md)_
Event apply: O(log M) index lookup, O(1) memory. Reconciliation: O(N + M) time, O(N/B) heap with B = 256 buckets, O(N) temp disk. First scaling limits: single Postgres writer, single outbox poller, table growth, per-row apply for mass changes.

## Tests _(counts filled in Phase 8)_
Full `./mvnw -B verify` passes 144 unit/property/architecture tests and 141 PostgreSQL integration tests (285 total), with no failures, errors or skips. Two jqwik properties each run 1,000 trials with fixed seeds `2026103001` and `2026103002`, including maximum-version preservation across deactivations and identical snapshots. Domain line coverage is 99.45% (359/361); JaCoCo reports at `target/site/jacoco/index.html`, without a gate. Integration tests cover migrations, health/auth/tenancy, ingestion/replay/ordering/rollback, ownership/concurrency, real-context restart recovery, timeouts/retention/rehire, CSV/200k safety-brake behavior, management-port isolation, rollback-safe caches, SQL-free scrapes, hybrid summary and PII boundaries. Phase 8 adds the separate memory benchmark and k6 load tests.

## Demo video
_link here_

## Local verification status

Verified on 2026-10-30 with Docker Desktop's Linux/WSL2 engine: clean-volume `docker compose up --build -d` succeeds. Readiness became UP in 33.36 seconds, and in 22.63 seconds after restarting the app. V1 defines 13 core tables; Phase 5 V2 adds processed_event alongside Flyway history; demo fixture counts are correct and unchanged after restart. All 30 captured application log lines were ECS JSON without fixture PII or sample secrets. Correlation headers and liveness pass. The container runs Temurin 21.0.13 as UID 10001 with writable `/data`.

Host Maven and Maven inside the build image reach Maven Central directly. The plain Docker build succeeds without HTTP_PROXY/HTTPS_PROXY build arguments, and Docker client automatic proxy defaults were unset. No proxy URLs, credentials, or network-specific defaults are committed. The temporary Maven settings used during initial setup remain outside the repository. First builds download images and dependencies; subsequent builds reuse the Maven build layers.

Domain line coverage after Phase 6 is 99.45% (359/361 lines, no gate).

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

Explicit ADVANCE actions preserve higher identical versions without business events. Apply rechecks planned versions under employee locks, has no receipts or nested event retries, and commits configured chunks (default 25 after measured tuning). Failed chunks roll back before per-action fallback. Startup resumes APPLYING runs, fails interrupted planning and preserves awaiting approvals; a heartbeat watchdog avoids failing live work. Multi-instance deployment would need leases and shared upload storage.

Demo OPERATOR endpoints seed S000001… fixtures with Java CRC32/COPY and stream deterministic CSV exports with seed/mutation/drop/new/truncation options. Seed fixtures intentionally bypass outbox/receipts. Export uses a read-only PostgreSQL cursor with fetchSize=1000. See the run JSON contracts in [API.md](docs/API.md) and the algorithm in [RECONCILIATION.md](docs/RECONCILIATION.md).
