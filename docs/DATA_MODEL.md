# Data Model

Phase 7 migration `V4__operational_summary_indexes.sql` adds indexes on `hr_event_receipt(tenant_id, received_at)`, `record_rejection(tenant_id, created_at)` and `outbox_event(tenant_id, status)`. The operational summary reads durable state from these tables; no minute-rollup table or per-tenant counter row is introduced.

PostgreSQL 17. All tables are created by Flyway (`src/main/resources/db/migration`). Every business table carries `tenant_id` and every query filters by it.

## Conventions

- `tenant_id UUID NOT NULL` on every tenant-scoped table, always the first column of composite indexes.
- `external_id` = the employee ID from the HR system (string, e.g. `E000123`). It is the only identifier we log.
- Timestamps are `TIMESTAMPTZ`, written in UTC.
- Status columns are `VARCHAR` + `CHECK` constraints (readable in `psql`, easy to extend) rather than Postgres enums.

Entity mappings use `TenantScopedEntity` for `tenantId` and `AuditedTenantEntity` extending it for `createdAt` and `updatedAt` only where those columns exist. Other timestamp fields are mapped by the concrete entity; inheritance does not add columns.

`tenant_settings` maps `tenantId` directly as its primary key: JPA does not allow a subclass to promote an inherited non-ID property to `@Id`. It still requires a tenant UUID on every repository operation. `tenant` is the tenant root and has no tenant_id column.

## V1__core_schema.sql (target DDL — agents implement exactly this unless a task says otherwise)

