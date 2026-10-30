#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
for tool in curl jq docker; do command -v "$tool" >/dev/null || { echo "Missing required command: $tool" >&2; exit 1; }; done
BASE=${BASE_URL:-http://localhost:8080/api/v1}
if [[ -f .env ]]; then set -a; source <(tr -d '\r' < .env); set +a; fi
: "${DEMO_PASSWORD:?Set DEMO_PASSWORD in .env or the environment}"
login() { jq -n --arg username "$1" --arg password "$DEMO_PASSWORD" '{username:$username,password:$password}' |
  curl --fail --silent --show-error "$BASE/auth/token" -H 'content-type: application/json' --data-binary @- | jq -er .accessToken; }
HR=$(login acme.hr); OPS=$(login acme.operator)
# A unique employee avoids depending on previous demo runs or an assumed current version.
EMPLOYEE="CRASH-$(date +%s)-${RANDOM}"
curl --fail --silent --show-error "$BASE/dev/faults" -X POST -H "authorization: Bearer $OPS" -H 'content-type: application/json' \
  -d '{"point":"OUTBOX_BEFORE_DISPATCH","mode":"HALT"}' >/dev/null
jq -n --arg id "$EMPLOYEE" '{events:[{type:"CREATE",employeeId:$id,version:1,name:"Crash Demo",email:"crash@demo.test",officeCode:"BLR-1",status:"ACTIVE"}]}' |
  curl --fail --silent --show-error "$BASE/hr/events" -H "authorization: Bearer $HR" -H 'content-type: application/json' --data-binary @- | jq .summary
for ((attempt=0;attempt<60;attempt++)); do
  STATE=$(docker inspect --format '{{.State.Status}} {{.State.ExitCode}} {{.RestartCount}}' "$(docker compose ps -aq app)")
  if [[ "$STATE" == *"137"* || "$STATE" =~ [1-9][0-9]*$ ]]; then break; fi
  sleep 0.2
done
echo "$STATE"; docker compose ps -a
# A fast restart can finish between polls. Docker's retained die event proves exit 137.
docker events --since 2m --until "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --filter "container=$(docker compose ps -aq app)" --filter event=die \
  --format 'container.die exitCode={{index .Actor.Attributes "exitCode"}}'
for ((attempt=0;attempt<90;attempt++)); do
  if curl --fail --silent "${MANAGEMENT_URL:-http://127.0.0.1:8081}/actuator/health/readiness" >/dev/null; then break; fi
  sleep 1
done
EVENT_ID=$(docker compose exec -T db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -c "$1"' sh \
  "SELECT event_id FROM outbox_event WHERE aggregate_id='$EMPLOYEE'" | tr -d '\r')
for ((attempt=0;attempt<30;attempt++)); do
  LINE=$(docker compose logs app | grep 'outbox.published' | grep -F "$EVENT_ID" || true)
  [[ -z "$LINE" ]] || { echo "$LINE"; exit 0; }
  sleep 1
done
echo "Event was not published after restart: $EVENT_ID" >&2; exit 1
