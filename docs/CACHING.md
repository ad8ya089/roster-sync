# Caching

Plus-point **7**: "Integrate caching for hot data with a sensible eviction policy. Say exactly what can become stale, for how long, and how staleness is bounded."

Implementation: Spring Cache abstraction + Caffeine (`spring-boot-starter-cache`, `com.github.ben-manes.caffeine:caffeine`), stats enabled so Micrometer exports `cache_gets_total{result=hit|miss}`, `cache_evictions_total`, `cache_size`.

## What is cached

| Cache | Key | Value | Why hot | Policy |
|---|---|---|---|---|
| `offices` | `(tenantId, officeCode)` | `OfficeRef(id, code, active)` | looked up for **every** event and every one of 200k file rows during validation | `expireAfterWrite=10m`, `maximumSize=10_000`, evicted on office create/update |
| `employeeView` | `(tenantId, employeeId)` | read model for `GET /me` and `GET /employees/{id}` | employees open the app many times a day; HR changes are rare per person | `expireAfterWrite=30s`, `maximumSize=50_000`, evicted **after commit** whenever that employee changes |

## What is deliberately NOT cached

- Anything used to **decide** a write: the employee row in the applier (always `SELECT … FOR UPDATE` from the DB), receipts, versions.
- Brake settings: read from the DB at the start of every reconciliation run — a safety decision must not use stale data.
- Reconciliation reads: go straight to the DB per bucket.
- Auth: JWT validation is stateless; users are read on login only.

## Exactly what can be stale, for how long, and how it's bounded

| Stale data | Where | Max staleness | Bound / mitigation |
|---|---|---|---|
| Newly created office | all instances | no negative-cache delay | Unknown codes are never cached. The next lookup queries the DB and sees the committed office. |
| Deactivated office still accepted | other instances | 10 min | The FK guarantees the office still **exists**. The applier re-reads `office.active` inside its transaction before writing, so a deactivated office is rejected `INACTIVE_OFFICE` even if the cache says active. |
| Employee profile shows old office/shift | other instances | 30 s | TTL; the writing instance evicts in `TransactionSynchronization.afterCommit`, subject to the in-flight refill race below. |
| Employee profile cached **before** a concurrent write commits | writing instance | until next eviction or 30 s | eviction runs after commit, so the race window is "read started before commit, finished after" → bounded by the 30 s TTL. |

Why evict *after commit* and not inside the transaction: evicting inside the transaction lets a concurrent reader re-populate the cache with the old row before the commit — the classic cache-aside race. After-commit eviction narrows it to the window above.

Precisely: a cache-miss read can load the old row before the writer commits, pause, then put that old DTO into the cache after the writer's afterCommit eviction has already completed. Later reads can then see that DTO for up to 30 seconds from the cache insertion. After-commit eviction does not make reads linearizable. All write/version/ownership/reconciliation decisions bypass employeeView and read PostgreSQL; this race affects the displayed profile only. No stronger consistency guarantee is implemented or claimed.

`spring.cache.cache-names=offices,employeeView` declares both caches at startup. Each Caffeine cache records statistics and uses its own configured TTL/maximum. Keys are OfficeKey/EmployeeKey records; OfficeRef and EmployeeView are immutable DTO records, never attached JPA entities. Misses are not cached. An in-transaction office read always bypasses the cache and uses a plain READ COMMITTED read.

AfterCommitCacheEvictor covers event Apply and VersionAdvanced, transport changes, every applied recon action (CREATE/UPDATE/HEAL/ADVANCE/DEACTIVATE), office writes, and clearing the employeeView cache after dev seeding. Without transaction synchronization eviction runs immediately; otherwise only afterCommit performs it. Rolled-back work leaves the prior cached DTO untouched. NO_CHANGE still invalidates because hrVersion and hrUpdatedAt are visible to readers.

## Multi-instance story (for the interview)

Caffeine is per JVM. With N instances, staleness on the non-writing instances is bounded by TTL only. Options, in order of effort: keep TTLs short (current), publish an invalidation message on the outbox (`EmployeeUpdated` already exists — a sink could evict), or move to Redis with the same Spring Cache annotations.

## Tests

`CachingIT`: cache hit statistics, HR/transport freshness, VersionAdvanced, rollback preservation, immediate no-sync eviction, all recon action types, seed clearing, tenant/self isolation before lookup, no negative office entries, independent tenant office states, TTL/size policy and next-run settings freshness.