```sql
CREATE TABLE tenant (
  id          UUID PRIMARY KEY,
  code        VARCHAR(32) NOT NULL UNIQUE,      -- 'acme', 'globex'
  name        VARCHAR(200) NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE tenant_settings (
  tenant_id          UUID PRIMARY KEY REFERENCES tenant(id),
  brake_max_missing_ratio NUMERIC(4,3),          -- NULL = use application default (0.300)
  brake_max_missing_count INTEGER,               -- NULL = disabled
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by         VARCHAR(100)
);

CREATE TABLE office (
  id         BIGSERIAL PRIMARY KEY,
  tenant_id  UUID NOT NULL REFERENCES tenant(id),
  code       VARCHAR(32) NOT NULL,
  name       VARCHAR(200) NOT NULL,
  active     BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, code)
);

CREATE TABLE app_user (
  id                   BIGSERIAL PRIMARY KEY,
  tenant_id            UUID NOT NULL REFERENCES tenant(id),
  username             VARCHAR(100) NOT NULL UNIQUE,   -- e.g. 'acme.hr', 'acme.admin'
  password_hash        VARCHAR(100) NOT NULL,          -- BCrypt
  role                 VARCHAR(20) NOT NULL CHECK (role IN ('HR_SYSTEM','TRANSPORT_ADMIN','OPERATOR','EMPLOYEE')),
  employee_external_id VARCHAR(64),                    -- only for EMPLOYEE
  enabled              BOOLEAN NOT NULL DEFAULT TRUE,
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE employee (
  id                   BIGSERIAL PRIMARY KEY,
  tenant_id            UUID NOT NULL REFERENCES tenant(id),
  external_id          VARCHAR(64) NOT NULL,
  -- HR-owned (by default; see FieldOwnershipPolicy)
  name                 VARCHAR(200),
  email                VARCHAR(254),
  phone                VARCHAR(20),
  manager_external_id  VARCHAR(64),
  office_id            BIGINT REFERENCES office(id),
  shift_code           VARCHAR(20),
  status               VARCHAR(10) NOT NULL CHECK (status IN ('ACTIVE','INACTIVE')),
  hr_version           BIGINT NOT NULL,
  hr_updated_at        TIMESTAMPTZ NOT NULL,
  -- transport-owned (by default)
  pickup_point         VARCHAR(300),
  special_assistance   VARCHAR(300),
  transport_version    INTEGER NOT NULL DEFAULT 0,
  transport_updated_at TIMESTAMPTZ,
  -- bookkeeping
  recon_bucket         SMALLINT NOT NULL,              -- CRC32(external_id) mod 256, computed in Java
  deactivated_at       TIMESTAMPTZ,
  deactivation_source  VARCHAR(10) CHECK (deactivation_source IN ('EVENT','RECON')),
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, external_id),
  -- a tombstone (DEACTIVATE received before any CREATE) may have NULL HR fields; an ACTIVE row may not
  CHECK (status = 'INACTIVE' OR (name IS NOT NULL AND email IS NOT NULL AND office_id IS NOT NULL))
);
CREATE INDEX ix_employee_bucket ON employee (tenant_id, recon_bucket);
CREATE INDEX ix_employee_status ON employee (tenant_id, status);

-- Idempotency ledger for real-time events. A rejected (invalid) event is NOT stored here.
CREATE TABLE hr_event_receipt (
  id            BIGSERIAL PRIMARY KEY,
  tenant_id     UUID NOT NULL,
  external_id   VARCHAR(64) NOT NULL,
  version       BIGINT NOT NULL,
  event_id      VARCHAR(100),                 -- optional, supplied by HR, informational
  event_type    VARCHAR(12) NOT NULL CHECK (event_type IN ('CREATE','UPDATE','DEACTIVATE')),
  payload_hash  CHAR(64) NOT NULL,            -- SHA-256 hex of the canonical HR payload
  outcome       VARCHAR(16) NOT NULL,         -- APPLIED | NO_CHANGE | STALE | DUPLICATE (accepted content only)
  warnings      VARCHAR(200),                 -- comma-separated warning codes, never values
  received_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, external_id, version)
);
CREATE INDEX ix_receipt_received ON hr_event_receipt (received_at);   -- retention purge

CREATE TABLE record_rejection (
  id            BIGSERIAL PRIMARY KEY,
  tenant_id     UUID NOT NULL,
  source        VARCHAR(10) NOT NULL CHECK (source IN ('API','FILE')),
  run_id        UUID,                         -- set when source = FILE
  row_number    INTEGER,                      -- file line number, when source = FILE
  external_id   VARCHAR(64),                  -- may be NULL if the ID itself was invalid
  version       BIGINT,
  error_codes   VARCHAR(500) NOT NULL,        -- e.g. 'phone:INVALID_FORMAT,officeCode:UNKNOWN_OFFICE'
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_rejection_run ON record_rejection (run_id);

CREATE TABLE outbox_event (
  id               BIGSERIAL PRIMARY KEY,     -- monotonic → publish order
  event_id         UUID NOT NULL UNIQUE,      -- consumers dedupe on this
  tenant_id        UUID NOT NULL,
  aggregate_type   VARCHAR(30) NOT NULL,      -- 'Employee', 'ReconciliationRun'
  aggregate_id     VARCHAR(64) NOT NULL,      -- external_id or run id
  aggregate_version BIGINT,                   -- hr_version after the change (lets consumers drop stale events)
  event_type       VARCHAR(40) NOT NULL,      -- EmployeeCreated, EmployeeUpdated, EmployeeDeactivated, EmployeeReactivated,
                                              -- TransportProfileUpdated, ReconciliationBrakeTripped
  payload          JSONB NOT NULL,            -- see "Outbox payload" below
  status           VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','PUBLISHED','DEAD')),
  attempts         INTEGER NOT NULL DEFAULT 0,
  next_attempt_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_error       VARCHAR(200),              -- error class / code only
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  published_at     TIMESTAMPTZ
);
CREATE INDEX ix_outbox_pending ON outbox_event (next_attempt_at, id) WHERE status = 'PENDING';

CREATE TABLE recon_run (
  id                   UUID PRIMARY KEY,
  tenant_id            UUID NOT NULL,
  status               VARCHAR(20) NOT NULL CHECK (status IN
                         ('UPLOADED','DIFFING','PLANNED','AWAITING_APPROVAL','APPLYING','COMPLETED','REJECTED','FAILED')),
  dry_run              BOOLEAN NOT NULL DEFAULT FALSE,
  file_path            VARCHAR(500) NOT NULL,
  file_size_bytes      BIGINT,
  as_of                TIMESTAMPTZ NOT NULL,
  expected_row_count   INTEGER,               -- optional, from request
  trailer_row_count    INTEGER,               -- optional, from '#TRAILER,row_count=N' last line
  rows_total           INTEGER NOT NULL DEFAULT 0,
  rows_valid           INTEGER NOT NULL DEFAULT 0,
  rows_rejected        INTEGER NOT NULL DEFAULT 0,
  active_before        INTEGER,
  missing_active       INTEGER,
  missing_ratio        NUMERIC(6,4),
  brake_ratio_used     NUMERIC(4,3),
  brake_count_used     INTEGER,
  brake_tripped        BOOLEAN NOT NULL DEFAULT FALSE,
  brake_reason         VARCHAR(40),           -- MISSING_RATIO | MISSING_COUNT | ROW_COUNT_MISMATCH
  planned_creates      INTEGER NOT NULL DEFAULT 0,
  planned_updates      INTEGER NOT NULL DEFAULT 0,
  planned_heals        INTEGER NOT NULL DEFAULT 0,
  planned_deactivations INTEGER NOT NULL DEFAULT 0,
  unchanged            INTEGER NOT NULL DEFAULT 0,
  stale_skipped        INTEGER NOT NULL DEFAULT 0,
  applied              INTEGER NOT NULL DEFAULT 0,
  superseded           INTEGER NOT NULL DEFAULT 0,
  failure_reason       VARCHAR(200),
  approval_mode        VARCHAR(30),           -- ALL | SKIP_DEACTIVATIONS
  decided_by           VARCHAR(100),
  decided_at           TIMESTAMPTZ,
  decision_note        VARCHAR(500),
  created_by           VARCHAR(100) NOT NULL,
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  diff_started_at      TIMESTAMPTZ,
  diff_finished_at     TIMESTAMPTZ,
  finished_at          TIMESTAMPTZ
);
-- at most one non-terminal run per tenant
CREATE UNIQUE INDEX ux_recon_one_active ON recon_run (tenant_id)
  WHERE status IN ('UPLOADED','DIFFING','AWAITING_APPROVAL','APPLYING');

CREATE TABLE recon_action (
  id                  BIGSERIAL PRIMARY KEY,
  run_id              UUID NOT NULL REFERENCES recon_run(id),
  tenant_id           UUID NOT NULL,
  external_id         VARCHAR(64) NOT NULL,
  action              VARCHAR(12) NOT NULL CHECK (action IN ('CREATE','UPDATE','HEAL','DEACTIVATE')),
  file_version        BIGINT,
  expected_db_version BIGINT,                 -- hr_version seen at plan time (NULL for CREATE)
  payload             JSONB,                  -- normalized HR fields for CREATE/UPDATE/HEAL; NULL for DEACTIVATE
  status              VARCHAR(12) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPLIED','SUPERSEDED','SKIPPED','FAILED')),
  outcome_reason      VARCHAR(60),
  UNIQUE (run_id, external_id)
);
CREATE INDEX ix_recon_action_pending ON recon_action (run_id, id) WHERE status = 'PENDING';

CREATE TABLE ops_alert (
  id               BIGSERIAL PRIMARY KEY,
  tenant_id        UUID NOT NULL,
  alert_type       VARCHAR(40) NOT NULL,      -- RECON_BRAKE_TRIPPED | OUTBOX_EVENT_DEAD | RECON_RUN_FAILED
  reference_id     VARCHAR(64),
  message          VARCHAR(500) NOT NULL,     -- counts and IDs only
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  acknowledged_at  TIMESTAMPTZ,
  acknowledged_by  VARCHAR(100)
);

-- Minimal transport domain, just enough for deactivation side effects
CREATE TABLE cab_booking (
  id                   BIGSERIAL PRIMARY KEY,
  tenant_id            UUID NOT NULL,
  employee_external_id VARCHAR(64) NOT NULL,
  pickup_at            TIMESTAMPTZ NOT NULL,
  status               VARCHAR(12) NOT NULL CHECK (status IN ('SCHEDULED','CANCELLED','COMPLETED')),
  cancel_reason        VARCHAR(40),
  updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_booking_employee ON cab_booking (tenant_id, employee_external_id, status);

CREATE TABLE route_seat (
  id                   BIGSERIAL PRIMARY KEY,
  tenant_id            UUID NOT NULL,
  route_code           VARCHAR(32) NOT NULL,
  seat_no              INTEGER NOT NULL,
  employee_external_id VARCHAR(64),
  updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, route_code, seat_no)
);
CREATE INDEX ix_seat_employee ON route_seat (tenant_id, employee_external_id);
```

