package org.hammer.audio.workflow.collaboration;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.hammer.audio.workflow.WorkflowOperation;

/**
 * Committed collaboration event published through an event bus.
 *
 * @param eventId unique event identifier
 * @param sessionId collaboration session identifier
 * @param occurredAt event creation timestamp
 * @param type event type
 * @param actor actor metadata for the event source
 * @param payload immutable event payload
 */
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
      String sessionId, OperationActor requestedBy, UndoDetails undoDetails) {
    return new WorkflowCollaborationEvent(
        UUID.randomUUID().toString(),
        sessionId,
        Instant.now(),
        TYPE_UNDO_APPLIED,
        requestedBy,
        Map.of(
            "undoScope", undoDetails.scope().name(),
            "revertedOperationId", undoDetails.revertedOperationId(),
            "revertedActorId", undoDetails.revertedActorId(),
            "undoOperationId", undoDetails.undoOperationId()));
  }

  /**
   * Details for a shared/personal undo event payload.
   *
   * @param scope effective undo scope
   * @param revertedOperationId operation id that was reverted
   * @param revertedActorId actor whose operation was reverted
   * @param undoOperationId semantic inverse operation id that was applied
   */
  public record UndoDetails(
      UndoScope scope, String revertedOperationId, String revertedActorId, String undoOperationId) {
    public UndoDetails {
      Objects.requireNonNull(scope, "scope");
      Objects.requireNonNull(revertedOperationId, "revertedOperationId");
      Objects.requireNonNull(revertedActorId, "revertedActorId");
      Objects.requireNonNull(undoOperationId, "undoOperationId");
    }
  }
}
