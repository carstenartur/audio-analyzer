package org.hammer.audio.infrastructure.workflow.collaboration.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import org.hammer.audio.workflow.collaboration.store.StoredWorkflowOperation;
import org.hammer.audio.workflow.collaboration.store.WorkflowOperationPersistenceData;

/** Hibernate-owned durable accepted semantic operation. */
@Entity
@Table(
    name = "workflow_collaboration_operation",
    indexes = {
      @Index(name = "idx_workflow_operation_session", columnList = "session_id"),
      @Index(name = "idx_workflow_operation_revision", columnList = "session_id, semantic_revision")
    },
    uniqueConstraints = {
      @UniqueConstraint(name = "uk_workflow_operation_id", columnNames = "operation_id"),
      @UniqueConstraint(
          name = "uk_workflow_operation_sequence",
          columnNames = {"session_id", "operation_sequence"})
    })
public class WorkflowOperationEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "session_id", nullable = false, length = 255)
  private String storedSessionId;

  @Column(name = "operation_id", nullable = false, length = 255)
  private String storedOperationId;

  @Column(name = "actor_id", nullable = false, length = 255)
  private String actorId;

  @Column(name = "operation_type", nullable = false, length = 255)
  private String operationType;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  @Column(name = "operation_sequence", nullable = false)
  private long storedOperationSequence;

  @Column(name = "semantic_revision", nullable = false)
  private long storedSemanticRevision;

  @Lob
  @Column(name = "operation_payload", nullable = false)
  private String payload;

  protected WorkflowOperationEntity() {
    // Required by Jakarta Persistence.
  }

  static WorkflowOperationEntity accepted(
      String sessionId, WorkflowOperationPersistenceData operation, long sequence, long revision) {
    WorkflowOperationEntity entity = new WorkflowOperationEntity();
    entity.storedSessionId = sessionId;
    entity.storedOperationId = operation.operationId();
    entity.actorId = operation.actorId();
    entity.operationType = operation.operationType();
    entity.occurredAt = operation.occurredAt();
    entity.storedOperationSequence = sequence;
    entity.storedSemanticRevision = revision;
    entity.payload = operation.payload();
    return entity;
  }

  StoredWorkflowOperation toStoredOperation() {
    return new StoredWorkflowOperation(
        storedSessionId,
        storedOperationId,
        actorId,
        operationType,
        occurredAt,
        storedOperationSequence,
        storedSemanticRevision,
        payload);
  }

  boolean hasSameSemanticContent(
      String expectedSessionId, WorkflowOperationPersistenceData candidate) {
    return storedSessionId.equals(expectedSessionId)
        && actorId.equals(candidate.actorId())
        && operationType.equals(candidate.operationType())
        && occurredAt.equals(candidate.occurredAt())
        && payload.equals(candidate.payload());
  }

  String sessionId() {
    return storedSessionId;
  }

  String operationId() {
    return storedOperationId;
  }

  long operationSequence() {
    return storedOperationSequence;
  }

  long semanticRevision() {
    return storedSemanticRevision;
  }
}
