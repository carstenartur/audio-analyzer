import assert from 'node:assert/strict';
import test from 'node:test';

import {
  historyControlPolicy,
  historyModeExplanation,
  historyShortcutAction,
} from '../src/historyControlsState.mjs';

function action(status = 'AVAILABLE') {
  return {
    operation: { operationId: 'operation-1' },
    status,
    available: status === 'AVAILABLE',
  };
}

function capabilities(overrides = {}) {
  return {
    mode: 'SHARED_SESSION_PERSONAL_UNDO',
    personalUndoPermitted: true,
    personalUndo: action(),
    redo: action(),
    sharedUndoPermitted: false,
    ...overrides,
  };
}

test('personal controls use only server-reported capabilities and live transport', () => {
  const ready = historyControlPolicy({
    capabilities: capabilities(),
    connectionState: 'live',
    historyStatus: 'ready',
  });
  const reconnecting = historyControlPolicy({
    capabilities: capabilities(),
    connectionState: 'reconnecting',
    historyStatus: 'ready',
  });

  assert.equal(ready.personalUndoVisible, true);
  assert.equal(ready.personalUndoEnabled, true);
  assert.equal(ready.redoEnabled, true);
  assert.equal(reconnecting.personalUndoEnabled, false);
  assert.match(reconnecting.personalUndoReason, /stream is live/);
});

test('blocked and legacy targets stay visible but cannot execute', () => {
  const blocked = historyControlPolicy({
    capabilities: capabilities({ personalUndo: action('BLOCKED') }),
    connectionState: 'live',
    historyStatus: 'ready',
  });
  const legacy = historyControlPolicy({
    capabilities: capabilities({ personalUndo: action('NOT_RECONSTRUCTIBLE') }),
    connectionState: 'live',
    historyStatus: 'ready',
  });

  assert.equal(blocked.personalUndoVisible, true);
  assert.equal(blocked.personalUndoEnabled, false);
  assert.match(blocked.personalUndoReason, /blocked/);
  assert.equal(legacy.personalUndoEnabled, false);
  assert.match(legacy.personalUndoReason, /predates reconstructible/);
});

test('shared mode hides personal controls and exposes target selection', () => {
  const policy = historyControlPolicy({
    capabilities: capabilities({
      mode: 'SHARED_SESSION_SHARED_UNDO',
      personalUndoPermitted: false,
      personalUndo: null,
      redo: null,
      sharedUndoPermitted: true,
    }),
    connectionState: 'live',
    historyStatus: 'ready',
  });

  assert.equal(policy.personalUndoVisible, false);
  assert.equal(policy.redoVisible, false);
  assert.equal(policy.sharedUndoVisible, true);
  assert.equal(policy.sharedUndoEnabled, true);
});

test('uncertain commands disable new actions until exact retry is resolved', () => {
  const policy = historyControlPolicy({
    capabilities: capabilities(),
    connectionState: 'live',
    historyStatus: 'uncertain',
  });

  assert.equal(policy.personalUndoEnabled, false);
  assert.equal(policy.redoEnabled, false);
});

test('keyboard shortcuts cannot bypass shared target selection or confirmation', () => {
  assert.equal(
    historyShortcutAction({
      mode: 'SHARED_SESSION_SHARED_UNDO',
      shiftKey: false,
      personalUndoEnabled: false,
      redoEnabled: false,
      sharedTargetSelected: false,
    }),
    'select-shared-target',
  );
  assert.equal(
    historyShortcutAction({
      mode: 'SHARED_SESSION_SHARED_UNDO',
      shiftKey: false,
      personalUndoEnabled: false,
      redoEnabled: false,
      sharedTargetSelected: true,
    }),
    'shared-preview',
  );
  assert.equal(
    historyShortcutAction({
      mode: 'SHARED_SESSION_PERSONAL_UNDO',
      shiftKey: false,
      personalUndoEnabled: true,
      redoEnabled: false,
      sharedTargetSelected: false,
    }),
    'personal-undo',
  );
  assert.equal(
    historyShortcutAction({
      mode: 'PRIVATE_WORKSPACE',
      shiftKey: true,
      personalUndoEnabled: true,
      redoEnabled: true,
      sharedTargetSelected: false,
    }),
    'redo',
  );
});

test('all immutable collaboration modes have explicit explanations', () => {
  assert.match(historyModeExplanation('PRIVATE_WORKSPACE'), /private/);
  assert.match(historyModeExplanation('SHARED_SESSION_PERSONAL_UNDO'), /your own/);
  assert.match(historyModeExplanation('SHARED_SESSION_SHARED_UNDO'), /explicit selection/);
});
