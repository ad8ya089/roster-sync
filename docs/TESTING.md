# Test Strategy

"Advanced Track" requires: every hard rule has an automated test, at least one **concurrency** test and one **failure** test, plus a benchmark with throughput and p50/p99 reported with the machine spec.

## Layers

| Layer | Tooling | Runs with | Speed |
|---|---|---|---|
| Unit — pure domain (`domain` package) | JUnit Jupiter (Boot-managed 6), AssertJ, jqwik (property tests) | `mvn test` | ms, no Spring, no DB |
| Web slice | `@WebMvcTest` + `spring-boot-starter-webmvc-test`, `@MockitoBean` | `mvn test` | fast |
| Integration | `@SpringBootTest` + Testcontainers Postgres (`org.testcontainers.postgresql.PostgreSQLContainer`, `@ServiceConnection`), Awaitility | `mvn verify` (Failsafe, `*IT`) | needs Docker |
| Benchmark | JUnit `@Tag("benchmark")` IT, `-Xmx256m` | `mvn verify -Pbenchmark` | ~1–2 min |
| Load test | k6 in Docker against `docker compose up` | `./scripts/loadtest.sh` | 1–2 min |

One shared `AbstractIntegrationTest` starts a single Postgres container per JVM (static, reused across test classes) and truncates all business tables between tests (`TRUNCATE … RESTART IDENTITY CASCADE`). Auth/office tests reseed their demo fixtures after truncation.

## Test catalogue (agents create exactly these; names are referenced in README and traceability)

### Unit (domain)
| Class | Proves |
|---|---|
| `RecordValidatorTest` | phone E.164 ok/bad, email ok/bad, required fields per type, unknown office, inactive office, version ≤ 0, manager = self, lengths; errors are `{field, code}` with no values |
| `FieldOwnershipPolicyTest` | default split; HR snapshot filtered to HR fields; transport keys reported as `FIELD_NOT_OWNED_BY_SOURCE`; moving `PHONE` to TRANSPORT via config flips both directions |
| `ChangeDeciderTest` | v > current → APPLY with changed fields; v < → STALE; v == & same hash → DUPLICATE; v == & different → CONFLICT; snapshot mode HEAL; tombstone + lower CREATE → STALE; UPDATE for unknown → APPLY(create) |
| `ChangeDeciderPropertyTest` (jqwik, 1,000 tries, seed `2026103001`) | **for any permutation of a set of full-state events, the final state equals the max-version event and each version is applied at most once** — the formal version of "order doesn't matter" |
| `RecordHasherTest` | stable across runs; field order fixed; email case-insensitive; transport fields don't affect hash; null vs empty distinct |
| `BucketPartitionerTest` | CRC32 bucket stable for known IDs (golden values); distribution roughly uniform over 200k IDs (chi-square-ish bound) |
| `BucketDifferTest` | each row of the decision table in `RECONCILIATION.md §3`, incl. `hr_updated_at > asOf` guard and present-but-invalid |
| `SafetyBrakeTest` | 29.99 % passes, 30.00 % trips (boundary), count guard, trailer mismatch, expectedRowCount, zero active skips ratio/count guards but trailer/expected-count mismatches still trip, threshold configurable |
| `BackoffPolicyTest` | outbox backoff 1,2,4,…,300 s cap; DEAD after max attempts |
| `ArchitectureTest` | `domain` depends on no Spring, Jakarta, java.sql, infrastructure, application or API classes; controllers don't touch repositories; tenant repository methods take `tenantId` |

