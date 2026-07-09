/**
 * Application service layer for the web workflow editor spike (ADR-007).
 *
 * <p>This package provides the server-side API surface that the React Flow editor adapter calls:
 *
 * <ul>
 *   <li>{@link org.hammer.audio.workflow.editor.WorkflowEditorService} — accepts {@code
 *       WorkflowOperation} values, validates with {@code WorkflowValidator}, applies to {@code
 *       WorkflowOperationLog}, and returns a {@code WorkflowProjection}.
 *   <li>{@link org.hammer.audio.workflow.editor.WorkflowProjection} — React Flow–ready read model
 *       derived from a {@code Workflow}. Contains typed-port handle descriptors so the UI can
 *       render typed {@code Handle} components without parsing domain objects directly.
 *   <li>{@link org.hammer.audio.workflow.editor.WorkflowOperationRejectedException} — thrown when a
 *       {@code WorkflowOperation} produces a structurally invalid workflow (type mismatch, broken
 *       port reference, etc.).
 * </ul>
 *
 * <p><b>Dependency rules</b>: this package must not depend on Swing, JGit, React, Yjs, or any web
 * framework. It is a pure Java application service boundary. The web layer (TypeScript/React Flow)
 * lives outside the Maven build.
 *
 * <p>See {@code workflow-editor-spike/} at the repository root for the matching TypeScript React
 * Flow component that consumes the {@code WorkflowProjection} JSON produced by this service.
 */
package org.hammer.audio.workflow.editor;
