import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const moduleDirectory = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const sourceDirectory = path.join(moduleDirectory, 'src');
const forbiddenImplementationTerms = [
  'hibernate',
  'org.eclipse.jgit',
  'sessionfactory',
  'entitymanager',
  'jdbc:',
  'git_packs',
];

async function sourceFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const nested = await Promise.all(
    entries.map(async (entry) => {
      const resolved = path.join(directory, entry.name);
      if (entry.isDirectory()) {
        return sourceFiles(resolved);
      }
      return /\.(?:ts|tsx|mjs|css)$/.test(entry.name) ? [resolved] : [];
    }),
  );
  return nested.flat();
}

for (const filename of await sourceFiles(sourceDirectory)) {
  const source = (await readFile(filename, 'utf8')).toLowerCase();
  for (const forbidden of forbiddenImplementationTerms) {
    assert.equal(
      source.includes(forbidden),
      false,
      `${path.relative(moduleDirectory, filename)} contains forbidden implementation term ${forbidden}`,
    );
  }
  assert.equal(
    source.includes('workflow-editor-spike'),
    false,
    `${path.relative(moduleDirectory, filename)} imports or references the historical spike`,
  );
}

const packageJson = JSON.parse(await readFile(path.join(moduleDirectory, 'package.json'), 'utf8'));
const productionDependencies = packageJson.dependencies ?? {};
assert.equal('yjs' in productionDependencies, false, 'canonical frontend must not require Yjs');
assert.equal('y-protocols' in productionDependencies, false, 'canonical frontend must not require Yjs awareness');

console.log(`Frontend architecture boundary verified across ${(await sourceFiles(sourceDirectory)).length} files.`);
