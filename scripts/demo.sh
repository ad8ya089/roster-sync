#!/usr/bin/env bash
set -euo pipefail
shopt -s inherit_errexit 2>/dev/null || true
cd "$(dirname "$0")/.."
for tool in curl jq; do command -v "$tool" >/dev/null || { echo "Missing command: $tool" >&2; exit 1; }; done
# An explicitly supplied remote credential must win over the local demo .env.
provided_password=${DEMO_PASSWORD-}
if [[ -f .env && ${DEMO_ENV_FILE:-.env} != none ]]; then set -a; source <(tr -d '\r' < .env); set +a; fi
[[ -z "$provided_password" ]] || DEMO_PASSWORD=$provided_password
: "${DEMO_PASSWORD:?Set DEMO_PASSWORD in .env or the environment}"
BASE=${BASE_URL:-http://127.0.0.1:8080}; BASE=${BASE%/}; [[ "$BASE" == */api/v1 ]] || BASE+=/api/v1
management_requested=${MANAGEMENT_URL+yes}
MANAGEMENT_URL=${MANAGEMENT_URL:-http://127.0.0.1:8081}
RUN="$(date +%s)_$RANDOM"; EMPLOYEE="DEMO_${RUN}_EMP"; WORK="target/demo-$RUN"; mkdir -p "$WORK"
die(){ echo "DEMO FAILED: $*" >&2; exit 1; }
step(){ printf '\n=== %s ===\n' "$*"; if [[ ${DEMO_AUTO:-0} != 1 ]]; then read -r -p 'Press Enter to continue... '; fi; }
api(){ local method=$1 path=$2 token=$3; shift 3; curl -fsS -X "$method" "$BASE$path" -H "authorization: Bearer $token" "$@"; }
json(){ local method=$1 path=$2 token=$3 body=$4; api "$method" "$path" "$token" -H 'content-type: application/json' --data-binary "$body"; }
login(){ jq -n --arg username "$1" --arg password "$DEMO_PASSWORD" '{username:$username,password:$password}' |
  curl -fsS "$BASE/auth/token" -H 'content-type: application/json' --data-binary @- | jq -er .accessToken; }
event(){ jq -n --arg id "$EMPLOYEE" --arg type "$1" --argjson version "$2" --arg office "$3" --arg shift "$4" --arg at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  '{events:[{type:$type,employeeId:$id,version:$version,name:"Demo User",email:"demo@example.test",phone:"+919800000101",managerId:"E1001",officeCode:$office,shiftCode:$shift,status:"ACTIVE",occurredAt:$at}]}'; }
assert_json(){ local body=$1 condition=$2; jq -e "$condition" <<<"$body" >/dev/null || die "Unexpected response: $body"; }
wait_run(){
  local id=$1 expected=$2 status body deadline=$((SECONDS+180))
  while :; do
    body=$(api GET "/reconciliation/runs/$id" "$OPS"); status=$(jq -r .status <<<"$body")
    echo "Waiting runId=$id status=$status" >&2
    if [[ "$status" =~ ^(PLANNED|AWAITING_APPROVAL|COMPLETED|REJECTED|FAILED)$ ]]; then
      [[ "$expected" == any || "$status" == "$expected" ]] || die "runId=$id status=$status expected=$expected"
      printf '%s\n' "$body"; return
    fi
    ((SECONDS<deadline)) || die "180s timeout: runId=$id status=$status"
    sleep 2
  done
}
cleanup_runs(){
  local state id result listing ids
  for state in UPLOADED DIFFING APPLYING AWAITING_APPROVAL; do
    listing=$(api GET "/reconciliation/runs?status=$state&size=200" "$OPS")
    assert_json "$listing" '.items|type=="array"'
    ids=$(jq -r '.items[].runId' <<<"$listing")
    while read -r id; do
      [[ -n "$id" ]] || continue
      result=$(wait_run "$id" any)
      if [[ $(jq -r .status <<<"$result") == AWAITING_APPROVAL ]]; then
        echo "Rejecting prior demo runId=$id"
        result=$(json POST "/reconciliation/runs/$id/reject" "$OPS" '{"note":"re-runnable demo cleanup"}')
        assert_json "$result" '.status=="REJECTED"'
      fi
    done <<<"$ids"
  done
}

step '0–1: readiness, tokens and prior-run cleanup'
if [[ ${DEMO_LIGHT:-0} == 1 && -z "$management_requested" ]]; then
  curl -fsS "${BASE%/api/v1}/" >/dev/null
else
  curl -fsS "$MANAGEMENT_URL/actuator/health/readiness" | jq -e '.status=="UP"' >/dev/null
fi
HR=$(login acme.hr); ADMIN=$(login acme.admin); OPS=$(login acme.operator); EMP=$(login E1001); GLOBEX=$(login globex.admin)
api DELETE /dev/faults "$OPS" >/dev/null
cleanup_runs
json PATCH /offices/BLR-1 "$ADMIN" '{"active":true}' >/dev/null
json PATCH /offices/BLR-2 "$ADMIN" '{"active":true}' >/dev/null
json PUT /settings/reconciliation "$OPS" '{"maxMissingRatio":0.3}' >/dev/null

step '2: create a unique employee'
result=$(json POST /hr/events "$HR" "$(event CREATE 5 BLR-1 S1)"); assert_json "$result" '.summary.applied==1'; jq .summary <<<"$result"
step '3: v7 arrives before v6'
result=$(json POST /hr/events "$HR" "$(event UPDATE 7 BLR-2 S1)"); assert_json "$result" '.summary.applied==1'
result=$(json POST /hr/events "$HR" "$(event UPDATE 6 BLR-1 S3)"); assert_json "$result" '.summary.stale==1 and .results[0].currentVersion==7'; jq .results <<<"$result"
result=$(api GET "/employees/$EMPLOYEE" "$ADMIN"); assert_json "$result" '.hrVersion==7 and .officeCode=="BLR-2" and .shiftCode=="S1"'
api GET "/employees/$EMPLOYEE/events" "$ADMIN" | jq '.items|map({version,outcome})'

step '4: mixed feed and an identical replay'
jq --arg prefix "DEMO_${RUN}_" '.events[].employeeId |= $prefix + .' samples/feed.json > "$WORK/feed.json"
result=$(api POST /hr/events "$HR" -H 'content-type: application/json' --data-binary "@$WORK/feed.json")
assert_json "$result" '.summary.applied==44 and .summary.stale==3 and .summary.duplicate==3'; jq .summary <<<"$result"
result=$(api POST /hr/events "$HR" -H 'content-type: application/json' --data-binary "@$WORK/feed.json")
assert_json "$result" '.summary.duplicate==50 and .summary.applied==0'; jq .summary <<<"$result"

step '5: invalid rows do not fail the batch'
jq --arg prefix "DEMO_${RUN}_" '.events[].employeeId |= $prefix + .' samples/bad-batch.json > "$WORK/bad.json"
result=$(api POST /hr/events "$HR" -H 'content-type: application/json' --data-binary "@$WORK/bad.json")
assert_json "$result" '.summary.applied==3 and .summary.rejected==2'; jq .summary <<<"$result"
jq '.results[]|select(.outcome=="REJECTED")|{employeeId,errors}' <<<"$result"

step '6: field ownership'
json PATCH "/employees/$EMPLOYEE/transport-fields" "$ADMIN" '{"pickupPoint":"Gate 3","specialAssistance":"WHEELCHAIR"}' >/dev/null
payload=$(event UPDATE 8 BLR-2 S2 | jq '.events[0].pickupPoint="HR value must be ignored"')
result=$(json POST /hr/events "$HR" "$payload");assert_json "$result" '.summary.applied==1 and any(.results[0].warnings[];.code=="FIELD_NOT_OWNED_BY_SOURCE")'
jq '.results[0]|{outcome,warnings}' <<<"$result"
result=$(api GET "/employees/$EMPLOYEE" "$ADMIN");assert_json "$result" '.shiftCode=="S2" and .transport.pickupPoint=="Gate 3"';jq '{shiftCode,transport}' <<<"$result"

step '7: healthy 200k reconciliation'
HEALTHY=skipped; CUT=skipped
if [[ ${DEMO_LIGHT:-0} != 1 ]]; then
api POST '/dev/seed-employees?count=200000' "$OPS" | jq .
api GET '/dev/export.csv?mutatePercent=1&dropPercent=0.5&seed=20261030' "$OPS" -o "$WORK/full.csv"
accepted=$(api POST /reconciliation/runs "$HR" -F "file=@$WORK/full.csv" -F "asOf=$(date -u +%Y-%m-%dT%H:%M:%SZ)")
HEALTHY=$(jq -er .runId <<<"$accepted");result=$(wait_run "$HEALTHY" COMPLETED)
assert_json "$result" '.brake.tripped==false and .timings.diffMs<60000';jq '{runId,status,plan,timings}' <<<"$result"

step '8: truncated file stops deactivations'
before=$(api GET '/employees?status=ACTIVE&size=1' "$ADMIN" | jq -r .totalElements)
api GET '/dev/export.csv?truncateAt=120000&seed=20261030' "$OPS" -o "$WORK/cut.csv"
accepted=$(api POST /reconciliation/runs "$HR" -F "file=@$WORK/cut.csv" -F "asOf=$(date -u +%Y-%m-%dT%H:%M:%SZ)")
CUT=$(jq -er .runId <<<"$accepted");result=$(wait_run "$CUT" AWAITING_APPROVAL);assert_json "$result" '.applied==0 and .brake.tripped==true';jq '{runId,status,brake}' <<<"$result"
after=$(api GET '/employees?status=ACTIVE&size=1' "$ADMIN" | jq -r .totalElements)
[[ "$before" == "$after" ]] || die "ACTIVE count changed: $before -> $after"
echo "ACTIVE unchanged: $before -> $after"
json PUT /settings/reconciliation "$OPS" '{"maxMissingRatio":0.5}' | jq .
result=$(json POST "/reconciliation/runs/$CUT/reject" "$OPS" '{"note":"truncated export"}');assert_json "$result" '.status=="REJECTED"'
json PUT /settings/reconciliation "$OPS" '{"maxMissingRatio":0.3}' >/dev/null
else
  echo 'DEMO_LIGHT=1: skipping 200k seed/reconciliation steps 7–8 (demonstrated locally)'
fi

step '9: actual process crash and outbox recovery'
if [[ ${DEMO_SKIP_CRASH:-0} == 1 ]]; then
  echo 'DEMO_SKIP_CRASH=1: crash/recovery is checked separately over SSM'
else
  command -v docker >/dev/null || die 'Missing command: docker'
  BASE_URL="$BASE" MANAGEMENT_URL="$MANAGEMENT_URL" ./scripts/demo-crash.sh
fi

step '10: deactivation cancels future bookings, preserves past ones'
current=$(api GET /employees/E1002 "$ADMIN");version=$(jq '.hrVersion+1' <<<"$current")
payload=$(jq --argjson version "$version" '{events:[{type:"UPDATE",employeeId,version:$version,name,email,phone,managerId,officeCode,shiftCode,status:"ACTIVE"}]}' <<<"$current")
json POST /hr/events "$HR" "$payload" >/dev/null
future=$(jq -n 'now+86400|todateiso8601');past=$(jq -n 'now-86400|todateiso8601')
one=$(json POST /bookings "$ADMIN" "$(jq -n --argjson at "$future" '{employeeId:"E1002",pickupAt:$at}')" | jq -r .id)
two=$(json POST /bookings "$ADMIN" "$(jq -n --argjson at "$future" '{employeeId:"E1002",pickupAt:$at}')" | jq -r .id)
old=$(json POST /bookings "$ADMIN" "$(jq -n --argjson at "$past" '{employeeId:"E1002",pickupAt:$at}')" | jq -r .id)
payload=$(jq -n --argjson version "$((version+1))" '{events:[{type:"DEACTIVATE",employeeId:"E1002",version:$version}]}')
result=$(json POST /hr/events "$HR" "$payload");assert_json "$result" '.summary.applied==1'
deadline=$((SECONDS+30))
while :; do
  result=$(api GET /employees/E1002/bookings "$ADMIN")
  if jq -e --argjson one "$one" --argjson two "$two" '[.[]|select(.id==$one or .id==$two)|select(.status=="CANCELLED" and .cancelReason=="EMPLOYEE_DEACTIVATED")]|length==2' <<<"$result" >/dev/null; then break; fi
  ((SECONDS<deadline)) || die 'Future bookings were not cancelled';sleep 1
done
jq -e --argjson id "$old" 'any(.[];.id==$id and .status=="SCHEDULED")' <<<"$result" >/dev/null || die 'Past booking changed'
api GET /routes/R-BLR-07/seats "$ADMIN" | jq -e 'any(.[];.seatNo==2 and .employeeId==null)' >/dev/null
echo 'Future bookings cancelled; past booking unchanged; seat 2 free'

step '11: tenant isolation and employee self-access'
status=$(curl -sS -o "$WORK/denied.json" -w '%{http_code}' "$BASE/employees/$EMPLOYEE" -H "authorization: Bearer $GLOBEX");[[ "$status" == 404 ]] || die "Cross-tenant status=$status"
status=$(curl -sS -o "$WORK/denied.json" -w '%{http_code}' "$BASE/employees/$EMPLOYEE" -H "authorization: Bearer $EMP");[[ "$status" == 404 ]] || die "Self-check status=$status"
api GET /me "$EMP" | jq -e '.employeeId=="E1001"' >/dev/null
step '12: operational view'
api GET /ops/summary "$OPS" | jq .
if [[ ${DEMO_LIGHT:-0} != 1 || -n "$management_requested" ]]; then
  curl -fsS "$MANAGEMENT_URL/actuator/prometheus" > "$WORK/prometheus.txt"
  grep -E '^(roster_events_total|outbox_pending|recon_brake_tripped_total)' "$WORK/prometheus.txt"
else
  status=$(curl -sS -o /dev/null -w '%{http_code}' "${BASE%/api/v1}/actuator/health/readiness")
  [[ "$status" == 404 ]] || die "Public management status=$status"
  echo 'Management remains private; use SSM forwarding for Prometheus/readiness'
fi
echo "DEMO PASS run=$RUN healthyRun=$HEALTHY rejectedRun=$CUT evidence=$WORK"
