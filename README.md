# HR Roster Sync — idempotent, out-of-order, self-healing

MoveInSync Backend Case Study 5 (Advanced Track). Java 21 · Spring Boot 4.1 · PostgreSQL 17 · one `docker compose up`.

> Status: **Phase 1 code implemented; Docker acceptance checks PENDING-DOCKER**. The scaffold, schema, demo seed, health probes and error foundation are present. Business APIs arrive in later phases. Benchmark results will be measured in Phase 8.

## Target behavior

- Ingests employee **create / update / deactivate** events from HR in near real time, ignoring **stale** and **duplicate** ones, and rejecting invalid records **per record** without failing the batch.
- Protects **transport-owned fields** (pickup point, special assistance) from HR overwrites.
- Reconciles a **nightly 200,000-row export** with a bounded-memory bucketed diff and a **safety brake** that refuses to deactivate anyone when the file looks truncated.
- Publishes a change event for every applied change through a **transactional outbox**; deactivations cancel future cab bookings and free seats.

## Run it

```bash
cp .env.example .env
docker compose up --build
curl localhost:8080/actuator/health/readiness
```
Requirements for the container run: Docker Desktop/Engine with Compose. No host Java or Maven is required. The guided `scripts/demo.sh` walkthrough will be added in Phase 8.

On Windows PowerShell, use `Copy-Item .env.example .env` for the first step and `curl.exe` for the readiness request. The app starts after PostgreSQL is healthy; Flyway creates the schema. Only port 8080 is exposed; PostgreSQL stays on the Compose network. The runtime is non-root and uploaded files will live on the `/data` volume.

Local builds require JDK 21 or newer; JDK 22 is supported with `maven.compiler.release=21`. The Maven wrapper downloads Maven automatically:

```bash
./mvnw -B test               # unit tests, no Docker
./mvnw -B verify             # unit + PostgreSQL integration tests, Docker required
./mvnw -B verify -Pbenchmark # includes the benchmark once added in Phase 8
```

In PowerShell, replace `./mvnw` with `.\mvnw.cmd`. Docker and CI use Temurin 21. A network proxy, if required by your machine, belongs in your local Maven settings rather than the repository.

Phase 1 provides public `/actuator/health/liveness` and `/actuator/health/readiness` probes. Readiness includes database connectivity. Other routes fail closed until JWT authentication and RBAC are implemented in Phase 3. Both demo and production logging use ECS JSON; correlation IDs are returned in `X-Correlation-Id`.

The `demo` profile seeds tenants `acme` and `globex`, offices `BLR-1`, `BLR-2`, inactive `BLR-3`, and `HYD-1`. Seeded usernames are `acme.hr`, `acme.admin`, `acme.operator`, `E1001`, `globex.hr`, `globex.admin` and `globex.operator`; their password comes from `DEMO_PASSWORD`. Acme has employees `E1001`–`E1005`, two future bookings and one completed booking for `E1002`, and route `R-BLR-07` with four seats. Restarting preserves existing data.

Compose defaults to `demo`. Ordinary production uses `prod` alone, without demo fixtures or dev endpoints. The interview deployment intentionally enables `prod,demo`; its future dev endpoints require OPERATOR authentication. Change all example credentials before exposing an interview deployment publicly.

## The rules, stated precisely

**Idempotency key:** `(tenantId, employeeId, version)`. **Version comparison** (per employee, under a row lock): `incoming > current` → apply · `incoming < current` → `STALE` (ignored) · equal → `DUPLICATE` if the payload hash matches, else `CONFLICT`. Events carry the **full HR-owned state** at that version, so the final state never depends on arrival order.

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
Phase 1 has 29 passing unit test cases across configuration, errors/correlation IDs and CRC32 partitioning. Seven PostgreSQL integration test cases cover migrations, health and demo seeding; execution is PENDING-DOCKER. Later phases add concurrency, failure-injection, reconciliation benchmarks and k6 load tests.

## Demo video
_link here_
