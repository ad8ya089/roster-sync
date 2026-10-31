import { test, expect } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";

test("account help explains access and prefills a role without authenticating", async ({
  page,
}) => {
  const logins: string[] = [];
  page.on("request", (request) => {
    if (request.url().endsWith("/auth/token")) logins.push(request.method());
  });
  await page.goto("/#/login");
  await page.getByRole("link", { name: "Need an account?" }).click();
  await expect(
    page.getByRole("heading", { name: "Get access to the demo" }),
  ).toBeVisible();
  await expect(page.getByText(/no public account registration/)).toBeVisible();
  await page
    .getByRole("link", { name: "Use this username for Transport admin" })
    .click();
  await expect(page.getByLabel("Username", { exact: true })).toHaveValue(
    "acme.admin",
  );
  await expect(page.getByLabel("Password", { exact: true })).toHaveValue("");
  expect(logins).toEqual([]);
});

test("access screens reflow and failed sign-in keeps the action visible", async ({
  page,
}) => {
  test.setTimeout(120_000);
  // Exercise layout/error presentation without sending an actual email or password.
  await page.route("**/api/v1/auth/token", (route) =>
    route.fulfill({
      status: 401,
      contentType: "application/json",
      body: JSON.stringify({
        code: "UNAUTHENTICATED",
        title: "Authentication required",
        detail: "Authentication required",
        correlationId: "test-reference",
      }),
    }),
  );
  for (const colorScheme of ["light", "dark"] as const) {
    await page.emulateMedia({ colorScheme });
    for (const viewport of [
      { width: 1920, height: 1080 },
      { width: 1366, height: 768 },
      { width: 1280, height: 600 },
      { width: 390, height: 844 },
      { width: 320, height: 568 },
      { width: 640, height: 360 },
    ]) {
      await page.setViewportSize(viewport);
      await page.goto("/#/login");
      await page
        .getByLabel("Username", { exact: true })
        .fill("unassigned-account");
      await page
        .getByLabel("Password", { exact: true })
        .fill("invalid-test-password");
      await page.getByRole("button", { name: "Sign in", exact: true }).click();
      await expect(page.getByRole("alert")).toContainText(
        "Username or password not recognised",
      );
      await expect(page.getByLabel("Password", { exact: true })).toHaveValue(
        "",
      );
      await expect(page.getByText("test-reference")).not.toBeVisible();
      const dimensions = await page.evaluate(() => ({
        content: document.documentElement.scrollWidth,
        viewport: document.documentElement.clientWidth,
      }));
      expect(dimensions.content).toBeLessThanOrEqual(dimensions.viewport);
      if (viewport.width >= 1280) {
        const button = await page
          .getByRole("button", { name: "Sign in", exact: true })
          .boundingBox();
        expect(button!.y + button!.height).toBeLessThan(viewport.height);
      }
      const audit = await new AxeBuilder({ page }).analyze();
      expect(
        audit.violations.filter((v) =>
          ["serious", "critical"].includes(v.impact ?? ""),
        ),
      ).toEqual([]);
      await page.getByRole("link", { name: "Need an account?" }).click();
      expect(
        await page.evaluate(
          () =>
            document.documentElement.scrollWidth <=
            document.documentElement.clientWidth,
        ),
      ).toBe(true);
    }
  }
});
