# Decisions & Trade-offs

## Phase 8 apply batching and lock scope
Reconciliation locks the selected employee rows in external-ID order using one tenant-scoped PESSIMISTIC_WRITE query (PostgreSQL FOR NO KEY UPDATE). The locking query deliberately has no joined fetch: Hibernate otherwise separates the fetch and lock steps. Submitted offices are read and refreshed once per distinct code inside the chunk transaction, with no office lock and no application cache. Deactivation after that plain read remains the documented benign READ COMMITTED race.

Commit `461da33` scopes this lock to employees in the chunk, not the tenant row, a table-wide blocking lock or an advisory lock. The captured Hibernate SQL for 40 IDs is exactly:

```sql
select ee1_0.id,ee1_0.created_at,ee1_0.deactivated_at,ee1_0.deactivation_source,ee1_0.email,ee1_0.external_id,ee1_0.hr_updated_at,ee1_0.hr_version,ee1_0.manager_external_id,ee1_0.name,ee1_0.office_id,ee1_0.phone,ee1_0.pickup_point,ee1_0.recon_bucket,ee1_0.shift_code,ee1_0.special_assistance,ee1_0.status,ee1_0.tenant_id,ee1_0.transport_updated_at,ee1_0.transport_version,ee1_0.updated_at
from employee ee1_0
where ee1_0.tenant_id=? and ee1_0.external_id in (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
order by ee1_0.external_id for no key update of ee1_0
```

`IN` binds the selected IDs individually, equivalent in scope to `external_id = ANY(?)`. `FOR NO KEY UPDATE` protects these rows' version/content writes while allowing ordinary foreign-key key-share checks. PostgreSQL still takes its normal compatible relation locks; unrelated employee updates are not serialized. `ReconciliationChunkIT` holds the chunk transaction open with a latch and proves both that a same-employee event waits and that an employee outside the chunk completes before release. The brief tenant-row lock for upload/watermark coordination and the demo-seeder advisory lock are separate paths, never the chunk/event apply lock.

FlushMode.COMMIT is scoped to the chunk and restored in finally, preventing query-triggered flushes per action. One explicit employee flush precedes a JDBC outbox batch, one recon_action status UPDATE using id=ANY arrays, and one run-counter/heartbeat update. All share the same transaction; failure rolls back before individual-action fallback. API ingestion retains its per-record saveAndFlush/receipt behavior. Version advances still produce no business outbox event. The measured default chunk increases from 25 to 40; raw profiling results stay outside Git.

Cancelling in-flight runs is deliberately left out: it would need cooperative cancellation between chunks and a CANCELLED state with defined partial-apply behavior. The re-runnable demo logs an active run's status every two seconds, waits at most 180 seconds, rejects it only at AWAITING_APPROVAL, and exits non-zero with runId/status on timeout.

## Bulk demo timeout allowance
Seed COPY and streaming export execute `SET LOCAL statement_timeout = '5min'` inside their own transactions. The normal connection setting stays 30 seconds and is restored automatically after commit or rollback. Fixture generation prioritizes robustness over the earlier arbitrary 20-second seed target; no additional seed optimization is required. Reconciliation apply and ordinary API statements retain their existing limits.

## ADR-018 State in SQL, time series in Prometheus
The operational summary separates tenant-scoped durable database state from explicitly instance-wide `sinceRestart` counters and rolling p99 estimates. Receipts/rejections reconstruct accepted outcomes and audited failures for the last 15 minutes. They cannot reconstruct replay deliveries, lock retries or historical publisher failures, so those remain Micrometer counters. Prometheus rate/increase handles reset-aware windows. No minute-rollup hot row serializes ingestion. V4 adds only the requested SQL indexes. Gauges refresh into AtomicLongs every 15 seconds; scraping does no database work.

Labels never contain tenant IDs, employee IDs or PII: finite outcome/reason/sink/type labels keep cardinality bounded even for 200k employees. Per-tenant views use the authenticated SQL endpoint. Only apply latency, ingest lag and HTTP requests have histograms and p50/p99. Negative clock-skew lag is ignored.

