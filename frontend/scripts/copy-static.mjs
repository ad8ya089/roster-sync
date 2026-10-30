import { cp, mkdir, realpath, rm } from 'node:fs/promises';
import path from 'node:path';

const root = await realpath(path.resolve(import.meta.dirname, '../..'));
const target = path.join(root, 'src/main/resources/static');
// This directory is exclusively generated and ignored; never follow a redirected target.
await mkdir(target, { recursive: true });
if (await realpath(target) !== target) throw new Error('Unexpected static resource target');
await rm(target, { recursive: true });
await cp(path.join(root, 'frontend/dist'), target, { recursive: true });
