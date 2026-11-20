package org.hammer.audio.experiment.document;

import java.util.List;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.history.WorkflowChange;
import org.hammer.audio.workflow.history.WorkflowDiff;

/** Transport-neutral semantic workflow changes for the confirmation preview. */
public final class ExperimentDocumentComparison {
  private ExperimentDocumentComparison() {
    // Utility class.
  }

  /** Compare domain objects using the established semantic workflow diff. */
  public static List<Change> compare(Workflow before, Workflow after) {
    return WorkflowDiff.compute(before, after).changes().stream()
        .map(ExperimentDocumentComparison::describe)
        .toList();
  }

  private static Change describe(WorkflowChange change) {
    return switch (change) {
      case WorkflowChange.NodeAdded added ->
          new Change("node-added", added.node().id(), "node", null, added.node().toString());
      case WorkflowChange.NodeRemoved removed ->
          new Change("node-removed", removed.node().id(), "node", removed.node().toString(), null);
      case WorkflowChange.EdgeAdded added ->
          new Change("edge-added", added.edge().id(), "edge", null, added.edge().toString());
      case WorkflowChange.EdgeRemoved removed ->
          new Change("edge-removed", removed.edge().id(), "edge", removed.edge().toString(), null);
      case WorkflowChange.ParameterChanged parameter ->
          new Change(
              "parameter",
              parameter.targetId(),
              parameter.propertyKey(),
              parameter.oldValue(),
              parameter.newValue());
      case WorkflowChange.FieldChanged field ->
          new Change(
              field.elementKind().name().toLowerCase(java.util.Locale.ROOT),
              field.targetId(),
              field.fieldPath(),
              field.oldValue(),
              field.newValue());
    };
  }

  /**
   * One affected semantic object or field and its prior/replacement values.
   *
   * @param kind semantic change category
   * @param targetId affected workflow object id
   * @param field affected field or property
   * @param before prior value, or null for an addition
   * @param after replacement value, or null for a removal
   */
  public record Change(String kind, String targetId, String field, String before, String after) {
    // Immutable value; record components carry the complete state.
  }
}
