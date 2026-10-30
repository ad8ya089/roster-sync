# Security, Tenant Isolation, PII and Error Handling

Phase 9 static resources: only GET `/`, `/index.html`, `/assets/**` and `/favicon.svg` are added to the public allowlist. They use same-origin CSP (including `font-src 'self'` and `frame-ancestors 'none'`), `Referrer-Policy: no-referrer` and `X-Content-Type-Options: nosniff`. Hash routing adds no SPA fallback for unknown server paths. API method authorization, tenant predicates and the separate management listener are unchanged. Browser tokens stay in memory, and logout/expiry clears tenant query data. Browser role hints never replace server authorization.

Covers plus-points **1 (Authentication + RBAC + tenant isolation)** and **8 (Error handling, no PII)** and the key point *"Never write personal data into logs or error reports. Use employee IDs."*

## 1. Authentication

- `spring-boot-starter-security` + `spring-boot-starter-security-oauth2-resource-server` (JWT, Boot 4 coordinate).
- Tokens are minted by `POST /api/v1/auth/token` using `NimbusJwtEncoder` with an HS256 secret from `JWT_SECRET` (≥ 32 bytes; the app refuses to start if shorter). Expiry 1 h (`roster.security.token-ttl`).
- Passwords: BCrypt (`BCryptPasswordEncoder`, strength 10). Unknown, wrong-password and disabled accounts return the same generic 401. Unknown accounts still execute one match against a fixed strength-10 dummy hash. `LoginTimingTest` verifies real BCrypt calls through a spy, without relying on wall-clock timing. Failed logins log `auth.login.failed` without supplied credentials or usernames, since untrusted usernames can themselves contain PII.
- Stateless: `SessionCreationPolicy.STATELESS`, CSRF disabled (no cookies), CORS off by default.
- 401 and 403 are written by a custom `AuthenticationEntryPoint` / `AccessDeniedHandler` in the same Problem Details format.
- `JwtClaimsValidator` requires the identity, tenant, role, issuance and expiry claims; role parses to the `Role` enum and EMPLOYEE requires `emp`. The 30-second clock allowance is tested at its exact boundary. Expiry emits `roster_token_expired`; the entry point examines typed validation errors through their exception causes, never exception text.
- No database lookup occurs during bearer-token validation. Disabling an account stops subsequent logins but leaves existing tokens valid until expiry (one hour by default). Production would add a revocation list or delegate to an identity provider; see ADR-013.
- `CorrelationIdFilter` is the outer servlet filter, before Spring Security, so rejected tokens and denied roles still receive correlation IDs. `TenantContextFilter` runs after bearer authentication and clears both its ThreadLocal and MDC in `finally`, including when downstream processing throws.
- Form login, HTTP Basic and logout are disabled entirely. `/login` and `/logout` reach MVC with no handler and return 404, including anonymously. Spring Security 7.1 adds a public OAuth protected-resource metadata filter without a disable switch; the final chain omits it. A regression test covers these framework paths as well as registered controller mappings.

## 2. Authorization (RBAC)

`@EnableMethodSecurity`; controllers use `@PreAuthorize("hasRole('OPERATOR')")` etc. Role → endpoint matrix is in `API.md`. Employee self-access: `EMPLOYEE` may read `/employees/{id}` only when `id == jwt.emp`; otherwise 404.

Office reads allow TRANSPORT_ADMIN and OPERATOR; writes allow TRANSPORT_ADMIN only. Role checks precede tenant-scoped resource lookups. `SecuredEndpointsTest` reflects over handler methods to require a method-level `@PreAuthorize` on every `/api/v1` endpoint except POST `/auth/token`. `AuthorizationIT` also enumerates registered mappings and proves protected endpoints reject missing tokens; later endpoints are automatically included.

## 3. Tenant isolation

1. A `TenantContextFilter` (after the JWT filter) reads `tid` from the token into a request-scoped `TenantContext` (a `ThreadLocal`-backed holder cleared in `finally`), and into MDC as `tenantId`.
2. Every repository / DAO method that reads or writes tenant data **takes `UUID tenantId` as a parameter**; there is no unscoped `findById(id)` on tenant tables. `ArchitectureTest` checks repository interfaces and write fragments. The sole current exception is `AppUserRepository.findByUsername(String)` at login, because usernames are globally unique; subsequent tenant lookup takes its UUID. No broad repository allowlist exists.
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
- **Java records** that hold PII override `toString()`. `HrSnapshot` prints only manager/office/shift/status, without an employee ID; credential/token DTOs redact credentials and tokens. Employee views expose only permitted non-PII metadata in their string representation. Default record string output would include every field.
- **Exceptions:** Postgres messages can contain rejected values. `GlobalExceptionHandler` forwards no persistence/parse messages and logs unexpected failures once using exception class and correlation ID only. Duplicate offices are recognized by structured PostgreSQL constraint name and SQLSTATE, never by message text. Jackson failures map to generic `MALFORMED_JSON`. Validation exposes only field names and codes. Problem instances use fixed `/api/v1`, avoiding request-path leaks.
- **SQL logging:** `spring.jpa.show-sql=false`; Hibernate bind-parameter logging (`org.hibernate.orm.jdbc.bind`) must stay at `INFO` or above.
- Hibernate SQL exception diagnostics are disabled because driver messages can contain values; the handler records safe metadata instead.
- **Access logs:** off. Paths contain employee IDs only.
- **Outbox payload:** PII appears only as field **names** in `changedFields`.
- **Metrics tags:** no tenant IDs, employee IDs or PII. Labels are bounded outcomes, reasons, sources, types, sink names, event types, phases and cache names. Per-tenant views use the authenticated SQL summary rather than multiplying time series.

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
- POST `/auth/token` is public. Other API routes require bearer authentication and method authorization. All actuator endpoints are permitAll on the separate management listener 8081; no actuator handlers exist on 8080. Compose publishes 8081 only on loopback. Production binds management only to the instance loopback interface for SSM forwarding, keeps it out of the security group, and Caddy proxies only 8080. The default generated Spring Security user is disabled so no generated password is logged.
- Caller correlation IDs must be UUIDs. Other values are replaced with a generated UUID before entering logs; error responses return the resulting ID.

Phase 6 file boundary: saved uploads use generated UUID names, never the client filename. Invalid headers remove the generated copy and create no run. Binary spills live in generated UUID directories and are deleted in finally. Run/status/rejection responses expose counts, identifiers and codes; normalized action payloads remain internal. Malformed CSV reports only MALFORMED_CSV and a record number. Demo CSV export contains employee data and is OPERATOR-only under demo; prod alone has no dev endpoints. Whole-file asOf ordering and expected-version rechecks protect against older exports overwriting newer work. Inbox polling preserves source files and uses tenant-specific directories under the configured root.

Boot 4 creates a child context for the management server. Its configuration is registered through `ManagementContextConfiguration.imports` and deliberately sits outside the application's component scan, so its permit-all filter chain cannot open API routes. A separate correlation filter wraps management requests. HealthIT and the endpoint guards assert management-public/API-404 separation. The approved production plan binds 8081 only to the instance loopback interface and permits access through SSM port forwarding; the security group never exposes it.

Cache keys include tenant IDs and values are immutable DTOs. EMPLOYEE ownership checks run before lookup. Employee invalidation occurs after commit (including NO_CHANGE version advances), and rollback cannot evict. Task decorators copy only correlationId; background jobs supply their own IDs and clear MDC in finally. PiiLeakIT scans logs, errors, outbox payloads, rejection and alert rows across ingest, transport, conflict, auth, forced failure and malformed recon rows.
