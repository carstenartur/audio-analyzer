import assert from 'node:assert/strict';
import test from 'node:test';

import {
  createHistoryCommandEnvelope,
  emptyHistoryState,
  historyCommandRequest,
  historyRecoveryForProblemCode,
  reduceHistoryState,
} from '../src/historyState.mjs';

const actor = { actorId: 'actor-a', userId: 'user-a', displayName: 'Alice' };
const operation = {
  operationId: 'operation-1',
  revision: 1,
};
const capabilities = {
  mode: 'PRIVATE_WORKSPACE',
  revision: 1,
  personalUndoPermitted: true,
  personalUndo: null,
  redo: null,
  sharedUndoPermitted: false,
};
const page = {
  operations: [operation],
  nextBeforeRevision: null,
  currentRevision: 1,
};

test('undo and redo envelopes map to the exact server command contract', () => {
  const undo = createHistoryCommandEnvelope({
    kind: 'UNDO',
    commandId: 'command-undo',
    expectedRevision: 3,
    targetOperationId: 'operation-forward',
    previewId: 'preview-1',
  });
  const redo = createHistoryCommandEnvelope({
    kind: 'REDO',
    commandId: 'command-redo',
    expectedRevision: 4,
    targetOperationId: 'operation-undo',
  });

  assert.deepEqual(historyCommandRequest(undo, actor), {
    endpoint: 'undo',
    body: {
      commandId: 'command-undo',
      actor,
      expectedRevision: 3,
      targetOperationId: 'operation-forward',
      previewId: 'preview-1',
    },
  });
  assert.deepEqual(historyCommandRequest(redo, actor), {
    endpoint: 'redo',
    body: {
      commandId: 'command-redo',
      actor,
      expectedRevision: 4,
      targetUndoOperationId: 'operation-undo',
    },
  });
});

test('ambiguous transport failure preserves the exact pending command for retry', () => {
  const command = createHistoryCommandEnvelope({
    kind: 'UNDO',
    commandId: 'command-stable',
    expectedRevision: 2,
    targetOperationId: 'operation-2',
  });
  const pending = reduceHistoryState(emptyHistoryState(), { type: 'COMMAND_STARTED', command });
  const uncertain = reduceHistoryState(pending, {
    type: 'COMMAND_UNCERTAIN',
    problem: { detail: 'Connection closed before response' },
  });

  assert.equal(uncertain.status, 'uncertain');
  assert.strictEqual(uncertain.pendingCommand, command);
  assert.equal(uncertain.pendingCommand.commandId, 'command-stable');
});

test('definitive rejection clears pending identity while acceptance requires reload', () => {
  const command = createHistoryCommandEnvelope({
    kind: 'REDO',
    commandId: 'command-redo',
    expectedRevision: 2,
    targetOperationId: 'operation-undo',
  });
  const pending = reduceHistoryState(emptyHistoryState(), { type: 'COMMAND_STARTED', command });
  const rejected = reduceHistoryState(pending, {
    type: 'COMMAND_REJECTED',
    problem: { code: 'REDO_TARGET_INVALID' },
  });
  const accepted = reduceHistoryState(pending, { type: 'COMMAND_ACCEPTED' });

  assert.equal(rejected.status, 'error');
  assert.equal(rejected.pendingCommand, null);
  assert.equal(accepted.status, 'loading');
  assert.equal(accepted.pendingCommand, null);
});

test('history pages replace then append without duplicates and remain newest-first', () => {
  const loaded = reduceHistoryState(emptyHistoryState(), {
    type: 'LOADED',
    capabilities,
    page: {
      operations: [
        { operationId: 'operation-3', revision: 3 },
        { operationId: 'operation-2', revision: 2 },
      ],
      nextBeforeRevision: 2,
    },
  });
  const appended = reduceHistoryState(loaded, {
    type: 'PAGE_APPENDED',
    page: {
      operations: [
        { operationId: 'operation-2', revision: 2 },
        { operationId: 'operation-1', revision: 1 },
      ],
      nextBeforeRevision: null,
    },
  });

  assert.deepEqual(
    appended.operations.map((entry) => entry.operationId),
    ['operation-3', 'operation-2', 'operation-1'],
  );
});

test('loaded capabilities and history replace stale browser state', () => {
  const stale = {
    ...emptyHistoryState(),
    operations: [{ operationId: 'stale', revision: 99 }],
    pendingCommand: { commandId: 'old' },
  };
  const loaded = reduceHistoryState(stale, { type: 'LOADED', capabilities, page });

  assert.deepEqual(loaded.operations, [operation]);
  assert.strictEqual(loaded.capabilities, capabilities);
  assert.equal(loaded.status, 'ready');
});

test('problem codes choose reconciliation reload reset or final rejection', () => {
  assert.equal(historyRecoveryForProblemCode('WORKFLOW_SESSION_REVISION_CONFLICT'), 'reconcile');
  assert.equal(historyRecoveryForProblemCode('UNDO_PREVIEW_STALE'), 'reconcile');
  assert.equal(historyRecoveryForProblemCode('UNDO_CONFLICT'), 'reload');
  assert.equal(historyRecoveryForProblemCode('OPERATION_NOT_UNDOABLE'), 'reload');
  assert.equal(historyRecoveryForProblemCode('SESSION_NOT_FOUND'), 'reset');
  assert.equal(historyRecoveryForProblemCode('DUPLICATE_OPERATION_ID'), 'reject');
});

test('invalid command envelopes fail before any request is sent', () => {
  assert.throws(() =>
    createHistoryCommandEnvelope({
      kind: 'DELETE',
      commandId: 'command',
      expectedRevision: 1,
      targetOperationId: 'operation',
    }),
  );
  assert.throws(() =>
    createHistoryCommandEnvelope({
      kind: 'REDO',
      commandId: 'command',
      expectedRevision: 1,
      targetOperationId: 'operation',
      previewId: 'not-valid-for-redo',
    }),
  );
});
