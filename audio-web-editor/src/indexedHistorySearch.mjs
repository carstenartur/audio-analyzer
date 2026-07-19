const DEFAULT_LIMIT = 20;
const MAX_LIMIT = 200;

/**
 * @param {unknown} value
 * @returns {number}
 */
export function normalizeHistorySearchLimit(value) {
  const parsed = Number.parseInt(String(value), 10);
  if (!Number.isFinite(parsed)) {
    return DEFAULT_LIMIT;
  }
  return Math.min(MAX_LIMIT, Math.max(1, parsed));
}

/**
 * @param {unknown} query
 * @param {unknown} [limit]
 * @returns {string}
 */
export function indexedHistorySearchUrl(query, limit = DEFAULT_LIMIT) {
  const parameters = new URLSearchParams();
  const normalizedQuery = String(query ?? '').trim();
  if (normalizedQuery.length > 0) {
    parameters.set('q', normalizedQuery);
  }
  parameters.set('limit', String(normalizeHistorySearchLimit(limit)));
  return `/workflow/history/index?${parameters.toString()}`;
}

/**
 * @param {unknown} [branch]
 * @param {unknown} [limit]
 * @returns {string}
 */
export function indexedHistoryRebuildUrl(branch = 'main', limit = -1) {
  const parameters = new URLSearchParams();
  const numericLimit = Number(limit);
  parameters.set('branch', String(branch ?? '').trim() || 'main');
  parameters.set('limit', String(Number.isInteger(numericLimit) ? numericLimit : -1));
  return `/workflow/history/index/rebuild?${parameters.toString()}`;
}

/**
 * @typedef HistoryHitLike
 * @property {unknown} [commitId]
 * @property {unknown} [message]
 */

/**
 * @param {HistoryHitLike | null | undefined} hit
 * @returns {string}
 */
export function historyHitLabel(hit) {
  const message = String(hit?.message ?? '').trim();
  const commitId = String(hit?.commitId ?? '');
  return message || commitId.slice(0, 12) || 'Unnamed checkpoint';
}

/**
 * @param {unknown} paths
 * @param {number} [maximum]
 * @returns {string[]}
 */
export function visibleChangedPaths(paths, maximum = 3) {
  if (!Array.isArray(paths) || maximum <= 0) {
    return [];
  }
  return paths.slice(0, maximum).map((path) => String(path));
}
