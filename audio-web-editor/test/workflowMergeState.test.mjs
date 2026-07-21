import assert from 'node:assert/strict';
import test from 'node:test';

import {
  emptyMergeResolutions,
  mergeDecisionsComplete,
  mergeResolveRequest,
  updateMergeResolution,
} from '../src/workflowMergeState.mjs';

const preview = Object.freeze({
  targetBranch: 'main',
  remoteBranch: 'feature',
  baseCommitId: 'base',
  localCommitId: 'local',
  remoteCommitId: 'remote',
  validationViolations: [],
  conflicts: [
    {
      conflictId: 'conflict.label',
      allowedChoices: ['BASE', 'LOCAL', 'REMOTE', 'CUSTOM'],
    },
    {
      conflictId: 'conflict.node',
      allowedChoices: ['BASE', 'LOCAL', 'REMOTE', 'DELETE'],
    },
  ],
});

test('requires every conflict decision and a non-empty custom value', () => {
  let resolutions = emptyMergeResolutions(preview.conflicts);
  assert.equal(mergeDecisionsComplete(preview, resolutions), false);

  resolutions = updateMergeResolution(resolutions, 'conflict.label', 'CUSTOM');
  resolutions = updateMergeResolution(resolutions, 'conflict.node', 'DELETE');
  assert.equal(mergeDecisionsComplete(preview, resolutions), false);

  resolutions = updateMergeResolution(resolutions, 'conflict.label', 'CUSTOM', 'Merged label');
  assert.equal(mergeDecisionsComplete(preview, resolutions), true);
});

test('validator violations block commit even when all field conflicts are resolved', () => {
  const resolutions = {
    'conflict.label': { choice: 'LOCAL', customValue: '' },
    'conflict.node': { choice: 'DELETE', customValue: '' },
  };
  assert.equal(
    mergeDecisionsComplete({ ...preview, validationViolations: ['missing source port'] }, resolutions),
    false,
  );
});

test('builds an exact expected-head request in preview conflict order', () => {
  const resolutions = {
    'conflict.label': { choice: 'CUSTOM', customValue: 'Merged label' },
    'conflict.node': { choice: 'REMOTE', customValue: '' },
  };

  const request = mergeResolveRequest(preview, resolutions, {
    author: ' Merger ',
    message: ' Merge workflow ',
    timestamp: '2026-07-21T08:00:00Z',
  });

  assert.equal(request.expectedHeadCommitId, 'local');
  assert.equal(request.author, 'Merger');
  assert.deepEqual(request.resolutions, [
    {
      conflictId: 'conflict.label',
      choice: 'CUSTOM',
      customValue: 'Merged label',
    },
    {
      conflictId: 'conflict.node',
      choice: 'REMOTE',
      customValue: null,
    },
  ]);
});

test('rejects unknown conflict ids instead of creating local-only state', () => {
  const resolutions = emptyMergeResolutions(preview.conflicts);
  assert.throws(
    () => updateMergeResolution(resolutions, 'unknown', 'LOCAL'),
    /Unknown merge conflict/,
  );
});
