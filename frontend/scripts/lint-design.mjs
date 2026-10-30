import { readdir, readFile } from "node:fs/promises";
import path from "node:path";

const failures = [];
async function walk(directory) {
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const file = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      await walk(file);
      continue;
    }
    const source = await readFile(file, "utf8");
    const checks = [
      /bg-gradient|(?:from|via|to)-(?:[a-z]+-\d|\[)|backdrop-blur/,
      /rounded-(?:lg|xl|2xl|3xl)\b|\buppercase\b|tracking-wide(?:r|st)?\b/,
      /(?:bg|text|border|ring)-(?:slate|gray|zinc|neutral|stone|red|orange|amber|yellow|lime|green|emerald|teal|cyan|sky|blue|indigo|violet|purple|fuchsia|pink|rose)-\d/,
      /\bInter\b|[\u{1F300}-\u{1FAFF}\u2600-\u27BF]/u,
      /\u2192|\u00B7/,
    ];
    if (!file.replaceAll("\\", "/").includes("components/overlay/"))
      checks.push(/shadow-/);
    if (!file.endsWith("Loading.tsx"))
      checks.push(/animate-(?:bounce|ping|pulse|spin)/);
    for (const check of checks)
      if (check.test(source))
        failures.push(`${file}: prohibited design pattern ${check.source}`);
  }
}
await walk("src");
if (failures.length) {
  process.stderr.write(failures.join("\n") + "\n");
  process.exitCode = 1;
} else
  process.stdout.write(
    "Design lint passed: tokens, type, restrained surfaces, no emoji.\n",
  );
