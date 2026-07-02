/**
 * Execution bounded context — runtime lifecycle model derived from a frozen workflow snapshot.
 *
 * <p>This package contains the execution model only: {@link
 * org.hammer.audio.workflow.execution.ExecutionSnapshot} (immutable freeze of a workflow at
 * execution start), {@link org.hammer.audio.workflow.execution.ExecutionPlan} (topological node
 * order), {@link org.hammer.audio.workflow.execution.ExecutionContext} (mutable per-node status
 * tracking), {@link org.hammer.audio.workflow.execution.ExecutionResult} (immutable terminal
 * outcome) and {@link org.hammer.audio.workflow.execution.ExecutionStatus} (node lifecycle states).
 *
 * <p><strong>Boundary rules</strong>:
 *
 * <ul>
 *   <li>May import from the Workflow bounded context ({@code org.hammer.audio.workflow}) and Java
 *       SE only.
 *   <li>No dependency on persistence packages ({@code org.hammer.audio.recording}).
 *   <li>No dependency on UI packages ({@code org.hammer.audio.ui}, {@code org.hammer}).
 *   <li>No dependency on experimental or plugin packages.
 * </ul>
 *
 * <p>See {@code docs/architecture/bounded-contexts.md} for the full bounded-context definitions and
 * the dependency-direction rules enforced by {@code ArchitectureBoundaryTest}.
 */
package org.hammer.audio.workflow.execution;
