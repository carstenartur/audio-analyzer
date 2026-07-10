package org.hammer.audio.workflow.collaboration;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.hammer.audio.workflow.WorkflowOperation;

/** Committed collaboration event published through an event bus. */
public record WorkflowCollaborationEvent(
    String eventId,
    String sessionId,
    Instant occurredAt,
    String type,
    OperationActor actor,
    Map<String, String> payload) {

  private static final String TYPE_OPERATION_APPLIED = "WORKFLOW_OPERATION_APPLIED";
  private static final String TYPE_UNDO_APPLIED = "WORKFLOW_UNDO_APPLIED";

  public WorkflowCollaborationEvent {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(payload, "payload");
    if (eventId.isBlank()) {
      throw new IllegalArgumentException("eventId must not be blank");
    }
    if (sessionId.isBlank()) {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
    if (type.isBlank()) {
      throw new IllegalArgumentException("type must not be blank");
    }
    payload = Map.copyOf(payload);
  }

  public static WorkflowCollaborationEvent operationApplied(
      String sessionId, OperationActor actor, WorkflowOperation operation) {
    return new WorkflowCollaborationEvent(
        UUID.randomUUID().toString(),
        sessionId,
        Instant.now(),
        TYPE_OPERATION_APPLIED,
        actor,
        Map.of(
            "operationId", operation.operationId(),
            "operationType", operation.getClass().getSimpleName(),
            "operationAuthor", operation.author()));
  }

  public static WorkflowCollaborationEvent undoApplied(
      String sessionId,
      OperationActor requestedBy,
      UndoScope scope,
      String revertedOperationId,
      String revertedActorId,
      String undoOperationId) {
    return new WorkflowCollaborationEvent(
        UUID.randomUUID().toString(),
        sessionId,
        Instant.now(),
        TYPE_UNDO_APPLIED,
        requestedBy,
        Map.of(
            "undoScope", scope.name(),
            "revertedOperationId", revertedOperationId,
            "revertedActorId", revertedActorId,
            "undoOperationId", undoOperationId));
  }
}
