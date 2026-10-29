# Requirements Traceability Matrix

Every sentence of the case-study PDF, mapped to design, code and proof. Agents must keep this file accurate: when a task is done, fill the **Status** column (`done` / `partial` + note). Interviewers grade against the PDF; this file is how you show nothing was skipped.

## Problem statement

| # | PDF requirement | Design / code | Proof | Status |
|---|---|---|---|---|
| P1 | Changes arrive through an API in near real time | `POST /api/v1/hr/events`, `HrEventController` → `EventIngestionService` | `OutOfOrderIT`, `ReplayIdempotencyIT` | |
| P2 | Full nightly export of up to 200,000 employees arrives as a file | `POST /api/v1/reconciliation/runs` (multipart CSV); optional folder poller `roster.reconciliation.inbox-dir` | `ReconciliationFlowIT`, `ReconciliationBenchmarkIT` | |
| P3 | Updates arrive twice | receipt idempotency key | `ReplayIdempotencyIT`, `ConcurrentEventsIT.sameEventManyTimes` | |
| P4 | …arrive out of order | version comparison under row lock | `OutOfOrderIT`, `ChangeDeciderPropertyTest` | partial — domain ordering/property proof passes; OutOfOrderIT arrives in Phase 4 |
| P5 | …contain bad data | `RecordValidator` at the boundary | `RecordValidatorTest`, `BatchIsolationIT` | partial — deterministic all-error domain validation passes; boundary persistence arrives later |
| P6 | …are sometimes cut short | safety brake + trailer check | `SafetyBrakeTest`, `ReconciliationFlowIT.truncatedFile` | |
| P7 | Some fields belong to HR, some to the transport admin (e.g. preferred pickup point) | `FieldOwnershipPolicy`, `PATCH /employees/{id}/transport-fields` | `FieldOwnershipIT` | |

## I. Ingest

| # | PDF requirement | Design / code | Proof | Status |
|---|---|---|---|---|
| I1 | Create, update and deactivate events | `HrCommand` sealed hierarchy (`CreateEmployee`, `UpdateEmployee`, `DeactivateEmployee`) + `HrEventType` enum | `ChangeDeciderTest`, `DeactivationIT` | |
| I2 | …each with the source system's version or timestamp | `version` (long); timestamp sources send epoch millis — documented in README/API.md | `RecordValidatorTest` (version ≤ 0 rejected) | |
| I3 | Reject invalid records (malformed phone, unknown office code) | E.164 phone rule, office lookup via cache + in-tx check | `RecordValidatorTest`, `BatchIsolationIT` | |
| I4 | …with a per-record error report | response `results[i].errors[{field, code}]`; `record_rejection` table; `/reconciliation/runs/{id}/rejections` | `BatchIsolationIT`, `ReconciliationFlowIT.badRows` | |
| I5 | One bad record must never fail a whole batch | per-record transaction (`EmployeeChangeApplier` separate bean, `@Transactional`), file rows rejected individually | `BatchIsolationIT`, `ReconciliationFlowIT.badRows` | |

## II. Ordering, Idempotency & Ownership

| # | PDF requirement | Design / code | Proof | Status |
|---|---|---|---|---|
| O1 | Apply changes per employee in version order | `SELECT … FOR UPDATE` + `ChangeDecider` (apply iff newer) | `OutOfOrderIT`, `ConcurrentEventsIT.sameEmployeeRandomOrder` | partial — ChangeDecider and 1,000-trial permutation proof pass; DB locks/concurrency arrive in Phase 4 |
| O2 | A stale update is ignored | outcome `STALE`, no write, no outbox | `OutOfOrderIT` | partial — domain stale rule tested, including Long.MAX_VALUE; API proof arrives in Phase 4 |
| O3 | A duplicate is a no-op | receipt hit → `DUPLICATE`, no write, no outbox | `ReplayIdempotencyIT` | partial — domain equal-version duplicate rule tested; receipt replay proof arrives in Phase 4 |
| O4 | HR owns name, email, manager, office | default policy (+ phone, shift, status as HR fields — documented) | `FieldOwnershipPolicyTest` | done — default HR ownership and declaration order unit-tested |
| O5 | Transport admin owns pickup point and special-assistance needs | default policy | `FieldOwnershipPolicyTest`, `FieldOwnershipIT` | partial — transport ownership and moving PHONE unit-tested; API enforcement arrives in Phase 4 |
| O6 | An HR update never overwrites a transport-owned field | HR snapshot filtered by policy; transport columns not in HR write path | `FieldOwnershipIT` | |
| O7 | The conflict rule is written down | `DECISIONS.md` ADR-006 + README section "Field ownership conflict rule" | review | |

