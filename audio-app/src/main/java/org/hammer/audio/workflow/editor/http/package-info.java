/**
 * Minimal HTTP adapter for the workflow editor MVP (ADR-007 / issue #210).
 *
 * <p>This package contains {@link org.hammer.audio.workflow.editor.http.WorkflowEditorHttpAdapter},
 * the JDK {@code HttpServer} adapter that bridges React Flow browser requests to {@link
 * org.hammer.audio.workflow.editor.WorkflowEditorService}.
 *
 * <p>The adapter exposes the single-user workbench endpoints for projection, node catalog,
 * validation, semantic operations, checkpoints, history, reload and deterministic snapshot export.
 *
 * <p><b>Dependency rules</b>: classes in this package may depend on {@code
 * org.hammer.audio.workflow.editor} and on the JDK built-in {@code com.sun.net.httpserver}. They
 * must not depend on Swing, JGit, React, Yjs, Selenium, Playwright, Testcontainers or any
 * audio-experimental package.
 */
package org.hammer.audio.workflow.editor.http;
