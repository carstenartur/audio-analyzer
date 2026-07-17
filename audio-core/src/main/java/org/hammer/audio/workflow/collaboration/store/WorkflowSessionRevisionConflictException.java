package org.hammer.audio.workflow.collaboration.store;

import java.util.Objects;

/** Typed durable-session failure raised when an append targets a stale semantic revision. */
public final class WorkflowSessionRevisionConflictException extends RuntimeException {

  private final String sessionId;
  private final long expectedRevision;
  private final long actualRevision;

  /** Creates a revision conflict with stable machine-readable values. */
  public WorkflowSessionRevisionConflictException(
      String sessionId, long expectedRevision, long actualRevision) {
    super(
        "Expected workflow session revision "
            + expectedRevision
            + " but found "
            + actualRevision
            + " for session "
            + sessionId);
    this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
    this.expectedRevision = expectedRevision;
    this.actualRevision = actualRevision;
  }

  public String sessionId() {
    return sessionId;
  }

  public long expectedRevision() {
    return expectedRevision;
  }

  public long actualRevision() {
    return actualRevision;
  }
}
