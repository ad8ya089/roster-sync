# Security, Tenant Isolation, PII and Error Handling

Covers plus-points **1 (Authentication + RBAC + tenant isolation)** and **8 (Error handling, no PII)** and the key point *"Never write personal data into logs or error reports. Use employee IDs."*

## 1. Authentication

- `spring-boot-starter-security` + `spring-boot-starter-security-oauth2-resource-server` (JWT, Boot 4 coordinate).
- Tokens are minted by `POST /api/v1/auth/token` using `NimbusJwtEncoder` with an HS256 secret from `JWT_SECRET` (≥ 32 bytes; the app refuses to start if shorter). Expiry 1 h (`roster.security.token-ttl`).
- Passwords: BCrypt (`PasswordEncoderFactories` / `BCryptPasswordEncoder`, strength 10). Login failures return one generic 401 for unknown user and wrong password (no user enumeration). Failed logins are logged as `auth.login.failed` with the **username only** (usernames are not personal data here — they are `acme.hr`-style service names; employee usernames are their employee IDs).
- Stateless: `SessionCreationPolicy.STATELESS`, CSRF disabled (no cookies), CORS off by default.
- 401 and 403 are written by a custom `AuthenticationEntryPoint` / `AccessDeniedHandler` in the same Problem Details format.

## 2. Authorization (RBAC)

`@EnableMethodSecurity`; controllers use `@PreAuthorize("hasRole('OPERATOR')")` etc. Role → endpoint matrix is in `API.md`. Employee self-access: `EMPLOYEE` may read `/employees/{id}` only when `id == jwt.emp`; otherwise 404.

## 3. Tenant isolation

1. A `TenantContextFilter` (after the JWT filter) reads `tid` from the token into a request-scoped `TenantContext` (a `ThreadLocal`-backed holder cleared in `finally`), and into MDC as `tenantId`.
2. Every repository / DAO method that reads or writes tenant data **takes `tenantId` as a parameter**; there is no `findById(id)` on tenant tables. `ArchitectureTest` asserts repository methods on tenant entities have a `tenantId` parameter (reflection over `*Repository` interfaces).
3. Foreign keys are always looked up with tenant: an office code from tenant A can never resolve for tenant B.
4. Background work (outbox, reconciliation, side effects) carries `tenantId` from the row it is processing — never from a thread-local.
5. Cross-tenant access returns **404 NOT_FOUND**, not 403, so a client can't probe whether an ID exists in another tenant.

Tests (`TenantIsolationIT`): globex token on acme employee → 404; globex operator approving acme run → 404; globex HR event for an employee ID that exists in acme → creates a separate globex employee (IDs are only unique per tenant); globex upload never touches acme rows; `/me` for an EMPLOYEE only ever returns their own row.

## 4. PII policy

**PII fields:** `name`, `email`, `phone`, `pickupPoint` (home location), `specialAssistance` (may imply health). Employee ID, office code, shift code, status, version are not PII.

Rules every agent must follow:
- **Logs** contain IDs, codes, counts, durations — never PII values, never request/response bodies. Structured fields: `tenantId`, `correlationId`, `employeeId`, `runId`, `eventId`, `outcome`, `errorCodes`.
- **Error responses and per-record reports** contain field names and codes — never the rejected value.
- **`record_rejection`, `ops_alert`, `outbox_event.last_error`** — codes and IDs only.
- **Java records** that hold PII (`HrSnapshot`, request DTOs, `EmployeeView`) override `toString()` to print only `employeeId` and non-PII fields. (Records auto-generate a `toString` with every field — the most common accidental leak.)
- **Exceptions:** Postgres messages can contain values (`Key (email)=(x@y) already exists`). `GlobalExceptionHandler` never forwards `getMessage()` of persistence/parse exceptions; it logs `exception class + constraint name + correlationId` only. Jackson parse errors are mapped to `MALFORMED_JSON` with the JSON path, not the content.
- **SQL logging:** `spring.jpa.show-sql=false`; Hibernate bind-parameter logging (`org.hibernate.orm.jdbc.bind`) must stay at `INFO` or above.
- **Access logs:** off. Paths contain employee IDs only.
- **Outbox payload:** PII appears only as field **names** in `changedFields`.
- **Metrics tags:** never employee IDs (cardinality) and never PII; tags are `outcome`, `reason`, `source`, `type`.

**Proof:** `PiiLeakIT` — seeds canary values (`canary.person@pii.test`, `+919999900000`, `Canary Lane 42`, name `Zzcanary Person`), runs every main flow (valid + invalid events, duplicate, conflict, transport update, recon with bad rows, auth failures, a forced 500), captures all log output with `OutputCaptureExtension`, and also collects every error response body; asserts none of the canary strings appear.

## 5. Error-handling framework

```
api/error/
  ErrorCode (enum: code, httpStatus, title)          ← single source of truth
  ApiException (abstract, carries ErrorCode)           ← inheritance
    ├── NotFoundException
    ├── ConflictException
    ├── InvalidStateException
    └── PayloadTooLargeException
  FieldError(field, code)                              ← per-record / per-field
  GlobalExceptionHandler (@RestControllerAdvice)       ← maps ApiException, MethodArgumentNotValid,
                                                          HttpMessageNotReadable, DataAccessResourceFailure (503),
                                                          AccessDenied, MaxUploadSizeExceeded, fallback 500
  CorrelationIdFilter                                  ← X-Correlation-Id in/out + MDC
```

- Domain errors are **values**, not exceptions: `ValidationResult` (list of `FieldError`) and `ApplyDecision` (sealed). Exceptions are for exceptional situations only.
- Transient DB errors (`CannotAcquireLockException`, `PessimisticLockingFailureException`, serialization failures) are retried by the applier (max 3, jittered backoff 20–100 ms) and counted in `roster_apply_retries_total`; if still failing, the record outcome is `RETRYABLE_ERROR` (HR should resend).
- `DataAccessResourceFailureException` / connection failures → 503 `DEPENDENCY_UNAVAILABLE`, readiness goes DOWN.
- Every 500 is logged once at ERROR with the correlation id and the exception class; the client sees a generic message plus the correlation id.

## 6. Secrets & config

`.env` (git-ignored) with `.env.example` committed: `POSTGRES_PASSWORD`, `JWT_SECRET`, `DEMO_PASSWORD`, `BRAKE_MAX_MISSING_RATIO`, `SPRING_PROFILES_ACTIVE`. In AWS, the same variables come from SSM Parameter Store / Secrets Manager (see `DEPLOYMENT.md`).

## 7. Input limits (cheap DoS hygiene)

Event batch ≤ 1000 and body ≤ 2 MB; CSV ≤ 100 MB (`spring.servlet.multipart.max-file-size`); string fields length-capped by validation; page size ≤ 200; `statement_timeout=30s`, `lock_timeout=5s` on the DB connection.

## 8. Runtime profiles

- `prod` alone has no demo seeder, dev endpoints, or active fault injection.
- The interview deployment intentionally enables `prod,demo`: production logging plus demo fixtures and, once implemented, demo endpoints. Every `/api/v1/dev/**` endpoint requires OPERATOR authentication; this is an explicit interview-only exception, not the normal production configuration.
- Phase 1 exposes health publicly and denies all other routes. JWT issuance, roles and the full error mapping arrive in Phase 3. The default generated Spring Security user is disabled so no generated password is logged.
- Caller correlation IDs must be UUIDs. Other values are replaced with a generated UUID before entering logs; error responses return the resulting ID.
