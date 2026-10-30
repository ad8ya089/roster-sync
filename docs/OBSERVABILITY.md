# Observability — Metrics, Logs, the 3 AM View

Plus-point **6**: "Add metrics and structured logging. Expose the counters an on-call engineer would need at 3 AM (lag, error rate, rejections, retries, queue depth)."

## Stack

- Micrometer + Actuator + Prometheus registry, on the separate management listener `:8081`. All management endpoints are public on that listener; none are registered on `:8080`.
- Histograms and p50/p99 are enabled only for `roster.apply.duration`, `roster.ingest.lag`, and `http.server.requests`. Other timers, including reconciliation phases, export count/sum/max without histogram buckets.
- Structured logs: Spring Boot built-in structured logging, `logging.structured.format.console=ecs` (JSON, one line per event) in `prod`/`demo`; plain text for local `dev` if preferred. MDC keys appear automatically as JSON fields.
- Optional `docker compose --profile monitoring up` adds Prometheus + Grafana with a provisioned dashboard (`ops/grafana/roster-sync.json`). Not required for the one-command run.

## Metrics catalogue

| Need at 3 AM | Metric (Prometheus name) | Type | Tags |
|---|---|---|---|
| Are events flowing? outcome mix | `roster_events_total` | counter | `outcome` = applied/no_change/duplicate/stale/conflict/rejected/retryable_error, `type` |
| **Rejections** and why | `roster_rejections_total` | counter | `reason` (error code), `source` = api/file |
| **Error rate** | derived: `rate(roster_events_total{outcome=~"rejected|retryable_error|conflict"}[5m]) / rate(roster_events_total[5m])`; plus `http_server_requests_seconds_count{status=~"5.."}` | | |
| **Ingest lag** (HR change → receipt processing) | `roster_ingest_lag_seconds` | timer/histogram | — (uses `occurredAt` when present; negative clock-skew samples are ignored) |
| Apply latency | `roster_apply_duration_seconds` | timer | `outcome` |
| **Retries** (lock/race) | `roster_apply_retries_total` | counter | `reason` = lock_timeout/unique_race/serialization |
| **Queue depth** | `outbox_pending` | gauge | — |
| **Publish lag** | `outbox_oldest_pending_age_seconds` | gauge | — |
| Publish results | `outbox_published_total`, `outbox_publish_failures_total`, `outbox_retries_total` | counter | `sink`, `event_type` |
| Dead letters | `outbox_dead` | gauge | — |
| Side effects | `side_effects_bookings_cancelled_total`, `side_effects_seats_freed_total`, `side_effects_skipped_total{reason}` | counter | |
| Reconciliation | `recon_runs_total{status}`, `recon_brake_tripped_total{reason}`, `recon_runs_awaiting_approval` (gauge), `recon_phase_duration_seconds{phase=parse|diff|apply}`, `recon_actions_total{action,result}`, `recon_rows_rejected_total` | | |
| Cache | `cache_gets_total{cache,result}`, `cache_evictions_total{cache}`, `cache_size` | | |
| JVM / pool | built-in `jvm_memory_used_bytes`, `hikaricp_connections_active/pending` | | |

Gauges that need DB queries (`outbox_pending`, `outbox_oldest_pending_age_seconds`, `outbox_dead`, `recon_runs_awaiting_approval`) are refreshed by `GaugeRefresher` every 15 s into `AtomicLong`s. A scrape reads those values and never queries the DB. Until the first refresh they are zero; a failed refresh retains the last values and logs safe exception metadata.

Tags are finite enums or configured sink names. No metric has tenant IDs, employee IDs, usernames or PII tags: adding an ID would multiply every outcome/type/histogram series by the number of tenants or employees. Per-tenant state is available through the authenticated SQL summary. Cache names are declared at startup and Caffeine stats are enabled, allowing Boot to bind both caches automatically.

## `GET /api/v1/ops/summary` (OPERATOR) — the human-readable 3 AM view

