# Decisions & Trade-offs

Short ADRs. Each: decision → why → what we gave up → when we'd revisit. This file answers plus-point **5 (Trade-offs)** and part of **2 (Cost estimation / where it stops scaling)**.

## ADR-001 Java 21 + Spring Boot 4.1, Maven
- **Why:** Java is preferred by the brief and MoveInSync's stack. Boot 4.1 is the supported line (Boot 3.5 open-source support ended June 2026). Java 21 is an LTS with records, sealed types and pattern matching that make the domain model concise.
- **Gave up:** some blog/tutorial code targets Boot 3 (starter names, Jackson 2 packages, `@MockBean`). The Maven build uses Boot 4 starter names and Jackson 3 packages.

## ADR-002 Events are full-state snapshots ordered by a per-employee version
- **Why:** with full state, "apply only if newer" makes the final state independent of arrival order (the v7-before-v6 example ends with v7's shift automatically). Deltas would require buffering and reordering, or vector merges.
- **Gave up:** bigger payloads; HR must send the whole HR-owned record each time. Realistic: HRIS webhooks (Workday, SuccessFactors) and SCIM PUT are full-resource.
- **Revisit:** if a source can only send deltas → buffer per employee until the gap is filled, with a timeout, or rely on nightly heal.

## ADR-003 No message broker; transactional outbox in Postgres + poller
- **Why:** the brief wants one-command run; the outbox gives the "DB update and event can never disagree" guarantee with zero extra infrastructure. `FOR UPDATE SKIP LOCKED` makes the poller safe even with several instances.
- **Gave up:** latency (≤ poll interval, 500 ms) and DB load from polling; no fan-out to external consumers out of the box.
- **Revisit:** many consumers or > ~1k events/s → keep the outbox table and relay it to Kafka (poller or Debezium CDC on the WAL). The `EventSink` interface is the seam.

## ADR-004 At-least-once delivery + idempotent consumers (not "exactly once")
- **Why:** exactly-once across a DB and a broker needs distributed transactions; at-least-once + `eventId` dedupe + version-aware consumers gives the same observable result.
- **Gave up:** consumers must be written idempotently (ours are: cancel-if-scheduled, free-seat-if-held).

## ADR-005 Pessimistic per-employee row lock (`SELECT … FOR UPDATE`)
- **Why:** contention is per employee and tiny; locking makes the read-decide-write sequence trivially correct and easy to explain; out-of-order bursts don't cause optimistic-retry storms.
- **Alternative considered:** single conditional statement `UPDATE … WHERE hr_version < :v` (compare-and-set). Faster, lock-free, but we need the old row to compute changed fields and the outbox payload, so we'd still read it. Mention as an optimization.
- **Gave up:** a hot employee serializes (irrelevant at our scale); `lock_timeout=5s` bounds waits.

## ADR-006 Field ownership as configuration (`FieldOwnershipPolicy`)
- **Conflict rule (written down, as the brief demands):** *each field has exactly one owner. A write from a source may change only fields that source owns. Fields from a non-owner are ignored, not rejected, and reported as the warning `FIELD_NOT_OWNED_BY_SOURCE`; the rest of the record is applied. Ownership is never decided by timestamps between sources.*
- **Why ignore-with-warning instead of reject:** HR exports often contain every column; rejecting the whole record would block legitimate HR changes because of a column HR doesn't own.
- **Why config:** "move a field between owners" is a one-line YAML change + restart; the hash and validators read the same policy.
- **Gave up:** after an ownership change, existing data in that column is kept as is; the new owner overwrites it on its next write.

## ADR-007 Outbox payload carries non-PII values + names of changed PII fields
- **Why:** consumers (routing) need office/shift/status immediately; PII in a long-lived event log multiplies exposure and violates "never write personal data into logs" in spirit.
- **Gave up:** consumers needing name/phone must call back with their own credentials.

## ADR-008 Bucketed (Grace-hash) diff in Java instead of SQL diff
- See `RECONCILIATION.md §2`. Chosen for bounded memory **and** a pure, unit-testable core, as the brief's instruction 4 asks.

## ADR-009 Brake halts the whole run, not only deactivations
- **Why:** a file that is missing 40 % of people is likely corrupt in other ways (wrong export, wrong tenant, half-written). Human approves; `SKIP_DEACTIVATIONS` mode exists for "the rows that came are fine".
- **Gave up:** creates/updates from tonight's file wait for a human. The real-time feed keeps flowing meanwhile, so the cost is small.

## ADR-010 Deactivation side effects via the outbox (eventual), not in the same transaction
- **Why:** bookings and seats belong to another bounded context; this is exactly the "downstream effect" the outbox exists for; retries and DEAD-lettering come for free.
- **Gave up:** a window of ~1 s where a deactivated employee still has scheduled bookings. Booking creation checks status, so no new booking can be made for an inactive employee.
- **Alternative:** same transaction (simpler, strongly consistent) — reasonable in a pure monolith; say you'd pick it if bookings lived in the same module forever.

## ADR-011 Fail closed when the DB is unavailable (consistency over availability)
- **Why:** the HR system already retries; accepting events we cannot order or persist would trade a temporary 503 for silent corruption. In CAP terms the write path is CP: during a partition from Postgres we refuse writes.
- **Gave up:** ingestion availability during DB outages.
- **Revisit:** put a durable queue (Kafka, SQS) in front to accept-and-buffer, with the same idempotent applier behind it.

## ADR-012 Synchronous per-record processing of event batches
- **Why:** exact per-record report in the response; each record isolated in its own transaction.
- **Gave up:** latency for large batches (~2–5 ms per record → a 1000-event batch takes seconds). Batch size capped at 1000.
- **Revisit:** async 202 + status endpoint, or a queue.

## ADR-013 Our own JWTs (HS256), users in our DB
- **Why:** demonstrable auth with roles and tenant claims without running Keycloak in compose.
- **Gave up:** key rotation, refresh tokens, SSO, MFA. Production: an OIDC provider and RS256 with JWKS; the resource-server config barely changes.

## ADR-014 Tenant isolation in the application layer
- **Why:** tenant comes from the token into a request-scoped `TenantContext`; every repository method takes `tenantId`; cross-tenant reads return 404. Tests prove it per endpoint.
- **Revisit / defense in depth:** Postgres Row-Level Security with `SET app.tenant_id` per transaction.

## ADR-015 Local Caffeine cache, small TTLs, after-commit eviction
- See `CACHING.md`. Gave up cross-instance coherence (Redis or pub/sub invalidation would fix).

## Consistency vs availability vs latency — the summary table

| Path | Consistency | Availability | Latency | Choice |
|---|---|---|---|---|
| Event ingest | strong per employee (row lock, single primary) | fails closed on DB outage | ~ms per record, synchronous | consistency first |
| Downstream events | eventual (outbox), at-least-once | publisher retries independently of ingest | ≤ 0.5 s + sink time | availability of ingest is decoupled from consumers |
| Side effects (bookings/seats) | eventual | retried | ~1 s | acceptable window, guarded at booking creation |
| Employee reads | ≤ 30 s stale on *other* instances, fresh on the writer | served from cache if DB slow | sub-ms when cached | latency for reads, never for decisions |
| Nightly reconciliation | authoritative snapshot, applied with per-row re-checks | can be paused by the brake | ~10–30 s diff for 200k | safety over speed |

## Where the system stops scaling (say this out loud)

1. **Single Postgres primary** is the write ceiling: each applied event = ~4 writes (receipt, employee, outbox, later outbox update). Expect low thousands of events/s on a laptop-class DB. Next step: partition tenants across DBs (tenant is already in every key).
2. **Outbox poller**: one thread, batch 100, bounded by sink latency. Add workers (SKIP LOCKED) at the cost of strict per-employee ordering → consumers already use `version` to drop stale events.
3. **Table growth**: `outbox_event` and `hr_event_receipt` grow by every event; retention jobs delete old rows; at high volume use time-partitioned tables and drop partitions.
4. **Reconciliation apply** of mass changes is per-row; bulk first loads would need set-based SQL.
5. **Local disk + local cache + in-process scheduler** assume one instance; going multi-instance needs S3 for files, ShedLock (or a leader) for the schedulers, and Redis or short TTLs for caches.
6. **Synchronous batch API**: big batches hold an HTTP connection for seconds.

## Deliberately left out

Kafka/RabbitMQ, Debezium, Redis, Kubernetes, an OIDC provider, refresh tokens, Postgres RLS, PII encryption at rest (pgcrypto / KMS), right-to-erasure workflow, SFTP/S3 ingestion (a scheduled folder poller is the stub), a UI, multi-region, rate limiting per client (would add Bucket4j). Each has a one-line "why not now" above.
