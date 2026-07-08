/**
 * First experiment node catalog for the modeling workbench.
 *
 * <p>Factory methods in this package produce typed {@code Node} prototypes that map directly to the
 * {@code audio-core} workflow model. The catalog lives in the workflow domain layer and must not
 * depend on UI, execution, persistence or JGit.
 *
 * <p><b>Allowed callers</b>: application services and tests.
 */
package org.hammer.audio.workflow.catalog;
