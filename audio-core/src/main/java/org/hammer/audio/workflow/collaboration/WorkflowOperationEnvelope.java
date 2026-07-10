package org.hammer.audio.workflow.collaboration;

import java.time.Instant;
import java.util.Objects;
import org.hammer.audio.workflow.WorkflowOperation;

/** Collaboration/application-service metadata wrapper around a semantic workflow operation. */
public record WorkflowOperationEnvelope(
    String sessionId,
    CollaborationMode mode,
    OperationActor actor,
    WorkflowOperation operation,
    Instant receivedAt) {

  public WorkflowOperationEnvelope {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(receivedAt, "receivedAt");
    if (sessionId.isBlank()) {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
    if (!operation.author().equals(actor.actorId())) {
      throw new IllegalArgumentException(
          "operation author '" + operation.author() + "' must match actorId '" + actor.actorId() + "'");
    }
  }

  public static WorkflowOperationEnvelope of(
      String sessionId, CollaborationMode mode, WorkflowOperation operation) {
    return new WorkflowOperationEnvelope(
        sessionId, mode, OperationActor.forAuthor(operation.author()), operation, Instant.now());
  }
}
