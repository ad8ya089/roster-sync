import { test, expect } from "@playwright/test";
import type { Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { mkdirSync, writeFileSync } from "node:fs";

async function login(page: Page, user: string) {
  await page.goto("/#/login");
  await expect(page.getByLabel("Password", { exact: true })).toBeVisible();
  await page.getByLabel("Username", { exact: true }).fill(user);
  try {
    await page
      .getByLabel("Password", { exact: true })
      .fill(process.env.DEMO_PASSWORD!);
  } catch {
    throw new Error("Private login form unavailable");
  }
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Log out", exact: true }),
  ).toBeVisible();
}
async function visit(page: Page, path: string, title: string) {
  await page.goto(`/#${path}`);
  await expect(
    page.getByRole("heading", { name: title, exact: true }),
  ).toBeVisible();
  await expect(page.getByText(/^Loading /)).toHaveCount(0);
  await expect(page.locator(".error-notice")).toHaveCount(0);
}
for (const theme of ["light", "dark"] as const)
  for (const size of [
    { name: "desktop", width: 1440, height: 900 },
    { name: "mobile", width: 390, height: 844 },
  ]) {
    test(`live screens ${theme} ${size.name}`, async ({ page }) => {
      await page.setViewportSize(size);
      await page.emulateMedia({ colorScheme: theme, reducedMotion: "reduce" });
      const output: string[] = [];
      const errors: string[] = [];
      page.on("console", (m) => output.push(m.text()));
      page.on("pageerror", () => errors.push("PAGE_ERROR"));
      const audits: { screen: string; issues: string[] }[] = [];
      async function audit(screen: string) {
        await expect(page.getByText(/^Loading /)).toHaveCount(0);
        await expect(page.locator(".error-notice")).toHaveCount(0);
        await page.evaluate(() => document.fonts.ready);
        expect(
          await page.evaluate(
            () => document.documentElement.scrollWidth <= innerWidth,
          ),
        ).toBe(true);
        const scan = await new AxeBuilder({ page }).analyze();
        const issues = scan.violations
          .filter((v) => ["serious", "critical"].includes(v.impact ?? ""))
          .map((v) => v.id);
        audits.push({ screen, issues });
        expect(issues).toEqual([]);
      }
      await login(page, "acme.operator");
      await visit(page, "/ops", "Operations");
      await audit("ops");
      await visit(page, "/runs", "Reconciliation runs");
      await audit("runs");
      await page
        .getByRole("button", { name: "Brake settings", exact: true })
        .click();
      await expect(
        page.getByLabel("Maximum missing ratio (0 to 1)"),
      ).toBeVisible();
      await audit("settings");
      await page.keyboard.press("Escape");
      await visit(page, "/demo", "Demo tools");
      await audit("demo");
      await visit(page, "/employees/E1001", "Employees");
      await expect(
        page.getByRole("heading", { name: "Employee detail", exact: true }),
      ).toBeVisible();
      await expect(
        page.getByRole("heading", { name: "Bookings", exact: true }),
      ).toHaveCount(0);
      await audit("operator-employee");
      await page.keyboard.press("Escape");
      await page.goto("/#/styleguide");
      await expect(
        page.getByRole("heading", { name: "Screen not found", exact: true }),
      ).toBeVisible();
      await page.getByRole("button", { name: "Log out", exact: true }).click();
      await login(page, "acme.admin");
      await visit(page, "/employees", "Employees");
      await audit("employees");
      await page.goto("/#/employees/E1001");
      await expect(
        page.getByRole("heading", { name: "Bookings", exact: true }),
      ).toBeVisible();
      await audit("admin-employee");
      await page.keyboard.press("Escape");
      await page.getByRole("button", { name: "Log out", exact: true }).click();
      await login(page, "acme.hr");
      await visit(page, "/events", "Event console");
      await audit("events");
      await page.getByRole("button", { name: "Log out", exact: true }).click();
      await login(page, "E1001");
      await visit(page, "/me", "My profile");
      await expect(
        page.getByRole("heading", { name: "Bookings", exact: true }),
      ).toBeVisible();
      await audit("me");
      await expect(
        page
          .getByRole("navigation", { name: "Main navigation" })
          .getByRole("link", { name: "Employees", exact: true }),
      ).toHaveCount(0);
      expect(
        output.some((line) => line.includes(process.env.DEMO_PASSWORD!)),
      ).toBe(false);
      expect(errors).toEqual([]);
      expect(
        await page.evaluate(() => [localStorage.length, sessionStorage.length]),
      ).toEqual([0, 0]);
      mkdirSync("test-results/live", { recursive: true });
      writeFileSync(
        `test-results/live/axe-${theme}-${size.name}.json`,
        JSON.stringify(audits, null, 2),
      );
    });
  }

test("live HR ordering, replay, no-change, ownership and deactivation", async ({
  page,
}) => {
  await login(page, "acme.hr");
  await visit(page, "/events", "Event console");
  await page
    .getByRole("button", { name: "Out-of-order (brief example)", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Step 3", exact: true }),
  ).toBeVisible();
  await expect(
    page.locator(".event-response").last().getByText("Stale", { exact: true }),
  ).toBeVisible();
  for (const [button, outcome] of [
    ["Replay last batch", "Duplicate"],
    ["No-op higher version", "No change"],
    ["Ownership violation", "Applied"],
    ["Deactivate", "Applied"],
  ]) {
    await expect(
      page.getByRole("button", { name: button, exact: true }),
    ).toBeEnabled();
    await page.getByRole("button", { name: button, exact: true }).click();
    await expect(
      page.locator(".event-response").getByText(outcome, { exact: true }),
    ).toBeVisible();
  }
  await page.getByRole("button", { name: "Bad batch", exact: true }).click();
  await expect(
    page.locator(".event-response").getByText("Applied", { exact: true }),
  ).toHaveCount(3);
  await expect(
    page.locator(".event-response").getByText("Rejected", { exact: true }),
  ).toHaveCount(2);
});

test("live small truncated export requires an operator decision", async ({
  page,
}) => {
  await login(page, "acme.operator");
  await visit(page, "/demo", "Demo tools");
  await page.getByLabel("Truncate at (optional)").fill("1");
  const download = page.waitForEvent("download");
  await page
    .getByRole("button", { name: "Generate export", exact: true })
    .click();
  await download;
  await page
    .getByRole("button", { name: "Upload as reconciliation run", exact: true })
    .click();
  await expect(page.locator(".brake-message")).toContainText("missing", {
    timeout: 60_000,
  });
  await expect(
    page.getByText("Waiting for approval", { exact: true }).first(),
  ).toBeVisible();
  await page.getByRole("button", { name: "Reject run", exact: true }).click();
  await page
    .getByLabel("Decision note", { exact: true })
    .fill(
      "Final assignment verification; intentionally truncated test export.",
    );
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Reject run", exact: true })
    .click();
  await expect(page.getByText("Run rejected.", { exact: true })).toBeVisible();
});
