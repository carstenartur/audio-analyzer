package org.hammer.audio.infrastructure.workflow.search;

import java.util.List;

/** Registry of Audio Analyzer-owned semantic workflow-history entities. */
public final class WorkflowSemanticPersistenceEntities {

  private WorkflowSemanticPersistenceEntities() {
    throw new AssertionError("No instances");
  }

  /** Returns immutable annotated classes for the shared application persistence context. */
  public static List<Class<?>> annotatedClasses() {
    return List.of(WorkflowSemanticIndexEntity.class);
  }
}
