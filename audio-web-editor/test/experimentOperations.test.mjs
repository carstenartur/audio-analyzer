import test from 'node:test';
import assert from 'node:assert/strict';
import { canStartExperiment, formatCapacity, formatSafeDuration, editProfileDefinition, applyMeasuredCalibration, resizeProfileDefinition, profileValues } from '../src/experimentOperationsState.mjs';

test('channel count changes preserve existing geometry and require new measured calibration', () => {
  const definition = 'array.channels=2\narray.microphone.0.id=Left\narray.microphone.0.x=0.1\narray.microphone.0.y=0.2\ncalibration.present=true\ncapture.present=true\ncapture.channels=2\n';
  const resized = profileValues(resizeProfileDefinition(definition, 4));
  assert.equal(resized['array.channels'], '4');
  assert.equal(resized['capture.channels'], '4');
  assert.equal(resized['array.microphone.0.x'], '0.1');
  assert.equal(resized['array.microphone.3.id'], 'mic-3');
  assert.equal(resized['calibration.present'], 'false');
  assert.equal(resized['profile.layout'], 'CUSTOM');
  assert.throws(() => resizeProfileDefinition(definition, 1));
});

test('only a fresh explicit ready decision without blockers permits Start', () => {
  assert.equal(canStartExperiment({ state: 'READY', findings: [] }, true), true);
  assert.equal(canStartExperiment({ state: 'READY_WITH_WARNINGS', findings: [{ severity: 'WARNING' }] }, true), true);
  assert.equal(canStartExperiment({ state: 'READY', findings: [{ severity: 'BLOCKING', acknowledged: true }] }, true), false);
  assert.equal(canStartExperiment({ state: 'READY', findings: [] }, false), false);
  assert.equal(canStartExperiment({ state: 'INCOMPLETE', findings: [] }, true), false);
});

test('unknown capacity is never presented as zero space or infinite recording time', () => {
  assert.equal(formatCapacity(-1), 'Unknown');
  assert.equal(formatCapacity(1048576), '1.0 MiB');
  assert.equal(formatSafeDuration(-1), 'Unknown');
  assert.equal(formatSafeDuration(125), '2m 5s');
});

test('geometry editing invalidates the previous calibration and preserves portable names', () => {
  const edited = editProfileDefinition('profile.name=Example\ncalibration.present=true\narray.microphone.0.x=0\n',
    { 'profile.name': 'Room A + B', 'array.microphone.0.x': '1.2' }, true);
  assert.match(edited, /calibration.present=false/);
  assert.match(edited, /profile.name=Room%20A%20%2B%20B/);
});

test('calibration input requires one measured row per channel and a zero reference offset', () => {
  const base = 'array.channels=2\ncalibration.present=false\n';
  assert.throws(() => applyMeasuredCalibration(base, 'measurement', '2026-11-20T12:00:00Z', '2026-11-21T12:00:00Z', '0,0,0,0.1,0.1,1,false'), /every channel/);
  assert.throws(() => applyMeasuredCalibration(base, 'measurement', '2026-11-20T12:00:00Z', '2026-11-21T12:00:00Z', '0,1,0,0.1,0.1,1,false\n1,0.1,0,0.1,0.1,1,false'), /reference/);
  const value = applyMeasuredCalibration(base, 'measurement', '2026-11-20T12:00:00Z', '2026-11-21T12:00:00Z', '0,0,0,0.1,0.1,1,false\n1,0.2,0,0.1,0.1,1,false');
  assert.match(value, /calibration.present=true/);
  assert.match(value, /calibration.channel.1.offsetSamples=0.2/);
});
