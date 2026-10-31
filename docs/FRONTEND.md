# Ops Console (Frontend) — Specification

A small web console on top of the existing API. It exists for two reasons:

1. **The brief requires a human decision.** A braked reconciliation run waits until "a human approves". The console gives that operator a screen: what is missing, how many, what will happen, and Approve / Reject.
2. **It makes the guarantees visible.** Outcome badges (APPLIED / STALE / DUPLICATE / NO_CHANGE / REJECTED / CONFLICT), an employee's version timeline and the brake panel show in one glance what curl output hides.

It is an **operator tool, not a product UI**. Dense, calm, utilitarian. No marketing pages, no landing hero, no animations beyond state transitions.

## 1. Non-goals

- No new business logic in the browser. Every rule (ordering, ownership, brake, roles, tenancy) stays on the server; the UI only displays and calls the API.
- No separate frontend deployment, no Node server in production, no SSR.
- The sole approved API change is the read-only createdAt field on reconciliation runs. Other backend changes are limited to §9.
- No user management, no theming engine, no i18n, no offline mode.

## 2. Stack (pin exact versions in package.json; use the latest stable at install time)

| Concern | Choice | Why |
|---|---|---|
| Language | TypeScript (strict) | API types catch contract drift |
| Framework | React (current major) | most common; easy to explain |
| Build | Vite | fast, simple static output |
| Routing | React Router, **hash router** (`/#/runs`) | the server needs no SPA fallback routes, so `/api/**` 404 behaviour and security guard tests are untouched |
| Server state | TanStack Query | caching, polling, retries, loading/error states without hand-written effects |
| Styling | Tailwind CSS | consistent spacing/colour tokens, no CSS framework look |
| Icons | lucide-react (line icons, 16 px, stroke 1.5) | **never emojis** |
| Fonts | Overpass + Overpass Mono, self-hosted via @fontsource | see §6; no Google Fonts CDN (CSP `font-src 'self'`) |
| Accessibility checks | @axe-core/playwright | contrast and ARIA verified automatically |
| Tests | Vitest + Testing Library; Playwright for one e2e smoke | Playwright is already used/available in this environment |
| Node | current LTS (24.x) | only at build time |

Not allowed: Redux/Zustand (TanStack Query + React state is enough), component kits with a recognisable look (MUI, Ant, Bootstrap), chart libraries (bars are simple divs), axios (use `fetch`).

## 3. Repository layout

```
frontend/
├── package.json  package-lock.json  tsconfig.json  vite.config.ts  index.html
├── src/
│   ├── main.tsx  App.tsx  routes.tsx
│   ├── api/            client.ts (fetch wrapper), types.ts (DTOs mirrored from docs/API.md), queries.ts (TanStack hooks)
│   ├── auth/           AuthContext.tsx (token in memory), RequireRole.tsx, jwt.ts (decode claims for display only)
│   ├── components/     OutcomeBadge, StatusPill, OwnerTag, RouteLine (shared by VersionTimeline and RunStages), BrakePanel, PlanBar, ServiceRow,
│   │                   DataTable, overlay/ (Drawer, ConfirmDialog, Menu — the only place shadows are allowed), ErrorNotice (shows correlationId), EmptyState, PageHeader
│   ├── pages/          StyleguidePage (VITE_STYLEGUIDE only), LoginPage, OpsDashboardPage, ReconciliationPage, RunDetailPage, EventConsolePage,
│   │                   EmployeesPage, EmployeeDetailDrawer, MePage, NotFoundPage
│   ├── scenarios/      presets for the event console (built relative to current employee state)
│   └── styles/         index.css (Tailwind + tokens)
└── tests/              unit (Vitest) and e2e/ (Playwright)
```

## 4. Auth & roles