### Integration — hard rules
| Class | Proves (brief wording in quotes) |
|---|---|
| `ReplayIdempotencyIT` | "replays the same feed twice and changes nothing": send feed (50 events, 10 employees, mixed types) → snapshot DB (employee rows hash) and outbox count → send identical feed again → every result `DUPLICATE`, employee table identical, **outbox count unchanged**, receipts unchanged |
| `OutOfOrderIT` | brief example: v5 create, then v7 office transfer, then v6 shift change → v6 `STALE`; final office = v7's, shift = v7's; exactly 2 outbox events (create, update v7) |
| `BatchIsolationIT` | "one bad record must never fail a whole batch": 5 events with 2 invalid → 3 applied, 2 rejected with codes, rejection rows stored, invalid key not consumed (resend fixed → APPLIED) |
| `FieldOwnershipIT` | admin sets pickup point; HR update containing `pickupPoint` → HR fields applied, pickup point untouched, warning returned; admin PATCH with `email` → 400 |
| `DeactivationIT` | DEACTIVATE → status INACTIVE, outbox EmployeeDeactivated, then (await) future bookings CANCELLED, past bookings untouched, seat freed; re-hire before handler runs → bookings kept; booking creation for inactive → 409 |
| `TombstoneIT` | DEACTIVATE v5 for unknown employee, then CREATE v3 → STALE, employee stays inactive |
| `TenantIsolationIT` | see `SECURITY.md §3` |
| `AuthorizationIT` | role matrix: each endpoint × each role → expected 2xx/403; no token → 401; expired token → 401 `TOKEN_EXPIRED` |
| `PiiLeakIT` | canary PII never in logs or error bodies (see `SECURITY.md §4`) |
| `ErrorHandlingIT` | malformed JSON → 400 `MALFORMED_JSON` without content; oversize batch → 400; forced 500 → generic body + correlationId; DB unique-violation text never reaches client |
| `CachingIT` | see `CACHING.md` |
| `MetricsIT` | after a mixed batch, `/actuator/prometheus` shows `roster_events_total{outcome="stale"}` etc.; `/ops/summary` numbers match |

### Concurrency (≥ 1 required — we have 4)
| Class | Scenario |
|---|---|
| `ConcurrentEventsIT.sameEmployeeRandomOrder` | 16 threads, versions 1..200 for one employee shuffled → final `hr_version = 200` with v200 payload; number of APPLIED outcomes = number of outbox `Employee*` rows; no deadlocks; no lost update |
| `ConcurrentEventsIT.sameEventManyTimes` | 20 threads send the identical event → exactly 1 `APPLIED`, 19 `DUPLICATE`, 1 outbox row |
| `ConcurrentEventsIT.concurrentCreateRace` | 10 threads create the same new employee with versions 1..10 → exactly one row, version 10 |
| `ReconciliationConcurrencyIT` | real-time UPDATE v9 lands while a run planned v8 for the same employee → action `SUPERSEDED`, final v9; deactivation planned but employee updated meanwhile → `SUPERSEDED`; two uploads at once → one 202, one 409 |

All concurrency tests use a `CountDownLatch` start gate so threads really collide, and assert with Awaitility where async.

### Failure injection (≥ 1 required — we have 4)
`FaultInjector` is a Spring bean with named points; production code calls `faults.hit(FaultPoint.X)` at the exact spot. In tests it's armed per test; in the `demo` profile it can be armed via `POST /dev/faults`; in `prod` it is a no-op bean.

| Class | Fault point | Proves |
|---|---|---|
| `OutboxCrashRecoveryIT.crashAfterCommitBeforePublish` | publisher disabled (`roster.outbox.enabled=false`), apply event, **close the Spring context** (simulated process death), start a **new context** on the same DB with publisher enabled | the brief's "test that crashes the process between the database write and the event publish": event is published exactly once to the recording sink after restart; employee row and outbox row both present |
| `OutboxCrashRecoveryIT.crashInsideTransaction` | `APPLY_AFTER_EMPLOYEE_WRITE` throws `SimulatedCrashException` | employee update **and** outbox row **and** receipt all rolled back (atomicity); resend → APPLIED once |
| `OutboxCrashRecoveryIT.crashAfterSinkBeforeMark` | `OUTBOX_BEFORE_MARK_PUBLISHED` throws once | event delivered twice to the sink, consumer-side dedupe by `eventId` → side effect applied once |
| `OutboxRetryIT` | sink throws / times out N times | attempts increment, backoff respected, `outbox_retries_total` increments, DEAD after max, `/ops/outbox/{id}/retry` revives |
| `ReconciliationRecoveryIT` | `RECON_AFTER_N_ACTIONS` throws mid-apply, context restarted | run resumes to COMPLETED, no action applied twice |

There is also a **real kill -9 demo** (not a JUnit test): `scripts/demo-crash.sh` arms `OUTBOX_BEFORE_DISPATCH` with mode `HALT` (`Runtime.getRuntime().halt(137)`), sends an event, shows the container died, `docker compose` restarts it (`restart: unless-stopped`), and shows the event published after restart.

