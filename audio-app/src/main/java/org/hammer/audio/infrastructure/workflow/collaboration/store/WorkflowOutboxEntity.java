package org.hammer.audio.infrastructure.workflow.collaboration.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import org.hammer.audio.workflow.collaboration.store.PendingWorkflowOutboxEntry;
import org.hammer.audio.workflow.collaboration.store.WorkflowOutboxEventData;

/** Hibernate-owned transactional outbox entry for an accepted collaboration command. */
@Entity
@Table(
    name = "workflow_collaboration_outbox",
    indexes = {
      @Index(
          name = "idx_workflow_outbox_pending",
          columnList = "published_at, next_attempt_at"),
      @Index(name = "idx_workflow_outbox_session", columnList = "session_id")
    },
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_workflow_outbox_sequence",
          columnNames = {"session_id", "event_sequence"})
    })
public class WorkflowOutboxEntity {

  @Id
  @Column(name = "event_id", nullable = false, length = 255)
  private String eventId;

  @Column(name = "session_id", nullable = false, length = 255)
  private String sessionId;

  @Column(name = "event_sequence", nullable = false)
  private long eventSequence;

  @Column(name = "semantic_revision", nullable = false)
  private long semanticRevision;

  @Column(name = "event_type", nullable = false, length = 255)
  private String eventType;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  @Lob
  @Column(name = "event_payload", nullable = false)
  private String payload;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "next_attempt_at", nullable = false)
  private Instant nextAttemptAt;

  @Column(name = "published_at")
  private Instant publishedAt;

  protected WorkflowOutboxEntity() {}

  static WorkflowOutboxEntity pending(
      String sessionId, WorkflowOutboxEventData event, long sequence, long revision) {
    WorkflowOutboxEntity entity = new WorkflowOutboxEntity();
    entity.eventId = event.eventId();
    entity.sessionId = sessionId;
    entity.eventSequence = sequence;
    entity.semanticRevision = revision;
    entity.eventType = event.eventType();
    entity.occurredAt = event.occurredAt();
    entity.payload = event.payload();
    entity.nextAttemptAt = event.occurredAt();
    return entity;
  }

  PendingWorkflowOutboxEntry toPendingEntry() {
    return new PendingWorkflowOutboxEntry(
        eventId,
        sessionId,
        eventSequence,
        semanticRevision,
        eventType,
        occurredAt,
        payload,
        attemptCount,
        nextAttemptAt);
  }

  String eventId() {
    return eventId;
  }

  String sessionId() {
    return sessionId;
  }

  long eventSequence() {
    return eventSequence;
  }
}
