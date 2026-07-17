package org.hammer.audio.workflow.collaboration;

import java.time.Instant;
import java.util.Objects;

/** Audit marker connecting a semantic undo with its target and optional redo. */
public record WorkflowUndoEntry(
    String requestedByActor,
    UndoScope scope,
    String targetOperationId,
    String inverseOperationId,
    String redoOperationId,
    boolean redone,
    Instant createdAt) {

  public WorkflowUndoEntry {
    requireNotBlank(requestedByActor, "requestedByActor");
    Objects.requireNonNull(scope, "scope");
    requireNotBlank(targetOperationId, "targetOperationId");
    requireNotBlank(inverseOperationId, "inverseOperationId");
    Objects.requireNonNull(createdAt, "createdAt");
  }

  public WorkflowUndoEntry markRedone(String operationId) {
    return new WorkflowUndoEntry(
        requestedByActor,
        scope,
        targetOperationId,
        inverseOperationId,
        requireNotBlank(operationId, "operationId"),
        true,
        createdAt);
  }

  private static String requireNotBlank(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
