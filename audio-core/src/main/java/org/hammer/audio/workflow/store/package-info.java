/**
 * Persistence facade for versioned workflow checkpoints.
 *
 * <p>This package defines the only acceptable boundary between Audio Analyzer workflow services and
 * any concrete storage back end (JGit, Hibernate, in-memory). Storage implementation types must
 * never leak through this API.
 *
 * <p><b>Allowed callers</b>: application services. Must not be imported from editor adapters, UI
 * components or workflow domain objects.
 */
package org.hammer.audio.workflow.store;
