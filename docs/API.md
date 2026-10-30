# API Contract

Base path `/api/v1`. JSON everywhere except the CSV upload. All endpoints except `/auth/token`, `/actuator/health/**` require `Authorization: Bearer <JWT>`. The tenant is **always** taken from the token, never from the URL or body.

## Roles

| Role | Who | Can |
|---|---|---|
| `HR_SYSTEM` | the client's HR integration (machine user) | push events, upload nightly file, read own run status |
| `TRANSPORT_ADMIN` | client's transport desk | read employees, edit transport-owned fields, manage offices and bookings |
| `OPERATOR` | MoveInSync on-call (scoped to a tenant) | view runs, approve/reject braked runs, change brake settings, view ops summary/alerts/outbox |
| `EMPLOYEE` | an employee | read **only their own** profile (`/me`) and bookings |

The brief names "employee vs admin vs operator"; `HR_SYSTEM` is added because the HR integration is a machine identity that should not have human admin rights (least privilege).

## Error format (RFC 9457 Problem Details)

```json
{
  "type": "https://docs.rostersync.local/errors/validation-failed",
  "title": "Request validation failed",
  "status": 400,
  "code": "VALIDATION_FAILED",
  "detail": "2 field(s) invalid",
  "correlationId": "c6a1…",
  "errors": [ { "field": "events[3].phone", "code": "INVALID_FORMAT" } ]
}
```

Rules: never echo the rejected **value**; never include stack traces, SQL, constraint values or class names; `correlationId` is also in the `X-Correlation-Id` response header and in every log line for that request.

