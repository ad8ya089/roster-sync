/** Render original Mermaid sources locally. See the dependency instructions beside this file. */
import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

const root = path.dirname(fileURLToPath(import.meta.url));
const tools = path.resolve(process.env.DIAGRAM_TOOLS ?? "target/diagram-tools");
const require = createRequire(path.join(tools, "package.json"));
const { chromium } = require("playwright");
const mermaid = path.join(tools, "node_modules/mermaid/dist/mermaid.min.js");
const names = process.argv.slice(2);
const files = names.length ? names.map((name) => name + ".mmd") : fs.readdirSync(root).filter((name) => name.endsWith(".mmd")).sort();
const browser = await chromium.launch();
try {
  const context = await browser.newContext({ deviceScaleFactor: 2, viewport: { width: 1200, height: 900 } });
  const page = await context.newPage();
  await page.setContent('<html><body style="margin:0;background:white"><main id="mount"></main></body></html>');
  await page.addScriptTag({ path: mermaid });
  const config = JSON.parse(fs.readFileSync(path.join(root, "mermaid-config.json"), "utf8"));
  await page.evaluate((config) => window.mermaid.initialize({ ...config, startOnLoad: false }), config);
  for (const file of files) {
    if (path.basename(file) !== file) throw new Error("Provide diagram stems only");
    const text = fs.readFileSync(path.join(root, file), "utf8");
    const svg = await page.evaluate(async ({ text, id }) => (await window.mermaid.render(id, text)).svg, { text, id: "roster-" + file.replace(/\W/g, "-") });
    await page.locator("#mount").evaluate((node, svg) => { node.innerHTML = svg; }, svg);
    // XML serialization closes HTML elements in foreignObject labels for standalone SVG viewers.
    const standalone = await page.locator("#mount svg").evaluate((node) => new XMLSerializer().serializeToString(node));
    fs.writeFileSync(path.join(root, file.replace(/\.mmd$/, ".svg")), standalone + "\n");
    await page.locator("#mount svg").evaluate((node) => {
      const box = node.viewBox.baseVal;
      node.style.maxWidth = "none";
      node.style.width = `${box.width}px`;
      node.style.height = `${box.height}px`;
    });
    await page.evaluate(() => document.fonts.ready);
    await page.locator("#mount svg").screenshot({ path: path.join(root, file.replace(/\.mmd$/, ".png")) });
    console.log("Rendered " + file);
  }
  await context.close();
} finally {
  await browser.close();
}
