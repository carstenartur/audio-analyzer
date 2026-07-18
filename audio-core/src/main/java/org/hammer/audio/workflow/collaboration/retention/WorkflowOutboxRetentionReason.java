package org.hammer.audio.workflow.collaboration.retention;

/** Auditable reason why a durable outbox event is eligible for deletion. */
public enum WorkflowOutboxRetentionReason {
  /** The event was published no later than the immutable retention-plan cutoff. */
  PUBLISHED_AT_OR_BEFORE_CUTOFF
}
