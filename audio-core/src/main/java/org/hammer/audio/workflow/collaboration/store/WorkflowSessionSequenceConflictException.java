package org.hammer.audio.workflow.collaboration.store;

import java.util.Objects;

/** Typed durable-session failure raised when an event targets a stale collaboration sequence. */
public final class WorkflowSessionSequenceConflictException extends RuntimeException {

  private final String sessionId;
  private final long expectedSequence;
  private final long actualSequence;

  /** Creates an event-sequence conflict with stable machine-readable values. */
  public WorkflowSessionSequenceConflictException(
      String sessionId, long expectedSequence, long actualSequence) {
    super(
        "Expected workflow session event sequence "
            + expectedSequence
            + " but found "
            + actualSequence
            + " for session "
            + sessionId);
    this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
    this.expectedSequence = expectedSequence;
    this.actualSequence = actualSequence;
  }

  /** Returns the affected stable session identifier. */
  public String sessionId() {
    return sessionId;
  }

  /** Returns the event sequence supplied by the caller. */
  public long expectedSequence() {
    return expectedSequence;
  }

  /** Returns the event sequence currently stored in the database. */
  public long actualSequence() {
    return actualSequence;
  }
}
