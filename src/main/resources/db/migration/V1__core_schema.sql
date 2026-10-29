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
  outcome       VARCHAR(16) NOT NULL,         -- APPLIED | STALE | DUPLICATE | CONFLICT (first processing result)
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
