import assert from 'node:assert/strict';
import test from 'node:test';
import { participantPresentation, visiblePresence } from '../src/participantIdentity.mjs';

const guest = {
  actorId: 'internal-a', displayName: 'Pat', provenance: 'SELF_ASSERTED', verified: false,
  accountHandle: null, role: 'EDITOR', participantCode: 'A72F', lastSeen: '2026-11-20T12:00:00Z',
};

test('same-named anonymous participants remain distinguishable without showing internal ids', () => {
  const first = participantPresentation(guest, 'other', 'live', Date.parse('2026-11-20T12:00:05Z'));
  const second = participantPresentation({ ...guest, actorId: 'internal-b', participantCode: 'B91E' }, 'other', 'live', Date.parse('2026-11-20T12:00:05Z'));
  assert.equal(first.identityLabel, 'Unverified pseudonym');
  assert.notEqual(first.label, second.label);
  assert.ok(!first.label.includes('internal-'));
  assert.equal(first.status, 'live');
});

test('verified account and private handle are visually explicit', () => {
  const authenticated = { ...guest, provenance: 'AUTHENTICATED', verified: true, role: 'OWNER' };
  assert.equal(participantPresentation(authenticated, guest.actorId, 'reconnecting', 0).identityLabel, 'Verified account · handle private');
  assert.equal(participantPresentation(authenticated, guest.actorId, 'reconnecting', 0).status, 'reconnecting');
  assert.equal(participantPresentation(authenticated, guest.actorId, 'live', 0).isYou, true);
  assert.equal(participantPresentation({ ...authenticated, accountHandle: 'carsten' }, 'other', 'live', 0).identityLabel, 'Verified @carsten');
  assert.equal(participantPresentation({ ...guest, accountHandle: 'forged' }, 'other', 'live', 0).identityLabel, 'Unverified pseudonym');
});

test('stale participant retains last activity with an offline status', () => {
  const result = participantPresentation(guest, 'other', 'live', Date.parse('2026-11-20T12:01:30Z'));
  assert.equal(result.status, 'offline');
  assert.equal(result.lastSeen, guest.lastSeen);
});

test('only bounded public presence fields are displayed', () => {
  assert.deepEqual(visiblePresence({ selection: 'node.1', executingRun: 'run.1', secret: 'token', role: 'owner', 'cursor.x': '20', 'viewport.zoom': '1.5' }), ['Selected node.1', 'Running run.1']);
  assert.deepEqual(visiblePresence({ selection: 'x'.repeat(300), executingRun: '<script>' }), []);
});