## V2__processed_event.sql (Phase 5 — consumer-side dedupe for at-least-once delivery)

```sql
CREATE TABLE processed_event (
  sink         VARCHAR(60) NOT NULL,       -- e.g. 'deactivation-side-effects'
  event_id     UUID NOT NULL,
  processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (sink, event_id)
);
CREATE INDEX ix_processed_event_retention ON processed_event (processed_at);
```
A sink inserts `(sink, event_id)` first in its own transaction; if the row already exists the event was handled before and the sink returns immediately.

## Field catalogue and default ownership

| Field (JSON) | Column | Default owner | Validation | PII? |
|---|---|---|---|---|
| `employeeId` | `external_id` | key | `^[A-Za-z0-9_-]{1,64}$` | no (the only thing we log) |
| `name` | `name` | HR | required for ACTIVE, 1–200 chars | **yes** |
| `email` | `email` | HR | required for ACTIVE, simple RFC-5322 subset, lower-cased | **yes** |
| `phone` | `phone` | HR | optional, E.164 `^\+[1-9][0-9]{7,14}$` | **yes** |
| `managerId` | `manager_external_id` | HR | optional, same pattern as employeeId, ≠ self | no |
| `officeCode` | `office_id` (lookup) | HR | required for ACTIVE, must exist & be active for tenant | no |
| `shiftCode` | `shift_code` | HR | optional, `^[A-Z0-9_-]{1,20}$` | no |
| `status` | `status` | HR | `ACTIVE` \| `INACTIVE` | no |
| `pickupPoint` | `pickup_point` | TRANSPORT | optional, ≤ 300 chars | **yes (home location)** |
| `specialAssistance` | `special_assistance` | TRANSPORT | optional, ≤ 300 chars | **yes (may imply health)** |

