package org.hammer.audio.workflow.collaboration.store;

import java.util.Objects;

/** Typed failure raised when an operation id is reused for different semantic content. */
public final class WorkflowOperationPersistenceConflictException extends RuntimeException {

  private final String conflictingSessionId;
  private final String conflictingOperationId;

  /** Creates an operation-id conflict. */
  public WorkflowOperationPersistenceConflictException(String sessionId, String operationId) {
    super("Operation id " + operationId + " is already used in session " + sessionId);
    this.conflictingSessionId = Objects.requireNonNull(sessionId, "sessionId");
    this.conflictingOperationId = Objects.requireNonNull(operationId, "operationId");
  }

  public String sessionId() {
    return conflictingSessionId;
  }

  public String operationId() {
    return conflictingOperationId;
  }
}
