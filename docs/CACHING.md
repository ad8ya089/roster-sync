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
| Newly created office not yet visible | other app instances (single instance: never, eviction is local) | 10 min | TTL. Effect: events for that office are rejected `UNKNOWN_OFFICE` and HR resends — no corruption. |
| Deactivated office still accepted | other instances | 10 min | The FK guarantees the office still **exists**. The applier re-reads `office.active` inside its transaction before writing, so a deactivated office is rejected `INACTIVE_OFFICE` even if the cache says active. |
| Employee profile shows old office/shift | other instances | 30 s | TTL; on the writing instance eviction happens in `TransactionSynchronization.afterCommit`, so a read right after the write is fresh. |
| Employee profile cached **before** a concurrent write commits | writing instance | until next eviction or 30 s | eviction runs after commit, so the race window is "read started before commit, finished after" → bounded by the 30 s TTL. |

Why evict *after commit* and not inside the transaction: evicting inside the transaction lets a concurrent reader re-populate the cache with the old row before the commit — the classic cache-aside race. After-commit eviction narrows it to the window above.

## Multi-instance story (for the interview)

Caffeine is per JVM. With N instances, staleness on the non-writing instances is bounded by TTL only. Options, in order of effort: keep TTLs short (current), publish an invalidation message on the outbox (`EmployeeUpdated` already exists — a sink could evict), or move to Redis with the same Spring Cache annotations.

## Tests

`CachingIT`: second `GET /me` is a cache hit (stats); HR update → next `GET /me` returns new data immediately (after-commit eviction); office created → immediately usable on the same instance; brake settings change takes effect on the very next run (no cache).
