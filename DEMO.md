# Reviewer walkthrough

Open **[the live application](https://rostersync-aditya.duckdns.org)**. The [README access section](README.md#demo-access) lists the four usernames, roles and shared demo password. Use only the synthetic interview data. The same deployment is shared between reviewers, so counts and employee versions can change.

## Browser checks, about five minutes

| Step | Action | Expected result |
|---|---|---|
| 1. Sign in | Use `acme.hr`. Open **Event console**. | HR event tools are available; transport and operator privileges are not granted. |
| 2. Arrival order | Select **Out-of-order (brief example)**. Copy the generated employee ID. | Create v5 and transfer v7 apply. The later-arriving v6 is STALE; current version stays 7. |
| 3. Replay | Select **Replay last batch**. | The replay is DUPLICATE. This preset replays its last request, not every earlier step. No extra employee change is created. |
| 4. Version-only change | Select **No-op higher version**. | NO_CHANGE advances the HR version without an Employee business event. |
| 5. Ownership | Select **Ownership violation**. | HR fields can apply; the transport-owned field is ignored with FIELD_NOT_OWNED_BY_SOURCE. |
| 6. Employee view | In a separate tab, sign in as `acme.admin`, open **Employees**, and find the copied ID. | The latest HR state and first-arrival version timeline are visible. Transport fields are editable; HR fields are not. |
| 7. Transport edit | Change a pickup point and save. Then clear it with the explicit clear control. | A real change advances transportVersion. Null clears a field; an unchanged form cannot create a no-op write. HR version is independent. |
| 8. Invalid records | Return to the same HR tab and select **Bad batch**. | Three records apply and two are rejected. The reports show fields/codes without echoing rejected values; a bad record does not stop its neighbours. |
| 9. Broken export | In another tab, sign in as `acme.operator`. In **Demo tools**, set **Truncate at** to `1`, generate an export, then upload it as a reconciliation run. | AWAITING_APPROVAL shows the missing count/share and recorded brake threshold. Applied remains zero. Do not approve this intentionally incomplete export. |
| 10. Human decision | Choose **Reject run**, enter a short note and confirm. | The run becomes REJECTED. The suspicious plan has not deactivated employees. If a prior run is already waiting, inspect/reject it before starting another. |
| 11. Operations | Open **Operations**. Inspect durable counts, pending/dead outbox state and alerts. | Tenant SQL state is separated from instance-wide sinceRestart counters. No public actuator link is needed. |
| 12. Self access | Sign in as `E1001` in its own tab. | Only the employee's own profile/bookings are available. It cannot use HR, admin or operator tools. |

Each tab keeps its token in memory. Refreshing signs it out. Keep the HR tab mounted while using the related presets, and use the generated ID instead of an old fixture from someone else's run. Vertical scrolling on detailed tables is normal.

## Repeat the API checks

Import the [Postman collection](postman/roster-sync.postman_collection.json) and [environment template](postman/roster-sync.postman_environment.json), or use Bash with curl/jq. Set baseUrl to the live origin and enter the demo password from the README. Store tokens in variables; do not enable shell tracing or print them.

From Git Bash or Linux, run the lightweight remote checks without needing Docker access to the EC2 host:

```bash
read -rsp 'Demo password: ' DEMO_PASSWORD; echo; export DEMO_PASSWORD
DEMO_AUTO=1 DEMO_LIGHT=1 DEMO_ENV_FILE=none DEMO_SKIP_CRASH=1 BASE_URL=https://rostersync-aditya.duckdns.org ./scripts/demo.sh
unset DEMO_PASSWORD
```

This checks ordering, the 44/3/3 mixed feed and replay, invalid records, ownership, booking cancellation, tenancy and operational state. It intentionally skips large-file work and the host-level crash. An unexpected result exits non-zero. It creates unique IDs and waits for old active runs; an intentionally waiting plan is rejected before continuing.

## Local scale and real crash proof

Use the [local setup](README.md#run-locally), then run the complete script from a terminal that can control that local Compose project:

```bash
DEMO_AUTO=1 ./scripts/demo.sh
```

The full flow seeds 200,000 employees, compares a healthy file, brakes a truncated 120,000-row file, and halts/restarts the app between durable commit and delivery. Step 9 requires observing exit137/restart plus publication of the same eventId afterward. Step 10 proves future bookings cancelled, past bookings kept and the seat freed. Do not seed 200k on the small public instance.

For exact commands and fault locations see [the detailed demo guide](docs/DEMO_SCRIPT.md), [crash helper](scripts/demo-crash.sh), [test catalogue](docs/TESTING.md) and [case-study coverage](docs/REQUIREMENTS_TRACEABILITY.md). The public host's app/db/Caddy runtime, private management port and single-instance limits are described in [deployment](docs/DEPLOYMENT.md).
