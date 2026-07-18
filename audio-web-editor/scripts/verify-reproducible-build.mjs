import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { readFile, readdir, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const moduleDirectory = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const executable = path.join(
  moduleDirectory,
  'node_modules',
  '.bin',
  process.platform === 'win32' ? 'vite.cmd' : 'vite',
);
const firstDirectory = path.join(moduleDirectory, 'target', 'reproducible-a');
const secondDirectory = path.join(moduleDirectory, 'target', 'reproducible-b');

async function files(directory, prefix = '') {
  const entries = await readdir(directory, { withFileTypes: true });
  const nested = await Promise.all(
    entries
      .sort((left, right) => left.name.localeCompare(right.name))
      .map(async (entry) => {
        const relative = path.posix.join(prefix, entry.name);
        const absolute = path.join(directory, entry.name);
        return entry.isDirectory() ? files(absolute, relative) : [{ relative, absolute }];
      }),
  );
  return nested.flat();
}

async function digest(directory) {
  const hash = createHash('sha256');
  const entries = await files(directory);
  for (const entry of entries) {
    hash.update(entry.relative);
    hash.update('\0');
    hash.update(await readFile(entry.absolute));
    hash.update('\0');
  }
  return { digest: hash.digest('hex'), names: entries.map((entry) => entry.relative) };
}

function build(outputDirectory) {
  execFileSync(executable, ['build', '--outDir', outputDirectory, '--emptyOutDir'], {
    cwd: moduleDirectory,
    stdio: 'inherit',
  });
}

await rm(firstDirectory, { recursive: true, force: true });
await rm(secondDirectory, { recursive: true, force: true });
build(firstDirectory);
build(secondDirectory);

const first = await digest(firstDirectory);
const second = await digest(secondDirectory);
assert.deepEqual(first.names, second.names, 'clean builds produced different file sets');
assert.equal(first.digest, second.digest, 'clean builds produced different content');

const cacheSafeAssets = first.names.filter((name) => name.startsWith('assets/'));
assert.ok(cacheSafeAssets.length > 0, 'production build contains no cache-safe assets');
for (const asset of cacheSafeAssets) {
  assert.match(asset, /-[A-Za-z0-9_-]{6,}\.[^.]+$/, `${asset} has no content hash`);
}

await rm(firstDirectory, { recursive: true, force: true });
await rm(secondDirectory, { recursive: true, force: true });
console.log(`Reproducible frontend build verified: ${first.digest}`);
