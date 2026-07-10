package org.hammer.audio.workflow.history;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Conflict report produced by reconciling two {@link WorkflowDiff} objects that share a common base
 * snapshot.
 *
 * <p>Conflicts are expressed as domain-level model objects (see {@link MergeConflict}), not as
 * text-diff hunks.
 *
 * <p>Two conflict patterns are detected:
 *
 * <ul>
 *   <li><b>Delete-vs-modify</b>: one diff removes a node while the other diff modifies a property
 *       on the same node. Because the intent of each side is incompatible, neither can be
 *       auto-resolved.
 *   <li><b>Connect-vs-parameter-change</b>: one diff adds an edge while the other diff modifies a
 *       property on one of the edge's endpoint nodes. The edge may become invalid after the
 *       property change, so human review is required.
 * </ul>
 *
 * <p>Use {@link #detect(WorkflowDiff, WorkflowDiff)} to create an instance. Both diffs must be
 * derived from the same base {@link org.hammer.audio.workflow.Workflow} snapshot.
 *
 * <p>Owned by the semantic-analysis/history-projection layer. Must not depend on UI, JGit or
 * execution internals.
 *
 * @param conflicts immutable list of detected conflicts; empty when the two diffs can be merged
 *     without conflict
 */
public record MergeConflictReport(List<MergeConflict> conflicts) {

  public MergeConflictReport {
    conflicts = List.copyOf(Objects.requireNonNull(conflicts, "conflicts"));
  }

  /** Returns {@code true} when at least one merge conflict was detected. */
  public boolean hasConflicts() {
    return !conflicts.isEmpty();
  }

  /**
   * Detects conflicts between two diffs that were computed from the same base workflow snapshot.
   *
   * @param ours diff from base to the local branch tip; must not be {@code null}
   * @param theirs diff from base to the remote branch tip; must not be {@code null}
   * @return conflict report; never {@code null}
   */
  public static MergeConflictReport detect(WorkflowDiff ours, WorkflowDiff theirs) {
    Objects.requireNonNull(ours, "ours");
    Objects.requireNonNull(theirs, "theirs");

    Set<String> oursRemovedNodeIds = removedNodeIds(ours);
    Set<String> theirsRemovedNodeIds = removedNodeIds(theirs);
    Set<String> oursChangedTargetIds = changedParameterTargetIds(ours);
    Set<String> theirsChangedTargetIds = changedParameterTargetIds(theirs);

    List<MergeConflict> conflicts = new ArrayList<>();
    detectDeleteVsModify(
        oursRemovedNodeIds,
        theirsChangedTargetIds,
        theirsRemovedNodeIds,
        oursChangedTargetIds,
        conflicts);
    detectConnectVsParameterChange(ours.changes(), theirsChangedTargetIds, conflicts);
    detectConnectVsParameterChange(theirs.changes(), oursChangedTargetIds, conflicts);
    return new MergeConflictReport(conflicts);
  }

  private static void detectDeleteVsModify(
      Set<String> oursRemovedNodeIds,
      Set<String> theirsChangedTargetIds,
      Set<String> theirsRemovedNodeIds,
      Set<String> oursChangedTargetIds,
      List<MergeConflict> conflicts) {
    for (String nodeId : oursRemovedNodeIds) {
      if (theirsChangedTargetIds.contains(nodeId)) {
        conflicts.add(new MergeConflict.DeleteVsModify(nodeId));
      }
    }
    for (String nodeId : theirsRemovedNodeIds) {
      if (oursChangedTargetIds.contains(nodeId)) {
        conflicts.add(new MergeConflict.DeleteVsModify(nodeId));
      }
    }
  }

  private static void detectConnectVsParameterChange(
      List<WorkflowChange> changes,
      Set<String> otherChangedTargetIds,
      List<MergeConflict> conflicts) {
    for (WorkflowChange change : changes) {
      if (change instanceof WorkflowChange.EdgeAdded added) {
        String conflictingNodeId =
            findChangedEndpoint(
                added.edge().sourceNodeId(), added.edge().targetNodeId(), otherChangedTargetIds);
        if (conflictingNodeId != null) {
          conflicts.add(
              new MergeConflict.ConnectVsParameterChange(added.edge().id(), conflictingNodeId));
        }
      }
    }
  }

  private static String findChangedEndpoint(
      String sourceNodeId, String targetNodeId, Set<String> changedTargetIds) {
    if (changedTargetIds.contains(sourceNodeId)) {
      return sourceNodeId;
    }
    if (changedTargetIds.contains(targetNodeId)) {
      return targetNodeId;
    }
    return null;
  }

  private static Set<String> removedNodeIds(WorkflowDiff diff) {
    Set<String> ids = new LinkedHashSet<>();
    for (WorkflowChange change : diff.changes()) {
      if (change instanceof WorkflowChange.NodeRemoved removed) {
        ids.add(removed.node().id());
      }
    }
    return ids;
  }

  private static Set<String> changedParameterTargetIds(WorkflowDiff diff) {
    Set<String> ids = new LinkedHashSet<>();
    for (WorkflowChange change : diff.changes()) {
      if (change instanceof WorkflowChange.ParameterChanged changed) {
        ids.add(changed.targetId());
      }
    }
    return ids;
  }
}
