package org.hammer.audio.workflow.collaboration;

import java.util.Objects;

/** Typed collaboration/session failure suitable for transport-independent error mapping. */
public final class WorkflowSessionException extends RuntimeException {

  /** Stable machine-readable failure codes. */
  public enum Code {
    SESSION_NOT_FOUND,
    SESSION_ALREADY_EXISTS,
    PRIVATE_WORKSPACE_ACCESS_DENIED,
    ACTOR_METADATA_MISMATCH,
    ACTOR_NOT_JOINED,
    SESSION_MODE_MISMATCH,
    SESSION_CLOSE_FORBIDDEN,
    INVALID_OPERATION_AUTHOR,
    REVISION_CONFLICT,
    NOTHING_TO_UNDO,
    NOTHING_TO_REDO,
    INVALID_WORKFLOW_OPERATION
  }

  private final Code code;
  private final String sessionId;

  public WorkflowSessionException(Code code, String sessionId, String message) {
    super(Objects.requireNonNull(message, "message"));
    this.code = Objects.requireNonNull(code, "code");
    this.sessionId = sessionId;
  }

  public Code code() {
    return code;
  }

  public String sessionId() {
    return sessionId;
  }
}
