# Demo Video Script (8–10 minutes)

The brief asks for: main flows, **one failure scenario handled live**, and a few sample API calls. `scripts/demo.sh` runs these steps with pauses (`read -p`) so you can narrate. A Postman collection with the same calls lives in `postman/roster-sync.postman_collection.json`.

Setup shown on screen: terminal (large font) + a second pane tailing `docker compose logs -f app | jq -c 'select(.message|test("hr.event|outbox|recon|side_effects"))'`.

```bash
BASE=${BASE_URL:-http://localhost:8080}/api/v1
tok() { curl -s $BASE/auth/token -H 'content-type: application/json' \
          -d "{\"username\":\"$1\",\"password\":\"${DEMO_PASSWORD:-demo-pass}\"}" | jq -r .accessToken; }
```

## 0. One command (30 s)
```bash
docker compose down -v && docker compose up --build -d
curl -s 127.0.0.1:8081/actuator/health/readiness | jq
```
Say: two containers, Postgres health-gated, Flyway schema, demo seed.

## 1. Tokens & roles (30 s)
```bash
HR=$(tok acme.hr); ADMIN=$(tok acme.admin); OPS=$(tok acme.operator); EMP=$(tok E1001); GLOBEX=$(tok globex.admin)
```

## 2. Create an employee (30 s)
```bash
curl -s $BASE/hr/events -H "authorization: Bearer $HR" -H 'content-type: application/json' -d '{"events":[
 {"type":"CREATE","employeeId":"E2001","version":5,"name":"Demo User","email":"demo.user@acme.test",
  "phone":"+919800000101","managerId":"E1001","officeCode":"BLR-1","shiftCode":"S1"}]}' | jq
```

## 3. Out of order: v7 before v6 (brief example) (1 min)
```bash
# v7 = office transfer to BLR-2 (full state, shift still S1)
curl -s $BASE/hr/events -H "authorization: Bearer $HR" -H 'content-type: application/json' -d '{"events":[
 {"type":"UPDATE","employeeId":"E2001","version":7,"name":"Demo User","email":"demo.user@acme.test",
  "phone":"+919800000101","managerId":"E1001","officeCode":"BLR-2","shiftCode":"S1","status":"ACTIVE"}]}' | jq '.results'
# v6 = shift change, arrives late
curl -s $BASE/hr/events -H "authorization: Bearer $HR" -H 'content-type: application/json' -d '{"events":[
 {"type":"UPDATE","employeeId":"E2001","version":6,"name":"Demo User","email":"demo.user@acme.test",
  "phone":"+919800000101","managerId":"E1001","officeCode":"BLR-1","shiftCode":"S3","status":"ACTIVE"}]}' | jq '.results'
curl -s $BASE/employees/E2001 -H "authorization: Bearer $ADMIN" | jq '{officeCode, shiftCode, hrVersion}'
curl -s $BASE/employees/E2001/events -H "authorization: Bearer $ADMIN" | jq
```
Expect: v6 `STALE` with `currentVersion: 7`; final BLR-2, S1, v7.

## 4. Replay the same feed twice (1 min)
```bash
curl -s $BASE/hr/events -H "authorization: Bearer $HR" -H 'content-type: application/json' -d @samples/feed.json | jq .summary
curl -s $BASE/ops/summary -H "authorization: Bearer $OPS" | jq .durable.outbox
curl -s $BASE/hr/events -H "authorization: Bearer $HR" -H 'content-type: application/json' -d @samples/feed.json | jq .summary
curl -s $BASE/ops/summary -H "authorization: Bearer $OPS" | jq .durable.outbox
```
Expect: second summary all `duplicate`; outbox totals unchanged. Mention `ReplayIdempotencyIT` proves the same.

## 5. Bad data doesn't fail the batch (45 s)
```bash
curl -s $BASE/hr/events -H "authorization: Bearer $HR" -H 'content-type: application/json' -d @samples/bad-batch.json | jq
```
Expect: 3 applied; `INVALID_FORMAT` on phone, `UNKNOWN_OFFICE` — note no values echoed.

