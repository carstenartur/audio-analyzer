import { createHash } from 'node:crypto';
import { readdir, readFile, rm } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import path from 'node:path';

const roots = ['target/reproducible-a', 'target/reproducible-b'];

function build(outDir) {
  const result = spawnSync(
    process.execPath,
    ['node_modules/vite/bin/vite.js', 'build', '--outDir', outDir, '--emptyOutDir'],
    { stdio: 'inherit', env: { ...process.env, SOURCE_DATE_EPOCH: '1784376000' } },
  );
  if (result.status !== 0) {
    throw new Error(`Vite reproducibility build failed for ${outDir}`);
  }
}

async function files(root, directory = root) {
  const entries = await readdir(directory, { withFileTypes: true });
  const result = [];
  for (const entry of entries.sort((left, right) => left.name.localeCompare(right.name))) {
    const absolute = path.join(directory, entry.name);
    if (entry.isDirectory()) result.push(...(await files(root, absolute)));
    else result.push(path.relative(root, absolute));
  }
  return result;
}

async function fingerprint(root) {
  const hash = createHash('sha256');
  for (const file of await files(root)) {
    hash.update(file);
    hash.update('\0');
    hash.update(await readFile(path.join(root, file)));
    hash.update('\0');
  }
  return hash.digest('hex');
}

for (const root of roots) await rm(root, { recursive: true, force: true });
for (const root of roots) build(root);

const first = await fingerprint(roots[0]);
const second = await fingerprint(roots[1]);
if (first !== second) {
  throw new Error(`Frontend build is not reproducible: ${first} != ${second}`);
}
console.error(`Reproducible frontend build: ${first}`);