### Benchmark (`ReconciliationBenchmarkIT`, `-Pbenchmark`)
1. Seed 200,000 active employees for tenant `bench` via SQL `generate_series` (seconds).
2. Generate a 200,000-row CSV to a temp file from the same generator with 1 % changed rows (higher version), 0.5 % missing, 0.1 % new.
3. Force GC, record `MemoryMXBean` heap used; start a sampler thread recording max heap used every 50 ms.
4. Run the diff (dry run) → assert `diffMs < 60_000`; record `peakHeapDeltaMb`; assert plan counts match the generator's ground truth exactly.
5. Run again in apply mode for the 1 % changes and time it.
6. Print a Markdown table row (machine, JDK, cores, RAM, rows, diff ms, apply ms, peak heap) that is pasted into the README.
Failsafe config for this test: `<argLine>-Xmx256m -XX:+UseG1GC</argLine>` — passing with a 256 MB heap is the "bounded memory" proof.

Also `IngestMicroBenchmarkIT` (optional): 10k events single-threaded through the service → events/s.

### Load test (k6) — throughput + p50/p99 over HTTP
`loadtest/ingest.js`: gets a token in `setup()`, then 20 VUs for 60 s posting batches of 50 random events (90 % new versions, 5 % duplicates, 5 % stale) to `/api/v1/hr/events`, with thresholds `http_req_failed < 1 %`. `handleSummary` writes `loadtest/results/summary.json`. Run:

```bash
docker compose up -d
./scripts/loadtest.sh   # = docker run --rm -i --network host -v $PWD/loadtest:/scripts grafana/k6 run /scripts/ingest.js
```
Report in README: machine (CPU model, cores, RAM, OS, Docker resources), requests/s, events/s (= requests/s × 50), `http_req_duration` p50 / p99, error rate.

## Commands

```bash
mvn test                      # unit + slices (no Docker)
mvn verify                    # + integration tests (Docker required for Testcontainers)
mvn verify -Pbenchmark        # + 200k benchmark
./scripts/loadtest.sh         # k6 against the running compose stack
./scripts/demo.sh             # scripted curl walkthrough used in the video
```

## Phase 2 proof

`./mvnw test` runs the fixed-seed jqwik property and ArchUnit alongside deterministic unit cases. Jupiter uses the Boot-managed JUnit 6 runtime because Spring Framework 7's SpringExtension requires it; forcing Jupiter 5 causes NoSuchMethodError before integration setup. See https://docs.spring.io/spring/reference/testing/testcontext-framework/support-classes.html.

JaCoCo attaches to Surefire and Failsafe, and its report is bound to `verify` without a coverage gate. Open `target/site/jacoco/index.html`; the domain line coverage measured after Phase 2 is 99.40% (334 covered, 2 missed lines). The missing lines are the impossible-on-a-conforming-JDK SHA-256 algorithm exception path. `./mvnw jacoco:report` can also render existing execution data while Docker-dependent verification is blocked.
## Docker acceptance verification (2026-10-30)

Full `./mvnw -B verify` passes 99 unit/property/architecture tests and all 7 PostgreSQL integration tests, with zero failures, errors or skips. The fixed-seed property still completes 1,000 trials; domain line coverage remains 99.40%.

Clean-volume Compose startup passes: readiness UP in 33.36 seconds; after app restart, UP in 22.63 seconds with fixture counts unchanged. Verified 13 core tables plus Flyway history, 2 tenants, 4 offices, 7 users, 5 employees, 3 bookings (2 future scheduled, 1 past completed), and 4 seats with E1002 in seat 2. Thirty captured application log lines parse as JSON and contain no seeded PII or sample secrets. Temurin 21.0.13 runs as UID 10001 and `/data` is writable. No proxy build arguments were needed.

## Phase 3 proof (2026-10-30)

`./mvnw -B verify` passes 125 unit/property/architecture tests and 58 PostgreSQL integration tests (183 total), with zero failures, errors or skips. The Phase 2 follow-up adds a second fixed-seed jqwik property: each property executes 1,000 trials (seeds `2026103001`, `2026103002`). Mixed deactivations and identical snapshots preserve the maximum source version after every arrival. The tombstone v5 → DEACTIVATE v8 → CREATE v6 → CREATE v9 regression produces NO_CHANGE → STALE → CREATED at the decision boundary; transactional receipt/timestamp/outbox assertions belong to Phase 4.

