package org.hammer.audio.infrastructure.workflow.collaboration.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import org.hammer.audio.workflow.collaboration.store.LeasedWorkflowOutboxEntry;
import org.hammer.audio.workflow.collaboration.store.StoredWorkflowOutboxEntry;
import org.hammer.audio.workflow.collaboration.store.WorkflowOutboxEventData;
import org.hammer.audio.workflow.collaboration.store.WorkflowOutboxLeaseConflictException;

/** Hibernate-owned transactional outbox entry for an accepted collaboration command. */
@Entity
@Table(
    name = "workflow_collaboration_outbox",
    indexes = {
      @Index(
          name = "idx_workflow_outbox_pending",
          columnList = "published_at, next_attempt_at, lease_expires_at"),
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
  private String storedEventId;

  @Version
  @Column(name = "entity_version", nullable = false)
  private long entityVersion;

  @Column(name = "session_id", nullable = false, length = 255)
  private String storedSessionId;

  @Column(name = "event_sequence", nullable = false)
  private long storedEventSequence;

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

  @Column(name = "lease_owner", length = 255)
  private String leaseOwner;

  @Column(name = "lease_token", length = 255)
  private String leaseToken;

  @Column(name = "lease_expires_at")
  private Instant leaseExpiresAt;

  protected WorkflowOutboxEntity() {
    // Required by Jakarta Persistence.
  }

  static WorkflowOutboxEntity pending(
      String sessionId, WorkflowOutboxEventData event, long sequence, long revision) {
    WorkflowOutboxEntity entity = new WorkflowOutboxEntity();
    entity.storedEventId = event.eventId();
    entity.storedSessionId = sessionId;
    entity.storedEventSequence = sequence;
    entity.semanticRevision = revision;
    entity.eventType = event.eventType();
    entity.occurredAt = event.occurredAt();
    entity.payload = event.payload();
    entity.nextAttemptAt = event.occurredAt();
    return entity;
  }

  StoredWorkflowOutboxEntry toStoredEntry() {
    return new StoredWorkflowOutboxEntry(
        storedEventId,
        storedSessionId,
        storedEventSequence,
        semanticRevision,
        eventType,
        occurredAt,
        payload,
        attemptCount,
        nextAttemptAt,
        publishedAt);
  }

  boolean claimableAt(Instant instant) {
    Instant requiredInstant = Objects.requireNonNull(instant, "instant");
    return publishedAt == null
        && !nextAttemptAt.isAfter(requiredInstant)
        && (leaseExpiresAt == null || !leaseExpiresAt.isAfter(requiredInstant));
  }

  LeasedWorkflowOutboxEntry lease(
      String owner, String token, Instant claimedAt, Instant expiresAt) {
    Instant requiredClaimedAt = Objects.requireNonNull(claimedAt, "claimedAt");
    Instant requiredExpiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    if (!requiredExpiresAt.isAfter(requiredClaimedAt)) {
      throw new IllegalArgumentException("expiresAt must be after claimedAt");
    }
    if (!claimableAt(requiredClaimedAt)) {
      throw new IllegalStateException("Outbox event is not claimable: " + storedEventId);
    }
    leaseOwner = requireNotBlank(owner, "owner");
    leaseToken = requireNotBlank(token, "token");
    leaseExpiresAt = requiredExpiresAt;
    return new LeasedWorkflowOutboxEntry(toStoredEntry(), leaseOwner, leaseToken, leaseExpiresAt);
  }

  StoredWorkflowOutboxEntry markPublished(String token, Instant publicationTime) {
    requireLease(token);
    attemptCount = Math.addExact(attemptCount, 1);
    publishedAt = Objects.requireNonNull(publicationTime, "publicationTime");
    clearLease();
    return toStoredEntry();
  }

  StoredWorkflowOutboxEntry markFailed(String token, Instant retryTime) {
    requireLease(token);
    attemptCount = Math.addExact(attemptCount, 1);
    nextAttemptAt = Objects.requireNonNull(retryTime, "retryTime");
    clearLease();
    return toStoredEntry();
  }

  boolean hasSameSemanticContent(
      String expectedSessionId,
      WorkflowOutboxEventData candidate,
      long expectedSequence,
      long expectedRevision) {
    return storedSessionId.equals(expectedSessionId)
        && storedEventId.equals(candidate.eventId())
        && storedEventSequence == expectedSequence
        && semanticRevision == expectedRevision
        && eventType.equals(candidate.eventType())
        && occurredAt.equals(candidate.occurredAt())
        && payload.equals(candidate.payload());
  }

  String eventId() {
    return storedEventId;
  }

  String sessionId() {
    return storedSessionId;
  }

  long eventSequence() {
    return storedEventSequence;
  }

  private void requireLease(String token) {
    String requiredToken = requireNotBlank(token, "token");
    if (leaseToken == null || !leaseToken.equals(requiredToken)) {
      throw new WorkflowOutboxLeaseConflictException(storedEventId, requiredToken);
    }
  }

  private void clearLease() {
    leaseOwner = null;
    leaseToken = null;
    leaseExpiresAt = null;
  }

  private static String requireNotBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
