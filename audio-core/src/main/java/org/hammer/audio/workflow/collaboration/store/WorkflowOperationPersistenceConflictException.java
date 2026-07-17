package org.hammer.audio.workflow.collaboration.store;

import java.util.Objects;

/** Typed failure raised when an operation id is reused for different semantic content. */
public final class WorkflowOperationPersistenceConflictException extends RuntimeException {

  private final String sessionId;
  private final String operationId;

  /** Creates an operation-id conflict. */
  public WorkflowOperationPersistenceConflictException(String sessionId, String operationId) {
    super("Operation id " + operationId + " is already used in session " + sessionId);
    this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
    this.operationId = Objects.requireNonNull(operationId, "operationId");
  }

  public String sessionId() {
    return sessionId;
  }

  public String operationId() {
    return operationId;
  }
}
