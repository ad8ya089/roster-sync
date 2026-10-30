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
curl -s localhost:8080/actuator/health/readiness | jq
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
curl -s $BASE/ops/summary -H "authorization: Bearer $OPS" | jq .outbox
curl -s $BASE/hr/events -H "authorization: Bearer $HR" -H 'content-type: application/json' -d @samples/feed.json | jq .summary
curl -s $BASE/ops/summary -H "authorization: Bearer $OPS" | jq .outbox
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
curl -s $BASE/ops/summary -H "authorization: Bearer $OPS" | jq .outbox   # pending 0
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
curl -s localhost:8080/actuator/prometheus -H "authorization: Bearer $OPS" | grep -E '^(roster_events_total|outbox_pending|recon_brake)'
```
Close with the README benchmark table and test count (`mvn verify` summary screenshot).

Phase 4 note for step 4: `/ops/summary` arrives in Phase 7. Until then compare outbox counts using `docker compose exec -T db psql -U roster -d rostersync -At -c 'SELECT count(*) FROM outbox_event'` before/after replay. This checks the same invariant without adding a premature ops endpoint. Publishing and booking side effects are implemented in Phase 5.

The mixed feed remains 50 events: first send yields 44 APPLIED, 3 STALE (delayed v2 after v5) and 3 in-batch DUPLICATE; replay yields 50 DUPLICATE with originalOutcome APPLIED/STALE. Three v4 events are replaced by duplicates, keeping the fixture at 50 records.

For a repeatable standalone step 9 on Linux or Git Bash/Windows, run `./scripts/demo-crash.sh` (curl + jq required). It uses a unique employee id, shows `docker compose ps -a` plus exit/restart metadata, then prints the exact eventId's published line. HALT is one-shot and arming disappears on restart. Check outbox status directly with psql until /ops/summary arrives in Phase 7. Step 10 keeps the past booking untouched, cancels both future scheduled bookings and clears seat 2; a delayed old deactivation after rehire is skipped.

Phase 5 clean-volume verification (2026-10-30): the portable crash script returned APPLIED, Docker retained `container.die exitCode=137`, restart count reached 2 after two intentional smoke runs, and the restarted process published eventId `9a80d461-9239-42b3-ba32-d4d0dda2869e`. Both Compose services became healthy. Step 10 returned APPLIED, changed the two future bookings to CANCELLED / EMPLOYEE_DEACTIVATED, retained the completed past booking and freed seat 2. The mixed feed produced 44 applied / 3 stale / 3 duplicate; replay produced 50 duplicate and the pending outbox returned zero.

## Phase 6 verified Compose evidence (2026-10-30)
Fresh-volume final image: both app/db healthy, migrations through V3. The isolated 200k seed took19062ms (explicitly checked <20000ms). Seed20261030 export used mutatePercent1/dropPercent0.5. Healthy run71fcdf8a-b5b2-4006-aee7-27dda5d3ce01 completed: rows198995/valid198995/rejected0, updates1922, deactivations1010, unchanged197073, applied2932, diff12577ms/apply31073ms. The store includes five demo fixture employees in addition to200000 S-id fixtures.

The subsequent120000-row file produced runf645c14d-8ffb-44a8-a59d-f3ec9aea9bbd, AWAITING_APPROVAL: activeBefore198995, missingActive79591 (40.0%), applied0, diff15916ms. Message: "File appears to be missing 79,591 of 198,995 active employees (40.0%). Threshold is 30.0%. No one has been deactivated. Approve or reject this run." ACTIVE count stayed198995 before/after. PUT maxMissingRatio0.5 returned0.500; reject returned REJECTED with applied0. Approval waiting time is not reported as apply time.
