import assert from 'node:assert/strict';
import test from 'node:test';
import { canRunWorkflowSource, isFreshHealthObservation } from '../src/workflowExperimentHealth.mjs';

const source = { sessionId: 'session.example', revision: 7 };
const ready = { state: 'READY', findings: [], facts: { 'workflow.sessionId': source.sessionId, 'workflow.revision': '7', 'workflow.sha256': 'a'.repeat(64) } };

test('an exact reviewed session revision permits its run', () => {
  assert.equal(canRunWorkflowSource(ready, source), true);
  assert.equal(canRunWorkflowSource(ready, { ...source, revision: 8 }), false);
  assert.equal(canRunWorkflowSource(ready, { ...source, sessionId: 'another-session' }), false);
});

test('a true blocker remains blocking regardless of acknowledgement', () => {
  assert.equal(canRunWorkflowSource({ ...ready, findings: [{ severity: 'BLOCKING', acknowledged: true }] }, source), false);
  assert.equal(canRunWorkflowSource(null, source), false);
});

test('stored-commit readiness never transfers to a different commit', () => {
  const health = { state: 'READY_WITH_WARNINGS', findings: [], facts: { 'workflow.commit': 'exact-commit', 'workflow.sha256': 'a'.repeat(64) } };
  assert.equal(canRunWorkflowSource(health, { commitId: 'exact-commit' }), true);
  assert.equal(canRunWorkflowSource(health, { commitId: 'another-commit' }), false);
});

test('readiness without the reviewed canonical graph hash cannot start a new run', () => {
  assert.equal(canRunWorkflowSource({ ...ready, facts: { ...ready.facts, 'workflow.sha256': '' } }, source), false);
});

test('a stalled refresh expires readiness independently of the unresolved request', () => {
  assert.equal(isFreshHealthObservation(10_000, 14_999), true);
  assert.equal(isFreshHealthObservation(10_000, 15_001), false);
  assert.equal(isFreshHealthObservation(0, 15_001), false);
  assert.equal(isFreshHealthObservation(10_000, 9_999), false);
});