## ADR-019 Management isolation and local immutable caches
All actuator endpoints live on a separate public management listener 8081; API 8080 has no actuator handlers. Local Compose binds 8081 to loopback. Production publishes management only on 127.0.0.1:8081 for SSM forwarding, proxies app:8080 through Caddy, and never opens 8081 in the security group. Boot 4's child management context gets its own security configuration outside the application component scan, avoiding a permit-all chain in the API context.

Caffeine caches immutable DTOs under tenant-inclusive record keys. Unknown offices are not cached; write decisions always use database state. Employee writes evict after commit, including version-only advances and every recon action. Rollback preserves cached data. Cross-instance invalidation remains bounded by TTL; shared invalidation is deferred until multi-instance deployment. Executor TaskDecorators propagate only correlationId, and workers scope their own IDs with finally cleanup.

Short ADRs. Each: decision → why → what we gave up → when we'd revisit. This file answers plus-point **5 (Trade-offs)** and part of **2 (Cost estimation / where it stops scaling)**.

## ADR-001 Java 21 + Spring Boot 4.1, Maven
- **Why:** Java is preferred by the brief and MoveInSync's stack. Boot 4.1 is the supported line (Boot 3.5 open-source support ended June 2026). Java 21 is an LTS with records, sealed types and pattern matching that make the domain model concise.
- **Gave up:** some blog/tutorial code targets Boot 3 (starter names, Jackson 2 packages, `@MockBean`). The Maven build uses Boot 4 starter names and Jackson 3 packages.

