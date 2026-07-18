/**
 * Framework-independent collaboration state used by the React adapter and Node-native tests.
 * Canonical workflow state always comes from server projections carried by REST or SSE.
 */

/** @typedef {{actorId: string, userId: string, displayName: string}} Actor */
/** @typedef {{workflowId: string, workflowName: string, nodes: Array<object>, edges: Array<object>}} Projection */
/**
 * @typedef {{
 *   sequence: number,
 *   revision: number,
 *   projection: Projection | null,
 *   participants: Actor[],
 *   presence: Record<string, {actor: Actor, observedAt: string, attributes: Record<string, string>}>,
 *   closed: boolean
 * }} CollaborationState
 */
/**
 * @typedef {{
 *   sessionId: string,
 *   sequence: number,
 *   revision: number,
 *   occurredAt: string,
 *   type: string,
 *   actor: Actor | null,
 *   projection: Projection | null,
 *   attributes: Record<string, string>
 * }} SessionEvent
 */

/**
 * Creates state from the latest session metadata and canonical projection.
 *
 * @param {{sequence: number, revision: number, participants: Actor[]}} session session metadata
 * @param {Projection} projection canonical projection
 * @returns {CollaborationState} initialized state
 */
export function collaborationState(session, projection) {
  return {
    sequence: session.sequence,
    revision: session.revision,
    projection,
    participants: [...session.participants],
    presence: {},
    closed: false,
  };
}

/**
 * Reduces one ordered SSE event. Duplicates are ignored. A non-snapshot gap is never guessed across;
 * callers must reload metadata and the canonical projection instead.
 *
 * @param {CollaborationState} current current accepted state
 * @param {SessionEvent} event server event
 * @returns {{kind: 'applied' | 'duplicate' | 'reconcile', state: CollaborationState}}
 */
export function reduceSessionEvent(current, event) {
  if (event.sequence <= current.sequence) {
    return { kind: 'duplicate', state: current };
  }
  if (event.type !== 'SNAPSHOT' && event.sequence !== current.sequence + 1) {
    return { kind: 'reconcile', state: current };
  }

  const participants = [...current.participants];
  const presence = { ...current.presence };
  let closed = current.closed;

  if (event.actor !== null && event.type === 'PRESENCE_JOINED') {
    upsertActor(participants, event.actor);
  }
  if (event.actor !== null && event.type === 'PRESENCE_LEFT') {
    removeActor(participants, event.actor.actorId);
    delete presence[event.actor.actorId];
  }
  if (
    event.actor !== null &&
    (event.type === 'PRESENCE_JOINED' || event.type === 'PRESENCE_UPDATED')
  ) {
    upsertActor(participants, event.actor);
    presence[event.actor.actorId] = {
      actor: event.actor,
      observedAt: event.occurredAt,
      attributes: { ...event.attributes },
    };
  }
  if (event.type === 'SESSION_CLOSED') {
    closed = true;
  }

  return {
    kind: 'applied',
    state: {
      sequence: event.sequence,
      revision: event.revision,
      projection: event.projection ?? current.projection,
      participants,
      presence,
      closed,
    },
  };
}

/**
 * Advances the expected semantic revision after a synchronous accepted command while the matching SSE
 * event is still in flight. Sequence remains unchanged and is advanced only by ordered SSE.
 *
 * @param {CollaborationState} current current state
 * @param {Projection} projection accepted server projection
 * @returns {CollaborationState} state ready for the next expected-revision command
 */
export function acceptCommandProjection(current, projection) {
  return {
    ...current,
    revision: current.revision + 1,
    projection,
  };
}

/**
 * Removes stale remote presence samples without touching participant membership or workflow state.
 *
 * @param {CollaborationState} current current state
 * @param {number} nowEpochMillis current wall-clock time
 * @param {number} ttlMillis presence time-to-live
 * @returns {CollaborationState} state with expired samples removed
 */
export function expirePresence(current, nowEpochMillis, ttlMillis) {
  const presence = Object.fromEntries(
    Object.entries(current.presence).filter(([, value]) => {
      const observed = Date.parse(value.observedAt);
      return Number.isFinite(observed) && nowEpochMillis - observed <= ttlMillis;
    }),
  );
  return Object.keys(presence).length === Object.keys(current.presence).length
    ? current
    : { ...current, presence };
}

/**
 * Bounded exponential reconnect delay.
 *
 * @param {number} attempt one-based reconnect attempt
 * @returns {number} delay in milliseconds
 */
export function reconnectDelay(attempt) {
  const normalizedAttempt = Math.max(1, Math.trunc(attempt));
  return Math.min(1000 * 2 ** (normalizedAttempt - 1), 10000);
}

/** @param {Actor[]} participants @param {Actor} actor */
function upsertActor(participants, actor) {
  const index = participants.findIndex((candidate) => candidate.actorId === actor.actorId);
  if (index < 0) {
    participants.push(actor);
  } else {
    participants[index] = actor;
  }
  participants.sort((left, right) => left.actorId.localeCompare(right.actorId));
}

/** @param {Actor[]} participants @param {string} actorId */
function removeActor(participants, actorId) {
  const index = participants.findIndex((candidate) => candidate.actorId === actorId);
  if (index >= 0) {
    participants.splice(index, 1);
  }
}
