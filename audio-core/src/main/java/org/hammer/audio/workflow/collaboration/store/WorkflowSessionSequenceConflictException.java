package org.hammer.audio.workflow.collaboration.store;

import java.util.Objects;

/** Typed durable-session failure raised when an event targets a stale collaboration sequence. */
public final class WorkflowSessionSequenceConflictException extends RuntimeException {

  private final String affectedSessionId;
  private final long requestedSequence;
  private final long currentSequence;

  /** Creates an event-sequence conflict with stable machine-readable values. */
  public WorkflowSessionSequenceConflictException(
      String sessionId, long expectedSequence, long actualSequence) {
    this(sessionId, expectedSequence, actualSequence, null);
  }

  /** Creates an event-sequence conflict while preserving the persistence failure. */
  public WorkflowSessionSequenceConflictException(
      String sessionId, long expectedSequence, long actualSequence, Throwable cause) {
    super(message(sessionId, expectedSequence, actualSequence), cause);
    this.affectedSessionId = Objects.requireNonNull(sessionId, "sessionId");
    this.requestedSequence = expectedSequence;
    this.currentSequence = actualSequence;
  }

  /** Returns the affected stable session identifier. */
  public String sessionId() {
    return affectedSessionId;
  }

  /** Returns the event sequence supplied by the caller. */
  public long expectedSequence() {
    return requestedSequence;
  }

  /** Returns the event sequence currently stored in the database. */
  public long actualSequence() {
    return currentSequence;
  }

  private static String message(String sessionId, long expectedSequence, long actualSequence) {
    return "Expected workflow session event sequence "
        + expectedSequence
        + " but found "
        + actualSequence
        + " for session "
        + sessionId;
  }
}
