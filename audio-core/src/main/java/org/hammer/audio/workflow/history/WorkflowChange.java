package org.hammer.audio.workflow.history;

import java.util.Objects;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Node;

/**
 * Sealed hierarchy of domain-level change atoms produced by a semantic workflow diff.
 *
 * <p>Each variant represents a single indivisible change between two {@link
 * org.hammer.audio.workflow.Workflow} snapshots. Callers can switch exhaustively over all variants
 * using a {@code switch} expression on the sealed type.
 *
 * <p>Owned by the semantic-analysis/history-projection layer. Must not contain UI, JGit or
 * execution dependencies.
 */
public sealed interface WorkflowChange
    permits WorkflowChange.NodeAdded,
        WorkflowChange.NodeRemoved,
        WorkflowChange.EdgeAdded,
        WorkflowChange.EdgeRemoved,
        WorkflowChange.ParameterChanged,
        WorkflowChange.FieldChanged {

  /** Semantic workflow element owning a field change. */
  enum ElementKind {
    WORKFLOW,
    NODE,
    EDGE
  }

  /**
   * A node that is present in the <em>after</em> snapshot but absent in the <em>before</em>
   * snapshot.
   *
   * @param node the added node
   */
  record NodeAdded(Node node) implements WorkflowChange {
    public NodeAdded {
      Objects.requireNonNull(node, "node");
    }
  }

  /**
   * A node that is present in the <em>before</em> snapshot but absent in the <em>after</em>
   * snapshot.
   *
   * @param node the removed node
   */
  record NodeRemoved(Node node) implements WorkflowChange {
    public NodeRemoved {
      Objects.requireNonNull(node, "node");
    }
  }

  /**
   * An edge that is present in the <em>after</em> snapshot but absent in the <em>before</em>
   * snapshot.
   *
   * @param edge the added edge
   */
  record EdgeAdded(Edge edge) implements WorkflowChange {
    public EdgeAdded {
      Objects.requireNonNull(edge, "edge");
    }
  }

  /**
   * An edge that is present in the <em>before</em> snapshot but absent in the <em>after</em>
   * snapshot.
   *
   * @param edge the removed edge
   */
  record EdgeRemoved(Edge edge) implements WorkflowChange {
    public EdgeRemoved {
      Objects.requireNonNull(edge, "edge");
    }
  }

  /**
   * A metadata property on a node or edge whose value changed between the two snapshots.
   *
   * <p>If the property was absent before the change {@code oldValue} is {@code null}. If it was
   * removed by the change {@code newValue} is {@code null}.
   *
   * @param targetId stable identifier of the node or edge that owns the property
   * @param propertyKey stable metadata key
   * @param oldValue value before the change, or {@code null} if the property was newly added
   * @param newValue value after the change, or {@code null} if the property was removed
   */
  record ParameterChanged(String targetId, String propertyKey, String oldValue, String newValue)
      implements WorkflowChange {
    public ParameterChanged {
      requireText(targetId, "targetId");
      requireText(propertyKey, "propertyKey");
      requireChanged(oldValue, newValue);
    }
  }

  /**
   * A non-legacy semantic field whose canonical value changed.
   *
   * <p>This variant extends the original metadata-focused diff without replacing the established
   * {@link ParameterChanged} contract. It covers workflow name/metadata, node type/label/ports and
   * edge endpoints.
   *
   * @param elementKind kind of workflow element owning the field
   * @param targetId stable workflow, node or edge identifier
   * @param fieldPath stable semantic field path
   * @param oldValue canonical previous value, or {@code null} when added
   * @param newValue canonical next value, or {@code null} when removed
   */
  record FieldChanged(
      ElementKind elementKind, String targetId, String fieldPath, String oldValue, String newValue)
      implements WorkflowChange {
    public FieldChanged {
      Objects.requireNonNull(elementKind, "elementKind");
      requireText(targetId, "targetId");
      requireText(fieldPath, "fieldPath");
      requireChanged(oldValue, newValue);
    }
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }

  private static void requireChanged(String oldValue, String newValue) {
    if (Objects.equals(oldValue, newValue)) {
      throw new IllegalArgumentException("A workflow change requires different values");
    }
  }
}
