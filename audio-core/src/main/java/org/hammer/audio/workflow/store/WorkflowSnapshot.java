package org.hammer.audio.workflow.store;

import java.util.Objects;

/**
 * Immutable value carrying the canonical DSL text of a workflow at a specific version.
 *
 * <p>The {@code workflowId} field is the domain identifier, not a storage key. The {@code dslText}
 * field is the byte-identical representation produced by {@code WorkflowDslSerializer}.
 *
 * <p>Owned by the persistence facade layer. Storage implementations wrap the DSL text in a
 * blob/commit; callers need not know the storage format.
 *
 * @param workflowId stable workflow domain identifier
 * @param dslText canonical DSL text (UTF-8)
 */
public record WorkflowSnapshot(String workflowId, String dslText) {

  public WorkflowSnapshot {
    if (workflowId == null || workflowId.isBlank()) {
      throw new IllegalArgumentException("workflowId must not be blank");
    }
    Objects.requireNonNull(dslText, "dslText");
  }
}
