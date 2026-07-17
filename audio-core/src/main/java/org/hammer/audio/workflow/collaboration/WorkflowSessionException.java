package org.hammer.audio.workflow.collaboration;

import java.util.Objects;

/** Typed application error for workflow-session lifecycle and membership failures. */
public final class WorkflowSessionException extends RuntimeException {

  /** Stable error codes independent of HTTP or any other transport. */
  public enum Code {
    SESSION_ALREADY_EXISTS,
    SESSION_NOT_FOUND,
    PRIVATE_WORKSPACE_ACCESS_DENIED,
    ACTOR_METADATA_MISMATCH,
    ACTOR_NOT_JOINED,
    SESSION_MODE_MISMATCH,
    SESSION_CLOSE_FORBIDDEN,
    DUPLICATE_OPERATION_ID,
    INVALID_OPERATION_AUTHOR
  }

  private final Code errorCode;
  private final String relatedSessionId;

  /** Creates a typed session exception. */
  public WorkflowSessionException(Code code, String sessionId, String message) {
    super(Objects.requireNonNull(message, "message"));
    this.errorCode = Objects.requireNonNull(code, "code");
    this.relatedSessionId = sessionId;
  }

  /** Returns the stable transport-independent error code. */
  public Code code() {
    return errorCode;
  }

  /** Returns the related session id, or {@code null} when none is available. */
  public String sessionId() {
    return relatedSessionId;
  }
}
