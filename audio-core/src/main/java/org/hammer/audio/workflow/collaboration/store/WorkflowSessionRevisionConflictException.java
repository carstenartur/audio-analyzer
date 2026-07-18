package org.hammer.audio.workflow.collaboration.store;

import java.util.Objects;

/** Typed durable-session failure raised when an append targets a stale semantic revision. */
public final class WorkflowSessionRevisionConflictException extends RuntimeException {

  private final String conflictingSessionId;
  private final long requiredRevision;
  private final long observedRevision;

  /** Creates a revision conflict with stable machine-readable values. */
  public WorkflowSessionRevisionConflictException(
      String sessionId, long expectedRevision, long actualRevision) {
    this(sessionId, expectedRevision, actualRevision, null);
  }

  /** Creates a revision conflict while preserving the triggering persistence failure. */
  public WorkflowSessionRevisionConflictException(
      String sessionId, long expectedRevision, long actualRevision, Throwable cause) {
    super(message(sessionId, expectedRevision, actualRevision), cause);
    this.conflictingSessionId = Objects.requireNonNull(sessionId, "sessionId");
    this.requiredRevision = expectedRevision;
    this.observedRevision = actualRevision;
  }

  public String sessionId() {
    return conflictingSessionId;
  }

  public long expectedRevision() {
    return requiredRevision;
  }

  public long actualRevision() {
    return observedRevision;
  }

  private static String message(String sessionId, long expectedRevision, long actualRevision) {
    return "Expected workflow session revision "
        + expectedRevision
        + " but found "
        + actualRevision
        + " for session "
        + sessionId;
  }
}