## III. Nightly Reconciliation

| # | PDF requirement | Design / code | Proof | Status |
|---|---|---|---|---|
| R1 | Compare the 200,000-row export with your store without loading both fully into memory | streaming parse + 256 hash buckets spilled to disk + per-bucket DB query | `ReconciliationBenchmarkIT` (`-Xmx256m`) | |
| R2 | …e.g. hash per record and hashed buckets | `RecordHasher` (SHA-256), `BucketPartitioner` (CRC32 mod 256), `BucketDiffer` | `RecordHasherTest`, `BucketDifferTest` | done — SHA-256 canonical hashing, CRC32 partitioning and full bucket decision table unit-tested |
| R3 | If tonight's export is missing 30 % of active employees… stop | `SafetyBrake`, run → `AWAITING_APPROVAL` | `SafetyBrakeTest`, `ReconciliationFlowIT.truncatedFile` | |
| R4 | …alert an operator | `ops_alert` row, outbox `ReconciliationBrakeTripped`, metric `recon_brake_tripped_total`, WARN log, `/ops/summary` | `ReconciliationFlowIT.truncatedFile` asserts alert + metric | |
| R5 | …deactivate nobody until a human approves | no action applied before `approve`; approve requires OPERATOR | `ReconciliationFlowIT` (count ACTIVE unchanged; approve/reject paths) | |

## IV. Downstream Effects

| # | PDF requirement | Design / code | Proof | Status |
|---|---|---|---|---|
| D1 | Publish a change event for every applied change | one outbox row per APPLIED decision, same transaction | `ReplayIdempotencyIT` (outbox count = applied count), `ConcurrentEventsIT` | |
| D2 | …using the outbox pattern, so the DB update and the event can never disagree | `outbox_event` + `OutboxPublisher` | `OutboxCrashRecoveryIT` (all three crash points) | |
| D3 | Leaver's future cab bookings cancelled | `DeactivationSideEffectHandler` | `DeactivationIT` | |
| D4 | …and their seat freed | same handler, `route_seat` | `DeactivationIT` | |

## Example use cases

| # | PDF example | Proof | Status |
|---|---|---|---|
| E1 | Office transfer v7 before shift change v6 → v6 stale, employee in new office with v7 shift | `OutOfOrderIT.briefExample` + demo step 3 | |
| E2 | File cut at 120,000 rows → brake stops run, nobody deactivated, operator sees how many records appear missing | `ReconciliationFlowIT.truncatedFileBriefExample` (200k seeded, 120k file) + demo step 8 | |

## Key points

| # | PDF key point | Where | Proof | Status |
|---|---|---|---|---|
| K1 | Define the idempotency key and the version comparison | README + `API.md` + `DESIGN.md §2` | — | |
| K2 | Show a test that replays the same feed twice and changes nothing | `ReplayIdempotencyIT` | itself | |
| K3 | Safety brake threshold configurable | property `BRAKE_MAX_MISSING_RATIO`, per-tenant `PUT /settings/reconciliation` | `SafetyBrakeTest`, `ReconciliationFlowIT.thresholdChangeTakesEffect` | partial — configurable exact brake thresholds unit-tested; tenant settings API arrives in Phase 6 |
| K4 | Never write personal data into logs or error reports. Use employee IDs | `SECURITY.md §4` | `PiiLeakIT` | |
| K5 | Diff a 200,000-row snapshot in under 60 s with bounded memory | `ReconciliationBenchmarkIT` | README benchmark table | |
| K6 | Run a test that crashes the process between the DB write and the event publish | `OutboxCrashRecoveryIT.crashAfterCommitBeforePublish` (+ live kill -9 demo) | itself | |

