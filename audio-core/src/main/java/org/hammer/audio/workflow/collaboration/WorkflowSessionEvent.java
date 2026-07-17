package org.hammer.audio.workflow.collaboration;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Ordered server fact delivered to collaboration transports. */
public record WorkflowSessionEvent(
    String eventId,
    String sessionId,
    long sequence,
    long revision,
    Type type,
    Instant occurredAt,
    OperationActor actor,
    String operationId,
    WorkflowSessionState state,
    Map<String, String> details) {

  /** Event categories kept independent from SSE, WebSocket and broker protocols. */
  public enum Type {
    SESSION_CREATED,
    ACTOR_JOINED,
    ACTOR_LEFT,
    SESSION_CLOSED,
    OPERATION_ACCEPTED,
    PRESENCE_UPDATED,
    PRESENCE_CLEARED,
    UNDO_ACCEPTED,
    REDO_ACCEPTED,
    SNAPSHOT
  }

  public WorkflowSessionEvent {
    requireNotBlank(eventId, "eventId");
    requireNotBlank(sessionId, "sessionId");
    if (sequence < 0 || revision < 0) {
      throw new IllegalArgumentException("sequence and revision must be >= 0");
    }
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(occurredAt, "occurredAt");
    details = Map.copyOf(Objects.requireNonNull(details, "details"));
  }

  public static WorkflowSessionEvent create(
      String sessionId,
      long sequence,
      long revision,
      Type type,
      OperationActor actor,
      String operationId,
      WorkflowSessionState state,
      Map<String, String> details) {
    return new WorkflowSessionEvent(
        UUID.randomUUID().toString(),
        sessionId,
        sequence,
        revision,
        type,
        Instant.now(),
        actor,
        operationId,
        state,
        details);
  }

  private static String requireNotBlank(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