- `JwtTokenServiceTest`: HS256 issuance, all roles/claims, EMPLOYEE identity, exact 30-second expiry allowance, missing/invalid claims, signature tampering, wrong key, short secret and UTF-8 byte counting.
- `LoginTimingTest`: one real BCrypt match per unknown/wrong-password attempt, verified by a spy; disabled and malformed EMPLOYEE accounts fail generically.
- `TenantContextFilterTest`: authenticated, failing, anonymous and second-tenant requests on the same thread cannot retain ThreadLocal/MDC state.
- `SecuredEndpointsTest` and `AuthorizationIT`: reflected method authorization, all registered protected mappings without tokens, role × office endpoint matrix, invalid JWT cases, role-before-lookup semantics, stateless account disabling, filter order and framework anonymous-route regression.
- `TenantIsolationIT`: cross-tenant GET/PATCH 404, same code in two tenants, duplicate 409, office/name boundaries, deactivation preserving existing employees, and INACTIVE_OFFICE through the database lookup port.
- `ErrorHandlingIT`: malformed JSON, invalid DTO/path/query input, 404/405/413/415/500/503, real PostgreSQL unique violation, 401/403 correlation and canary-free responses/logs. Test-only fault routes are absent from the packaged application.

Fresh-volume Compose build/run passes on Temurin 21. Curl obtains tokens for all seven demo accounts; acme.admin lists BLR-1/BLR-2/BLR-3, globex.admin lists HYD-1, and globex accessing BLR-1 returns 404. Anonymous offices, logout and OAuth metadata requests return 401. Domain coverage is rendered by JaCoCo at verify, without a gate.

Measured Phase 3 domain line coverage is 99.43% (349/351 lines). The final repository guard also checks inherited repository methods, excluding only java.lang.Object methods and the exact login-by-username exception; inheriting an unscoped CRUD query therefore fails the guard.

## Phase 4 proof (2026-10-30)
- ReplayIdempotencyIT verifies 50 duplicate replays, byte-identical employee/receipt row serialization, stable receipt counts and outbox totals, ignored metadata, type/content conflicts and the seed/recon conflict-correct-resend case.
- OutOfOrderIT.briefExample proves v7 office transfer defeats late v6 shift state. NO_CHANGE tests verify source version/timestamp advancement, receipt outcomes and no Employee event; TombstoneIT includes v5 -> DEACTIVATE v8 -> CREATE v6 -> CREATE v9.
- BatchIsolationIT covers three valid/two invalid records, corrected keys, unknown properties, malformed record types, DEACTIVATE field ignoring, empty/overlarge envelopes and declared/chunked 2 MiB limits.
- TransactionAtomicityIT confirms JpaTransactionManager, verifies employee+outbox+provisional receipt exist inside the transaction, then forces rollback and proves all three are removed/restored. It also verifies an inactive office found after initial validation and refreshing an already-managed JPA office before its active flag is used.
- TransportFieldsIT/FieldOwnershipIT/MovedPhoneOwnershipIT cover null/absent/no-op/inactive semantics, ownership warnings, malformed non-owned values, unknown keys and moving PHONE to transport ownership.
- EmployeeApiIT verifies paged DTOs, version-descending receipts without payloads, role/self-access and tenant isolation with identical external IDs. Existing annotation and registered-mapping guards include all new endpoints automatically.
- ConcurrentEventsIT runs 200 shuffled versions, 20 identical sends and a forced concurrent-create race; each uses a start latch and verifies Employee outbox rows == APPLIED count. ReceiptConcurrencyIT observes PostgreSQL's actual lock wait, then proves both first-commit and first-rollback outcomes. Each new integration test has a 30-second timeout. Test pools use eight connections to keep cached contexts below PostgreSQL's connection budget.
- PiiLeakIT exercises ingest, conflicts, rejections, transport changes and outbox JSON with canaries. Future phases extend it to publishing and reconciliation.

Final measured run: 136 unit/property/architecture tests + 92 PostgreSQL integration tests = 228, with zero failures/errors/skips. Two properties each run 1,000 fixed-seed trials. Domain line coverage is 99.44% (354/356), no gate.
