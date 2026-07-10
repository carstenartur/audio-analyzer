/**
 * Semantic-analysis and history-projection layer for workflow versioning.
 *
 * <p>This package provides read-only, domain-level views derived from persisted workflow snapshots
 * and operation logs:
 *
 * <ul>
 *   <li>{@link org.hammer.audio.workflow.history.WorkflowChange} – sealed hierarchy of semantic
 *       change atoms (NodeAdded, NodeRemoved, EdgeAdded, EdgeRemoved, ParameterChanged).
 *   <li>{@link org.hammer.audio.workflow.history.WorkflowDiff} – computes the ordered list of
 *       {@link org.hammer.audio.workflow.history.WorkflowChange} objects between two {@link
 *       org.hammer.audio.workflow.Workflow} snapshots.
 *   <li>{@link org.hammer.audio.workflow.history.MergeConflict} – sealed hierarchy describing
 *       conflicts that arise when two diverging diff paths are reconciled.
 *   <li>{@link org.hammer.audio.workflow.history.MergeConflictReport} – detects conflicts between
 *       two {@link org.hammer.audio.workflow.history.WorkflowDiff} objects produced from a common
 *       base.
 *   <li>{@link org.hammer.audio.workflow.history.WorkflowHistorySearch} – read-only query API for
 *       finding workflow versions in a {@link
 *       org.hammer.audio.workflow.store.VersionedWorkflowStore} by node type, parameter name/value,
 *       or commit author.
 * </ul>
 *
 * <p><b>Layer boundary</b>: this package is pure domain analysis. It consumes {@link
 * org.hammer.audio.workflow.Workflow} value objects and {@link
 * org.hammer.audio.workflow.store.VersionedWorkflowStore} snapshots. It must not reach into editor
 * state, UI rendering, JGit internals or execution internals. Search projections produced here are
 * derived views only; they are never authoritative for workflow state.
 */
package org.hammer.audio.workflow.history;
