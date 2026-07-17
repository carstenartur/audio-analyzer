package org.hammer.audio.infrastructure.workflow.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Test-only Audio Analyzer entity sharing the Hibernate-backed JGit persistence context. */
@Entity
@Table(name = "workflow_persistence_probe")
public class WorkflowPersistenceProbeEntity {

  @Id
  @Column(name = "probe_id", nullable = false, length = 128)
  private String id;

  @Column(name = "probe_status", nullable = false, length = 128)
  private String status;

  protected WorkflowPersistenceProbeEntity() {}

  public WorkflowPersistenceProbeEntity(String id, String status) {
    this.id = id;
    this.status = status;
  }

  public String getStatus() {
    return status;
  }
}
