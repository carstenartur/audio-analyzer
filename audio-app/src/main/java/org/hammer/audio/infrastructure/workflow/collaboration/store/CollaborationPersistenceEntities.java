package org.hammer.audio.infrastructure.workflow.collaboration.store;

import java.util.List;

/** Registry of Audio Analyzer-specific collaboration persistence entities. */
public final class CollaborationPersistenceEntities {

  private CollaborationPersistenceEntities() {
    throw new AssertionError("No instances");
  }

  /** Returns immutable annotated classes registered in the shared Hibernate context. */
  public static List<Class<?>> annotatedClasses() {
    return List.of(
        WorkflowSessionEntity.class, WorkflowOperationEntity.class, WorkflowOutboxEntity.class);
  }
}
