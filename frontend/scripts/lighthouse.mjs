// Run from frontend: npm exec --yes --package=lighthouse@13.6.0 -- node scripts/lighthouse.mjs
// Snapshot mode preserves the deliberately memory-only authenticated session.
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { createRequire } from "node:module";
import { chromium } from "@playwright/test";

if (existsSync("../.env")) process.loadEnvFile("../.env");
const base = process.env.BASE_URL ?? "http://127.0.0.1:8080";
if (!["localhost", "127.0.0.1"].includes(new URL(base).hostname))
  throw new Error("Accessibility fixture requires the local app");
let browser;
try {
  const bin = process.env.PATH.split(path.delimiter).find((dir) =>
    existsSync(path.join(dir, "..", "lighthouse", "package.json")),
  );
  if (!bin) throw new Error("Run with the pinned npm exec command");
  const lighthouseRoot = path.resolve(bin, "..", "lighthouse");
  const require = createRequire(path.join(lighthouseRoot, "package.json"));
  const { default: puppeteer } = await import(
    pathToFileURL(require.resolve("puppeteer-core"))
  );
  const { startFlow, desktopConfig } = await import(
    pathToFileURL(path.join(lighthouseRoot, "core/index.js"))
  );
  browser = await puppeteer.launch({
    executablePath: chromium.executablePath(),
    headless: true,
    args: ["--no-sandbox"],
  });
  const page = await browser.newPage();
  await page.goto(`${base}/#/login`);
  await page.type("#username", "acme.admin");
  await page.type("#password", process.env.DEMO_PASSWORD);
  await page.click('button[type="submit"], .login-form button.button-primary');
  await page.waitForFunction(
    () => document.querySelector("h1")?.textContent === "Employees",
  );
  const flow = await startFlow(page, {
    config: {
      ...desktopConfig,
      settings: {
        ...desktopConfig.settings,
        onlyCategories: ["accessibility"],
        disableStorageReset: true,
      },
    },
  });
  for (const theme of ["light", "dark"]) {
    await page.emulateMediaFeatures([
      { name: "prefers-color-scheme", value: theme },
    ]);
    for (const [route, title] of [
      ["runs", "Reconciliation runs"],
      ["employees", "Employees"],
    ]) {
      await page.goto(`${base}/#/${route}`);
      await page.waitForFunction(
        (title) => document.querySelector("h1")?.textContent === title,
        {},
        title,
      );
      await page.waitForSelector("tbody tr a");
      await page.evaluate(() => document.fonts.ready);
      process.stdout.write(`Auditing ${title} (${theme})\n`);
      await flow.snapshot({ name: `${title} (${theme})` });
      if ((await page.$eval("h1", (element) => element.textContent)) !== title)
        throw new Error("Authenticated screen changed during the audit");
    }
  }
  const result = await flow.createFlowResult();
  mkdirSync("../target", { recursive: true });
  writeFileSync(
    "../target/phase9-lighthouse.json",
    JSON.stringify(result, null, 2),
  );
  for (const step of result.steps) {
    const score = Math.round(step.lhr.categories.accessibility.score * 100);
    process.stdout.write(`${step.name}: accessibility ${score}\n`);
    if (score < 95) process.exitCode = 1;
  }
} catch {
  process.stderr.write(
    "Accessibility audit unavailable; check the local app, pinned tool and private credentials.\n",
  );
  process.exitCode = 1;
} finally {
  await browser?.close();
}