Ownership is configured, not hard-coded:

```yaml
roster:
  ownership:
    transport-owned: [PICKUP_POINT, SPECIAL_ASSISTANCE]   # everything else in EmployeeField is HR-owned
```

## Canonical record hash

`RecordHasher.hash(HrSnapshot)` = SHA-256 hex of the HR-owned field values in `EmployeeField` declaration order, joined with `\u001F` (unit separator), `\u0000` for null, strings trimmed, email lower-cased, office as its **code** (not DB id). Used for reconciliation and resolved-state decisions. Receipt hashes use SHA-256(eventType + unit separator + the same canonical HR-owned fields), excluding eventId/occurredAt. DEACTIVATE hashes its fixed inactive tombstone intent, not mutable stored HR values. Transport-owned fields are never in the hash, so a transport edit never looks like an HR diff.

Keep this canonical format unchanged for stored-receipt compatibility. It assumes validated input: an unvalidated unit separator can shift a field boundary, and a literal NUL can imitate the null marker. All owned API/CSV string values reject U+0000–U+001F and U+007F before hashing. `RecordHasherTest` documents both ambiguities and freezes the legacy digest; `ReplayIdempotencyIT` proves a stored legacy receipt still returns DUPLICATE without rewriting employee/receipt state. No receipt-hash migration or rehash is performed.

## Outbox payload (no PII values)

```json
{
  "eventId": "3f0c…", "eventType": "EmployeeUpdated", "tenantId": "…",
  "employeeId": "E000123", "version": 7, "occurredAt": "2026-10-30T02:00:00Z",
  "source": "API",                     // API | RECON | TRANSPORT_ADMIN
  "changedFields": ["officeCode", "shiftCode", "email"],
  "values": { "officeCode": "BLR-2", "shiftCode": "S2", "status": "ACTIVE", "managerId": "E000042" }
}
```

Non-PII values are included so consumers (routing, rostering) can act without a call back; PII fields appear only by **name** in `changedFields`. Consumers that need them call `GET /api/v1/employees/{id}` with their own credentials. This is a deliberate trade-off (ADR-007).

## Retention

| Table | Kept for | Job |
|---|---|---|
| `hr_event_receipt` | 30 days (`roster.retention.receipts`) | nightly delete. Older duplicates still become no-ops via the version check; they are just reported as `STALE` instead of `DUPLICATE`. |
| `outbox_event` (PUBLISHED) | 7 days | nightly delete |
| `recon_action` | 7 days after terminal `finished_at` (`roster.retention.recon-actions`) | nightly delete in separate transactions of at most 5,000 rows; only COMPLETED/REJECTED/FAILED/PLANNED runs |
| uploaded CSV copies | 7 days after terminal `finished_at` (`roster.retention.uploads`) | bounded nightly cleanup; unlink the generated copy, set `file_path='deleted'`, keep the run row/counters for audit |
| spill directories | normally removed in the worker's finally block | startup and nightly cleanup also remove abandoned terminal/orphan directories older than 1 day |
| orphan upload copies without a run row | 1 day | startup and nightly cleanup; generated UUID CSV names only |

The seven-day cutoffs are strict (`finished_at < now - retention`), using the injected Clock. All non-terminal and AWAITING_APPROVAL runs retain their actions and files, regardless of age. Only files below the configured generated `data-dir/recon` namespace are eligible; source inbox files and unrelated names are untouched. Recursive cleanup never follows symbolic links. A crash between unlink and marking the upload is safe to retry. Global maintenance inventories are internal, with tenant-scoped run updates. Single-instance scheduling remains the assumption.

Receipt reservation uses provisional PROCESSING inside the record transaction. Final stored outcomes are APPLIED, STALE, NO_CHANGE or DUPLICATE; conflicts roll back the reservation and write VERSION_CONFLICT through an independent rejection transaction. V1 has no receipt-outcome CHECK constraint; the writer enforces this final-outcome vocabulary without rewriting V1.


## V3__reconciliation_advances.sql (Phase 6)
The recon_action.action CHECK adds ADVANCE. recon_run adds planned_advances INTEGER NOT NULL DEFAULT 0 and nullable heartbeat_at TIMESTAMPTZ. The pending-action index (tenant_id,run_id,external_id), filtered to PENDING, supports ordered chunk claims. Earlier migrations stay unchanged. ADVANCE keeps a normalized snapshot payload for locked re-checking, advances only hr_version/hr_updated_at and writes no business outbox event. Run.applied counts processed actions, including advances; event-ingestion APPLIED remains the count matching Employee outbox events.
