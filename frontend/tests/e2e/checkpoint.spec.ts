import { test, expect } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";

const sizes = [
  { label: "desktop", width: 1440, height: 900 },
  { label: "mobile", width: 390, height: 844 },
];
for (const theme of ["light", "dark"] as const)
  for (const size of sizes) {
    test(`checkpoint ${theme} ${size.label}`, async ({ page }) => {
      await page.setViewportSize({ width: size.width, height: size.height });
      await page.emulateMedia({ colorScheme: theme, reducedMotion: "reduce" });
      const failures: string[] = [];
      const consoleMessages: string[] = [];
      page.on("pageerror", () => failures.push("PAGE_ERROR"));
      page.on("console", (message) => consoleMessages.push(message.text()));
      await page.goto("/#/login");
      await expect(
        page.getByRole("heading", { name: "Sign in to operations" }),
      ).toBeVisible();
      const loginAudit = await new AxeBuilder({ page }).analyze();
      const loginIssues = loginAudit.violations
        .filter((violation) =>
          ["serious", "critical"].includes(violation.impact ?? ""),
        )
        .map((violation) => violation.id);
      expect(loginIssues).toEqual([]);
      mkdirSync("tests/e2e/screenshots", { recursive: true });
      await page.screenshot({
        path: `tests/e2e/screenshots/login-${theme}-${size.label}.png`,
        animations: "disabled",
      });
      await page.getByLabel("Username", { exact: true }).fill("acme.operator");
      await page
        .getByLabel("Password", { exact: true })
        .fill(process.env.DEMO_PASSWORD!);
      await page.getByRole("button", { name: "Sign in", exact: true }).click();
      await expect(page.getByRole("button", { name: "Log out" })).toBeVisible();
      const { runId } = JSON.parse(readFileSync(".checkpoint.json", "utf8"));
      mkdirSync("tests/e2e/screenshots", { recursive: true });
      const audits = [
        { screen: "login", theme, viewport: size.label, serious: loginIssues },
      ];
      for (const screen of ["styleguide", "run-detail"]) {
        await page.goto(
          screen === "styleguide" ? "/#/styleguide" : `/#/runs/${runId}`,
        );
        await expect(
          page.getByRole("heading", {
            name:
              screen === "styleguide"
                ? "A dispatch board for roster decisions"
                : "Review nightly export",
          }),
        ).toBeVisible();
        await page.evaluate(() => document.fonts.ready);
        if (screen === "run-detail") {
          await expect(
            page.getByText("Waiting for approval", { exact: true }).first(),
          ).toBeVisible();
          await expect(page.locator(".brake-message")).toContainText("missing");
          await expect(
            page.getByRole("button", { name: "Reject run", exact: true }),
          ).toBeVisible();
          if (size.label === "mobile") {
            const reject = await page
              .getByRole("button", { name: "Reject run", exact: true })
              .boundingBox();
            expect(
              reject !== null && reject.y + reject.height <= size.height,
            ).toBe(true);
          }
        }
        expect(
          await page.evaluate(
            () => document.documentElement.scrollWidth <= innerWidth,
          ),
        ).toBe(true);
        await page.screenshot({
          path: `tests/e2e/screenshots/${screen}-${theme}-${size.label}.png`,
          animations: "disabled",
        });
        const result = await new AxeBuilder({ page }).analyze();
        const serious = result.violations
          .filter((violation) =>
            ["serious", "critical"].includes(violation.impact ?? ""),
          )
          .map((violation) => violation.id);
        audits.push({ screen, theme, viewport: size.label, serious });
        expect(serious).toEqual([]);
      }
      await page
        .getByRole("button", { name: "Reject run", exact: true })
        .click();
      await expect(page.getByRole("dialog")).toBeVisible();
      await expect(
        page.getByRole("button", { name: "Reject run", exact: true }).last(),
      ).toBeDisabled();
      await page.getByLabel("Decision note", { exact: true }).focus();
      await page.keyboard.press("Tab");
      await expect(
        page.getByRole("button", { name: "Cancel", exact: true }),
      ).toBeFocused();
      await page.keyboard.press("Escape");
      await expect(page.getByRole("dialog")).not.toBeVisible();
      const leaked = consoleMessages.some(
        (message) =>
          /canary\.person|Canary Lane|test-demo-password|fixture@example/.test(
            message,
          ) || message.includes(process.env.DEMO_PASSWORD!),
      );
      expect(leaked).toBe(false);
      expect(failures).toEqual([]);
      const storage = await page.evaluate(() => ({
        local: localStorage.length,
        session: sessionStorage.length,
      }));
      expect(storage).toEqual({ local: 0, session: 0 });
      const version = await page.evaluate(
        () =>
          JSON.parse(
            '{"version":9223372036854775807}',
            (key: string, value: unknown, context?: { source?: string }) =>
              key === "version" ? context?.source : value,
          ).version,
      );
      expect(version).toBe("9223372036854775807");
      writeFileSync(
        `test-results/axe-${theme}-${size.label}.json`,
        JSON.stringify(audits, null, 2),
      );
    });
  }

test("transport admin review is read-only and reload clears the session", async ({
  page,
}) => {
  await page.goto("/#/login");
  await page.getByLabel("Username", { exact: true }).fill("acme.admin");
  await page
    .getByLabel("Password", { exact: true })
    .fill(process.env.DEMO_PASSWORD!);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page.getByRole("button", { name: "Log out" })).toBeVisible();
  const { runId } = JSON.parse(readFileSync(".checkpoint.json", "utf8"));
  await page.goto(`/#/runs/${runId}`);
  await expect(
    page.getByText("Waiting for an operator. No changes have been applied."),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Approve all changes", exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "Reject run", exact: true }),
  ).toHaveCount(0);
  await page.reload();
  await expect(
    page.getByRole("heading", { name: "Sign in to operations" }),
  ).toBeVisible();
});
