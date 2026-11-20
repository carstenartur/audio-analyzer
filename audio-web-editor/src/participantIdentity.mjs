/** Privacy-aware public participant labels; internal subjects never reach this model. */

/**
 * @param {import('./api').ActorIdentity} actor public server projection
 * @param {string} currentActorId current client
 * @param {string} connectionState current client's transport state
 * @param {number} now current time in milliseconds
 */
export function participantPresentation(actor, currentActorId, connectionState, now) {
  const isYou = actor.actorId === currentActorId;
  const verified = actor.provenance === 'AUTHENTICATED' && actor.verified === true;
  const identityLabel = verified
    ? actor.accountHandle ? `Verified @${actor.accountHandle}` : 'Verified account · handle private'
    : actor.provenance === 'ANONYMOUS' ? 'Anonymous guest' : 'Unverified pseudonym';
  const elapsed = actor.lastSeen ? now - Date.parse(actor.lastSeen) : Number.POSITIVE_INFINITY;
  const status = isYou
    ? connectionState === 'connecting' ? 'reconnecting' : connectionState
    : elapsed <= 15_000 ? 'live' : elapsed <= 60_000 ? 'idle' : 'offline';
  return {
    label: `${actor.displayName} · ${actor.participantCode}`,
    identityLabel,
    isYou,
    status,
    lastSeen: actor.lastSeen,
  };
}

/** @param {Record<string, string>} attributes server-approved presence */
export function visiblePresence(attributes) {
  const fields = [['selection', 'Selected'], ['executingRun', 'Running']];
  return fields.flatMap(([key, label]) => {
    const value = attributes[key];
    return typeof value === 'string' && /^[A-Za-z0-9._:/-]{1,128}$/.test(value)
      ? [`${label} ${value}`] : [];
  });
}
