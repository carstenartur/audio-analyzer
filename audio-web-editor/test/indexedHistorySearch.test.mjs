import assert from 'node:assert/strict';
import test from 'node:test';

import {
  historyHitLabel,
  indexedHistoryRebuildUrl,
  indexedHistorySearchUrl,
  localHistoryTimeToInstant,
  normalizeHistorySearchLimit,
  visibleChangedPaths,
} from '../src/indexedHistorySearch.mjs';

test('builds an encoded indexed-history query without leaking blank filters', () => {
  assert.equal(
    indexedHistorySearchUrl('  wingbeat gain + FFT  ', 25, {
      authorEmail: ' researcher@example.org ',
      pathText: ' workflows insect ',
      from: '2026-07-01T00:00:00.000Z',
      to: '2026-07-19T23:59:59.000Z',
    }),
    '/workflow/history/index?q=wingbeat+gain+%2B+FFT&author=researcher%40example.org&path=workflows+insect&from=2026-07-01T00%3A00%3A00.000Z&to=2026-07-19T23%3A59%3A59.000Z&limit=25',
  );
  assert.equal(
    indexedHistorySearchUrl('   ', 20, { authorEmail: ' ', pathText: null }),
    '/workflow/history/index?limit=20',
  );
});

test('bounds search limits and converts optional browser times', () => {
  assert.equal(normalizeHistorySearchLimit('not-a-number'), 20);
  assert.equal(normalizeHistorySearchLimit(0), 1);
  assert.equal(normalizeHistorySearchLimit(500), 200);
  assert.equal(
    localHistoryTimeToInstant('2026-07-19T12:30:00Z'),
    '2026-07-19T12:30:00.000Z',
  );
  assert.equal(localHistoryTimeToInstant(''), null);
  assert.equal(localHistoryTimeToInstant('not-a-date'), null);
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