- Login form → `POST /api/v1/auth/token`. The access token is kept **in memory only** (React context). Reloading the page logs you out — a deliberate trade-off: nothing for an XSS payload to read from storage, and tokens live 1 h anyway.
- Role, tenant code and employee id are read from the JWT payload **for display and menu gating only** (no signature check in the browser; the server verifies every call).
- `401 TOKEN_EXPIRED` or `UNAUTHENTICATED` from any call → clear token, go to login with "Session expired".
- `403` → inline "You don't have access to this action" (should be rare because actions are hidden).
- Header shows: tenant code, username, role, Logout.
- The sign-in page links to public `/account-access` help. It explains that staff accounts are prepared in advance, lists the same demo usernames as README and can prefill a username. It never creates an account, grants a role or exposes a password. This review-approved help replaces the earlier hidden username-hints panel.

| Screen | HR_SYSTEM | TRANSPORT_ADMIN | OPERATOR | EMPLOYEE |
|---|---|---|---|---|
| Ops dashboard | – | – | ✓ (default) | – |
| Reconciliation runs + detail | ✓ (view, upload) | ✓ (view) | ✓ (view, upload, approve/reject, settings) | – |
| Event console | ✓ (default) | – | – | – |
| Employees + detail | – | ✓ (default; edit transport fields, bookings) | ✓ (view) | – |
| My profile | – | – | – | ✓ (default) |

(Use ✓/– only in this doc. In the UI, never use emoji or check-mark characters as icons — use lucide icons.)

## 5. Screens

### 5.1 Ops dashboard (`/#/ops`, OPERATOR) — a service-status board, not a card grid
- Modelled on a transit "service status" board. One row per line of work: **Event intake**, **Change delivery** (outbox), **Nightly reconciliation**, **Alerts**. Each row: signal dot + status word (*Running normally* / *Delayed* / *Needs a decision* / *Stopped*), one plain sentence built from real numbers ("53 changes applied, 4 stale, 2 rejected in the last 15 minutes"), the key figures right-aligned in tabular numerals, and the row's action ("Review run", "Retry", "Acknowledge"). Data: `GET /api/v1/ops/summary`, poll 10 s.
- Status rules (document them in the code): Change delivery is *Delayed* when oldest pending age > 60 s, *Stopped* when dead > 0; Reconciliation is *Needs a decision* when a run awaits approval; Event intake is *Delayed* when the rejection share > 5 %. Use rejected / (applied + stale + noChange + rejected + conflict), zero when that denominator is zero, labelled "of durable recorded outcomes"; do not mix in sinceRestart counters.
- Below the board, two plainly labelled groups of figures: "This tenant, from the database" and "This app instance, since <time>" (the durable vs sinceRestart split).
- Needs-attention list: runs awaiting approval (link), open alerts (Acknowledge → `POST /ops/alerts/{id}/ack`), DEAD outbox rows (Retry → `POST /ops/outbox/{id}/retry`, with confirm).
- Empty state: every row reads *Running normally*; no celebration copy.

### 5.2 Reconciliation (`/#/runs`, `/#/runs/:runId`)
- Runs table: Uploaded (createdAt), Export taken (asOf), status pill, rows (total/rejected), plan counts, brake tripped, applied. Filter by status. Poll 5 s while any run is non-terminal.
- Upload panel (HR_SYSTEM, OPERATOR): file input, `asOf` (defaults to now, editable), `expectedRowCount` (optional), `dryRun` checkbox → `POST /reconciliation/runs` (multipart). Show 400/409 problem details inline (e.g. `RECON_EXPORT_STALE`, `RECON_RUN_ALREADY_ACTIVE`, `INVALID_CSV_HEADER`).
- **Run detail** (poll 2 s while non-terminal):
  - Run stages drawn as a **route line** (§6 signature): Uploaded → Comparing → (Waiting for approval) → Applying → Completed, with Rejected/Failed as a terminating stop in red. Current stop is filled; future stops hollow.
  - Plan bar: one horizontal stacked bar (creates, updates, heals, advances, deactivations, unchanged, stale-skipped) with a legend and exact numbers.
  - **Brake panel** (when `brake.tripped`): large amber panel, the server's `brake.message` verbatim, the numbers (active before, missing, ratio vs threshold), and three actions — *Approve all*, *Approve without deactivations*, *Reject* — each opening a confirm dialog that restates the consequence in plain words and requires a note. Buttons only for OPERATOR; others see "Waiting for an operator".
  - Progress during APPLYING: applied / superseded vs planned.
  - Rejections tab: `GET /runs/{id}/rejections` (row number, employee id, error codes — never values).
  - Timings: diff ms, apply ms.
