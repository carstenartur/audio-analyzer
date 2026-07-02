/**
 * Workflow bounded context — immutable domain model for workflow graphs.
 *
 * <p>This package contains the design-time workflow model only: {@link
 * org.hammer.audio.workflow.Workflow}, {@link org.hammer.audio.workflow.Node}, {@link
 * org.hammer.audio.workflow.Port}, {@link org.hammer.audio.workflow.Edge}, {@link
 * org.hammer.audio.workflow.Metadata}, the {@link org.hammer.audio.workflow.TypeRegistry} / {@link
 * org.hammer.audio.workflow.DataType} port-type system, semantic {@link
 * org.hammer.audio.workflow.WorkflowOperation}s with their {@link
 * org.hammer.audio.workflow.WorkflowOperationLog}, and the structural {@link
 * org.hammer.audio.workflow.WorkflowValidator}.
 *
 * <p><strong>Boundary rules</strong>:
 *
 * <ul>
 *   <li>No dependency on the execution sub-package ({@code org.hammer.audio.workflow.execution}).
 *       The workflow model is framework-independent and must not carry any runtime execution state.
 *   <li>No dependency on audio-processing packages ({@code org.hammer.audio.core}, {@code
 *       org.hammer.audio.dsp}, etc.). Workflow graphs are domain-neutral and reusable outside the
 *       audio domain.
 *   <li>No dependency on UI packages ({@code org.hammer.audio.ui}, {@code org.hammer}).
 *   <li>No dependency on persistence packages ({@code org.hammer.audio.recording}).
 * </ul>
 *
 * <p>See {@code docs/architecture/bounded-contexts.md} for the full bounded-context definitions and
 * the dependency-direction rules enforced by {@code ArchitectureBoundaryTest}.
 */
package org.hammer.audio.workflow;