```json
{
  "generatedAt": "2026-10-30T03:00:00Z",
  "durable": {
    "last15m": { "applied": 1203, "stale": 7, "noChange": 23, "rejected": 12, "conflict": 2 },
    "outbox": { "pending": 4, "dead": 0, "oldestPendingAgeSeconds": 0.7, "currentlyRetrying": 1 },
    "reconciliation": { "awaitingApproval": 1, "lastRun": { "runId": "…", "status": "AWAITING_APPROVAL", "brakeReason": "MISSING_RATIO", "missingActive": 80000 } },
    "openAlerts": 1
  },
  "sinceRestart": {
    "since": "2026-10-30T02:00:00Z", "instanceWide": true,
    "duplicates": 40, "lockRetries": 3, "outboxPublishFailures": 0,
    "p99ApplyMs": 18.4, "lagP99Seconds": 2.1
  }
}
```

`durable` is tenant-scoped SQL and survives application restart. The 15-minute window uses `received_at` on accepted APPLIED/STALE/NO_CHANGE receipts and `created_at` on rejections. VERSION_CONFLICT audit rows count as conflicts, separate from rejected rows. Outbox fields describe current state; currentlyRetrying means PENDING with attempts > 0. V4 adds the tenant/time and tenant/status indexes; it adds no rollup table.

`sinceRestart` contains instance-wide Micrometer counters plus p99 estimates, with the start timestamp. Counters reset on restart. Percentiles use Micrometer's rolling distribution window and can be null before any sample; they are not durable lifetime percentiles. The apply summary aggregates across outcome timers in a private, non-exported registry, avoiding double-counting Prometheus observations. Exact 15-minute rates/increases for duplicates, lock retries and publisher failures come from Prometheus `rate()` / `increase()`, which account for counter resets. The database stores state; Prometheus stores time series. No per-tenant minute row serializes ingestion.

Only OPERATOR can request the summary, and durable queries always use the token's tenant. The instance-wide label explicitly identifies the broader scope of counters and percentiles.

## Suggested alert rules (documented; `ops/prometheus/alerts.yml`)

- `outbox_oldest_pending_age_seconds > 60` for 5 m — consumers/publisher stuck.
- `outbox_dead > 0` — needs manual retry.
- `recon_runs_awaiting_approval > 0` for 30 m — a human must decide before morning shifts.
- rejection rate > 5 % for 10 m — HR sending bad data (often an office code missing on our side).
- `increase(roster_apply_retries_total[5m]) > 100` — lock contention / DB trouble.
- `up{job="roster-sync"} == 0` — application scrape unreachable. This does not prove database readiness; Docker checks the separate readiness probe including DB connectivity.

## Logging conventions

- One event name per line in the `message`, lowercase dotted: `hr.event.applied`, `hr.event.stale`, `hr.event.rejected`, `outbox.published`, `outbox.dead`, `recon.run.brake_tripped`, `side_effects.bookings_cancelled`.
- MDC: `correlationId`, `tenantId`, `employeeId`, `runId`, `eventId`.
- A TaskDecorator copies only the request correlation ID into recon and sink executors. Workers set their own run/event/employee IDs and restore or clear MDC in `finally`, including failure paths and queued approval work.
- Levels: INFO for state changes, WARN for conflicts/brake/dead letters, ERROR only for unexpected failures. Per-record rejections are DEBUG (counted by metrics) to avoid log floods from a bad batch.
- `LogEventSink` (the stand-in for a broker) logs `outbox.published` with `eventId, eventType, employeeId, version` — this is what the demo video shows.

## Health

`http://127.0.0.1:8081/actuator/health/liveness` (process up) and `/actuator/health/readiness` (DB reachable + Flyway done). Docker healthcheck uses 8081 readiness. All `/actuator/**` requests to port 8080 return 404.

## Local monitoring

Set `GRAFANA_ADMIN_PASSWORD` in the ignored `.env`, then run `docker compose --profile monitoring up -d`. Prometheus scrapes `app:8081` over the Compose network; UI ports 9090 and 3000 bind only to loopback. Grafana's provisioned **Roster Sync Operations** dashboard has outcome rates, outbox depth/dead/age, awaiting approvals, apply p50/p99, lag p99, lock retries, publish failures, cache hit ratios, JVM heap and scrape health. Default `docker compose up` starts only app and db.

Validate rules with:
```bash
docker run --rm --entrypoint promtool -v "${PWD}/ops/prometheus:/rules:ro" prom/prometheus:v3.15.1 check rules /rules/alerts.yml
```
Production publishes no management port. Caddy proxies only app:8080, and the security group never opens 8081; monitoring must use the private container network or an authenticated tunnel.
