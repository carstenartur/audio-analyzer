package org.hammer.audio.workflow.collaboration;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.hammer.audio.workflow.Workflow;

/**
 * Ordered transport-neutral event emitted by one collaboration session.
 *
 * <p>Semantic workflow state is carried as an immutable {@link Workflow} snapshot. HTTP adapters
 * may derive renderer-specific projections from it, while the collaboration package stays
 * independent of editor and visualization types.
 *
 * @param eventId stable event identifier
 * @param sessionId collaboration session identifier
 * @param sequence monotonically increasing sequence within the session
 * @param revision monotonically increasing semantic workflow revision
 * @param occurredAt server timestamp for the event
 * @param type event type
 * @param actor actor associated with the event, or {@code null} for a replay snapshot
 * @param operationId accepted semantic operation identifier, or {@code null}
 * @param workflow canonical workflow snapshot for semantic/snapshot events, or {@code null}
 * @param attributes immutable presence or operation metadata
 */
public record WorkflowSessionEvent(
    String eventId,
    String sessionId,
    long sequence,
    long revision,
    Instant occurredAt,
    Type type,
    OperationActor actor,
    String operationId,
    Workflow workflow,
    Map<String, String> attributes) {

  /** Event types exposed to collaboration transports. */
  public enum Type {
    SESSION_CREATED,
    SESSION_CLOSED,
    OPERATION_ACCEPTED,
    PRESENCE_JOINED,
    PRESENCE_UPDATED,
    PRESENCE_LEFT,
    SNAPSHOT
  }

  public WorkflowSessionEvent {
    eventId = requireNotBlank(eventId, "eventId");
    sessionId = requireNotBlank(sessionId, "sessionId");
    if (sequence <= 0) {
      throw new IllegalArgumentException("sequence must be > 0");
    }
    if (revision < 0) {
      throw new IllegalArgumentException("revision must be >= 0");
    }
    occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    type = Objects.requireNonNull(type, "type");
    attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
    validatePayload(type, actor, operationId, workflow);
  }

  private static void validatePayload(
      Type type, OperationActor actor, String operationId, Workflow workflow) {
    switch (type) {
      case SESSION_CREATED -> {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(workflow, "workflow");
      }
      case OPERATION_ACCEPTED -> {
        Objects.requireNonNull(actor, "actor");
        requireNotBlank(operationId, "operationId");
        Objects.requireNonNull(workflow, "workflow");
      }
      case PRESENCE_JOINED, PRESENCE_UPDATED, PRESENCE_LEFT, SESSION_CLOSED ->
          Objects.requireNonNull(actor, "actor");
      case SNAPSHOT -> Objects.requireNonNull(workflow, "workflow");
    }
  }

  private static String requireNotBlank(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