## 6. Field ownership (45 s)
```bash
curl -s -X PATCH $BASE/employees/E2001/transport-fields -H "authorization: Bearer $ADMIN" -H 'content-type: application/json' \
  -d '{"pickupPoint":"Gate 3, Sector 21","specialAssistance":"WHEELCHAIR"}' | jq .transport
# HR sends a payload that (wrongly) includes pickupPoint
curl -s $BASE/hr/events -H "authorization: Bearer $HR" -H 'content-type: application/json' -d '{"events":[
 {"type":"UPDATE","employeeId":"E2001","version":8,"name":"Demo User","email":"demo.user@acme.test","phone":"+919800000101",
  "managerId":"E1001","officeCode":"BLR-2","shiftCode":"S2","status":"ACTIVE","pickupPoint":"HR thinks it knows"}]}' | jq '.results[0]'
curl -s $BASE/employees/E2001 -H "authorization: Bearer $ADMIN" | jq '{shiftCode, transport}'
```
Expect: applied with warning `FIELD_NOT_OWNED_BY_SOURCE`; shift S2; pickup point unchanged.

## 7. Nightly reconciliation, healthy (1 min)
```bash
curl -s -X POST "$BASE/dev/seed-employees?count=200000" -H "authorization: Bearer $OPS" | jq
curl -s "$BASE/dev/export.csv?mutatePercent=1&dropPercent=0.5" -H "authorization: Bearer $OPS" -o /tmp/full.csv
wc -l /tmp/full.csv
RUN=$(curl -s $BASE/reconciliation/runs -H "authorization: Bearer $HR" -F file=@/tmp/full.csv -F asOf=$(date -u +%FT%TZ) | jq -r .runId)
watch -n1 "curl -s $BASE/reconciliation/runs/$RUN -H 'authorization: Bearer $OPS' | jq '{status, plan, brake: .brake.tripped, timings}'"
```
Expect: `COMPLETED`, diff in seconds, ~1,000 deactivations (0.5 % — below threshold).

## 8. Truncated file → safety brake (brief example) (1.5 min)
```bash
curl -s "$BASE/dev/export.csv?truncateAt=120000" -H "authorization: Bearer $OPS" -o /tmp/cut.csv
RUN=$(curl -s $BASE/reconciliation/runs -H "authorization: Bearer $HR" -F file=@/tmp/cut.csv -F asOf=$(date -u +%FT%TZ) | jq -r .runId)
sleep 15; curl -s $BASE/reconciliation/runs/$RUN -H "authorization: Bearer $OPS" | jq '{status, brake}'
curl -s "$BASE/employees?status=ACTIVE&size=1" -H "authorization: Bearer $ADMIN" | jq .totalElements   # unchanged
curl -s $BASE/ops/alerts -H "authorization: Bearer $OPS" | jq
# live change: threshold is configurable
curl -s -X PUT $BASE/settings/reconciliation -H "authorization: Bearer $OPS" -H 'content-type: application/json' -d '{"maxMissingRatio":0.5}' | jq
curl -s -X POST $BASE/reconciliation/runs/$RUN/reject -H "authorization: Bearer $OPS" -H 'content-type: application/json' -d '{"note":"broken export"}' | jq .status
```
Expect: `AWAITING_APPROVAL`, "missing ~80,000 of ~200,000 active (40.0%)", active count unchanged.

