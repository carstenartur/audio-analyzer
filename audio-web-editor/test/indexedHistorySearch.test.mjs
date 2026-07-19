import assert from 'node:assert/strict';
import test from 'node:test';

import {
  historyHitLabel,
  indexedHistoryRebuildUrl,
  indexedHistorySearchUrl,
  normalizeHistorySearchLimit,
  visibleChangedPaths,
} from '../src/indexedHistorySearch.mjs';

test('builds an encoded indexed-history query without leaking blank text', () => {
  assert.equal(
    indexedHistorySearchUrl('  wingbeat gain + FFT  ', 25),
    '/workflow/history/index?q=wingbeat+gain+%2B+FFT&limit=25',
  );
  assert.equal(indexedHistorySearchUrl('   '), '/workflow/history/index?limit=20');
});

test('bounds search limits to the public HTTP contract', () => {
  assert.equal(normalizeHistorySearchLimit('not-a-number'), 20);
  assert.equal(normalizeHistorySearchLimit(0), 1);
  assert.equal(normalizeHistorySearchLimit(500), 200);
});

test('builds deterministic rebuild URLs and safe hit labels', () => {
  assert.equal(
    indexedHistoryRebuildUrl(' feature/audio search ', -1),
    '/workflow/history/index/rebuild?branch=feature%2Faudio+search&limit=-1',
  );
  assert.equal(historyHitLabel({ commitId: '1234567890abcdef', message: '' }), '1234567890ab');
  assert.deepEqual(visibleChangedPaths(['a', 'b', 'c', 'd']), ['a', 'b', 'c']);
  assert.deepEqual(visibleChangedPaths(null), []);
});
