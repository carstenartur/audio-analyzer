import assert from 'node:assert/strict';
import test from 'node:test';

import {
  acceptCommandProjection,
  collaborationState,
  expirePresence,
  reconnectDelay,
  reduceSessionEvent,
} from '../src/collaborationState.mjs';

const actorAlice = { actorId: 'actor-alice', userId: 'user-alice', displayName: 'Alice' };
const actorBob = { actorId: 'actor-bob', userId: 'user-bob', displayName: 'Bob' };
const emptyProjection = { workflowId: 'workflow-1', workflowName: 'Workflow', nodes: [], edges: [] };
const oneNodeProjection = {
  ...emptyProjection,
  nodes: [{ id: 'node-1' }],
};

function initialState() {
  return collaborationState(
    { sequence: 4, revision: 2, participants: [actorAlice] },
    emptyProjection,
  );
}

function event(overrides = {}) {
  return {
    sessionId: 'session-1',
    sequence: 5,
    revision: 3,
    occurredAt: '2026-07-18T12:00:00Z',
    type: 'OPERATION_ACCEPTED',
    actor: actorAlice,
    projection: oneNodeProjection,
    attributes: {},
    ...overrides,
  };
}

test('accepted semantic events replace only the canonical server projection', () => {
  const result = reduceSessionEvent(initialState(), event());

  assert.equal(result.kind, 'applied');
  assert.equal(result.state.sequence, 5);
  assert.equal(result.state.revision, 3);
  assert.deepEqual(result.state.projection, oneNodeProjection);
});

test('duplicate delivery is idempotent', () => {
  const state = initialState();
  const result = reduceSessionEvent(state, event({ sequence: 4 }));

  assert.equal(result.kind, 'duplicate');
  assert.strictEqual(result.state, state);
});

test('a non-snapshot sequence gap requires canonical reconciliation', () => {
  const state = initialState();
  const result = reduceSessionEvent(state, event({ sequence: 9 }));

  assert.equal(result.kind, 'reconcile');
  assert.strictEqual(result.state, state);
});

test('snapshot fallback safely crosses a replay gap', () => {
  const result = reduceSessionEvent(
    initialState(),
    event({ sequence: 9, revision: 7, type: 'SNAPSHOT', projection: oneNodeProjection }),
  );

  assert.equal(result.kind, 'applied');
  assert.equal(result.state.sequence, 9);
  assert.equal(result.state.revision, 7);
  assert.deepEqual(result.state.projection, oneNodeProjection);
});

test('snapshot fallback also recovers a client cursor ahead of a restarted server', () => {
  const aheadState = collaborationState(
    { sequence: 12, revision: 8, participants: [actorAlice] },
    oneNodeProjection,
  );
  const result = reduceSessionEvent(
    aheadState,
    event({ sequence: 3, revision: 1, type: 'SNAPSHOT', projection: emptyProjection }),
  );

  assert.equal(result.kind, 'applied');
  assert.equal(result.state.sequence, 3);
  assert.equal(result.state.revision, 1);
  assert.deepEqual(result.state.projection, emptyProjection);
});

test('presence is separate, actor-scoped and removable', () => {
  const joined = reduceSessionEvent(
    initialState(),
    event({
      type: 'PRESENCE_JOINED',
      revision: 2,
      actor: actorBob,
      projection: null,
      attributes: { cursorX: '12', cursorY: '40' },
    }),
  ).state;

  assert.deepEqual(joined.projection, emptyProjection);
  assert.deepEqual(joined.participants, [actorAlice, actorBob]);
  assert.equal(joined.presence['actor-bob'].attributes.cursorX, '12');

  const left = reduceSessionEvent(
    joined,
    event({
      sequence: 6,
      type: 'PRESENCE_LEFT',
      revision: 2,
      actor: actorBob,
      projection: null,
    }),
  ).state;

  assert.deepEqual(left.participants, [actorAlice]);
  assert.equal(left.presence['actor-bob'], undefined);
});

test('synchronous command acceptance uses reported revision but not SSE sequence', () => {
  const accepted = acceptCommandProjection(initialState(), oneNodeProjection, 3);

  assert.equal(accepted.revision, 3);
  assert.equal(accepted.sequence, 4);
  assert.deepEqual(accepted.projection, oneNodeProjection);
});

test('idempotent command retry does not invent a revision', () => {
  const accepted = acceptCommandProjection(initialState(), oneNodeProjection, 2);

  assert.equal(accepted.revision, 2);
  assert.equal(accepted.sequence, 4);
});

test('stale presence expires without changing semantic state', () => {
  const state = {
    ...initialState(),
    presence: {
      stale: {
        actor: actorBob,
        observedAt: '2026-07-18T11:59:00Z',
        attributes: { selection: 'node-1' },
      },
      current: {
        actor: actorAlice,
        observedAt: '2026-07-18T11:59:59Z',
        attributes: {},
      },
    },
  };

  const expired = expirePresence(state, Date.parse('2026-07-18T12:00:00Z'), 15_000);

  assert.equal(expired.presence.stale, undefined);
  assert.ok(expired.presence.current);
  assert.strictEqual(expired.projection, state.projection);
  assert.equal(expired.revision, state.revision);
});

test('reconnect backoff is bounded', () => {
  assert.equal(reconnectDelay(1), 1000);
  assert.equal(reconnectDelay(2), 2000);
  assert.equal(reconnectDelay(5), 10000);
  assert.equal(reconnectDelay(50), 10000);
});
