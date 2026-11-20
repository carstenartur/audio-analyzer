package org.hammer.audio.infrastructure.workflow.collaboration.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hammer.audio.workflow.collaboration.OperationActor;
import org.hammer.audio.workflow.collaboration.identity.CollaborationIdentity;

/** Durable immutable attribution; only a hash of the bearer credential is stored. */
@Entity
@Table(name = "workflow_collaboration_identity")
public class CollaborationIdentityEntity {
  @Id
  @Column(name = "actor_id", nullable = false, length = 255)
  private String actorId;

  @Column(name = "subject_id", nullable = false, length = 255)
  private String subjectId;

  @Column(name = "display_name", nullable = false, length = 120)
  private String displayName;

  @Column(name = "provenance", nullable = false, length = 32)
  private String provenance;

  @Column(name = "account_handle", length = 255)
  private String accountHandle;

  @Column(name = "credential_hash", nullable = false, length = 64)
  private String credentialHash;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected CollaborationIdentityEntity() {
    // Required by Jakarta Persistence.
  }

  static CollaborationIdentityEntity from(CollaborationIdentity identity) {
    CollaborationIdentityEntity entity = new CollaborationIdentityEntity();
    entity.actorId = identity.actor().actorId();
    entity.subjectId = identity.actor().userId();
    entity.displayName = identity.actor().displayName();
    entity.provenance = identity.provenance();
    entity.accountHandle = identity.accountHandle();
    entity.credentialHash = identity.credentialHash();
    entity.createdAt = identity.createdAt();
    return entity;
  }

  CollaborationIdentity toIdentity() {
    return new CollaborationIdentity(
        new OperationActor(actorId, subjectId, displayName),
        provenance,
        accountHandle,
        credentialHash,
        createdAt);
  }
}
