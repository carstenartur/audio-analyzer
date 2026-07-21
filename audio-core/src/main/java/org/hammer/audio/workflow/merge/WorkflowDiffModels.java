package org.hammer.audio.workflow.merge;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Framework-independent contracts for deterministic semantic workflow comparison. */
public interface WorkflowDiffModels {

  /** Semantic workflow element owning a changed value. */
  enum ElementKind {
    WORKFLOW,
    NODE,
    EDGE
  }

  /** Kind of semantic change relative to the compared workflow. */
  enum ChangeKind {
    ADDED,
    REMOVED,
    MODIFIED
  }

  /**
   * One deterministic field-level semantic workflow change.
   *
   * @param elementKind kind of owning workflow element
   * @param elementId stable workflow, node or edge identifier
   * @param fieldPath stable semantic field path
   * @param changeKind added, removed or modified classification
   * @param beforeValue canonical previous value, or {@code null} when added
   * @param afterValue canonical next value, or {@code null} when removed
   */
  record Change(
      ElementKind elementKind,
      String elementId,
      String fieldPath,
      ChangeKind changeKind,
      String beforeValue,
      String afterValue) {

    /** Stable ordering used by APIs, tests and user interfaces. */
    public static final Comparator<Change> ORDERING =
        Comparator.comparing(Change::elementKind)
            .thenComparing(Change::elementId)
            .thenComparing(Change::fieldPath)
            .thenComparing(Change::changeKind);

    public Change {
      Objects.requireNonNull(elementKind, "elementKind");
      requireText(elementId, "elementId");
      requireText(fieldPath, "fieldPath");
      Objects.requireNonNull(changeKind, "changeKind");
      if (beforeValue == null && afterValue == null) {
        throw new IllegalArgumentException("A change requires a before or after value");
      }
    }
  }

  /**
   * Deterministic semantic difference between two workflow values.
   *
   * @param beforeWorkflowId stable source workflow identifier
   * @param afterWorkflowId stable target workflow identifier
   * @param changes immutable field-level changes in stable order
   */
  record Diff(String beforeWorkflowId, String afterWorkflowId, List<Change> changes) {
    public Diff {
      requireText(beforeWorkflowId, "beforeWorkflowId");
      requireText(afterWorkflowId, "afterWorkflowId");
      changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
      if (!changes.equals(changes.stream().sorted(Change.ORDERING).toList())) {
        throw new IllegalArgumentException("changes must use stable semantic ordering");
      }
    }

    /** Returns whether the compared workflows are semantically equal. */
    public boolean empty() {
      return changes.isEmpty();
    }
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
