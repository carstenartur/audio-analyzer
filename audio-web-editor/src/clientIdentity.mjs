let heldActorId = '';
/** @type {(() => void) | null} */
let releaseHeldActor = null;

/** Releases the per-tab actor lease when this page is unloaded or changes identity. */
export function releaseClientActor() {
  releaseHeldActor?.();
  releaseHeldActor = null;
  heldActorId = '';
}

/**
 * A duplicated browser tab may inherit sessionStorage. A Web Lock gives only one live tab that actor.
 * The other tab requests a fresh server identity while retaining its display-name preference.
 * @param {string} actorId existing public client selector
 * @returns {Promise<boolean>} whether this page owns the existing selector
 */
export async function claimClientActor(actorId) {
  if (!actorId || heldActorId === actorId) {
    return true;
  }
  if (!navigator.locks) {
    return true;
  }
  return new Promise((resolve) => {
    void navigator.locks.request(`audio-analyzer.collaboration.${actorId}`, { ifAvailable: true }, async (lock) => {
      if (lock === null) {
        resolve(false);
        return;
      }
      heldActorId = actorId;
      resolve(true);
      await new Promise((release) => { releaseHeldActor = () => release(undefined); });
    }).catch(() => resolve(false));
  });
}

if (typeof window !== 'undefined') {
  window.addEventListener('pagehide', releaseClientActor);
}
