import { test, expect } from "@playwright/test";
import type { Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
const consoleLines = new WeakMap<Page, string[]>();
test.beforeEach(async ({ page }) => {
  const lines: string[] = [];
  consoleLines.set(page, lines);
  page.on("console", (message) => lines.push(message.text()));
});
test.afterEach(async ({ page }) => {
  expect(
    (consoleLines.get(page) ?? []).some(
      (line) =>
        /Canary Lane|Canary support|canary\.person/.test(line) ||
        line.includes(process.env.DEMO_PASSWORD!),
    ),
  ).toBe(false);
});

async function login(page: Page, user: string) {
  await page.goto("/#/login");
  await page.getByLabel("Username", { exact: true }).fill(user);
  await page
    .getByLabel("Password", { exact: true })
    .fill(process.env.DEMO_PASSWORD!);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Log out", exact: true }),
  ).toBeVisible();
}
async function visit(page: Page, path: string, heading: string) {
  await page.goto(`/#${path}`);
  await expect(
    page.getByRole("heading", { name: heading, exact: true }),
  ).toBeVisible();
  await page.evaluate(() => document.fonts.ready);
  await expect(page.getByText(/^Loading /)).toHaveCount(0);
}
for (const theme of ["light", "dark"] as const)
  for (const viewport of [
    { name: "desktop", width: 1440, height: 900 },
    { name: "mobile", width: 390, height: 844 },
  ]) {
    test(`console gallery ${theme} ${viewport.name}`, async ({ page }) => {
      test.setTimeout(120_000);
      await page.setViewportSize(viewport);
      await page.emulateMedia({ colorScheme: theme, reducedMotion: "reduce" });
      const consoleOutput: string[] = [];
      const failures: string[] = [];
      page.on("console", (message) => consoleOutput.push(message.text()));
      page.on("pageerror", () => failures.push("PAGE_ERROR"));
      const audits: { screen: string; serious: string[] }[] = [];
      mkdirSync("tests/e2e/screenshots", { recursive: true });
      async function capture(screen: string) {
        await page.evaluate(() => document.fonts.ready);
        expect(
          await page.evaluate(
            () => document.documentElement.scrollWidth <= innerWidth,
          ),
        ).toBe(true);
        const result = await new AxeBuilder({ page }).analyze();
        const serious = result.violations
          .filter((v) => ["serious", "critical"].includes(v.impact ?? ""))
          .map((v) => v.id);
        audits.push({ screen, serious });
        expect(serious).toEqual([]);
        await page.screenshot({
          path: `tests/e2e/screenshots/${screen}-${theme}-${viewport.name}.png`,
          animations: "disabled",
        });
      }
      await login(page, "acme.operator");
      await visit(page, "/ops", "Operations");
      await expect(
        page.getByRole("heading", { name: "Needs attention", exact: true }),
      ).toBeVisible();
      await capture("ops");
      await visit(page, "/runs", "Reconciliation runs");
      await expect(page.getByRole("table")).toBeVisible();
      await capture("runs");
      await page
        .getByRole("button", { name: "Brake settings", exact: true })
        .click();
      await expect(
        page.getByLabel("Maximum missing ratio (0 to 1)"),
      ).toBeVisible();
      await capture("settings");
      await page.keyboard.press("Escape");
      await expect(page.getByRole("dialog")).toHaveCount(0);
      await visit(page, "/demo", "Demo tools");
      await expect(
        page.getByRole("button", { name: "Generate export", exact: true }),
      ).toBeEnabled();
      await capture("demo");
      await visit(page, "/employees", "Employees");
      await expect(
        page.getByRole("link", { name: "E1001", exact: true }),
      ).toBeVisible();
      await capture("employees");
      await page.getByRole("link", { name: "E1001", exact: true }).click();
      await expect(
        page.getByRole("heading", { name: "Employee detail", exact: true }),
      ).toBeVisible();
      await expect(
        page.getByText("HR updates never overwrite purple fields."),
      ).toBeVisible();
      await expect(
        page.getByRole("heading", { name: "Bookings", exact: true }),
      ).toHaveCount(0);
      await expect(
        page.getByRole("button", {
          name: "Save transport fields",
          exact: true,
        }),
      ).toHaveCount(0);
      await capture("employee-readonly");
      await page.keyboard.press("Escape");
      await page.getByRole("button", { name: "Log out", exact: true }).click();
      await login(page, "acme.admin");
      await visit(page, "/employees", "Employees");
      await page.getByRole("link", { name: "E1001", exact: true }).click();
      await expect(
        page.getByRole("button", {
          name: "Save transport fields",
          exact: true,
        }),
      ).toBeDisabled();
      await expect(
        page.getByRole("heading", { name: "Bookings", exact: true }),
      ).toBeVisible();
      await capture("employee-detail");
      await page.keyboard.press("Escape");
      await page.getByRole("button", { name: "Log out", exact: true }).click();
      await login(page, "acme.hr");
      await visit(page, "/events", "Event console");
      await page
        .getByRole("button", {
          name: "Out-of-order (brief example)",
          exact: true,
        })
        .click();
      await expect(
        page.getByRole("heading", { name: "Step 3", exact: true }),
      ).toBeVisible();
      await expect(
        page.getByRole("button", { name: "Send batch", exact: true }),
      ).toBeEnabled();
      await capture("events");
      await page.getByRole("button", { name: "Log out", exact: true }).click();
      await login(page, "E1001");
      await visit(page, "/me", "My profile");
      await expect(
        page.getByRole("heading", { name: "Bookings", exact: true }),
      ).toBeVisible();
      await capture("me");
      expect(failures).toEqual([]);
      expect(
        consoleOutput.some(
          (line) =>
            /Canary Lane|canary\.person|canary@example/.test(line) ||
            line.includes(process.env.DEMO_PASSWORD!),
        ),
      ).toBe(false);
      expect(
        await page.evaluate(() => [localStorage.length, sessionStorage.length]),
      ).toEqual([0, 0]);
      writeFileSync(
        `test-results/console-axe-${theme}-${viewport.name}.json`,
        JSON.stringify(audits, null, 2),
      );
    });
  }

