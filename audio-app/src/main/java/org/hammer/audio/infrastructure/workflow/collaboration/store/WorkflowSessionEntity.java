package org.hammer.audio.infrastructure.workflow.collaboration.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import org.hammer.audio.workflow.collaboration.CollaborationMode;
import org.hammer.audio.workflow.collaboration.OperationActor;
import org.hammer.audio.workflow.collaboration.store.StoredWorkflowSession;

/** Hibernate-owned durable collaboration-session aggregate. */
@Entity
@Table(
    name = "workflow_collaboration_session",
    indexes = {
      @Index(name = "idx_workflow_session_open", columnList = "session_closed"),
      @Index(name = "idx_workflow_session_workflow", columnList = "workflow_id")
    })
public class WorkflowSessionEntity {

  @Id
  @Column(name = "session_id", nullable = false, length = 255)
  private String storedSessionId;

  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;

  @Enumerated(EnumType.STRING)
  @Column(name = "collaboration_mode", nullable = false, length = 64)
  private CollaborationMode mode;

  @Column(name = "owner_actor_id", nullable = false, length = 255)
  private String ownerActorId;

  @Column(name = "owner_user_id", nullable = false, length = 255)
  private String ownerUserId;

  @Column(name = "owner_display_name", nullable = false, length = 1024)
  private String ownerDisplayName;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "workflow_id", nullable = false, length = 255)
  private String storedWorkflowId;

  @Lob
  @Column(name = "workflow_dsl", nullable = false)
  private String storedWorkflowDsl;

  @Column(name = "semantic_revision", nullable = false)
  private long storedSemanticRevision;

  @Column(name = "event_sequence", nullable = false)
  private long storedEventSequence;

  @Column(name = "session_closed", nullable = false)
  private boolean sessionClosed;

  protected WorkflowSessionEntity() {
    // Required by Jakarta Persistence.
  }

  static WorkflowSessionEntity from(StoredWorkflowSession stored) {
    WorkflowSessionEntity entity = new WorkflowSessionEntity();
    entity.storedSessionId = stored.sessionId();
    entity.mode = stored.mode();
    entity.ownerActorId = stored.owner().actorId();
    entity.ownerUserId = stored.owner().userId();
    entity.ownerDisplayName = stored.owner().displayName();
    entity.createdAt = stored.createdAt();
    entity.storedWorkflowId = stored.workflowId();
    entity.storedWorkflowDsl = stored.workflowDsl();
    entity.storedSemanticRevision = stored.revision();
    entity.storedEventSequence = stored.sequence();
    entity.sessionClosed = stored.closed();
    return entity;
  }

  StoredWorkflowSession toStoredSession() {
    return new StoredWorkflowSession(
        storedSessionId,
        mode,
        new OperationActor(ownerActorId, ownerUserId, ownerDisplayName),
        createdAt,
        storedWorkflowId,
        storedWorkflowDsl,
        storedSemanticRevision,
        storedEventSequence,
        sessionClosed);
  }

  void applyAcceptedOperation(
      String updatedWorkflowId,
      String updatedWorkflowDsl,
      long updatedRevision,
      long updatedSequence) {
    this.storedWorkflowId = updatedWorkflowId;
    this.storedWorkflowDsl = updatedWorkflowDsl;
    this.storedSemanticRevision = updatedRevision;
    this.storedEventSequence = updatedSequence;
  }

  String sessionId() {
    return storedSessionId;
  }

  String workflowId() {
    return storedWorkflowId;
  }

  String workflowDsl() {
    return storedWorkflowDsl;
  }

  long semanticRevision() {
    return storedSemanticRevision;
  }

  long eventSequence() {
    return storedEventSequence;
  }

  boolean closed() {
    return sessionClosed;
  }
}