## 9. Live failure: kill -9 between DB commit and publish (1.5 min)  ← the required failure scenario
```bash
curl -s -X POST $BASE/dev/faults -H "authorization: Bearer $OPS" -H 'content-type: application/json' \
  -d '{"point":"OUTBOX_BEFORE_DISPATCH","mode":"HALT"}'
curl -s $BASE/hr/events -H "authorization: Bearer $HR" -H 'content-type: application/json' -d '{"events":[
 {"type":"UPDATE","employeeId":"E2001","version":9,"name":"Demo User","email":"demo.user@acme.test","phone":"+919800000101",
  "managerId":"E1001","officeCode":"BLR-1","shiftCode":"S2","status":"ACTIVE"}]}' | jq '.results[0].outcome'   # APPLIED
docker compose ps            # app restarting (exit 137)
sleep 20; docker compose logs app | grep 'outbox.published' | grep 'employeeId=E2001 version=9'   # shows its eventId after restart
curl -s $BASE/ops/summary -H "authorization: Bearer $OPS" | jq .durable.outbox   # pending 0
```
Narrate: the employee row and outbox row committed together; the process died before publishing; on restart the poller found the PENDING row. Automated version: `OutboxCrashRecoveryIT`.

## 10. Deactivation side effects (45 s)
```bash
curl -s $BASE/employees/E1002/bookings -H "authorization: Bearer $ADMIN" | jq '[.[] | {pickupAt, status}]'
curl -s $BASE/hr/events -H "authorization: Bearer $HR" -H 'content-type: application/json' -d '{"events":[{"type":"DEACTIVATE","employeeId":"E1002","version":99}]}' | jq .summary
sleep 2; curl -s $BASE/employees/E1002/bookings -H "authorization: Bearer $ADMIN" | jq '[.[] | {pickupAt, status, cancelReason}]'
curl -s $BASE/routes/R-BLR-07/seats -H "authorization: Bearer $ADMIN" | jq
```

## 11. Tenant isolation + employee self-access (30 s)
```bash
curl -s -o /dev/null -w '%{http_code}\n' $BASE/employees/E2001 -H "authorization: Bearer $GLOBEX"   # 404
curl -s -o /dev/null -w '%{http_code}\n' $BASE/employees/E2001 -H "authorization: Bearer $EMP"      # 404 (not self)
curl -s $BASE/me -H "authorization: Bearer $EMP" | jq .employeeId                                 # E1001
```

## 12. Observability + close (45 s)
```bash
curl -s $BASE/ops/summary -H "authorization: Bearer $OPS" | jq
curl -s 127.0.0.1:8081/actuator/prometheus | grep -E '^(roster_events_total|outbox_pending|recon_brake)'
```
Close with the README benchmark table and test count (`mvn verify` summary screenshot).

The summary is available: `.durable` is tenant-scoped SQL state and survives restart; `.sinceRestart` explicitly labels instance-wide Micrometer counters and rolling p99. Outbox pending is a current-state gauge and can fall while the publisher drains. Compare total SQL outbox rows for the replay invariant.

The mixed feed remains 50 events: first send yields 44 APPLIED, 3 STALE (delayed v2 after v5) and 3 in-batch DUPLICATE; replay yields 50 DUPLICATE with originalOutcome APPLIED/STALE. Three v4 events are replaced by duplicates, keeping the fixture at 50 records.

For a repeatable standalone step 9 on Linux or Git Bash/Windows, run `./scripts/demo-crash.sh` (curl + jq required). It uses a unique employee id, shows `docker compose ps -a` plus exit/restart metadata, then prints the exact eventId's published line. HALT is one-shot and arming disappears on restart. Readiness uses `${MANAGEMENT_URL:-http://127.0.0.1:8081}`; remote readiness must be checked privately over SSM. Step 10 keeps the past booking untouched, cancels both future scheduled bookings and clears seat 2; a delayed old deactivation after rehire is skipped.

Optional dashboard: set `GRAFANA_ADMIN_PASSWORD` in `.env`; `docker compose --profile monitoring up -d`. Open Grafana at `http://127.0.0.1:3000`, log in as admin, then open **Roster Sync Operations**. Prometheus scrapes app:8081. The 13 panels show rates, outbox state, approval backlog, percentiles, retries/failures, cache hit ratios, heap, scrape reachability and reconciliation decisions. `rate()`/`increase()` handle the deliberate crash's counter reset. No management path is exposed by production Caddy; port 8080 returns 404 for actuator paths.

