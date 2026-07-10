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
        WorkflowChange.ParameterChanged {

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
      Objects.requireNonNull(targetId, "targetId");
      Objects.requireNonNull(propertyKey, "propertyKey");
      // oldValue and newValue are intentionally nullable (null = absent)
    }
  }
}
