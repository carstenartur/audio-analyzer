package org.hammer.audio.workflow.history;

import java.util.Objects;

/**
 * Sealed hierarchy of domain-level merge conflicts.
 *
 * <p>Each variant describes a specific type of conflict that arises when two independently evolving
 * workflow branches are reconciled. Conflicts are reported as model-level domain objects, not as
 * text-diff hunks.
 *
 * <p>Owned by the semantic-analysis/history-projection layer. Must not contain UI, JGit or
 * execution dependencies.
 *
 * @see MergeConflictReport
 */
public sealed interface MergeConflict
    permits MergeConflict.DeleteVsModify, MergeConflict.ConnectVsParameterChange {

  /**
   * One branch deleted a node while the other branch modified it (e.g. renamed or updated a
   * parameter).
   *
   * <p>This conflict cannot be auto-resolved because discarding the modification would silently
   * lose intent; keeping the node would ignore the deletion intent.
   *
   * @param nodeId stable identifier of the conflicting node
   */
  record DeleteVsModify(String nodeId) implements MergeConflict {
    public DeleteVsModify {
      Objects.requireNonNull(nodeId, "nodeId");
    }
  }

  /**
   * One branch connected two ports (adding an edge) while the other branch changed a parameter on
   * one of the involved nodes.
   *
   * <p>The new edge may be invalid after the parameter change, so the conflict must be reviewed by
   * a human author.
   *
   * @param edgeId stable identifier of the newly added edge that may be invalid
   * @param nodeId stable identifier of the node whose parameter was changed on the other branch
   */
  record ConnectVsParameterChange(String edgeId, String nodeId) implements MergeConflict {
    public ConnectVsParameterChange {
      Objects.requireNonNull(edgeId, "edgeId");
      Objects.requireNonNull(nodeId, "nodeId");
    }
  }
}