Phase 5 clean-volume verification (2026-10-30): the portable crash script returned APPLIED, Docker retained `container.die exitCode=137`, restart count reached 2 after two intentional smoke runs, and the restarted process published eventId `9a80d461-9239-42b3-ba32-d4d0dda2869e`. Both Compose services became healthy. Step 10 returned APPLIED, changed the two future bookings to CANCELLED / EMPLOYEE_DEACTIVATED, retained the completed past booking and freed seat 2. The mixed feed produced 44 applied / 3 stale / 3 duplicate; replay produced 50 duplicate and the pending outbox returned zero.

## Phase 6 verified Compose evidence (2026-10-30)
Fresh-volume final image: both app/db healthy, migrations through V3. The isolated 200k seed took19062ms (explicitly checked <20000ms). Seed20261030 export used mutatePercent1/dropPercent0.5. Healthy run71fcdf8a-b5b2-4006-aee7-27dda5d3ce01 completed: rows198995/valid198995/rejected0, updates1922, deactivations1010, unchanged197073, applied2932, diff12577ms/apply31073ms. The store includes five demo fixture employees in addition to200000 S-id fixtures.

The subsequent120000-row file produced runf645c14d-8ffb-44a8-a59d-f3ec9aea9bbd, AWAITING_APPROVAL: activeBefore198995, missingActive79591 (40.0%), applied0, diff15916ms. Message: "File appears to be missing 79,591 of 198,995 active employees (40.0%). Threshold is 30.0%. No one has been deactivated. Approve or reject this run." ACTIVE count stayed198995 before/after. PUT maxMissingRatio0.5 returned0.500; reject returned REJECTED with applied0. Approval waiting time is not reported as apply time.

## Phase 7 verified Compose evidence (2026-10-30)
The fresh-volume build starts only app and db by default, with migrations through V4. Readiness on loopback 8081 is UP. Anonymous Prometheus returns 200 on 8081 and 404 on 8080.

Steps 2–6: CREATE applied once; v7 applied before stale v6/currentVersion 7. The mixed feed returned 44 applied / 3 stale / 3 duplicate; replay returned 50 duplicate, with outbox rows 46 → 46 and unchanged employee/receipt contents. The bad batch returned 3 applied / 2 rejected. Transport ownership retained the pickup point. Disabled login/logout returned 404; the body limit returned 413.

Steps 7–8: the healthy run read 199,008 valid rows and applied 1,923 updates plus 1,011 deactivations. Diff took 14,393 ms; apply took 28,586 ms. The truncated 120,000-row file reported 79,605 missing, left ACTIVE unchanged at 199,008, and took 13,821 ms to diff. The run was rejected after demonstrating the threshold change.

One seed attempt hit the unchanged 30-second statement timeout while the large image download was active and rolled back completely (zero S-id rows). The later seed completed in 26,791 ms, above the earlier 20-second target; Phase 8 must profile this variation. The full integration suite measured 16,930 ms. These observations do not establish the cause of the slower runtime seed.

Step 9: docker compose ps showed `restarting 137 1`, and Docker retained `container.die exitCode=137`. After restart the sink published event `4705a75e-0e9b-434c-bb9d-9e39a590bf2f`. SQL retained 50 applied receipts plus the crash event; Micrometer duplicates reset from 53 to 0 and the since timestamp changed. Step 10 applied once, cancelled both future bookings, retained the completed past booking and freed seat 2.

Grafana 13.3.0 reports database OK and the provisioned **Roster Sync Operations** dashboard with 12 panels. Its Prometheus data source is healthy; all 13 panel expressions parse successfully (p50 and p99 use separate expressions). Retry/failure panels have no samples when no such events occur. Prometheus reports `http://app:8081/actuator/prometheus` UP with no scrape error.

An extra safe brake demonstration after restart leaves generated demo run `81a26974-ce56-4e2c-86ab-4cdc88f3596c` awaiting approval, with 79,607 missing and zero actions applied. The dashboard shows pending/dead zero, approval backlog one, cache hit ratios, apply/lag percentiles, heap and scrape reachability. Management and monitoring UIs bind only to loopback; production exposure checks belong to Phase 8's deployment files.

