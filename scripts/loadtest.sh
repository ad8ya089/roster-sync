#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -f .env ]]; then set -a; source <(tr -d '\r' < .env); set +a; fi
: "${DEMO_PASSWORD:?Set DEMO_PASSWORD in .env or the environment}"
export DEMO_PASSWORD
mkdir -p loadtest/results
network=${DOCKER_NETWORK:-bridge}
if [[ "$network" == host ]]; then
  network_args=(--network host)
  BASE_URL=${BASE_URL:-http://127.0.0.1:8080}
else
  # Docker Desktop reaches the host through this name; Linux gets an explicit gateway alias.
  network_args=()
  if [[ $(docker info --format '{{.OperatingSystem}}') != *'Docker Desktop'* ]]; then
    network_args=(--add-host=host.docker.internal:host-gateway)
  fi
  BASE_URL=${BASE_URL:-http://host.docker.internal:8080}
fi
mount="$(pwd)/loadtest"
if command -v cygpath >/dev/null; then mount=$(cygpath -w "$mount"); fi
MSYS_NO_PATHCONV=1 docker run --rm -i "${network_args[@]}" \
  --mount "type=bind,source=$mount,target=/scripts" \
  -e "BASE_URL=$BASE_URL" -e DEMO_PASSWORD -e "LOAD_VUS=${LOAD_VUS:-20}" \
  -e "LOAD_DURATION=${LOAD_DURATION:-60s}" -e "RUN_ID=${RUN_ID:-$(date +%s)-$RANDOM}" \
  -e "BATCH_SIZE=${BATCH_SIZE:-50}" \
  "${K6_IMAGE:-grafana/k6:2.3.0}" run /scripts/ingest.js
