package org.hammer.audio.workflow.store;

/**
 * Result of a ref-update (optimistic-concurrency commit) attempt.
 *
 * <p>Owned by the persistence facade layer.
 */
public enum RefUpdateResult {

  /** The ref was updated successfully. */
  SUCCESS,

  /**
   * The ref was not updated because the expected old commit did not match the actual current commit
   * (optimistic-concurrency conflict).
   */
  STALE
}
