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
  private String sessionId;

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
  private String workflowId;

  @Lob
  @Column(name = "workflow_dsl", nullable = false)
  private String workflowDsl;

  @Column(name = "semantic_revision", nullable = false)
  private long semanticRevision;

  @Column(name = "event_sequence", nullable = false)
  private long eventSequence;

  @Column(name = "session_closed", nullable = false)
  private boolean closed;

  protected WorkflowSessionEntity() {}

  static WorkflowSessionEntity from(StoredWorkflowSession stored) {
    WorkflowSessionEntity entity = new WorkflowSessionEntity();
    entity.sessionId = stored.sessionId();
    entity.mode = stored.mode();
    entity.ownerActorId = stored.owner().actorId();
    entity.ownerUserId = stored.owner().userId();
    entity.ownerDisplayName = stored.owner().displayName();
    entity.createdAt = stored.createdAt();
    entity.workflowId = stored.workflowId();
    entity.workflowDsl = stored.workflowDsl();
    entity.semanticRevision = stored.revision();
    entity.eventSequence = stored.sequence();
    entity.closed = stored.closed();
    return entity;
  }

  StoredWorkflowSession toStoredSession() {
    return new StoredWorkflowSession(
        sessionId,
        mode,
        new OperationActor(ownerActorId, ownerUserId, ownerDisplayName),
        createdAt,
        workflowId,
        workflowDsl,
        semanticRevision,
        eventSequence,
        closed);
  }

  void applyAcceptedOperation(
      String updatedWorkflowId,
      String updatedWorkflowDsl,
      long updatedRevision,
      long updatedSequence) {
    this.workflowId = updatedWorkflowId;
    this.workflowDsl = updatedWorkflowDsl;
    this.semanticRevision = updatedRevision;
    this.eventSequence = updatedSequence;
  }

  String sessionId() {
    return sessionId;
  }

  String workflowId() {
    return workflowId;
  }

  long semanticRevision() {
    return semanticRevision;
  }

  long eventSequence() {
    return eventSequence;
  }

  boolean closed() {
    return closed;
  }
}
