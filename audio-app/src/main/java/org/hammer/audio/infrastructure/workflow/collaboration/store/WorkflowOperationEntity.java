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
import org.hammer.audio.workflow.WorkflowOperation;
import org.hammer.audio.workflow.collaboration.store.StoredWorkflowOperation;
import org.hammer.audio.workflow.collaboration.store.WorkflowOperationBodyCodec;
import org.hammer.audio.workflow.collaboration.store.WorkflowOperationCommandMetadata;
import org.hammer.audio.workflow.collaboration.store.WorkflowOperationPersistenceData;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

  @Column(name = "operation_body_version")
  private Integer bodyVersion;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "operation_body")
  private String operationBody;

  @Column(name = "command_kind", length = 32)
  private String commandKind;

  @Column(name = "command_id", length = 255)
  private String commandId;

  @Column(name = "target_operation_id", length = 255)
  private String targetOperationId;

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
    entity.bodyVersion = operation.hasOperationBody() ? operation.bodyVersion() : null;
    entity.operationBody = operation.operationBody();
    entity.commandKind = operation.command().kind().name();
    entity.commandId = operation.command().commandId();
    entity.targetOperationId = operation.command().targetOperationId();
    return entity;
  }

  StoredWorkflowOperation toStoredOperation() {
    WorkflowOperationCommandMetadata command =
        commandKind == null || commandId == null
            ? WorkflowOperationCommandMetadata.normal(storedOperationId)
            : new WorkflowOperationCommandMetadata(
                WorkflowOperationCommandMetadata.Kind.valueOf(commandKind),
                commandId,
                targetOperationId);
    return new StoredWorkflowOperation(
        storedSessionId,
        storedOperationId,
        actorId,
        operationType,
        occurredAt,
        storedOperationSequence,
        storedSemanticRevision,
        payload,
        bodyVersion == null ? 0 : bodyVersion,
        operationBody,
        command);
  }

  boolean hasSameSemanticContent(
      String expectedSessionId, WorkflowOperationPersistenceData candidate) {
    boolean identityMatches =
        storedSessionId.equals(expectedSessionId)
            && actorId.equals(candidate.actorId())
            && operationType.equals(candidate.operationType())
            && payload.equals(candidate.payload())
            && command().equals(candidate.command());
    if (!identityMatches || bodyVersion == null) {
      return identityMatches;
    }
    if (!candidate.hasOperationBody() || bodyVersion != candidate.bodyVersion()) {
      return false;
    }
    WorkflowOperation candidateOperation =
        WorkflowOperationBodyCodec.decode(candidate.bodyVersion(), candidate.operationBody());
    WorkflowOperation normalizedCandidate =
        WorkflowOperationBodyCodec.reidentify(
            candidateOperation, candidate.operationId(), occurredAt, candidate.actorId());
    return operationBody.equals(WorkflowOperationBodyCodec.encode(normalizedCandidate).body());
  }

  private WorkflowOperationCommandMetadata command() {
    return commandKind == null || commandId == null
        ? WorkflowOperationCommandMetadata.normal(storedOperationId)
        : new WorkflowOperationCommandMetadata(
            WorkflowOperationCommandMetadata.Kind.valueOf(commandKind),
            commandId,
            targetOperationId);
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