- Settings drawer (OPERATOR): current threshold ratio/count and source; edit → `PUT /settings/reconciliation`; note "applies to the next run".

### 5.3 Event console (`/#/events`, HR_SYSTEM)
- Left: JSON editor (monospace textarea with format/validate button) for a `{ "events": [...] }` batch. Right: response.
- Response view: summary chips by outcome, then a results table: index, employeeId, version, **OutcomeBadge**, currentVersion/originalOutcome, warnings and errors as `field: CODE` chips.
- **Scenario presets** (buttons that fill the editor and, for multi-step ones, send sequentially with a short delay, showing each step's result):
  - *Out-of-order (brief example)*: new id `UI-<random>`; send v5 CREATE (BLR-1, S1), then v7 UPDATE (BLR-2, S1), then v6 UPDATE (BLR-1, S3). Expect APPLIED, APPLIED, STALE. Render a VersionTimeline from the three responses, with the note "Server-side history: Employees screen (admin/operator)".
  - *Replay*: resend the last batch exactly → all DUPLICATE.
  - *Bad batch*: 3 valid + 1 bad phone + 1 unknown office → isolated REJECTED rows.
  - *Ownership violation*: an UPDATE that includes `pickupPoint` → APPLIED with `FIELD_NOT_OWNED_BY_SOURCE`.
  - *No-op higher version*: same content, version+1 → NO_CHANGE.
  - *Deactivate*: DEACTIVATE (next version) for the last `UI-<random>` employee → APPLIED.
  HR_SYSTEM cannot read employees (least privilege, unchanged). So presets only use fresh `UI-<random>` employees and versions the console itself sent in this session, kept in memory. Bookings/side effects are shown from the Employees screen by an admin.
- Batch size limit (1000) and body limit (2 MB) shown under the editor.

### 5.4 Employees (`/#/employees`, `/#/employees/:id` drawer)
- Paged table (`GET /employees`, size 50): id, name, office, shift, status pill, HR version, transport version. Filters: status, office code; search by **employee id** only (no name search → no PII in URLs or logs).
- Detail drawer:
  - Two field groups with **OwnerTag** colour coding: HR-owned (blue) and Transport-owned (purple), plus a caption: "HR updates never overwrite purple fields."
  - **VersionTimeline** uses the unchanged receipt endpoint. Sort loaded receipts by receivedAt ascending and label it "First arrival of each version". Applied stops stay on the route; stale and duplicate stops branch beside it. Display the loaded-page scope because receipts are paged by version and replays do not create a new receipt. Event-console session results use the same arrival-order rendering.
  - Transport fields form (TRANSPORT_ADMIN): pickup point and special assistance; *Clear* sends explicit `null`; *Save* is disabled when nothing changed. Show the returned transport version.
  - Bookings list (TRANSPORT_ADMIN only; hidden for OPERATOR): pickup time, status pill, cancel reason.

### 5.5 My profile (`/#/me`, EMPLOYEE)
- `GET /me` and own bookings. Read-only.

### 5.6 Demo tools panel (OPERATOR, only when demo profile detected)
- Seed N employees (`POST /dev/seed-employees`), *Generate export* (`GET /dev/export.csv` with truncateAt / mutatePercent / dropPercent) that downloads the CSV as a Blob and offers *Upload as reconciliation run* in one click. This makes "upload a truncated file and watch the brake" a two-click demo.
- Fault injection stays in the terminal on purpose (the kill -9 is more convincing there).

## 6. Visual design — "dispatch board"

### Subject, audience, job
- **Subject:** the employee roster behind corporate shuttle routes — shift rosters, office pickups, night cabs, route seats. The visual vocabulary comes from transit operations: route-line diagrams, service-status boards, wayfinding signage.
- **Audience:** a transport operator or on-call engineer, often at night, deciding whether tonight's HR file can be trusted.
- **Primary job:** see at a glance whether intake, delivery and reconciliation are healthy, and make the one human decision the system requires (approve or reject a braked run) with full confidence.

### Tokens (the only colours allowed; define them as Tailwind theme tokens and CSS variables)

| Token | Hex | Use |
|---|---|---|
| `paper` | `#F3F5F2` | page background (faint green-grey, like a printed route map — deliberately not cream, not slate) |
| `surface` | `#FFFFFF` | tables, drawers, dialogs |
| `ink` | `#1B2430` | primary text, route lines |
| `ink-muted` | `#5B6673` | secondary text, column headers |
| `rule` | `#D5DBD6` | 1 px borders and table rules |
| `route` | `#00756F` | accent: links, primary buttons, focus ring, current stop |
| `signal-amber` | `#F2B705` | **brake panel fill only** (with `ink` text) — signage amber, the one loud colour |
| `line-hr` | `#0057B8` | HR-owned fields (a "line colour") |
| `line-transport` | `#7A3E9D` | transport-owned fields (a second line colour) |

Outcome colours, each with a **text** shade (≥ 4.5:1 on white) and a **tint** for backgrounds:
applied `#1E6B43` / `#E6F2EA` · stale & skipped `#8A5300` / `#FBF1DD` · duplicate & no-change `#5B6673` / `#EEF0F1` · rejected, conflict & failed `#B42318` / `#FBE9E7`.
Dark theme (`prefers-color-scheme: dark`): paper `#11181F`, surface `#18212A`, ink `#E6ECEA`, rule `#2C3742`, same hues lightened to keep ≥ 4.5:1; verify with axe.

### Type
- **Overpass** (open-source, derived from the US Highway Gothic road-sign lettering) for all UI text — a typeface that belongs to the transport world, not a default UI font. **Overpass Mono** only for employee ids, versions, run ids and the JSON editor — never for labels.
- Scale (px / line-height): 12/16 captions · 13/20 table body · 14/20 controls · 16/24 drawer body · 20/28 page titles · 28/34 the brake message. Weights 400 and 600 only.
- Sentence case everywhere: titles, column headers, buttons, badges' words ("Applied", "Stale"). **No ALL-CAPS labels, no letter-spaced eyebrows above headings.**
- Tabular figures for every number column (`font-variant-numeric: tabular-nums`), right-aligned; thousands separators in `en-US` to match the API message.
- Body text max ~72 characters per line (drawers, dialogs, empty states).

### Layout
Left-aligned throughout; nothing centred except dialogs.
```
+--------+-------------------------------------------------------------+
| Roster |  acme   ·   operator: acme.operator         [Log out]        |  <- top bar: tenant + user (only place a separator is used)
|  Sync  +-------------------------------------------------------------+
|        |  Service status                                             |
| Status |  ● Event intake        Running normally   53 applied   ...  |
| Runs   |  ● Change delivery     Running normally    0 pending   ...  |
| Events |  ● Reconciliation      Needs a decision    1 run    [Review]|
| People |  ● Alerts              2 open                  [Acknowledge]|
|        |  -----------------------------------------------------------|
|        |  This tenant, from the database | This instance, since 12:27|
+--------+-------------------------------------------------------------+
```
Run detail (the screen that carries the design):
```
 Run 81a2…  asOf 30 Oct 02:00                           Waiting for approval
 o----o====●- - - o- - - o        Uploaded  Comparing  Waiting  Applying  Done
 +---------------------------------------------------------------------+
 |█ The file appears to be missing 79,591 of 198,995 active employees  |  <- signal-amber panel,
 |█ (40.0%). The threshold is 30.0%. No one has been deactivated.      |     28 px ink text
 |  [Approve all changes] [Approve without deactivations] [Reject run] |
 +---------------------------------------------------------------------+
 Plan  ███████████████████████████████████░░░░  unchanged 119,635 · ...
```
Employee drawer timeline:
```
 Version history
 ● v7  Applied   10:15:02   Current
 |  ○ v6  Stale — arrived after v7   10:15:04
 ● v5  Applied   10:14:58
```

### Principles
1. **Spend boldness in one place: route lines.** Sequences that really are sequences (run stages, version history) are drawn as route-line diagrams with stops. Nothing else gets decoration.
2. **The brake panel is the only loud surface.** Signage amber with ink text, large type, the server's sentence verbatim. Everything else stays quiet so this is unmistakable.
3. **Tables, not cards.** Data lives in tables with 1 px rules, 36 px rows, sticky headers. Cards are not used; the dashboard is a status board (rows), not a tile grid.
4. **Status = signal dot + word.** Never colour alone, never an icon inside a coloured circle.
5. **Shape and depth:** radius 3 px on controls and badges, 6 px on drawers and dialogs, nothing larger. No shadows except on overlays (drawer, dialog, menu). No gradients, no blur/glass, no tinted near-black (`#0B0B0B`, `#111`) — use the tokens.
6. **Motion only answers an action:** drawer slide, dialog fade, and one deliberate moment — the brake panel expanding when a run enters *Waiting for approval*. No entrance animations, no hover lifts, no animated counters, no shimmer skeletons (a plain "Loading runs" row). Respect `prefers-reduced-motion`.
7. **Words from the domain, written for the operator:** "run", "plan", "brake", "version", "pickup point". Buttons name the action and the result keeps the same word ("Reject run" → toast "Run rejected"). No exclamation marks, no "Oops", no "Welcome back", no arrows appended to button text, no middle-dot meta strings (the top bar is the single exception). Errors say what happened and what to do, with the correlation id.
8. **Real data only.** No placeholder charts, sparklines or fake trends. Every number on screen comes from the API.
9. **Quality floor:** visible focus ring (`route`, 2 px offset), keyboard-operable dialogs and drawers, responsive to 390 px (tables scroll inside their container, nav collapses to a top menu), axe clean.

### Enforced by `npm run lint:design` (fails the build)
Searches `frontend/src` and fails on: `bg-gradient`, `from-`/`via-`/`to-` gradient utilities, `backdrop-blur`, `shadow-` outside `components/overlay/`, `rounded-(lg|xl|2xl|3xl)`, `uppercase`, `tracking-wide(r|st)?`, raw Tailwind palette classes (`(bg|text|border|ring)-(slate|gray|zinc|neutral|stone|red|orange|amber|yellow|lime|green|emerald|teal|cyan|sky|blue|indigo|violet|purple|fuchsia|pink|rose)-[0-9]`), `animate-(bounce|ping|pulse|spin)` outside the loading indicator, `Inter`, the characters `→` and `·` in JSX text (top bar exempt via one constant), and any emoji code point. Tokens only.

## 7. API client rules

Versions are strings in the UI model. JSON.parse uses the reviver source-text context to preserve numeric version tokens exactly, including Long.MAX_VALUE; no big-number library. Presets use small versions.

- One `fetch` wrapper: base path `/api/v1`, adds `Authorization`, parses RFC 9457 problem details into a typed `ApiError {status, code, title, detail, correlationId, errors[]}`.
- Every error shown to the user includes the `correlationId` (copy button) — ties the UI to the backend error framework and logs.
- No retries for non-idempotent POSTs (events, approve, reject, upload). GETs: TanStack default retry 1.
- Never `console.log` request or response bodies (they contain PII). Lint rule: `no-console` (allow `console.error` with codes only).
- Types in `api/types.ts` mirror `docs/API.md`. If the real API differs from the doc, fix the doc or report — don't silently adapt.

## 8. Demo-profile detection

`GET /api/v1/dev/faults` as OPERATOR: 200 → demo profile active (show Demo tools); 404/403 → hide. Do not add a new endpoint for this. Public account help is independent of this authenticated probe. It lists only the usernames already documented in README and explains private credential access. Passwords remain private in every build. The earlier `VITE_DEMO_HINTS` flag is retained for build compatibility but no longer controls access help.

## 9. Backend integration (the only server changes allowed)

1. **Serving:** the Vite build output is copied into the Spring Boot jar as static resources (`classpath:/static`), served at `/`. Same origin → no CORS configuration.
2. **Docker:** add a first stage `node:<lts>-alpine` that runs `npm ci && npm run build` in `frontend/`; the Maven stage copies `frontend/dist` into `src/main/resources/static/` before `package`. `./mvnw verify` without Node still works (backend tests don't need the UI). Provide `scripts/build-frontend.sh` for running the jar locally with the UI.
3. **Security:** `permitAll` for `GET /`, `/index.html`, `/assets/**`, `/favicon.svg` only. Everything under `/api/**` unchanged. Update `SecuredEndpointsTest`/guard tests only by allow-listing these static paths.
4. **Headers** (Spring Security): `Content-Security-Policy: default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self'; font-src 'self'; frame-ancestors 'none'`, `Referrer-Policy: no-referrer`, `X-Content-Type-Options: nosniff`. Cache: `index.html` no-cache; hashed `/assets/**` immutable 1 year.
5. **Caddy (AWS):** remove the existing root-path service-identification response so / reaches Spring; keep the actuator block. This exception is explicitly approved and requires no infrastructure changes.
6. Unknown non-API paths keep returning the normal 404 problem detail (hash routing means the browser never requests `/runs`).

## 10. Testing

- Unit (Vitest): OutcomeBadge maps every outcome to label/colour/icon; BrakePanel renders the server message and hides actions for non-operators; PlanBar sums correctly; api client parses problem details and surfaces correlationId; RequireRole gating; transport form sends `null` on Clear and nothing on no-op.
- E2E (Playwright, against `docker compose up`, `npm run e2e`): (1) HR runs the *Out-of-order* preset → third row shows STALE; (2) operator generates a 120k truncated export and uploads it (seed first if needed) → brake panel shows "missing" and Reject works; (3) employee login sees only "My profile". Screenshots of each step saved to `frontend/tests/e2e/screenshots/` (git-ignored) for the README/deck.
- Accessibility: `@axe-core/playwright` scan on every screen in light and dark themes; zero serious/critical violations.
- Lint + typecheck in CI: `npm ci && npm run lint && npm run lint:design && npm run typecheck && npm test` as a separate CI job (Node LTS). E2E stays local/manual.

## 11. Acceptance criteria

- `docker compose up --build` serves the console at `http://localhost:8080/` with no extra command.
- All five screens work for the right roles; hidden for others; the server still returns 403/404 if called directly.
- The brief's two examples are demonstrable in the UI in under 30 seconds each (out-of-order preset; truncated file → brake panel → reject).
- `npm run lint:design` passes; the design checkpoint (§12) was approved before pages 5.1–5.5 were built.
- No emoji characters in `frontend/` (`grep -P "[\x{1F300}-\x{1FAFF}\x{2600}-\x{27BF}]" -r frontend/src` returns nothing).
- No PII in browser console output during the e2e run (Playwright captures console; assert no canary values).
- axe: zero serious/critical violations on every screen; Lighthouse accessibility ≥ 95 on Runs and Employees (record in BUILD_LOG).
- Bundle: initial JS < 300 KB gzipped.
- Live URL serves the console over HTTPS after redeploy; `/#/` login works with the private demo password.

## 12. Design checkpoint (before building the screens)

1. Build `/#/styleguide` first (only when `VITE_STYLEGUIDE=true`, never in the AWS build): tokens, type scale, table with real-looking rows, outcome badges, owner tags, both route-line components, the brake panel, buttons, inputs, empty and error states, light and dark.
2. Build the **Run detail** screen against a real braked run (it carries the design).
3. Screenshot both at 1440×900 and 390×844, light and dark (Playwright), and self-review against this checklist; fix before sending:
   - Would this screen be mistaken for a generic SaaS admin template? If yes, what makes it so — remove it.
   - Is anything decorated that isn't a sequence or the brake? Remove it.
   - Any ALL-CAPS, eyebrow labels, arrows in buttons, cards, shadows outside overlays, raw palette colours? Remove them.
   - Remove one more accessory.
4. **Stop and send me the screenshots and a 5-line note on what you removed.** Continue with the other screens only after approval.
5. At the end of Phase 9, screenshot every screen the same way for the README and the deck.
