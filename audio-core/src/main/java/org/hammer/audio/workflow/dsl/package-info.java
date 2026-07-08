/**
 * Deterministic DSL serializer and parser for workflow domain objects.
 *
 * <p>This package owns the canonical text representation of {@code Workflow} graphs. The
 * representation is stable, Git-diffable and free of layout or presence state.
 *
 * <p><b>Allowed callers</b>: persistence facade ({@code org.hammer.audio.workflow.store}) and
 * application services. Must not depend on UI, JGit internals or execution runtime.
 */
package org.hammer.audio.workflow.dsl;