## Plus points (overall evaluation criteria)

| # | Plus point | Where | Proof | Status |
|---|---|---|---|---|
| X1 | Authentication + role-based access (employee vs admin vs operator) | `SECURITY.md §1–2` | `AuthorizationIT` | |
| X1b | Tenant isolation — one client can never read another's data | `SECURITY.md §3` | `TenantIsolationIT`, `ArchitectureTest` | |
| X2 | Time & space complexity of core logic + small benchmark; where it stops scaling and why | `RECONCILIATION.md §8`, `DECISIONS.md` "Where the system stops scaling", README complexity section | `ReconciliationBenchmarkIT`, k6 | |
| X3 | Fault tolerance: crashes, timeouts, duplicate & out-of-order messages, partial failures; ≥ 1 failure-injection test | `DESIGN.md §6`, `FaultInjector` | `OutboxCrashRecoveryIT`, `OutboxRetryIT` (timeouts), `ReconciliationRecoveryIT` (partial failure) | |
| X4 | OOP: encapsulation, inheritance, polymorphism; controller/service/repository layering | `docs/DESIGN.md` layering and entity/command hierarchies | `ArchitectureTest` + code review | partial — sealed commands, composed rules and domain/API ArchUnit checks pass; entity encapsulation arrives later |
| X5 | Trade-offs: consistency vs availability vs latency; what was left out | `DECISIONS.md` | — | |
| X6 | Metrics + structured logging; 3 AM counters: lag, error rate, rejections, retries, queue depth | `OBSERVABILITY.md` | `MetricsIT` | |
| X7 | Caching for hot data, eviction policy, what can be stale / how long / how bounded | `CACHING.md` | `CachingIT` | |
| X8 | Error handling framework, meaningful non-leaking messages, no PII in logs or error responses | `SECURITY.md §5` | `ErrorHandlingIT`, `PiiLeakIT` | partial — safe error/correlation foundation tested; full mappings and PII-flow proof arrive later |

## Advanced track

| # | Requirement | Where | Status |
|---|---|---|---|
| A1 | Every hard rule has an automated test, incl. ≥ 1 concurrency and ≥ 1 failure test | this file + `TESTING.md` | |
| A2 | Load test / benchmark with throughput and p50/p99, in README with the machine | `TESTING.md` benchmark + k6; README "Benchmarks" | |
| A3 | Design note (1–2 pages): architecture diagram, sequence diagram for hardest flow, failure-mode table | `docs/DESIGN.md` | |
| A4 | One-command run incl. DB/broker | `docker compose up` | partial — scaffold implemented; Compose readiness/schema/log checks PENDING-DOCKER |

## Instructions

| # | Instruction | Where | Status |
|---|---|---|---|
| N1 | Understand requirements, inputs, outputs, hard rules, scale targets | this file | |
| N2 | Java + Spring Boot | ADR-001 | done — Boot 4.1.2; Java release 21 verified on local JDK 22 |
| N3 | Plan first: data model, components, APIs, failure modes — in the design note | `docs/` written before code | done — architecture, schema, API and failure modes documented before implementation |
| N4 | Clean layered code, meaningful names; core algorithm in plain classes testable without web server or DB | `domain` package, `ArchitectureTest` | done — pure Java decision/diff/brake core with ArchUnit enforcing dependency boundaries |
| N5 | Unit tests for core logic + concurrency, failure, benchmark tests; normal inputs AND edge cases | `TESTING.md` | |
| N6 | README: how to run, assumptions, benchmark results, trade-offs note | `README.md` | |
| N7 | Public GitHub repo; meaningful commit history, not one giant commit | Conventional Commits, small verified commits | |
| N8 | Demo video: main flows, one failure scenario live, sample API calls (Postman/curl) | `docs/DEMO_SCRIPT.md`, `scripts/demo.sh`, `postman/` collection | |
