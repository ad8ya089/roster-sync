import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { request } from "@playwright/test";
import type { FullConfig, APIResponse } from "@playwright/test";
import { execFileSync } from "node:child_process";

async function safe(response: Promise<APIResponse>) {
  try {
    return await response;
  } catch {
    throw new Error("Checkpoint request unavailable");
  }
}

export default async function prepare(config: FullConfig) {
  const baseURL = config.projects[0].use.baseURL!;
  if (!["localhost", "127.0.0.1"].includes(new URL(baseURL).hostname))
    throw new Error("Checkpoint fixtures require the local app");
  const password = process.env.DEMO_PASSWORD;
  if (!password) throw new Error("Set the local DEMO_PASSWORD");
  const api = await request.newContext({ baseURL, timeout: 300_000 });
  const readyDeadline = Date.now() + 120_000;
  while (Date.now() < readyDeadline) {
    try {
      if ((await api.get("/", { timeout: 5000 })).ok()) break;
    } catch {
      /* Wait for local startup without request diagnostics. */
    }
    await new Promise((resolve) => setTimeout(resolve, 2000));
  }
  const login = await safe(
    api.post("/api/v1/auth/token", {
      data: { username: "acme.operator", password },
    }),
  );
  if (!login.ok()) throw new Error("Operator fixture login failed");
  const token = (await login.json()).accessToken as string;
  const headers = { Authorization: `Bearer ${token}` };
  mkdirSync("test-results", { recursive: true });
  if (process.env.ROSTER_TEST_PROJECT) {
    const project = process.env.ROSTER_TEST_PROJECT;
    if (!/^roster-sync-(phase9|checkpoint|video)$/.test(project))
      throw new Error(
        "Ops fixture requires the isolated Phase 9 Compose project",
      );
    const eventId = "00000000-0000-4000-8000-000000000009";
    const sql = `INSERT INTO outbox_event(event_id,tenant_id,aggregate_type,aggregate_id,aggregate_version,event_type,payload,status,attempts,last_error)
      VALUES ('${eventId}','00000000-0000-0000-0000-000000000001','Employee','UI-DELIVERY-FIXTURE',1,'EmployeeUpdated','{"eventId":"${eventId}","eventType":"EmployeeUpdated","tenantId":"00000000-0000-0000-0000-000000000001","employeeId":"UI-DELIVERY-FIXTURE","version":1,"occurredAt":"2026-10-31T00:00:00Z","source":"API","changedFields":["status"],"values":{"status":"ACTIVE"}}','DEAD',6,'SimulatedCrashException')
      ON CONFLICT(event_id) DO UPDATE SET status='DEAD',attempts=6,published_at=NULL;
      INSERT INTO ops_alert(tenant_id,alert_type,reference_id,message) SELECT '00000000-0000-0000-0000-000000000001','OUTBOX_EVENT_DEAD','${eventId}','Checkpoint delivery fixture'
      WHERE NOT EXISTS (SELECT 1 FROM ops_alert WHERE reference_id='${eventId}' AND acknowledged_at IS NULL);`;
    try {
      execFileSync(
        "docker",
        [
          "compose",
          "-p",
          project,
          "exec",
          "-T",
          "db",
          "psql",
          "-U",
          process.env.POSTGRES_USER ?? "roster",
          "-d",
          process.env.POSTGRES_DB ?? "rostersync",
          "-v",
          "ON_ERROR_STOP=1",
          "-c",
          sql,
        ],
        { cwd: "..", stdio: "pipe" },
      );
    } catch {
      throw new Error("Isolated operations fixture could not be prepared");
    }
  }
  if (existsSync(".checkpoint.json")) {
    const previous = JSON.parse(readFileSync(".checkpoint.json", "utf8"));
    const response = await safe(
      api.get(`/api/v1/reconciliation/runs/${previous.runId}`, { headers }),
    );
    if (
      response.ok() &&
      (await response.json()).status === "AWAITING_APPROVAL"
    ) {
      await api.dispose();
      return;
    }
  }
  const seed = await safe(
    api.post("/api/v1/dev/seed-employees?count=200000", { headers }),
  );
  if (!seed.ok()) throw new Error("Checkpoint seed failed");
  const asOf = new Date().toISOString();
  const csv = await safe(
    api.get("/api/v1/dev/export.csv?truncateAt=120000", { headers }),
  );
  if (!csv.ok()) throw new Error("Checkpoint export failed");
  const upload = await safe(
    api.post("/api/v1/reconciliation/runs", {
      headers,
      multipart: {
        asOf,
        file: {
          name: "checkpoint.csv",
          mimeType: "text/csv",
          buffer: await csv.body(),
        },
      },
    }),
  );
  if (!upload.ok()) throw new Error("Checkpoint upload failed");
  const { runId } = await upload.json();
  const deadline = Date.now() + 180_000;
  while (Date.now() < deadline) {
    const response = await safe(
      api.get(`/api/v1/reconciliation/runs/${runId}`, { headers }),
    );
    const run = await response.json();
    if (run.status === "AWAITING_APPROVAL") {
      writeFileSync(".checkpoint.json", JSON.stringify({ runId }));
      await api.dispose();
      return;
    }
    if (["COMPLETED", "FAILED", "REJECTED"].includes(run.status))
      throw new Error(`Unexpected checkpoint status: ${run.status}`);
    await new Promise((resolve) => setTimeout(resolve, 2000));
  }
  throw new Error("Checkpoint comparison timed out");
}
