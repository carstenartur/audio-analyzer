/**
 * Computes a deterministic initial layout for a server-owned workflow projection.
 * Browser movement remains presentation state and is never written into canonical workflow data.
 *
 * @param {Array<{id: string}>} nodes projected workflow nodes
 * @returns {Record<string, {x: number, y: number}>} positions keyed by node id
 */
export function layoutNodePositions(nodes) {
  return Object.fromEntries(
    nodes.map((node, index) => [
      node.id,
      {
        x: 80 + index * 230,
        y: 180 + (index % 2) * 90,
      },
    ]),
  );
}

/**
 * Creates a client command identifier without making it a durable workflow identity.
 * The server remains authoritative for validation, ordering and persistence.
 *
 * @param {string} prefix operation kind prefix
 * @param {number} timestamp current timestamp
 * @param {string} randomSuffix collision-resistant suffix
 * @returns {string} command identifier
 */
export function operationId(prefix, timestamp, randomSuffix) {
  if (!prefix || !randomSuffix) {
    throw new Error('prefix and randomSuffix are required');
  }
  return `op.${prefix}.${timestamp}.${randomSuffix}`;
}
