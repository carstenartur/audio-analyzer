package org.hammer.audio.workflow.collaboration;

import java.util.Objects;

/** Raised when one durable collaboration session cannot be reconstructed safely. */
public final class WorkflowSessionRecoveryException extends RuntimeException {

  private final String failedSessionId;

  /** Creates a session-specific recovery failure. */
  public WorkflowSessionRecoveryException(String sessionId, String message, Throwable cause) {
    super(message, cause);
    this.failedSessionId = Objects.requireNonNull(sessionId, "sessionId");
  }

  /** Creates a session-specific recovery failure without a lower-level cause. */
  public WorkflowSessionRecoveryException(String sessionId, String message) {
    super(message);
    this.failedSessionId = Objects.requireNonNull(sessionId, "sessionId");
  }

  /** Returns the durable session that could not be recovered. */
  public String sessionId() {
    return failedSessionId;
  }
}
