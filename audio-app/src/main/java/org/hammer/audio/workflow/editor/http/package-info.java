/**
 * Minimal HTTP adapter for the workflow editor spike (ADR-007).
 *
 * <p>This package contains {@link org.hammer.audio.workflow.editor.http.WorkflowEditorHttpAdapter},
 * the JDK {@code HttpServer} adapter that bridges React Flow browser requests to {@link
 * org.hammer.audio.workflow.editor.WorkflowEditorService}.
 *
 * <p><b>Dependency rules</b>: classes in this package may depend on {@code
 * org.hammer.audio.workflow.editor} and on the JDK built-in {@code com.sun.net.httpserver}. They
 * must not depend on Swing, JGit, or any audio-experimental package.
 */
package org.hammer.audio.workflow.editor.http;