test("all six event presets show server decisions without employee reads", async ({
  page,
}) => {
  const paths: string[] = [];
  page.on("request", (request) => {
    if (new URL(request.url()).pathname.startsWith("/api/"))
      paths.push(new URL(request.url()).pathname);
  });
  await login(page, "acme.hr");
  await expect(
    page.getByRole("heading", { name: "Event console", exact: true }),
  ).toBeVisible();
  const start = Date.now();
  await page
    .getByRole("button", { name: "Out-of-order (brief example)", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Step 3", exact: true }),
  ).toBeVisible();
  await expect(
    page.locator(".event-response").last().getByText("Stale", { exact: true }),
  ).toBeVisible();
  expect(Date.now() - start).toBeLessThan(30_000);
  await expect(
    page.getByRole("button", { name: "Send batch", exact: true }),
  ).toBeEnabled();
  await page
    .getByRole("button", { name: "Replay last batch", exact: true })
    .click();
  await expect(
    page.locator(".event-response").getByText("Duplicate", { exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "No-op higher version", exact: true })
    .click();
  await expect(
    page.locator(".event-response").getByText("No change", { exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Ownership violation", exact: true })
    .click();
  await expect(
    page.locator(".event-response").getByText("Applied", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText("pickupPoint: FIELD_NOT_OWNED_BY_SOURCE", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Deactivate", exact: true }).click();
  await expect(
    page.locator(".event-response").getByText("Applied", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Bad batch", exact: true }).click();
  await expect(
    page.locator(".event-response").getByText("Applied", { exact: true }),
  ).toHaveCount(3);
  await expect(
    page.locator(".event-response").getByText("Rejected", { exact: true }),
  ).toHaveCount(2);
  expect(
    paths.some(
      (path) =>
        path.startsWith("/api/v1/employees") ||
        path.startsWith("/api/v1/offices"),
    ),
  ).toBe(false);
});

test("employee sees only self and cannot open an admin screen", async ({
  page,
}) => {
  const paths: string[] = [];
  page.on("request", (request) => paths.push(new URL(request.url()).pathname));
  await login(page, "E1001");
  await expect(
    page.getByRole("heading", { name: "My profile", exact: true }),
  ).toBeVisible();
  const nav = page.getByRole("navigation", { name: "Main navigation" });
  await expect(
    nav.getByRole("link", { name: "My profile", exact: true }),
  ).toHaveCount(1);
  for (const name of [
    "Employees",
    "Runs",
    "Operations",
    "Event console",
    "Demo tools",
  ])
    await expect(nav.getByRole("link", { name, exact: true })).toHaveCount(0);
  await page.goto("/#/employees/E1002");
  await expect(
    page.getByRole("heading", { name: "Access restricted", exact: true }),
  ).toBeVisible();
  expect(paths.some((path) => path.includes("E1002"))).toBe(false);
});

test("transport edit sends clear only and no-op save stays disabled", async ({
  page,
}) => {
  await login(page, "acme.admin");
  await visit(page, "/employees/E1001", "Employees");
  await expect(
    page.getByRole("heading", { name: "Employee detail", exact: true }),
  ).toBeVisible();
  await page.getByLabel("Pickup point", { exact: true }).fill("Canary Lane");
  await page
    .getByLabel("Special assistance", { exact: true })
    .fill("Canary support");
  await page
    .getByRole("button", { name: "Save transport fields", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText(
    "Transport fields saved",
  );
  await expect(
    page.getByRole("button", { name: "Save transport fields", exact: true }),
  ).toBeDisabled();
  const body = page.waitForRequest((request) => request.method() === "PATCH");
  await page
    .getByRole("button", { name: "Clear pickup point", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Save transport fields", exact: true })
    .click();
  expect(JSON.parse((await body).postData()!)).toEqual({ pickupPoint: null });
  await expect(
    page.getByRole("button", { name: "Save transport fields", exact: true }),
  ).toBeDisabled();
  await expect(
    page.getByLabel("Special assistance", { exact: true }),
  ).toHaveValue("Canary support");
  await page
    .getByRole("button", { name: "Clear special assistance", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Save transport fields", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Save transport fields", exact: true }),
  ).toBeDisabled();
});

test("operator acknowledges alerts and revives a DEAD delivery", async ({
  page,
}) => {
  test.skip(
    !process.env.ROSTER_TEST_PROJECT,
    "Set the isolated Compose project for database fixtures",
  );
  await login(page, "acme.operator");
  await visit(page, "/ops", "Operations");
  const alert = page
    .locator(".attention-row")
    .filter({ hasText: "Checkpoint delivery fixture" });
  await alert.getByRole("button", { name: "Acknowledge", exact: true }).click();
  await expect(
    page.getByText("Alert acknowledged.", { exact: true }),
  ).toBeVisible();
  const row = page
    .locator(".attention-row")
    .filter({ hasText: "00000000-0000-4000-8000-000000000009" });
  await row
    .getByRole("button", { name: "Retry delivery", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Retry delivery", exact: true })
    .click();
  await expect(
    page.getByText("Delivery queued for retry.", { exact: true }),
  ).toBeVisible();
  await expect(row).toHaveCount(0);
});

test("settings persist for the next run and malformed JSON shows a correlation ID", async ({
  page,
}) => {
  const output: string[] = [];
  page.on("console", (message) => output.push(message.text()));
  await login(page, "acme.operator");
  await visit(page, "/runs", "Reconciliation runs");
  await page
    .getByRole("button", { name: "Brake settings", exact: true })
    .click();
  await page.getByLabel("Maximum missing ratio (0 to 1)").fill("0.3");
  await page
    .getByRole("button", { name: "Save settings", exact: true })
    .click();
  await expect(
    page.getByText("Settings saved.", { exact: true }),
  ).toBeVisible();
  await page.keyboard.press("Escape");
  await page.getByRole("button", { name: "Log out", exact: true }).click();
  await login(page, "acme.hr");
  await page
    .getByLabel("Event JSON", { exact: true })
    .fill('{"events":[canary.person@example.test');
  await page.getByRole("button", { name: "Send batch", exact: true }).click();
  await expect(page.locator(".error-notice")).toBeVisible();
  await expect(
    page.getByRole("button", { name: /Copy correlation ID/ }),
  ).toBeVisible();
  expect(await page.locator(".error-notice").innerText()).not.toContain(
    "canary.person",
  );
  expect(output.some((line) => line.includes("canary.person"))).toBe(false);
});

test("operator generates a truncated export, uploads and rejects it", async ({
  page,
}) => {
  test.setTimeout(180_000);
  await login(page, "acme.operator");
  const { runId } = JSON.parse(readFileSync(".checkpoint.json", "utf8"));
  await page.goto(`/#/runs/${runId}`);
  await page.getByRole("button", { name: "Reject run", exact: true }).click();
  await page
    .getByLabel("Decision note", { exact: true })
    .fill("Checkpoint reviewed; testing a fresh export.");
  const firstRejection = page.waitForResponse(
    (response) =>
      response.url().endsWith(`/reconciliation/runs/${runId}/reject`) &&
      response.request().method() === "POST",
  );
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Reject run", exact: true })
    .click();
  expect((await firstRejection).status()).toBe(200);
  await expect(page.getByText("Run rejected.", { exact: true })).toBeVisible();
  await visit(page, "/demo", "Demo tools");
  const start = Date.now();
  await page.getByLabel("Truncate at (optional)").fill("120000");
  const download = page.waitForEvent("download");
  await page
    .getByRole("button", { name: "Generate export", exact: true })
    .click();
  expect((await download).suggestedFilename()).toBe("roster-export.csv");
  await page
    .getByRole("button", { name: "Upload as reconciliation run", exact: true })
    .click();
  await expect(page.locator(".brake-message")).toContainText("missing", {
    timeout: 90_000,
  });
  const elapsed = Date.now() - start;
  writeFileSync(
    "test-results/truncated-ui-duration.json",
    JSON.stringify({ milliseconds: elapsed }),
  );
  // The acceptance budget covers the two-click workflow including the real local diff.
  expect(elapsed).toBeLessThan(30_000);
  await page.getByRole("button", { name: "Reject run", exact: true }).click();
  await page
    .getByLabel("Decision note", { exact: true })
    .fill("Broken export; HR will resend.");
  const secondRejection = page.waitForResponse(
    (response) =>
      response.url().endsWith("/reject") &&
      response.request().method() === "POST",
  );
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Reject run", exact: true })
    .click();
  expect((await secondRejection).status()).toBe(200);
  await expect(page.getByText("Run rejected.", { exact: true })).toBeVisible();
});
