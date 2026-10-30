# Observability — Metrics, Logs, the 3 AM View

Plus-point **6**: "Add metrics and structured logging. Expose the counters an on-call engineer would need at 3 AM (lag, error rate, rejections, retries, queue depth)."

## Stack

- Micrometer + `spring-boot-starter-actuator` + `micrometer-registry-prometheus` → `/actuator/prometheus`.
- `management.metrics.distribution.percentiles-histogram.http.server.requests=true` and percentiles `0.5, 0.99` so p50/p99 are visible without Grafana.
- Structured logs: Spring Boot built-in structured logging, `logging.structured.format.console=ecs` (JSON, one line per event) in `prod`/`demo`; plain text for local `dev` if preferred. MDC keys appear automatically as JSON fields.
- Optional `docker compose --profile monitoring up` adds Prometheus + Grafana with a provisioned dashboard (`ops/grafana/roster-sync.json`). Not required for the one-command run.

## Metrics catalogue

| Need at 3 AM | Metric (Prometheus name) | Type | Tags |
|---|---|---|---|
| Are events flowing? outcome mix | `roster_events_total` | counter | `outcome` = applied/no_change/duplicate/stale/conflict/rejected/retryable_error, `type` |
| **Rejections** and why | `roster_rejections_total` | counter | `reason` (error code), `source` = api/file |
| **Error rate** | derived: `rate(roster_events_total{outcome=~"rejected|retryable_error|conflict"}[5m]) / rate(roster_events_total[5m])`; plus `http_server_requests_seconds_count{status=~"5.."}` | | |
| **Ingest lag** (HR change → applied) | `roster_ingest_lag_seconds` | timer/histogram | — (uses `occurredAt` when present) |
| Apply latency | `roster_apply_duration_seconds` | timer | `outcome` |
| **Retries** (lock/race) | `roster_apply_retries_total` | counter | `reason` = lock_timeout/unique_race/serialization |
| **Queue depth** | `outbox_pending` | gauge | — |
| **Publish lag** | `outbox_oldest_pending_age_seconds` | gauge | — |
| Publish results | `outbox_published_total`, `outbox_publish_failures_total`, `outbox_retries_total` | counter | `sink`, `event_type` |
| Dead letters | `outbox_dead` | gauge | — |
| Side effects | `side_effects_bookings_cancelled_total`, `side_effects_seats_freed_total`, `side_effects_skipped_total{reason}` | counter | |
| Reconciliation | `recon_runs_total{status}`, `recon_brake_tripped_total{reason}`, `recon_runs_awaiting_approval` (gauge), `recon_phase_duration_seconds{phase=parse|diff|apply}`, `recon_actions_total{action,result}`, `recon_rows_rejected_total` | | |
| Cache | `cache_gets_total{cache,result}`, `cache_evictions_total{cache}` | | |
| JVM / pool | built-in `jvm_memory_used_bytes`, `hikaricp_connections_active/pending` | | |

Gauges that need DB queries (`outbox_pending`, `outbox_oldest_pending_age_seconds`, `outbox_dead`, `recon_runs_awaiting_approval`) are refreshed by a `@Scheduled` job every 15 s into `AtomicLong`s — never computed on scrape (a slow DB must not make `/actuator/prometheus` time out).

## `GET /api/v1/ops/summary` (OPERATOR) — the human-readable 3 AM view

```json
{
  "generatedAt": "2026-10-30T03:00:00Z",
  "ingest":   { "last15m": { "applied": 1203, "noChange": 23, "duplicate": 40, "stale": 7, "conflict": 0, "rejected": 12, "retryableError": 0 },
                "rejectionRate": 0.0095, "topRejectionReasons": { "UNKNOWN_OFFICE": 9, "INVALID_FORMAT": 3 },
                "p99ApplyMs": 18.4, "lagP99Seconds": 2.1, "retriesLast15m": 3 },
  "outbox":   { "pending": 4, "oldestPendingAgeSeconds": 0.7, "dead": 0, "failuresLast15m": 0 },
  "reconciliation": { "awaitingApproval": 1, "lastRun": { "runId": "…", "status": "AWAITING_APPROVAL", "brakeReason": "MISSING_RATIO", "missingActive": 80000 } },
  "openAlerts": 1
}
```

## Suggested alert rules (documented; `ops/prometheus/alerts.yml`)

- `outbox_oldest_pending_age_seconds > 60` for 5 m — consumers/publisher stuck.
- `outbox_dead > 0` — needs manual retry.
- `recon_runs_awaiting_approval > 0` for 30 m — a human must decide before morning shifts.
- rejection rate > 5 % for 10 m — HR sending bad data (often an office code missing on our side).
- `increase(roster_apply_retries_total[5m]) > 100` — lock contention / DB trouble.
- readiness DOWN.

## Logging conventions

- One event name per line in the `message`, lowercase dotted: `hr.event.applied`, `hr.event.stale`, `hr.event.rejected`, `outbox.published`, `outbox.dead`, `recon.run.brake_tripped`, `side_effects.bookings_cancelled`.
- MDC: `correlationId`, `tenantId`, `employeeId`, `runId`, `eventId`.
- Levels: INFO for state changes, WARN for conflicts/brake/dead letters, ERROR only for unexpected failures. Per-record rejections are DEBUG (counted by metrics) to avoid log floods from a bad batch.
- `LogEventSink` (the stand-in for a broker) logs `outbox.published` with `eventId, eventType, employeeId, version` — this is what the demo video shows.

## Health

`/actuator/health/liveness` (process up) and `/actuator/health/readiness` (DB reachable + Flyway done). Docker healthcheck uses readiness.
