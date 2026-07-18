/**
 * Application-service layer for the web workflow editor (ADR-007).
 *
 * <p>This package provides the server-side API surface that the maintained React Flow editor adapter
 * calls:
 *
 * <ul>
 *   <li>{@link org.hammer.audio.workflow.editor.WorkflowEditorService} — accepts {@code
 *       WorkflowOperation} values, validates with {@code WorkflowValidator}, applies to {@code
 *       WorkflowOperationLog}, loads/saves checkpoints through the persistence facade and returns
 *       {@code WorkflowProjection} read models.
 *   <li>{@link org.hammer.audio.workflow.editor.WorkflowProjection} — React Flow–ready read model
 *       derived from a {@code Workflow}. Contains typed-port handle descriptors and node property
 *       values so the UI can render a graph without parsing domain objects directly.
 *   <li>{@link org.hammer.audio.workflow.editor.WorkflowOperationRejectedException} — thrown when a
 *       {@code WorkflowOperation} or loaded graph produces a structurally invalid workflow.
 * </ul>
 *
 * <p><b>Dependency rules</b>: this package must not depend on Swing, JGit, React, Yjs, Selenium,
 * Playwright, Testcontainers or any web framework. It is a pure Java application-service boundary.
 * The maintained browser adapter lives in the {@code audio-web-editor} Maven module; HTTP wiring
 * lives in {@code audio-app}.
 */
package org.hammer.audio.workflow.editor;