| HTTP | `code` examples |
|---|---|
| 400 | `VALIDATION_FAILED`, `MALFORMED_JSON`, `BATCH_TOO_LARGE`, `INVALID_CSV_HEADER` |
| 401 | `UNAUTHENTICATED`, `TOKEN_EXPIRED` |
| 403 | `FORBIDDEN` |
| 404 | `NOT_FOUND` (also for another tenant's resources) |
| 405 | `METHOD_NOT_ALLOWED` |
| 409 | `OFFICE_CODE_EXISTS`, `RECON_RUN_ALREADY_ACTIVE`, `INVALID_RUN_STATE`, `CONCURRENT_MODIFICATION` |
| 413 | `PAYLOAD_TOO_LARGE` |
| 415 | `UNSUPPORTED_MEDIA_TYPE` |
| 503 | `DEPENDENCY_UNAVAILABLE` (DB down) |
| 500 | `INTERNAL_ERROR` (message is generic; details only in logs, by correlationId) |

## Per-record error / warning codes

`MISSING_REQUIRED_FIELD`, `INVALID_FORMAT`, `TOO_LONG`, `UNKNOWN_OFFICE`, `INACTIVE_OFFICE`, `INVALID_VERSION`, `INVALID_STATUS`, `INVALID_EVENT_TYPE`, `MANAGER_SELF_REFERENCE`, `UNKNOWN_FIELD`, `DUPLICATE_ROW_IN_FILE`, `VERSION_CONFLICT`, `RETRYABLE_ERROR`.
Warnings (record still applied): `FIELD_NOT_OWNED_BY_SOURCE` (e.g. HR sent `pickupPoint`), `UNKNOWN_FIELD_IGNORED`.

---

## Auth

### `POST /auth/token`
```json
{ "username": "acme.hr", "password": "…" }
→ 200 { "accessToken": "eyJ…", "tokenType": "Bearer", "expiresIn": 3600, "role": "HR_SYSTEM", "tenant": "acme" }
```
Wrong credentials → 401 `UNAUTHENTICATED` (same message for unknown user and wrong password). JWT (HS256) claims: `sub`, `tid` (tenant UUID), `tcode`, `role`, `emp` (employee id, EMPLOYEE only), `iat`, `exp`.

Disabled users also receive the same 401. Login performs one BCrypt match even for unknown accounts. Tokens expire after one hour by default, with a 30-second clock allowance; expired tokens return `TOKEN_EXPIRED` using a distinct validator error code. Unknown roles, missing tenant/identity claims or EMPLOYEE tokens without `emp` return `UNAUTHENTICATED`. Validation is stateless: disabling an account prevents new tokens, while an existing token remains valid until expiry.

---

## HR events (real time)

### `POST /hr/events` — role `HR_SYSTEM`
Batch of 1–1000 events. Each event is processed in **its own transaction**; one bad record never fails the batch. Always HTTP 200 if the envelope is valid.

```json
{
  "events": [
    {
      "eventId": "hr-981273",            // optional, informational
      "type": "UPDATE",                  // CREATE | UPDATE | DEACTIVATE
      "employeeId": "E000123",
      "version": 7,                      // per-employee, strictly increasing at the source
      "occurredAt": "2026-10-29T10:15:00Z", // optional, used only for lag metric
      "name": "…", "email": "…", "phone": "+919876543210",
      "managerId": "E000042", "officeCode": "BLR-2", "shiftCode": "S2",
      "status": "ACTIVE"
    }
  ]
}
```

**Contract (write this in the README):**
- `CREATE` and `UPDATE` carry the **full HR-owned state** of the employee at that version (state transfer, not a delta). `status` defaults to `ACTIVE` for `CREATE` and is **required** for `UPDATE`.
- `DEACTIVATE` needs only `employeeId` + `version`; it sets `status=INACTIVE` and keeps the other HR fields.
- `UPDATE` for an unknown employee is treated as a create (the `CREATE` was delayed or lost). `DEACTIVATE` for an unknown employee stores a **tombstone** so a late, lower-version `CREATE` is ignored as stale.
- If the source only has timestamps, it sends `version = epoch milliseconds` of the change.
- A higher-version event with the same resolved HR-owned hash returns `NO_CHANGE` (`VersionAdvanced(newVersion)`): advance `hr_version` and `hr_updated_at`, retain business fields, and write no outbox row. This covers identical CREATE/UPDATE snapshots and DEACTIVATE on an already-INACTIVE full record or tombstone. The version high-water mark always advances, preventing a late intermediate CREATE from resurrecting an employee.
- `Apply` always has non-empty `changedFields` and exactly one Employee outbox event. `VersionAdvanced` is a distinct decision, never an empty-field Apply. Its receipt outcome is `NO_CHANGE`, response summary counter is `noChange`, and metrics tag is `no_change`. Phase 4 implements the transactional database effects.
- DEACTIVATE ignores all carried HR/transport fields with `UNKNOWN_FIELD_IGNORED` warnings. Equal-version identical content remains `DUPLICATE`; lower versions remain `STALE`, including against tombstones.
- Email is trimmed and lower-cased before validation and hashing. Phone validation uses the original string: spaces and dashes are invalid. CREATE requires name/email/officeCode; UPDATE additionally requires status. Errors are sorted by field name, then code, with no values.
- Snapshot decisions use the same version comparison, with equal-version differing content represented as `Apply(heal=true)`. Replacing a tombstone with ACTIVE state emits EmployeeCreated; reactivating an existing inactive full record emits EmployeeReactivated.

Response:
```json
{
  "summary": { "received": 5, "applied": 2, "noChange": 0, "duplicate": 1, "stale": 1, "conflict": 0, "rejected": 1 },
  "results": [
    { "index": 0, "employeeId": "E000123", "version": 7, "outcome": "APPLIED", "warnings": [] },
    { "index": 1, "employeeId": "E000123", "version": 6, "outcome": "STALE", "currentVersion": 7 },
    { "index": 2, "employeeId": "E000124", "version": 3, "outcome": "DUPLICATE" },
    { "index": 3, "employeeId": "E000125", "version": 1, "outcome": "REJECTED",
      "errors": [ { "field": "phone", "code": "INVALID_FORMAT" }, { "field": "officeCode", "code": "UNKNOWN_OFFICE" } ] },
    { "index": 4, "employeeId": "E000126", "version": 2, "outcome": "APPLIED",
      "warnings": [ { "field": "pickupPoint", "code": "FIELD_NOT_OWNED_BY_SOURCE" } ] }
  ]
}
```
Envelope errors: empty / > 1000 events → 400 `BATCH_TOO_LARGE`/`VALIDATION_FAILED`; body > 2 MB → 413.

---

## Employees

| Method & path | Roles | Notes |
|---|---|---|
| `GET /employees?status=&officeCode=&page=&size=` | TRANSPORT_ADMIN, OPERATOR | paginated, max size 200 |
| `GET /employees/{employeeId}` | TRANSPORT_ADMIN, OPERATOR, EMPLOYEE (self only) | other tenant / not self → 404 |
| `GET /me` | EMPLOYEE | cached (see caching) |
| `GET /employees/{employeeId}/events` | TRANSPORT_ADMIN, OPERATOR | receipts: version, type, outcome, receivedAt — the "v7 applied, v6 stale" story |
| `PATCH /employees/{employeeId}/transport-fields` | TRANSPORT_ADMIN | body: `{ "pickupPoint": "...", "specialAssistance": "WHEELCHAIR" }`; only transport-owned fields allowed, an HR-owned key → 400 `FIELD_NOT_OWNED_BY_SOURCE`; emits `TransportProfileUpdated` |
| `GET /employees/{employeeId}/bookings` | TRANSPORT_ADMIN, EMPLOYEE (self) | |

Employee response:
```json
{ "employeeId": "E000123", "status": "ACTIVE", "hrVersion": 7, "name": "…", "email": "…", "phone": "…",
  "managerId": "E000042", "officeCode": "BLR-2", "shiftCode": "S2",
  "transport": { "pickupPoint": "…", "specialAssistance": null, "version": 3 },
  "hrUpdatedAt": "…", "deactivatedAt": null }
```

## Offices
| Method & path | Roles | Response |
|---|---|---|
| `GET /offices?includeInactive=true` | TRANSPORT_ADMIN, OPERATOR | 200, array of `{code, name, active}`, sorted by code; false excludes inactive offices |
| `GET /offices/{code}` | TRANSPORT_ADMIN, OPERATOR | 200, `{code, name, active}` |
| `POST /offices {code, name}` | TRANSPORT_ADMIN | 201, `{code, name, active: true}` |
| `PATCH /offices/{code} {name, active}` | TRANSPORT_ADMIN | 200, updated office; omitted properties retain their values |

Codes match `^[A-Z0-9-]{1,32}$`; names contain 1–200 characters and cannot be blank. Duplicate code within a tenant returns 409 `OFFICE_CODE_EXISTS`; the same code may exist in different tenants. A cross-tenant or missing office returns 404. Role authorization runs before the resource lookup, so a disallowed role receives 403 for both existing and absent offices.

Deactivation is allowed: existing employees keep their office references; future HR records using that office fail validation with `INACTIVE_OFFICE`. The database-backed `OfficeLookup` port is the cache seam; Phase 7 adds caching and write eviction. Invalid path/query types return 400 `VALIDATION_FAILED` with field names and codes only. Error instances use the fixed `/api/v1` URI so malformed request paths cannot leak input values.

## Bookings (stub transport domain, for side effects)
`POST /bookings {employeeId, pickupAt}` — TRANSPORT_ADMIN; rejects inactive employees (`409 EMPLOYEE_INACTIVE`).
`GET /routes/{routeCode}/seats` — TRANSPORT_ADMIN.

---

## Reconciliation

### `POST /reconciliation/runs` — HR_SYSTEM, OPERATOR (multipart)
Fields: `file` (CSV, ≤ 100 MB), `asOf` (ISO instant, required — when the export was taken), `expectedRowCount` (optional), `dryRun` (optional, default false).
→ `202 { "runId": "…", "status": "UPLOADED" }`. Another non-terminal run for the tenant → 409 `RECON_RUN_ALREADY_ACTIVE`.

CSV format (header required, column order free, UTF-8, optional BOM):
```
employee_id,version,name,email,phone,manager_id,office_code,shift_code,status
E000001,12,Asha Rao,asha@acme.test,+919800000001,E000042,BLR-1,S1,ACTIVE
...
#TRAILER,row_count=200000        ← optional last line; if present and wrong → brake forced
```
Unknown columns are ignored with a run-level warning; transport columns (e.g. `pickup_point`) are ignored with `FIELD_NOT_OWNED_BY_SOURCE`.

### `GET /reconciliation/runs/{runId}` — HR_SYSTEM (own runs), OPERATOR, TRANSPORT_ADMIN
```json
{
  "runId": "…", "status": "AWAITING_APPROVAL", "dryRun": false, "asOf": "…",
  "rows": { "total": 120000, "valid": 119990, "rejected": 10 },
  "plan": { "creates": 12, "updates": 340, "heals": 3, "unchanged": 119635, "staleSkipped": 0, "deactivations": 80010 },
  "brake": {
    "tripped": true, "reason": "MISSING_RATIO", "reasons": ["MISSING_RATIO"],
    "activeBefore": 200000, "missingActive": 80010, "missingRatio": 0.4001,
    "thresholdRatio": 0.30, "thresholdCount": null,
    "message": "File appears to be missing 80,010 of 200,000 active employees (40.0%). Threshold is 30.0%. No one has been deactivated. Approve or reject this run."
  },
  "applied": 0, "superseded": 0,
  "timings": { "diffMs": 8123, "applyMs": null }
}
```

### `GET /reconciliation/runs?status=&page=` · `GET /reconciliation/runs/{runId}/rejections?page=`
Rejections list `rowNumber`, `employeeId`, `errorCodes` — never values.

### `POST /reconciliation/runs/{runId}/approve` — OPERATOR
`{ "mode": "ALL" | "SKIP_DEACTIVATIONS", "note": "confirmed 80k exits after divestiture" }` → 202, status `APPLYING`. Only from `AWAITING_APPROVAL` (else 409 `INVALID_RUN_STATE`).

### `POST /reconciliation/runs/{runId}/reject` — OPERATOR
`{ "note": "broken export, HR re-sending" }` → 200, status `REJECTED`, all actions discarded.

### Settings — OPERATOR
`GET /settings/reconciliation` → `{ "maxMissingRatio": 0.30, "maxMissingCount": null, "source": "DEFAULT" | "TENANT" }`
`PUT /settings/reconciliation { "maxMissingRatio": 0.25, "maxMissingCount": 5000 }` — validation: ratio in (0, 1], count ≥ 1 or null.

---

## Ops — OPERATOR

| Endpoint | Returns |
|---|---|
| `GET /ops/summary` | the 3 AM view: outbox pending/dead/oldest-age, events last 15 min by outcome, rejection rate, retries, runs awaiting approval, last run status — see `OBSERVABILITY.md` |
| `GET /ops/alerts?acknowledged=false` | open alerts |
| `POST /ops/alerts/{id}/ack` | acknowledge |
| `GET /ops/outbox?status=PENDING\|DEAD&page=` | outbox rows (no payload values) |
| `POST /ops/outbox/{id}/retry` | DEAD → PENDING, attempts reset |

Actuator: `/actuator/health/liveness`, `/actuator/health/readiness` (public), `/actuator/prometheus` (OPERATOR, or open on an internal management port — see `OBSERVABILITY.md`).

---

## Dev / demo only (`demo` Spring profile; absent with `prod` alone)

All dev endpoints require OPERATOR. The interview deployment intentionally enables `prod,demo`; ordinary production enables only `prod`.

| Endpoint | Purpose |
|---|---|
| `POST /dev/seed-employees?count=200000` | bulk-insert active employees for the caller's tenant via `generate_series` (seconds) |
| `GET /dev/export.csv?truncateAt=120000&mutatePercent=1&dropPercent=0.5` | stream a CSV "HR export" of the current DB, optionally cut short / with changed rows / with missing rows, plus a correct trailer unless truncated |
| `POST /dev/faults {"point":"OUTBOX_BEFORE_MARK_PUBLISHED","mode":"HALT"}` | arm a one-shot fault (HALT = `Runtime.halt(137)`, i.e. kill -9 semantics; THROW; DELAY_MS) |
| `DELETE /dev/faults` | disarm |