## Automated repeatable walkthrough

Run `DEMO_AUTO=1 ./scripts/demo.sh` in Git Bash on Windows or Bash on Linux, with curl, jq and Docker available. The default API is localhost:8080 and management is 127.0.0.1:8081. BASE_URL and MANAGEMENT_URL can override those. Unique employee/event identifiers isolate each invocation. Every expected response is asserted; any unexpected outcome exits non-zero. Before uploading, the script logs active run status every two seconds for at most 180 seconds, rejects only AWAITING_APPROVAL, and reports runId/status if the deadline expires. There is no cancellation API. Booking setup reads the current version and reactivates its fixture, so the walkthrough can run again.

The crash helper drains older pending rows before arming HALT. A connection closed by process death (curl 52/56) is accepted only when the exact durable event row, exit code 137, successful restart and published log line are subsequently proven. Other curl failures fail the demo. Credentials/tokens are never printed. The Postman collection is a manual companion; choose export files for multipart uploads and deliberately invoke fault/approval/retry actions.

The Phase 7 seed timeout is addressed with SET LOCAL statement_timeout=5min in seed/export transactions only; the global 30s timeout stays. Seed robustness replaces the former arbitrary 20s target. The current dashboard has 13 panels, including terminal/decision reconciliation states only.

Fresh-clone Phase 8 proof (2026-10-30): clone at commit3e5916f, copy .env.example to .env, default app/db only, isolated empty Compose volumes. Both consecutive DEMO_AUTO=1 invocations exit0: run 1793371920_24310 and1793372162_14849. Healthy reconciliation diff/apply timings were15115/5128ms and13058/3074ms. Truncated files retained ACTIVE199009 and199023 respectively. Each run proved process exit137 plus publication after restart (first event27332fe1-2c35-446a-ab2f-11d858fb37e1). The second invocation reuses all persisted state; no fixture reset or cancellation endpoint is needed. Credentials and tokens are omitted from the evidence.

## Live light demo

Run overSSM in /opt/roster-sync: `DEMO_AUTO=1 DEMO_LIGHT=1 BASE_URL=https://rostersync-aditya.duckdns.org ./scripts/demo.sh`. The root-only .env supplies credentials; they are never printed. DEMO_LIGHT skips only the 200kseed/recon steps7–8. It keeps the real crash test on the instance, where COMPOSE_FILE points at the production file. From a separate machine, DEMO_SKIP_CRASH=1 may omit only the Docker-dependent crash step, which is verified separately overSSM. MANAGEMENT_URL can point at anSSMforward; without it the light mode checks public actuator404 and tenant-scoped ops instead. Full local200k demonstration remains available.


## Console walkthrough (Phase 9, local until redeploy approval)

Open http://localhost:8080/ and use the password provided privately. As acme.hr, choose Out-of-order: the three server outcomes are Applied, Applied, Stale; Replay returns Duplicate; No-op higher version returns No change; Ownership violation returns Applied with a warning; Deactivate applies. Bad batch isolates two rejected rows. HR never reads privileged employee history.

As acme.operator, review the durable/since-restart Operations board. Demo tools can seed200000 employees once, then generate a truncateAt120000 export and Upload as reconciliation run. The amber sentence comes verbatim from the server; reject with a note, or review the consequences before approving either mode. Uploaded and Export taken are separate timestamps. Operator employee details omit bookings and transport editing.

As acme.admin, find an employee by ID, inspect field ownership and the first-arrival receipt timeline, then edit transport fields. Clear explicitly sends null; unchanged fields are omitted and Save is disabled for a no-op. Receipts are scoped to the displayed page. E1001 shows only My profile and own bookings. Reload deliberately signs out. The200k demonstration is local; the existing HTTPS deployment gets the console only after the pre-deploy report is approved.
