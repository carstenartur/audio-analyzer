import assert from 'node:assert/strict';
import test from 'node:test';

import { layoutNodePositions, operationId } from '../src/workflowProjection.mjs';

test('layout is deterministic and keyed by server node identity', () => {
  const nodes = [{ id: 'input' }, { id: 'gain' }, { id: 'output' }];

  assert.deepEqual(layoutNodePositions(nodes), {
    input: { x: 80, y: 180 },
    gain: { x: 310, y: 270 },
    output: { x: 540, y: 180 },
  });
  assert.deepEqual(layoutNodePositions(nodes), layoutNodePositions(nodes));
});

test('operation identifiers carry kind, time and collision suffix', () => {
  assert.equal(operationId('connect', 1234, 'fixed-id'), 'op.connect.1234.fixed-id');
  assert.throws(() => operationId('', 1234, 'fixed-id'), /required/);
  assert.throws(() => operationId('connect', 1234, ''), /required/);
});