## ADR-002 Events are full-state snapshots ordered by a per-employee version
- **Why:** with full state, "apply only if newer" makes the final state independent of arrival order (the v7-before-v6 example ends with v7's shift automatically). Deltas would require buffering and reordering, or vector merges.
- **Version-only changes:** a newer event with the same resolved HR hash yields `VersionAdvanced` / `NO_CHANGE`. Advance the HR version and timestamp without a business event; `Apply` retains its non-empty-field invariant. A repeated DEACTIVATE must advance tombstones too, otherwise v5 + DEACTIVATE v8 could admit a stale CREATE v6 and resurrect the employee. Only APPLIED business changes produce Employee outbox events.
- **Gave up:** bigger payloads; HR must send the whole HR-owned record each time. Realistic: HRIS webhooks (Workday, SuccessFactors) and SCIM PUT are full-resource.
- **Revisit:** if a source can only send deltas → buffer per employee until the gap is filled, with a timeout, or rely on nightly heal.

## ADR-003 No message broker; transactional outbox in Postgres + poller
- **Transaction scope:** one claimed row per transaction, including its bounded dispatch; never one transaction across the batch. One production poller selects due rows by id; retries scheduled for later do not block newer rows. Multiple pollers are safe with SKIP LOCKED, but neither retries nor multiple pollers promise global delivery order.
- **Why:** the brief wants one-command run; the outbox gives the "DB update and event can never disagree" guarantee with zero extra infrastructure. `FOR UPDATE SKIP LOCKED` makes the poller safe even with several instances.
- **Gave up:** latency (≤ poll interval, 500 ms) and DB load from polling; no fan-out to external consumers out of the box.
- **Revisit:** many consumers or > ~1k events/s → keep the outbox table and relay it to Kafka (poller or Debezium CDC on the WAL). The `EventSink` interface is the seam.

## ADR-004 At-least-once delivery + idempotent consumers (not "exactly once")
- **Timeout and reordering:** a timed-out sink keeps running and may commit after the publisher records a failure. Consumers deduplicate `(sink,event_id)` in their own transaction and check current employee state/version. A delayed deactivation skips ACTIVE employees after rehire; event versions need not change for reconciliation deactivation. The handler locks employees FOR UPDATE; booking creation uses FOR SHARE. Never infer current state from arrival order alone.
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
- **Account disabling:** bearer validation is stateless and performs no user lookup. A disabled user's existing token stays valid until its one-hour expiry (plus the 30-second clock allowance). New logins fail immediately. A short TTL limits this window; production would add a revocation list or an IdP with centrally managed sessions/revocation.
- **Expiry classification:** use a dedicated validator error code and typed exception causes, avoiding fragile matching of library exception messages.

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

Kafka/RabbitMQ, Debezium, Redis, Kubernetes, an OIDC provider, refresh tokens, Postgres RLS, application-level field encryption (for example pgcrypto), right-to-erasure workflow, SFTP/S3 ingestion (a scheduled folder poller is the stub), multi-region, rate limiting per client (would add Bucket4j). AWS EBS/S3 encryption at rest and SSM SecureString are implemented; they are distinct from field encryption. Each omitted component has a one-line "why not now" above.

## Phase 4 transaction choices
- Receipt INSERT reserves a key before locking the employee. Unique-create collisions abort PostgreSQL transactions; retry the entire apply call once from the non-transactional ingestion service. Lock failures get up to three retries (four total attempts), with 20–100 ms jitter outside transactions.
- Rejections/conflicts are audited through REQUIRES_NEW only after the failed apply transaction has rolled back. Conflict payloads never replace accepted receipt hashes.
- Office activity is checked with a plain READ COMMITTED read and refreshed explicitly without a lock: a query returning an already-managed office may otherwise retain stale first-level-cache fields. DEACTIVATE carries existing office references and does not introduce/revalidate a submitted office.
- The APPLY_AFTER_EMPLOYEE_WRITE hook runs after employee flush and outbox insertion but before commit, allowing one fault to prove JPA/JDBC/receipt atomicity. It remains inert in deployed code during Phase 4.
- Outbox JSONB is serialized with Boot's Jackson 3 JsonMapper and bound using CAST(? AS jsonb) on the JpaTransactionManager connection.

For early JSON body rejection, Tomcat drains at most 4 MiB (OVERSIZE_SWALLOW_LIMIT), while the application body remains capped at 2 MiB plus one detection byte. The bounded drain lets modest oversized requests receive their 413 response. See the [Tomcat HTTP connector reference](https://tomcat.apache.org/tomcat-11.0-doc/config/http.html#Standard_Implementation) for maxSwallowSize; both declared/chunked integration checks and the Compose curl oversized-body check pass.

## Office-read follow-up (Phase 5 entry)
The previous office mode was JPA PESSIMISTIC_READ (PostgreSQL FOR SHARE), including refresh. It is now a plain READ COMMITTED lookup/refresh with LockModeType.NONE: office rows are not locked by ingestion. A deactivation just after the read is a benign race; the FK guarantees existence and the next event observes the inactive office. This avoids per-office contention and multixact overhead. Employee version/order locks remain unchanged.

## Phase 5 operational limits
- The scheduled production poller is single-instance and uses id order among due rows. SKIP LOCKED permits concurrent publishers, proved with 500 rows, but retries deliberately allow later ids through. Consumers cannot rely on global delivery order.
- Retention is looped in 5,000-row REQUIRES_NEW transactions to release locks between batches. One scheduler instance is assumed; use ShedLock or equivalent coordination before multi-instance scheduling. Runtime timestamps come from Clock; SQL defaults remain schema defaults only.
- Demo faults are instance-wide diagnostics, not tenant data. HALT is enabled only with demo and requires OPERATOR; prod alone has no dev controllers. Outbox/alert access and mutations always scope by the caller's tenant.
- Bookings/seats remain a deliberately small JDBC-backed transport stub; bulk cancellation/free-seat operations share the consumer transaction. Employee access stays through tenant-explicit JPA repositories and their row locks.

## ADR-016 Ordering applies to whole files too
A tenant's completed export is a whole-file watermark: reject asOf <= the last COMPLETED asOf with RECON_EXPORT_STALE. Otherwise an older complete file could deactivate people legitimately added by a newer export. Upload acceptance locks the tenant row around this check and run insertion; employee ordering remains independent. Failed, rejected and dry-run exports do not advance this watermark. Future asOf is limited to five minutes of clock allowance.

## ADR-017 Bounded binary spill and resumable reconciliation
Binary DataOutputStream records use explicit UTF-8 byte lengths and a null marker, preserving tabs, quotes, newlines and nulls without a second escaping format. The file and database are compared one CRC32 bucket at a time. Invalid present identifiers suppress deactivation; ambiguous equal-version duplicates suppress updates too. Only one bucket is materialized. Spill directories are generated UUID directories and always cleaned in finally.

Planning has no outer transaction. Counters/heartbeats commit per bucket. Applying locks at most one configured chunk in external_id order; chunk failure rolls back before individual-action fallback. Snapshot writes use the existing applier/decider without receipts or nested lock retries. ADVANCE preserves the source watermark without an outbox event. Real-time events touching a locked employee wait at most that chunk's duration (subject to the configured 5s lock_timeout).

Startup recovery assumes one instance and treats old UPLOADED/DIFFING workers as interrupted, resumes APPLYING and preserves AWAITING_APPROVAL. The five-minute watchdog only considers heartbeats older than two minutes and skips local live/queued jobs. Multi-instance operation requires a lease and shared upload storage. An optional folder poller is disabled without RECON_INBOX_DIR; it copies files without deleting the original and uses file modification time as asOf, subject to normal whole-file ordering.

Phase 6 measured tuning: the initial 500-action chunk reached 7,676 ms under integration instrumentation, exceeding the 5s lock timeout. The first reduction used 100 (RECON_CHUNK_SIZE remains configurable); a subsequent 200k run measured 1,983 ms maximum. The diff's <60s target is separate from total apply time. The slow 200k test allows time for transactional application and independently asserts diff duration, chunk duration and seed duration.

Fresh fixture seeding uses direct COPY when the requested S-id range is empty, avoiding per-row speculative ON CONFLICT insert overhead. Repeated complete seeds return quickly without rewriting rows; partial seeds use staging plus ON CONFLICT DO NOTHING. A tenant-derived advisory transaction lock serializes only demo seeders and does not lock tenant/office rows against event foreign keys. Upload acceptance and COMPLETED transitions share a brief tenant-row lock so completion cannot race past the whole-file watermark check.

Final Phase 6 tuning and proof scope: a full-suite 100-action transaction later reached 7,279 ms, so the configured default was reduced again to 25. The integration JVM has a 1 GiB heap and at most six cached Spring contexts, preventing idle framework contexts from accumulating during the performance check. A subsequent 25-action run measured 1,379 ms maximum; diff times remained below 60 seconds. Seed timing is recorded in the instrumented test, while its <20s local runtime target is verified separately against Compose (16,135 ms on the first isolated smoke). This keeps host instrumentation/build contention distinct from the runtime target. Comparison and storage of asOf both truncate to database microsecond precision, preventing rounding from admitting an identical completed export.

Final full-suite measurement with the25-action default: maximum chunk668ms; healthy200k diff31159ms, apply24395ms; truncated diff29191ms; seed16934ms. Both diffs are under60s and lock duration is well below5s. Full verification passes272 tests. The isolated peak-heap benchmark remains Phase8 work; these numbers are phase acceptance evidence, not a claimed publication-ready memory benchmark.

Phase 8 measurement scope: the matched 64-action profiling comparison improved 89.6 to 380.3 actions/s and reduced JDBC execute/batch/commit calls from 6.08 to 0.17 per action. Earlier 40-action mixed-file samples measured median 303–304 actions/s and maxima 779–1,265ms. Additional instrumented probes measured 251–258 actions/s and cold maxima 833–1,605ms; a stressed 64-action probe reached 6,140ms. Cold JIT, class loading, instrumentation and host scheduling affect tail latency; those causes were not isolated individually. These are measurements, not a universal sub-second guarantee.

The approved final decision keeps chunk size 40 and replaces the former maximum <1s target with **p99 <1s AND max <2.5s**, half the configured 5s lock_timeout. Cold chunks are included, not discarded. Old summary files recorded maxima only, so p50/p99 cannot honestly be reconstructed from them; the same three-sample harness now retains every chunk duration, including transaction commit, and asserts both bounds. Seed optimization remains deferred; transaction-local five-minute timeouts improve robustness without relaxing the global 30s limit.


Final three-sample distribution (237 nonempty committed chunks, cold work included): p50 127.6 ms, p99 310.0 ms, max 1,253.6 ms. Both approved bounds pass; chunk size 40 is retained and the acceptance item is closed. Median dry-run wall 17.067 s, diff 16.954 s, apply 10.563 s (296.5 actions/s), sampled peak heap 103.62 MiB at -Xmx256m. This repeat is about 1.2% below 300 actions/s; earlier repeats reached 303–304. The throughput floor is not consistent across runs. Cold variation is a measurement limitation, not a reason to discard outliers. No further application optimization was requested.

The local ingestion matrix keeps Hikari at 20: batch-50 throughput at 1/5/10/20 VUs is 75.9/245.1/322.1/405.0 events/s. Pending connections peak at one only in the normal 20-VU case. WALWrite/WalSync spot samples and a diagnostic asynchronous-commit repeat (492.0 events/s, +21.5%) show durable commit I/O contributes, without isolating every remaining SQL/CPU/coordination cost. Restore synchronous_commit=on before further checks; never use the diagnostic result as a durable production rate. Queue growth to 22,260 pending rows means ingress burst capacity exceeds the single publisher's delivery capacity. The pool remains unchanged rather than increasing concurrency against those costs.

## ADR-020 Same-origin operator console (Phase 9)
The console is a static React build inside the Spring Boot jar. Hash routing avoids server fallbacks and preserves API/unknown-route behavior; Node exists only in the build stage. Tokens stay in memory and tenant query data is cleared on logout/expiry. Reloading deliberately requires signing in again. Role-based menus are display hints; existing server role and tenant checks remain authoritative.

The only API addition is read-only reconciliation createdAt from the existing database timestamp; Uploaded and Export taken are separate labels. Version values use JSON.parse source-text revival to strings, without a big-number dependency. Loaded receipts are sorted by receivedAt and labelled First arrival of each version, not a replay-delivery audit. Operator booking reads stay disallowed. Dashboard rejection share uses rejected / (applied + stale + noChange + rejected + conflict), zero for an empty denominator, labelled as durable recorded outcomes. Since-restart counters never enter that fraction.

The canonical design is the dispatch board in FRONTEND.md: self-hosted Overpass fonts, semantic tokens, route-line sequences and one amber safety-brake surface. Styleguide and username hints are compile-time opt-ins for local review and default off in the AWS build. The mandatory design checkpoint was approved before the other screens were implemented. Local verification precedes an explicit pre-deploy report; the AWS console redeploy remains pending review.

HR cannot read employees or offices. Scenario presets therefore use fresh UI-prefixed identifiers and successful in-memory session state, never a privileged lookup or persistent browser storage. The brief preset requires the demo tenant's active BLR-1/BLR-2 offices; unavailable offices show the real server rejection. Employee search uses the existing exact ID lookup rather than introducing a new API filter. Transport editing preserves absent versus explicit-null intent; unchanged keys are omitted and no-op Save stays disabled. OPERATOR sees employee details/receipts without bookings or transport writes. CSV generation/download and upload share the authenticated client and remain same-origin.

## Staff access and presentation follow-up (31 October 2026)

The user chose clear access help instead of public self-registration. Prepared staff accounts remain tenant/role scoped. The browser's public account-access route can prefill a documented username, but cannot create an account, assign a company/role or supply a password. Generic sign-in failures do not distinguish unknown, disabled or wrong-password accounts. Account provisioning remains the existing deployment/seeding responsibility, rather than an unimplemented sign-up form.

The sign-in screen uses two columns where space permits and reflows to one on narrow screens. Long content retains normal vertical scrolling rather than shrinking text or clipping controls. Failed sign-in uses a compact message with a collapsed support reference. Inline links are underlined, with the same keyboard focus and theme tokens as the console.

Google Safe Browsing reputation and Content Security Policy are separate checks. A successful TLS response and an eval-blocking policy do not prove that a browser reputation warning is cleared. The public deployment status remains explicit while the owner verifies the property and investigates Security issues. No unsafe-eval exception or disabled certificate verification is part of this follow-up.
